package com.mirantis.mcp

/**
 * Parse HEAD of current directory and return commit hash
 */
def getGitCommit() {
    git_commit = sh(
            script: 'git rev-parse HEAD',
            returnStdout: true
    ).trim()
    return git_commit
}

/**
 * Describe a commit using the most recent tag reachable from it
 *
 * @param useShort Boolean, which String format returns as result.
 *              false (Default): {gitTag}-{numCommits}-g{gitsha}
 *              true:            {gitTag}-{numCommits}
 */
def getGitDescribe(Boolean useShort = false) {
    if (useShort) {
        // original sed "s/-g[0-9a-f]\+$//g" should be escaped in groovy
        git_commit = sh (
                script: 'git describe --tags | sed "s/-g[0-9a-f]\\+$//g"',
                returnStdout: true
        ).trim()
    } else {
        git_commit = sh (
                script: 'git describe --tags',
                returnStdout: true
        ).trim()
    }
    return git_commit
}
