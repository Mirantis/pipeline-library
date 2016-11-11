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
 */
def getGitDescribe() {
    git_commit = sh (
            script: 'git describe --tags',
            returnStdout: true
    ).trim()
    return git_commit
}
