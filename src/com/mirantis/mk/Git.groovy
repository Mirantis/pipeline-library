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
 */
def checkoutGitRepository(path, url, branch, credentialsId = null){
    checkout([
        $class: 'GitSCM',
        branches: [[name: "*/${branch}"]],
        doGenerateSubmoduleConfigurations: false,
        extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: path]],
        submoduleCfg: [],
        userRemoteConfigs: [[url: url, credentialsId: credentialsId]]
    ])
    dir(path) {
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
 * Change actual working branch of repo
 *
 * @param path            Path to the git repository
 * @param branch          Branch desired to switch to
 */
def changeGitBranch(path, branch) {
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
def commitGitChanges(path, message) {
    dir(path) {
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
 * @param path            Path to the git repository
 * @param branch          Branch on the remote git repository
 * @param remote          Name of the remote repository
 */
def pushGitChanges(path, branch = 'master', remote = 'origin') {
    dir(path) {
        git_cmd = sh(
            script: "git push ${remote} ${branch}",
            returnStdout: true
        ).trim()
    }
    return git_cmd
}


/**
 * Checkout git repositories in parallel
 *
 * @param path            Directory to checkout to
 * @param url             Git repository url
 * @param branch          Git repository branch
 * @param credentialsId   Credentials ID to use
 * @param poll            Poll automatically
 * @param clean           Clean status
 */
def checkoutGitParallel(path, url, branch, credentialsId = null, poll = true, clean = true) {
    return {
        print "Checking out ${url}, branch ${branch} into ${path}"
        dir(path) {
            git url: url,
                branch: branch,
                credentialsId: credentialsId,
                poll: poll,
                clean: clean
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
    if (branches instanceof String) {
        branches = branches.tokenize(',')
    }

    def ssh = new com.mirantis.mk.Ssh()
    ssh.prepareSshAgentKey(credentialsId)
    ssh.ensureKnownHosts(targetUrl)
    sh "git config user.email '${gitEmail}'"
    sh "git config user.name '${gitName}'"

    sh "git remote | grep target || git remote add target ${TARGET_URL}"
    ssh.agentSh "git remote update --prune"

    for (i=0; i < branches.size; i++) {
        branch = branches[i]
        sh "git branch | grep ${branch} || git checkout -b ${branch} origin/${branch}"
        sh "git branch | grep ${branch} && git checkout ${branch} && git reset --hard origin/${branch}"

        sh "git ls-tree target/${branch} && git merge --no-edit --ff target/${branch} || echo 'Target repository is empty, skipping merge'"
        followTagsArg = followTags ? "--follow-tags" : ""
        ssh.agentSh "git push ${followTagsArg} target HEAD:${branch}"

        if (pushSource == true) {
            followTagsArg = followTags && pushSourceTags ? "--follow-tags" : ""
            agentSh "git push ${followTagsArg} origin HEAD:${branch}"
        }
    }

    if (followTags == true) {
        ssh.agentSh "git push target --tags"

        if (pushSourceTags == true) {
            ssh.agentSh "git push origin --tags"
        }
    }
}
