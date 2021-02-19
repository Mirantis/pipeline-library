import org.jfrog.hudson.pipeline.common.types.ArtifactoryServer
import java.util.concurrent.TimeoutException
import groovy.time.TimeCategory

class Lock {
    String  name, id, path
    String retryInterval, timeout, expiration
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
        this.id = args.getOrDefault('id', '')
        this.path = args.getOrDefault('path', 'binary-dev-local/locks')
        this.retryInterval = args.getOrDefault('retryInterval', '5m')
        this.timeout = args.getOrDefault('timeout', '3h')
        this.expiration = args.getOrDefault('expiration', '24h')
        this.force = args.getOrDefault('force', false)
        this.lockExtraMetadata = args.getOrDefault('lockExtraMetadata', [:])

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
        if (this.force || (this.id && this.id == lockMeta.getOrDefault('lockID', ''))) {
            artifactoryTools.restCall(this.artObj, this.fileUri, 'DELETE', null, [:], '')
            common.infoMsg("Lock file '${this.artObj['url']}${this.fileUri}' has been removed")
        } else {
            throw new RuntimeException("Given lock ID '${this.id}' is not equal to '${lockMeta.get('lockID')}' ID in lock file")
        }
    }

    private void createLockFile() {
        this.id = UUID.randomUUID().toString()

        Date now = new Date()
        Date expiredAt = TimeCategory.plus(now, common.getDuration(this.expiration))
        Map lockMeta = [
            'lockID': this.id,
            'createdAt': now.toString(),
            'expiredAt': expiredAt.toString(),
        ]
        lockMeta.putAll(this.lockExtraMetadata)

        def commonMCP = new com.mirantis.mcp.Common()
        artifactoryTools.restCall(this.artObj, this.fileUri, 'PUT', commonMCP.dumpYAML(lockMeta), [:], '')
        common.infoMsg("Lock file '${this.artObj['url']}${this.fileUri}' has been created")
    }

    private void waitLockReleased() {
        Long startTime = System.currentTimeMillis()
        Long timeoutMillis = common.getDuration(this.timeout).toMilliseconds()
        Long retryIntervalMillis = common.getDuration(this.retryInterval).toMilliseconds()
        while (isLocked()) {
            if (System.currentTimeMillis() - startTime >= timeoutMillis) {
                throw new TimeoutException("Execution of waitLock timed out after ${this.timeout}")
            }
            common.infoMsg("'${this.name}' is locked. Retry in ${this.retryInterval}")
            // Reset the cache so it will re-retrieve the file and its content
            // otherwise it cannot determine that file has been removed on artifactory
            // in the middle of waiting
            this.lockFileContentCache = null
            sleep(retryIntervalMillis)
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
        Date expirationTime = new Date(lockMeta.getOrDefault('expiredAt', '01/01/1970'))
        Date currentTime = new Date()
        return currentTime.after(expirationTime)
    }
}
