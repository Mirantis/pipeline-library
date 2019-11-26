package com.mirantis.mk
/**
 * Checkout KaaS release metadata repo with clone or without, if cloneRepo parameter is set
 *
 * @param params map with expected parameters:
 *    - metadataCredentialsId
 *    - metadataGitRepoUrl
 *    - metadataGitRepoBranch
 *    - repoDir
 *    - cloneRepo
 */
def checkoutKaasReleaseMetadataRepo(Map params = [:]) {
    def git = new com.mirantis.mk.Git()

    String gitCredentialsId = params.get('metadataCredentialsId', 'mcp-ci-gerrit')
    String gitUrl           = params.get('metadataGitRepoUrl', "ssh://${gitCredentialsId}@gerrit.mcp.mirantis.net:29418/kaas/releases")
    String gitBranch        = params.get('metadataGitRepoBranch', 'master')
    String gitRef           = params.get('metadataGitRepoRef', 'HEAD')
    String repoDir          = params.get('repoDir', 'releases')
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
 * Update KaaS release metadata value and upload CR to release metadata repository
 *
 * @param key metadata key (Several keys could be passed joined by ';' character)
 * @param value metadata value (Several values could be passed joined by ';' character)
 * @param params map with expected parameters:
 *    - metadataCredentialsId
 *    - metadataGitRepoUrl
 *    - metadataGitRepoBranch
 *    - repoDir
 *    - comment
 *    - crTopic
 *    - crAuthorName
 *    - crAuthorEmail
 *    - kaasMetadataFileToUpdate
 */

def updateKaasReleaseMetadata(String key, String value, Map params) {
    String gitCredentialsId     = params.get('metadataCredentialsId', 'mcp-ci-gerrit')
    String metadataRepoUrl      = params.get('metadataGitRepoUrl', "ssh://${gitCredentialsId}@gerrit.mcp.mirantis.net:29418/kaas/releases")
    String metadataGerritBranch = params.get('metadataGitRepoBranch', 'master')
    String repoDir              = params.get('repoDir', 'releases')
    String comment              = params.get('comment', '')
    String crTopic              = params.get('crTopic', '')
    String changeAuthorName     = params.get('crAuthorName', 'MCP-CI')
    String changeAuthorEmail    = params.get('crAuthorEmail', 'mcp-ci-jenkins@ci.mcp.mirantis.net')
    String fileToUpdatePath     = params.get('kaasMetadataFileToUpdatePath', '')
    String updateChartVersion   = params.get('kaasMetadataUpdateChartVersion', '1')
    String updateTagVersion     = params.get('kaasMetadataUpdateTagVersion', '1')

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
    String ChangeId
    String commitMessage
    String gitRemote
    stage("Installing virtualenv") {
        python.setupVirtualenv(venvDir, 'python3', ['git-review'])
    }
    checkoutKaasReleaseMetadataRepo(params)
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

        def keyArr = key.split(';')
        def valueArr = value.split(';')
        if (keyArr.size() == valueArr.size()) {
            for (i in 0..keyArr.size()-1) {
                common.infoMsg("Setting ${keyArr[i]} version to: ${valueArr[i]}")
                sh '''set +x
                tmpfile=\$(mktemp kaas_tmp_file.XXXXXX)
                trap "rm -f \$tmpfile" 1 2 3 6

                awk \
                  -v key_name='''+keyArr[i]+''' \
                  -v key_ver='''+valueArr[i]+''' \
                  -v update_chart_version='''+updateChartVersion+''' \
                  -v update_tag_version='''+updateTagVersion+''' '
BEGIN {
    match_found = 0;
}

// {
if ($0 ~ "^\\\\s+- name: "key_name) {
    match_found = 1;
    print $0;
    next;
}
if ($0 ~ /^\\s+- name: /) {
    match_found = 0;
}
if (update_chart_version && match_found && $0 ~ /^\\s+version: /) {
    print gensub(/(\\s+version:).*/,"\\\\1 "key_ver,1,$0);
    next;
}
if (update_tag_version && match_found && $0 ~ /^\\s+tag: /) {
    print gensub(/(\\s+tag:).*/,"\\\\1 "key_ver,1,$0);
    next;
}
print $0
}' '''+repoDir+'/'+fileToUpdatePath+''' > "\$tmpfile"
                mv "\${tmpfile}" '''+repoDir+'/'+fileToUpdatePath+'''
                '''
            }
        }

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
