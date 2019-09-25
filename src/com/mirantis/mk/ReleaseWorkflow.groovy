package com.mirantis.mk
/**
 * Checkout release metadata repo with clone or without, if cloneRepo parameter is set
 *
 * @param params map with expected parameters:
 *    - metadataCredentialsId
 *    - metadataGitRepoUrl
 *    - metadataGitRepoBranch
 *    - repoDir
 *    - cloneRepo
 */
def checkoutReleaseMetadataRepo(Map params = [:]) {
    def git = new com.mirantis.mk.Git()

    String gitCredentialsId = params.get('metadataCredentialsId', 'mcp-ci-gerrit')
    String gitUrl           = params.get('metadataGitRepoUrl', "ssh://${gitCredentialsId}@gerrit.mcp.mirantis.net:29418/mcp/artifact-metadata")
    String gitBranch        = params.get('metadataGitRepoBranch', 'master')
    String gitRef           = params.get('metadataGitRepoRef', '')
    String repoDir          = params.get('repoDir', 'artifact-metadata')
    Boolean cloneRepo       = params.get('cloneRepo', true)
    if (cloneRepo) {
        stage('Cleanup repo dir') {
            dir(repoDir) {
                deleteDir()
            }
        }
        stage('Cloning artifact-metadata repository') {
            git.checkoutGitRepository(repoDir, gitUrl, gitBranch, gitCredentialsId, true, 10, 0, gitRef)
        }
    } else {
        git.changeGitBranch(repoDir, gitRef ?: gitBranch)
    }
}

/**
 * Get release metadata value for given key
 *
 * @param key metadata key
 * @param params map with expected parameters:
 *    - toxDockerImage
 *    - outputFormat
 *    - repoDir
 */
def getReleaseMetadataValue(String key, Map params = [:]) {
    String result
    // Get params
    String toxDockerImage   = params.get('toxDockerImage', 'docker-prod-virtual.docker.mirantis.net/mirantis/external/tox')
    String outputFormat     = params.get('outputFormat', 'json')
    String repoDir          = params.get('repoDir', 'artifact-metadata')

    // Libs
    def common = new com.mirantis.mk.Common()

    String opts = ''
    if (outputFormat && !outputFormat.isEmpty()) {
        opts += " --${outputFormat}"
    }

    checkoutReleaseMetadataRepo(params)

    docker.image(toxDockerImage).inside {
        result = sh(script: "cd ${repoDir} && tox -qq -e metadata -- ${opts} get --key ${key}", returnStdout: true).trim()
    }
    common.infoMsg("""
    Release metadata key ${key} has value:
        ${result}
    """)
    return result
}

/**
 * Update release metadata value and upload CR to release metadata repository
 *
 * @param key metadata key
 * @param value metadata value
 * @param params map with expected parameters:
 *    - metadataCredentialsId
 *    - metadataGitRepoUrl
 *    - metadataGitRepoBranch
 *    - repoDir
 *    - comment
 *    - crTopic
 *    - crAuthorName
 *    - crAuthorEmail
 */

def updateReleaseMetadata(String key, String value, Map params) {
    String gitCredentialsId     = params.get('metadataCredentialsId', 'mcp-ci-gerrit')
    String metadataRepoUrl      = params.get('metadataGitRepoUrl', "ssh://${gitCredentialsId}@gerrit.mcp.mirantis.net:29418/mcp/artifact-metadata")
    String metadataGerritBranch = params.get('metadataGitRepoBranch', 'master')
    String repoDir              = params.get('repoDir', 'artifact-metadata')
    String comment              = params.get('comment', '')
    String crTopic              = params.get('crTopic', '')
    String changeAuthorName     = params.get('crAuthorName', 'MCP-CI')
    String changeAuthorEmail    = params.get('crAuthorEmail', 'mcp-ci-jenkins@ci.mcp.mirantis.net')

    def common = new com.mirantis.mk.Common()
    def python = new com.mirantis.mk.Python()
    def gerrit = new com.mirantis.mk.Gerrit()
    def git    = new com.mirantis.mk.Git()

    def cred = common.getCredentials(gitCredentialsId, 'key')
    String gerritUser = cred.username
    String gerritHost = metadataRepoUrl.tokenize('@')[-1].tokenize(':')[0]
    String metadataProject = metadataRepoUrl.tokenize('/')[-2..-1].join('/')
    String gerritPort = metadataRepoUrl.tokenize(':')[-1].tokenize('/')[0]
    String workspace = common.getWorkspace()
    String venvDir = "${workspace}/gitreview-venv"
    String metadataDir = "${repoDir}/metadata"
    String ChangeId
    String commitMessage
    String gitRemote
    stage("Installing virtualenv") {
        python.setupVirtualenv(venvDir, 'python3', ['git-review', 'PyYaml'])
    }
    checkoutReleaseMetadataRepo(params)
    dir(repoDir) {
        gitRemote = sh(
            script:
                'git remote -v | head -n1 | cut -f1',
                returnStdout: true,
        ).trim()
    }

    stage('Creating CR') {
        def gerritAuth = ['PORT': gerritPort, 'USER': gerritUser, 'HOST': gerritHost]
        def changeParams = ['owner': gerritUser, 'status': 'open', 'project': metadataProject, 'branch': metadataGerritBranch, 'topic': crTopic]
        def gerritChange = gerrit.findGerritChange(gitCredentialsId, gerritAuth, changeParams)
        git.changeGitBranch(repoDir, metadataGerritBranch)
        if (gerritChange) {
            def jsonChange = readJSON text: gerritChange
            changeNum = jsonChange['number']
            ChangeId = 'Change-Id: '
            ChangeId += jsonChange['id']
        } else {
            ChangeId = ''
            git.createGitBranch(repoDir, crTopic)
        }
        cmdText = "python '${repoDir}/utils/app.py' --path '${metadataDir}' update --key '${key}' --value '${value}'"
        python.runVirtualenvCommand(venvDir, cmdText)
        commitMessage =
                """${comment}

               |${ChangeId}
            """.stripMargin()
        //commit change
        git.commitGitChanges(repoDir, commitMessage, changeAuthorEmail, changeAuthorName, false)
        //post change
        gerrit.postGerritReview(gitCredentialsId, venvDir, repoDir, changeAuthorName, changeAuthorEmail, gitRemote, crTopic, metadataGerritBranch)
    }
}
