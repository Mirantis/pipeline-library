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
 * Mirror git repository
 */
def mirrorReporitory(sourceUrl, targetUrl, credentialsId, branches, followTags = false, gitEmail = 'jenkins@localhost', gitUsername = 'Jenkins') {
    def ssl = new com.mirantis.mk.ssl()
    if (branches instanceof String) {
        branches = branches.tokenize(',')
    }
    ssl.prepareSshAgentKey(credentialsId)
    ssl.ensureKnownHosts(targetUrl)

    sh "git remote | grep target || git remote add target ${TARGET_URL}"
    agentSh "git remote update --prune"
    for (i=0; i < branches.size; i++) {
        branch = branches[i]
        sh "git branch | grep ${branch} || git checkout -b ${branch} origin/${branch}"
        sh "git branch | grep ${branch} && git checkout ${branch} && git reset --hard origin/${branch}"

        sh "git config --global user.email '${gitEmail}'"
        sh "git config --global user.name '${gitUsername}'"
        sh "git ls-tree target/${branch} && git merge --no-edit --ff target/${branch} || echo 'Target repository is empty, skipping merge'"
        followTagsArg = followTags ? "--follow-tags" : ""
        agentSh "git push ${followTagsArg} target HEAD:${branch}"
    }
}
