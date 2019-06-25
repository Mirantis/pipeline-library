package com.mirantis.mk

/**
 * ReleaseWorkflow functions
 *
 */

/**
 * Update release metadata after image build
 */

def updateReleaseMetadata(){
    def python = new com.mirantis.mk.Python()
    def gerrit = new com.mirantis.mk.Gerrit()
    def git = new com.mirantis.mk.Git()
    def changeAuthorName = "MCP-CI"
    def changeAuthorEmail = "mcp-ci-jenkins@ci.mcp.mirantis.net"
    def cred = common.getCredentials(env["CREDENTIALS_ID"], 'key')
    String gerritUser = cred.username
    def gerritHost = env["METADATA_REPO_URL.tokenize"]('@')[-1].tokenize(':')[0]
    def metadataProject = env["METADATA_REPO_URL"].tokenize('/')[-2..-1].join('/')
    def gerritPort = env["METADATA_REPO_URL"].tokenize(':')[-1].tokenize('/')[0]
    def workspace = common.getWorkspace()
    def venvDir = "${workspace}/gitreview-venv"
    def repoDir = "${venvDir}/repo"
    def metadataDir = "${repoDir}/metadata"
    def imageChangeId
    def commitMessage
    def gitRemote
    if (env["RELEASE_METADATA_CR"].toBoolean()) {
        stage("Installing virtualenv") {
            python.setupVirtualenv(venvDir, 'python3', ['git-review', 'PyYaml'])
        }

        stage('Cleanup repo dir') {
            dir(repoDir) {
                deleteDir()
            }
        }
        stage('Cloning release-metadata repository') {
            git.checkoutGitRepository(repoDir, METADATA_REPO_URL, METADATA_GERRIT_BRANCH, CREDENTIALS_ID, true, 10, 0)
            dir(repoDir) {
                gitRemote = sh(
                        script:
                                'git remote -v | head -n1 | cut -f1',
                        returnStdout: true,
                ).trim()
            }
        }
        stage('Creating CRs') {
            for (openstackRelease in resultBuiltImages.keySet()) {
                def crTopic = "nightly_update_images_" + openstackRelease
                //Check if CR already exist

                def gerritAuth = ['PORT': gerritPort, 'USER': gerritUser, 'HOST': gerritHost]
                def changeParams = ['owner': gerritUser, 'status': 'open', 'project': metadataProject, 'branch': env["METADATA_GERRIT_BRANCH"], 'topic': crTopic]
                def gerritChange = gerrit.findGerritChange(env["CREDENTIALS_ID"], gerritAuth, changeParams)

                git.changeGitBranch(repoDir, env["METADATA_GERRIT_BRANCH"])
                if (gerritChange) {
                    def jsonChange = readJSON text: gerritChange
                    changeNum = jsonChange['number']
                    imageChangeId = 'Change-Id: '
                    imageChangeId += jsonChange['id']
                    //get existent change from gerrit
                    gerrit.getGerritChangeByNum(env["CREDENTIALS_ID"], venvDir, repoDir, gitRemote, changeNum)
                } else {
                    imageChangeId = ''
                    git.createGitBranch(repoDir, crTopic)
                }

                for (component in resultBuiltImages[openstackRelease].keySet()) {
                    resultBuiltImages[openstackRelease][component].each {
                        //runReleaseMetadataApp(venvDir, repoDir, metadataDir, "update", "images:openstack:${openstackRelease}:${component}:${it.key}", "${it.value}")
                        cmdText = "python ${repoDir}/utils/app.py --path ${metadataDir} update --key images:openstack:${openstackRelease}:${component}:${it.key} --value ${it.value}"
                        python.runVirtualenvCommand(venvDir, cmdText)
                    }
                }

                commitMessage =
                        """[oscore] Auto-update ${metadataProject}

               |${imageChangeId}
            """.stripMargin()
                //commit change
                if (gerritChange) {
                    git.commitGitChanges(repoDir, commitMessage, changeAuthorEmail, changeAuthorName, false, true)
                } else {
                    git.commitGitChanges(repoDir, commitMessage, changeAuthorEmail, changeAuthorName, false)
                }
                //post change
                gerrit.postGerritReview(CREDENTIALS_ID, venvDir, repoDir, changeAuthorName, changeAuthorEmail, gitRemote, crTopic, METADATA_GERRIT_BRANCH)

            }
        }
    }
}