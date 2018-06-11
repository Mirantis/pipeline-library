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
 */
def checkoutGitRepository(path, url, branch, credentialsId = null, poll = true, timeout = 10, depth = 0){
    dir(path) {
        checkout(
            changelog:true,
            poll: poll,
            scm: [
                $class: 'GitSCM',
                branches: [[name: "*/${branch}"]],
            doGenerateSubmoduleConfigurations: false,
            extensions: [
                [$class: 'CheckoutOption', timeout: timeout],
                [$class: 'CloneOption', depth: depth, noTags: false, reference: '', shallow: depth > 0, timeout: timeout]],
            submoduleCfg: [],
            userRemoteConfigs: [[url: url, credentialsId: credentialsId]]]
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
 */
def commitGitChanges(path, message, gitEmail='jenkins@localhost', gitName='jenkins-slave') {
    def git_cmd
    dir(path) {
        sh "git config --global user.email '${gitEmail}'"
        sh "git config --global user.name '${gitName}'"

        sh(
            script: 'git add -A',
            returnStdout: true
        ).trim()
        git_cmd = sh(
            script: "git commit -m '${message}'",
            returnStdout: true
        ).trim()
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
 * @param credentialsId  Credentials id to use for accessing source/target
 *                       repositories
 * @param branches       List or comma-separated string of branches to sync
 * @param followTags     Mirror tags
 * @param pushSource     Push back into source branch, resulting in 2-way sync
 * @param pushSourceTags Push target tags into source or skip pushing tags
 * @param gitEmail       Email for creation of merge commits
 * @param gitName        Name for creation of merge commits
 */
def mirrorGit(sourceUrl, targetUrl, credentialsId, branches, followTags = false, pushSource = false, pushSourceTags = false, gitEmail = 'jenkins@localhost', gitName = 'Jenkins') {
    def common = new com.mirantis.mk.Common()
    def ssh = new com.mirantis.mk.Ssh()
    if (branches instanceof String) {
        branches = branches.tokenize(',')
    }

    ssh.prepareSshAgentKey(credentialsId)
    ssh.ensureKnownHosts(targetUrl)
    sh "git config user.email '${gitEmail}'"
    sh "git config user.name '${gitName}'"

    def remoteExistence = sh(script: "git remote -v | grep ${TARGET_URL} | grep target", returnStatus: true)
    if(remoteExistence != 0){
       // silently try to remove target
       sh(script:"git remote remove target", returnStatus: true)
       sh("git remote add target ${TARGET_URL}")
    }
    ssh.agentSh "git remote update --prune"

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
        ssh.agentSh "git push ${followTagsArg} target HEAD:${branch}"

        if (pushSource == true) {
            followTagsArg = followTags && pushSourceTags ? "--follow-tags" : ""
            ssh.agentSh "git push ${followTagsArg} origin HEAD:${branch}"
        }
    }
    if (followTags == true) {
        ssh.agentSh "git push -f target --tags"

        if (pushSourceTags == true) {
            ssh.agentSh "git push -f origin --tags"
        }
    }
    sh "git remote rm target"
}
