package com.mirantis.mk

/**
 *
 * Git functions
 *
 */

/**
 * Checkout single git repository
 *
 * @param path            Directory to checkout repository to
 * @param url             Source Git repository URL
 * @param branch          Source Git repository branch
 * @param credentialsId   Credentials ID to use for source Git
 * @param poll            Enable git polling (default true)
 * @param timeout         Set checkout timeout (default 10)
 * @param depth           Git depth param (default 0 means no depth)
 * @param reference       Git reference param to checkout (default empyt, i.e. no reference)
 */
def checkoutGitRepository(path, url, branch, credentialsId = null, poll = true, timeout = 10, depth = 0, reference = ''){
    def branch_name = reference ? 'FETCH_HEAD' : "*/${branch}"
    dir(path) {
        checkout(
            changelog:true,
            poll: poll,
            scm: [
                $class: 'GitSCM',
                branches: [[name: branch_name]],
            doGenerateSubmoduleConfigurations: false,
            extensions: [
                [$class: 'CheckoutOption', timeout: timeout],
                [$class: 'CloneOption', depth: depth, noTags: false, shallow: depth > 0, timeout: timeout]],
            submoduleCfg: [],
            userRemoteConfigs: [[url: url, credentialsId: credentialsId, refspec: reference]]]
        )
        sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
    }
}

/**
 * Parse HEAD of current directory and return commit hash
 */
def getGitCommit() {
    git_commit = sh (
        script: 'git rev-parse HEAD',
        returnStdout: true
    ).trim()
    return git_commit
}

/**
 * Change actual working branch of repo
 *
 * @param path            Path to the git repository
 * @param branch          Branch desired to switch to
 */
def changeGitBranch(path, branch) {
    dir(path) {
        git_cmd = sh (
            script: "git checkout ${branch}",
            returnStdout: true
        ).trim()
    }
    return git_cmd
}

/**
 * Get remote URL
 *
 * @param name  Name of remote (default any)
 * @param type  Type (fetch or push, default fetch)
 */
def getGitRemote(name = '', type = 'fetch') {
    gitRemote = sh (
        script: "git remote -v | grep '${name}' | grep ${type} | awk '{print \$2}' | head -1",
        returnStdout: true
    ).trim()
    return gitRemote
}

/**
 * Get commit message for given commit reference
 */
def getGitCommitMessage(String path, String commitRef = 'HEAD') {
    dir(path) {
        commitMsg = sh (
            script: "git log --format=%B -n 1 ${commitRef}",
            returnStdout: true
        ).trim()
    }
    return commitMsg
}

/**
 * Create new working branch for repo
 *
 * @param path            Path to the git repository
 * @param branch          Branch desired to switch to
 */
def createGitBranch(path, branch) {
    def git_cmd
    dir(path) {
        git_cmd = sh (
            script: "git checkout -b ${branch}",
            returnStdout: true
        ).trim()
    }
    return git_cmd
}

/**
 * Commit changes to the git repo
 *
 * @param path            Path to the git repository
 * @param message         A commit message
 * @param global          Use global config
 * @param amend           Whether to use "--amend" in commit command
 */
def commitGitChanges(path, message, gitEmail='jenkins@localhost', gitName='jenkins-slave', global=false, amend=false) {
    def git_cmd
    def gitOpts
    def global_arg = ''
    if (global) {
        global_arg = '--global'
    }
    if (amend) {
        gitOpts = '--amend'
    } else {
        gitOpts = ''
    }
    def gitEnv = [
        "GIT_AUTHOR_NAME=${gitName}",
        "GIT_AUTHOR_EMAIL=${gitEmail}",
        "GIT_COMMITTER_NAME=${gitName}",
        "GIT_COMMITTER_EMAIL=${gitEmail}",
    ]
    dir(path) {
        sh "git config ${global_arg} user.email '${gitEmail}'"
        sh "git config ${global_arg} user.name '${gitName}'"

        sh(
            script: 'git add -A',
            returnStdout: true
        ).trim()
        withEnv(gitEnv) {
            git_cmd = sh(
                script: "git commit ${gitOpts} -m '${message}'",
                returnStdout: true
            ).trim()
        }
    }
    return git_cmd
}

/**
 * Push git changes to remote repo
 *
 * @param path            Path to the local git repository
 * @param branch          Branch on the remote git repository
 * @param remote          Name of the remote repository
 * @param credentialsId   Credentials with write permissions
 */
def pushGitChanges(path, branch = 'master', remote = 'origin', credentialsId = null) {
    def ssh = new com.mirantis.mk.Ssh()
    dir(path) {
        if (credentialsId == null) {
            sh script: "git push ${remote} ${branch}"
        }
        else {
            ssh.prepareSshAgentKey(credentialsId)
            ssh.runSshAgentCommand("git push ${remote} ${branch}")
        }
    }
}


