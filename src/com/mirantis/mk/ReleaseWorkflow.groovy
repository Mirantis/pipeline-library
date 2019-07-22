package com.mirantis.mk
/**
 * ReleaseWorkflow functions
 *
 */

/**
 * Get release metadata value for given key
 *
 * @param key metadata key
 * @param params map with expected parameters:
 *    - toxDockerImage
 *    - metadataCredentialsId
 *    - metadataGitRepoUrl
 *    - metadataGitRepoBranch
 *    - repoDir
 */
def getReleaseMetadataValue(String key, Map params = [:]) {
    String result
    // Get params
    String toxDockerImage   = params.get('toxDockerImage', 'docker-prod-virtual.docker.mirantis.net/mirantis/external/tox')
    String gitCredentialsId = params.get('metadataCredentialsId', 'mcp-ci-gerrit')
    String gitUrl           = params.get('metadataGitRepoUrl', "ssh://${gitCredentialsId}@gerrit.mcp.mirantis.net:29418/mcp/release-metadata")
    String gitBranch        = params.get('metadataGitRepoBranch', 'master')
    String repoDir          = params.get('repoDir', 'release-metadata')
    String outputFormat     = params.get('outputFormat', 'json')

    // Libs
    def git = new com.mirantis.mk.Git()

    String opts = ''
    if (outputFormat && !outputFormat.isEmpty()) {
        opts += " --${outputFormat}"
    }
    // TODO cache it somehow to not checkout it all the time
    git.checkoutGitRepository(repoDir, gitUrl, gitBranch, gitCredentialsId, true, 10, 0)
    docker.image(toxDockerImage).inside {
        result = sh(script: "cd ${repoDir} && tox -qq -e metadata -- ${opts} get --key ${key}", returnStdout: true).trim()
    }
    return result
}

/**
 * Update release metadata after image build
 *
 * @param key metadata key
 * @param value metadata value
 * @param params string map with credentialsID, metadataRepoUrl, metadataGerritBranch and crTopic
 */

def updateReleaseMetadata(String key, String value, Map params) {
    credentialsID = params['credentialsID'] ?: "mcp-ci-gerrit"
    metadataRepoUrl = params['metadataRepoUrl'] ?: "ssh://mcp-ci-gerrit@gerrit.mcp.mirantis.net:29418/mcp/release-metadata"
    metadataGerritBranch = params['metadataGerritBranch'] ?: "master"
    comment = params['comment'] ?: ""
    crTopic = params['crTopic'] ?: ""
    def common = new com.mirantis.mk.Common()
    def python = new com.mirantis.mk.Python()
    def gerrit = new com.mirantis.mk.Gerrit()
    def git = new com.mirantis.mk.Git()
    def changeAuthorName = "MCP-CI"
    def changeAuthorEmail = "mcp-ci-jenkins@ci.mcp.mirantis.net"
    def cred = common.getCredentials(credentialsID, 'key')
    String gerritUser = cred.username
    def gerritHost = metadataRepoUrl.tokenize('@')[-1].tokenize(':')[0]
    def metadataProject = metadataRepoUrl.tokenize('/')[-2..-1].join('/')
    def gerritPort = metadataRepoUrl.tokenize(':')[-1].tokenize('/')[0]
    def workspace = common.getWorkspace()
    def venvDir = "${workspace}/gitreview-venv"
    def repoDir = "${venvDir}/repo"
    def metadataDir = "${repoDir}/metadata"
    def ChangeId
    def commitMessage
    def gitRemote
    stage("Installing virtualenv") {
        python.setupVirtualenv(venvDir, 'python3', ['git-review', 'PyYaml'])
    }
    stage('Cleanup repo dir') {
        dir(repoDir) {
            deleteDir()
        }
    }
    stage('Cloning release-metadata repository') {
        git.checkoutGitRepository(repoDir, metadataRepoUrl, metadataGerritBranch, credentialsID, true, 10, 0)
        dir(repoDir) {
            gitRemote = sh(
                    script:
                            'git remote -v | head -n1 | cut -f1',
                    returnStdout: true,
            ).trim()
        }
    }
    stage('Creating CR') {
        def gerritAuth = ['PORT': gerritPort, 'USER': gerritUser, 'HOST': gerritHost]
        def changeParams = ['owner': gerritUser, 'status': 'open', 'project': metadataProject, 'branch': metadataGerritBranch, 'topic': crTopic]
        def gerritChange = gerrit.findGerritChange(credentialsID, gerritAuth, changeParams)
        git.changeGitBranch(repoDir, metadataGerritBranch)
        if (gerritChange) {
            def jsonChange = readJSON text: gerritChange
            changeNum = jsonChange['number']
            ChangeId = 'Change-Id: '
            ChangeId += jsonChange['id']
            //get existent change from gerrit
            gerrit.getGerritChangeByNum(credentialsID, venvDir, repoDir, gitRemote, changeNum)
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
        if (gerritChange) {
            git.commitGitChanges(repoDir, commitMessage, changeAuthorEmail, changeAuthorName, false, true)
        } else {
            git.commitGitChanges(repoDir, commitMessage, changeAuthorEmail, changeAuthorName, false)
        }
        //post change
        gerrit.postGerritReview(credentialsID, venvDir, repoDir, changeAuthorName, changeAuthorEmail, gitRemote, crTopic, metadataGerritBranch)
    }
}
