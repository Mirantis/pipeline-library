import org.jfrog.hudson.pipeline.common.types.ArtifactoryServer
import java.util.concurrent.TimeoutException

class Lock {
    String  name, id, path
    Integer retryInterval, timeout, expiration
    Boolean force
    Map lockExtraMetadata
    ArtifactoryServer artifactoryServer

    private String lockFileContent
    private String lockFileContentCache
    private Boolean fileNotFound

    final private String fileUri

    final private def common = new com.mirantis.mk.Common()
    final private def artifactoryTools = new com.mirantis.mk.Artifactory()

    // Constructor
    public Lock(Map args) {
        // Mandatory
        this.name = args.name
        this.artifactoryServer = args.artifactoryServer

        // Defaults
        this.id = args.get('id', '')
        this.path = args.get('path', 'binary-dev-local/locks')
        this.retryInterval = args.get('retryInterval', 5*60)
        this.timeout = args.get('timeout', 3*60*60)
        this.expiration = args.get('expiration', 24*60*60)
        this.force = args.get('force', false)
        this.lockExtraMetadata = args.get('lockExtraMetadata', [:])

        // Internal
        this.fileUri = "/${path}/${name}.yaml".toLowerCase()
    }

    final private Map artObj
    // getPasswordCredentials() is CPS-transformed function and cannot be used in constructor
    final private Map getArtObj() {
        def artifactoryCreds = common.getPasswordCredentials(artifactoryServer.getCredentialsId())
        return [
            'url': "${artifactoryServer.getUrl()}/artifactory",
            'creds': [
                'username': artifactoryCreds['username'],
                'password': artifactoryCreds['password'],
            ]
        ]
    }

    // getter for lockFileContent
    final private String getLockFileContent() {
        if (this.lockFileContentCache == null) {
            try {
                this.lockFileContentCache = artifactoryTools.restCall(this.artObj, this.fileUri, 'GET', null, [:], '')
                this.fileNotFound = false // file found
            } catch (java.io.FileNotFoundException e) {
                this.lockFileContentCache = ''
                this.fileNotFound = true // file not found
            } catch (Exception e) {
                common.errorMsg(e.message)
                this.lockFileContentCache = ''
                this.fileNotFound = null // we don't know about file existence
            }
        }
        return this.lockFileContentCache
    }

    public void lock() {
        if (this.force) {
            common.infoMsg("Ignore lock checking due 'force' flag presence")
        } else {
            waitLockReleased()
        }
        createLockFile()
    }

    public void unlock() {
        if (!isLockFileExist()) {
            common.infoMsg("Lock file '${this.artObj['url']}${this.fileUri}' does not exist. No need to remove it")
            // No need to continue if file does not exist
            return
        }

        Map lockMeta = common.readYaml2(text: this.lockFileContent ?: '{}')
        if (this.force || (this.id && this.id == lockMeta.get('lockID', ''))) {
            artifactoryTools.restCall(this.artObj, this.fileUri, 'DELETE', null, [:], '')
            common.infoMsg("Lock file '${this.artObj['url']}${this.fileUri}' has been removed")
        } else {
            throw new RuntimeException("Given lock ID '${this.id}' is not equal to '${lockMeta.get('lockID')}' ID in lock file")
        }
    }

    private void createLockFile() {
        this.id = UUID.randomUUID().toString()

        Calendar now = Calendar.getInstance()
        Calendar expiredAt = now.clone()
        expiredAt.add(Calendar.SECOND, this.expiration)

        Map lockMeta = [
            'lockID': this.id,
            'createdAt': now.getTime().toString(),
            'expiredAt': expiredAt.getTime().toString(),
        ]
        lockMeta.putAll(this.lockExtraMetadata)

        def commonMCP = new com.mirantis.mcp.Common()
        artifactoryTools.restCall(this.artObj, this.fileUri, 'PUT', commonMCP.dumpYAML(lockMeta), [:], '')
        common.infoMsg("Lock file '${this.artObj['url']}${this.fileUri}' has been created")
    }

    private void waitLockReleased() {
        Long startTime = System.currentTimeMillis()
        while (isLocked()) {
            if (System.currentTimeMillis() - startTime >= timeout*1000 ) {
                throw new TimeoutException("Execution of waitLock timed out after ${this.timeout} seconds")
            }
            common.infoMsg("'${this.name}' is locked. Retry in ${this.retryInterval} seconds")
            // Reset the cache so it will re-retrieve the file and its content
            // otherwise it cannot determine that file has been removed on artifactory
            // in the middle of waiting
            this.lockFileContentCache = null
            sleep(this.retryInterval*1000)
        }
    }

    private Boolean isLocked() {
        if (!isLockFileExist()) {
            common.infoMsg("Lock file for '${this.name}' does not exist")
            return false
        } else if (isLockExpired()) {
            common.infoMsg("Lock '${this.name}' has been expired")
            return false
        }
        return true
    }

    private Boolean isLockFileExist() {
        // If there is something in file's content that it definitly exists
        // If we don't know about file existence (fileNotFound == null) we assume it exists
        return !this.lockFileContent.isEmpty() || !this.fileNotFound
    }

    private Boolean isLockExpired() {
        if (!isLockFileExist()) {
            return true
        }
        Map lockMeta = common.readYaml2(text: this.lockFileContent ?: '{}')
        Date expirationTime = new Date(lockMeta.get('expiredAt', '01/01/1970'))
        Date currentTime = new Date()
        return currentTime.after(expirationTime)
    }
}