/**
 * Mirror git repository, merge target changes (downstream) on top of source
 * (upstream) and push target or both if pushSource is true
 *
 * @param sourceUrl      Source git repository
 * @param targetUrl      Target git repository
 * @param credentialsId  Credentials id to use for accessing target repositories
 * @param branches       List or comma-separated string of branches to sync
 * @param followTags     Mirror tags
 * @param pushSource     Push back into source branch, resulting in 2-way sync
 * @param pushSourceTags Push target tags into source or skip pushing tags
 * @param gitEmail       Email for creation of merge commits
 * @param gitName        Name for creation of merge commits
 */
def mirrorGit(sourceUrl, targetUrl, credentialsId, branches, followTags = false, pushSource = false, pushSourceTags = false, gitEmail = 'jenkins@localhost', gitName = 'Jenkins', sourceRemote = 'origin') {
    def common = new com.mirantis.mk.Common()
    def ssh = new com.mirantis.mk.Ssh()
    if (branches instanceof String) {
        branches = branches.tokenize(',')
    }
    // If both source and target repos are secured and accessible via http/https,
    // we need to switch GIT_ASKPASS value when running git commands
    def sourceAskPass
    def targetAskPass

    def sshCreds = common.getCredentialsById(credentialsId, 'sshKey') // True if found
    if (sshCreds) {
        ssh.prepareSshAgentKey(credentialsId)
        ssh.ensureKnownHosts(targetUrl)
        sh "git config user.name '${gitName}'"
    } else {
        withCredentials([[$class          : 'UsernamePasswordMultiBinding',
                          credentialsId   : credentialsId,
                          passwordVariable: 'GIT_PASSWORD',
                          usernameVariable: 'GIT_USERNAME']]) {
            sh """
                set +x
                git config --global credential.${targetUrl}.username \${GIT_USERNAME}
                echo "echo \${GIT_PASSWORD}" > ${WORKSPACE}/${credentialsId}_askpass.sh
                chmod +x ${WORKSPACE}/${credentialsId}_askpass.sh
                git config user.name \${GIT_USERNAME}
            """
            sourceAskPass = env.GIT_ASKPASS ?: ''
            targetAskPass =  "${WORKSPACE}/${credentialsId}_askpass.sh"
        }
    }
    sh "git config user.email '${gitEmail}'"

    def remoteExistence = sh(script: "git remote -v | grep ${TARGET_URL} | grep target", returnStatus: true)
    if(remoteExistence == 0) {
        // silently try to remove target
        sh(script: "git remote remove target", returnStatus: true)
    }
    sh("git remote add target ${TARGET_URL}")
    if (sshCreds) {
        ssh.agentSh "git remote update --prune"
    } else {
        env.GIT_ASKPASS = sourceAskPass
        sh "git remote update ${sourceRemote} --prune"
        env.GIT_ASKPASS = targetAskPass
        sh "git remote update target --prune"
    }

    for (i=0; i < branches.size; i++) {
        branch = branches[i]
        sh "git branch | grep ${branch} || git checkout -b ${branch}"
        def resetResult = sh(script: "git checkout ${branch} && git reset --hard origin/${branch}", returnStatus: true)
        if(resetResult != 0){
            common.warningMsg("Cannot reset to origin/${branch} for perform git mirror, trying to reset from target/${branch}")
            resetResult = sh(script: "git checkout ${branch} && git reset --hard target/${branch}", returnStatus: true)
            if(resetResult != 0){
                throw new Exception("Cannot reset even to target/${branch}, git mirroring failed!")
            }
        }

        sh "git ls-tree target/${branch} && git merge --no-edit --ff target/${branch} || echo 'Target repository is empty, skipping merge'"
        followTagsArg = followTags ? "--follow-tags" : ""
        if (sshCreds) {
            ssh.agentSh "git push ${followTagsArg} target HEAD:${branch}"
        } else {
            sh "git push ${followTagsArg} target HEAD:${branch}"
        }

        if (pushSource == true) {
            followTagsArg = followTags && pushSourceTags ? "--follow-tags" : ""
            if (sshCreds) {
                ssh.agentSh "git push ${followTagsArg} origin HEAD:${branch}"
            } else {
                sh "git push ${followTagsArg} origin HEAD:${branch}"
            }
        }
    }
    if (followTags == true) {
        if (sshCreds) {
            ssh.agentSh "git push -f target --tags"
        } else {
            sh "git push -f target --tags"
        }

        if (pushSourceTags == true) {
            if (sshCreds) {
                ssh.agentSh "git push -f origin --tags"
            } else {
                sh "git push -f origin --tags"
            }
        }
    }
    sh "git remote rm target"
    if (!sshCreds) {
        sh "set +x; rm -f ${targetAskPass}"
        sh "git config --global --unset credential.${targetUrl}.username"
    }
}


/**
 * Return all branches for the defined git repository that match the matcher.
 *
 * @param repoUrl        URL of git repository
 * @param branchMatcher  matcher to filter out the branches (If '' or '*', returns all branches without filtering)
 * @return branchesList  list of branches
 */

def getBranchesForGitRepo(repoUrl, branchMatcher = ''){

    if (branchMatcher.equals("*")) {
        branchMatcher = ''
    }
    branchesList = sh (
                script: "git ls-remote --heads ${repoUrl} | cut -f2 | grep -e '${branchMatcher}' | sed 's/refs\\/heads\\///g'",
                returnStdout: true
        ).trim()
    return branchesList.tokenize('\n')
}

/**
 * Method for preparing a tag to be SemVer 2 compatible, and can handle next cases:
 * - length of tag splitted by dots is more than 3
 * - first part of splitted tag starts not from digit
 * - length of tag is lower than 3
 *
 * @param   tag       String which contains a git tag from repository
 * @return  HashMap   HashMap in the form: ['version': 'x.x.x', 'extra': 'x.x.x'], extra
 *                    is added only if size of original tag splitted by dots is more than 3
 */

def prepareTag(tag){
    def parts = tag.tokenize('.')
    def res = [:]
    // Handle case with tags like v1.1.1
    parts[0] = parts[0].replaceFirst("[^\\d.]", '')
    // handle different sizes of tags - 1.1.1.1 or 1.1.1.1rc1
    if (parts.size() > 3){
        res['extra'] = parts[3..-1].join('.')
    } else if (parts.size() < 3){
        (parts.size()..2).each {
            parts[it] = '0'
        }
    }
    res['version'] = "${parts[0]}.${parts[1]}.${parts[2]}"
    return res
}

/**
 * Method for incrementing SemVer 2 compatible version
 *
 * @param version  String which contains main part of SemVer2 version - '2.1.0'
 * @return string  String conaining version with Patch part of version incremented by 1
 */

def incrementVersion(version){
    def parts = checkVersion(version)
    return "${parts[0]}.${parts[1]}.${parts[2].toInteger() + 1}"
}

/**
 * Method for checking whether version is compatible with Sem Ver 2
 *
 * @param version  String which contains main part of SemVer2 version - '2.1.0'
 * @return list    With 3 strings as result of splitting version by dots
 */

def checkVersion(version) {
    def parts = version.tokenize('.')
    if (parts.size() != 3 || !(parts[0] ==~ /^\d+/)) {
        error "Bad version ${version}"
    }
    return parts
}

/**
 * Method for constructing SemVer2 compatible version from tag in Git repository:
 * - if current commit matches the last tag, last tag will be returned as version
 * - if no tag found assuming no release was done, version will be 0.0.1 with pre release metadata
 * - if tag found - patch part of version will be incremented and pre-release metadata will be added
 *
 *
 * @param repoDir          String which contains path to directory with git repository
 * @param allowNonSemVer2  Bool   whether to allow working with tags which aren't compatible
 *                                with Sem Ver 2 (not in form X.Y.Z). if set to true tag will be
*                                 converted to Sem Ver 2 version e.g tag 1.1.1.1rc1 -> version 1.1.1-1rc1
 * @return version  String
 */
def getVersion(repoDir, allowNonSemVer2 = false) {
    def common = new com.mirantis.mk.Common()
    dir(repoDir){
        def cmd = common.shCmdStatus('git describe --tags --first-parent --abbrev=0')
        def tag_data = [:]
        def last_tag = cmd['stdout'].trim()
        def commits_since_tag
        if (cmd['status'] != 0){
            if (cmd['stderr'].contains('fatal: No names found, cannot describe anything')){
                common.warningMsg('No parent tag found, using initial version 0.0.0')
                tag_data['version'] = '0.0.0'
                commits_since_tag = sh(script: 'git rev-list --count HEAD', returnStdout: true).trim()
            } else {
                error("Something went wrong, cannot find git information ${cmd['stderr']}")
            }
        } else {
            tag_data['version'] = last_tag
            commits_since_tag = sh(script: "git rev-list --count ${last_tag}..HEAD", returnStdout: true).trim()
        }
        try {
            checkVersion(tag_data['version'])
        } catch (Exception e) {
            if (allowNonSemVer2){
                common.errorMsg(
    """Git tag isn't compatible with SemVer2, but allowNonSemVer2 is set.
    Trying to convert git tag to Sem Ver 2 compatible version
    ${e.message}""")
                tag_data = prepareTag(tag_data['version'])
            } else {
                error("Git tag isn't compatible with SemVer2\n${e.message}")
            }
        }
        // If current commit is exact match to the first parent tag than return it
        def pre_release_meta = []
        if (tag_data.get('extra')){
            pre_release_meta.add(tag_data['extra'])
        }
        if (common.shCmdStatus('git describe --tags --first-parent --exact-match')['status'] == 0){
            if (pre_release_meta){
                return "${tag_data['version']}-${pre_release_meta[0]}"
            } else {
                return tag_data['version']
            }
        }
        // If we away from last tag for some number of commits - add additional metadata and increment version
        pre_release_meta.add(commits_since_tag)
        def next_version = incrementVersion(tag_data['version'])
        def commit_sha = sh(script: 'git rev-parse --short=7 HEAD', returnStdout: true).trim()
        return "${next_version}-${pre_release_meta.join('.')}-${commit_sha}"
    }
}


/**
 * Method for uploading a change request
 *
 * @param repo              String which contains path to directory with git repository
 * @param credentialsId     Credentials id to use for accessing target repositories
 * @param commit            Id of commit which should be uploaded
 * @param branch            Name of the branch for uploading
 * @param topic             Topic of the change
 *
 */
def pushForReview(repo, credentialsId, commit, branch, topic='', remote='origin') {
    def common = new com.mirantis.mk.Common()
    def ssh = new com.mirantis.mk.Ssh()
    common.infoMsg("Uploading commit ${commit} to ${branch} for review...")

    def pushArg = "${commit}:refs/for/${branch}"
    def process = [:]
    if (topic){
        pushArg += '%topic=' + topic
    }
    dir(repo){
        ssh.prepareSshAgentKey(credentialsId)
        ssh.runSshAgentCommand("git push ${remote} ${pushArg}")
    }
}

/**
 * Generates a commit message with predefined or auto generate change id. If change
 * id isn't provided, changeIdSeed and current sha of git head will be used in
 * generation of commit change id.
 *
 * @param repo              String which contains path to directory with git repository
 * @param message           Commit message main part
 * @param changeId          User defined change-id usually sha1 hash
 * @param changeIdSeed      Custom part of change id which can be added during change id generation
 *
 *
 * @return commitMessage    Multiline String with generated commit message
 */
def genCommitMessage(repo, message, changeId = '', changeIdSeed = ''){
    def git = new com.mirantis.mk.Git()
    def common = new com.mirantis.mk.Common()
    def commitMessage
    def id = changeId
    def seed = changeIdSeed
    if (!id) {
        if (!seed){
            seed = common.generateRandomHashString(32)
        }
        def head_sha
        dir(repo){
            head_sha = git.getGitCommit()
        }
        id = 'I' + sh(script: 'echo -n ' + seed + head_sha + ' | sha1sum | awk \'{print $1}\'', returnStdout: true)
    }
    commitMessage =
            """${message}

           |Change-Id: ${id}
        """.stripMargin()

    return commitMessage
}

/**
 * Update (or create if cannot find) gerrit change request
 *
 * @param params   Map of parameters to customize commit
 *   - gerritAuth         A map containing information about Gerrit. Should include HOST, PORT and USER
 *   - credentialsId      Jenkins credentials id for gerrit
 *   - repo               Local directory with repository
 *   - comment            Commit comment
 *   - change_id_seed     Custom part of change id which can be added during change id generation
 *   - branch             Name of the branch for uploading
 *   - topic              Topic of the change
 *   - project            Gerrit project to search in for gerrit change request
 *   - status             Change request's status to search for
 *   - changeAuthorEmail  Author's email of the change
 *   - changeAuthorName   Author's name of the change
 */
def updateChangeRequest(Map params) {
    def gerrit = new com.mirantis.mk.Gerrit()

    def commitMessage
    def auth = params['gerritAuth']
    def creds = params['credentialsId']
    def repo = params['repo']
    def comment = params['comment']
    def change_id_seed = params.get('change_id_seed', JOB_NAME)
    def branch = params['branch']
    def topic = params['topic']
    def project = params['project']
    def status = params.get('status', 'open')
    def changeAuthorEmail = params['changeAuthorEmail']
    def changeAuthorName = params['changeAuthorName']

    def changeParams = ['owner': auth['USER'], 'status': status, 'project': project, 'branch': branch, 'topic': topic]
    def gerritChange = gerrit.findGerritChange(creds, auth, changeParams)
    def changeId = params.get('changeId', '')
    def commit
    if (gerritChange) {
        def jsonChange = readJSON text: gerritChange
        changeId = jsonChange['id']
    }
    commitMessage = genCommitMessage(repo, comment, changeId, change_id_seed)
    commitGitChanges(repo, commitMessage, changeAuthorEmail, changeAuthorName, false, false)
    dir(repo){
        commit = getGitCommit()
    }
    pushForReview(repo, creds, commit, branch, topic)
}
