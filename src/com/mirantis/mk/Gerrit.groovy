package com.mirantis.mk

/**
 * Gerrit functions
 *
 */

/**
 * Execute git clone and checkout stage from gerrit review
 *
 * @param config LinkedHashMap
 *        config includes next parameters:
 *          - credentialsId, id of user which should make checkout
 *          - withMerge, prevent detached mode in repo
 *          - withWipeOut, wipe repository and force clone
 *
 * Usage example:
 * //anonymous gerrit checkout
 * def gitFunc = new com.mirantis.mcp.Git()
 * gitFunc.gerritPatchsetCheckout([
 *   withMerge : true
 * ])
 *
 * def gitFunc = new com.mirantis.mcp.Git()
 * gitFunc.gerritPatchsetCheckout([
 *   credentialsId : 'mcp-ci-gerrit',
 *   withMerge : true
 * ])
 */
def gerritPatchsetCheckout(LinkedHashMap config) {
    def merge = config.get('withMerge', false)
    def wipe = config.get('withWipeOut', false)
    def credentials = config.get('credentialsId','')
    def gerritScheme = config.get('gerritScheme', env["GERRIT_SCHEME"] ? env["GERRIT_SCHEME"] : "")
    def gerritRefSpec = config.get('gerritRefSpec', env["GERRIT_REFSPEC"] ? env["GERRIT_REFSPEC"] : "")
    def gerritName = config.get('gerritName', env["GERRIT_NAME"] ? env["GERRIT_NAME"] : "")
    def gerritHost = config.get('gerritHost', env["GERRIT_HOST"] ?  env["GERRIT_HOST"] : "")
    def gerritPort = config.get('gerritPort', env["GERRIT_PORT"] ? env["GERRIT_PORT"] : "")
    def gerritProject = config.get('gerritProject', env["GERRIT_PROJECT"] ? env["GERRIT_PROJECT"] : "")
    def gerritBranch = config.get('gerritBranch', env["GERRIT_BRANCH"] ? env["GERRIT_BRANCH"] : "")
    def path = config.get('path', "")
    def depth = config.get('depth', 0)
    def timeout = config.get('timeout', 20)

    // default parameters
    def scmExtensions = [
        [$class: 'CleanCheckout'],
        [$class: 'BuildChooserSetting', buildChooser: [$class: 'GerritTriggerBuildChooser']],
        [$class: 'CheckoutOption', timeout: timeout],
        [$class: 'CloneOption', depth: depth, noTags: false, reference: '', shallow: depth > 0, timeout: timeout]
    ]
    def scmUserRemoteConfigs = [
        name: 'gerrit',
        refspec: gerritRefSpec
    ]

    if (credentials == '') {
        // then try to checkout in anonymous mode
        scmUserRemoteConfigs.put('url',"${gerritScheme}://${gerritHost}/${gerritProject}")
    } else {
        // else use ssh checkout
        scmUserRemoteConfigs.put('url',"ssh://${gerritName}@${gerritHost}:${gerritPort}/${gerritProject}.git")
        scmUserRemoteConfigs.put('credentialsId',credentials)
    }

    // if we need to "merge" code from patchset to GERRIT_BRANCH branch
    if (merge) {
        scmExtensions.add([$class: 'LocalBranch', localBranch: "${gerritBranch}"])
    }
    // we need wipe workspace before checkout
    if (wipe) {
        scmExtensions.add([$class: 'WipeWorkspace'])
    }

    if (path == "") {
        checkout(
            scm: [
                $class: 'GitSCM',
                branches: [[name: "${gerritBranch}"]],
                extensions: scmExtensions,
                userRemoteConfigs: [scmUserRemoteConfigs]
            ]
        )
    } else {
        dir(path) {
            checkout(
                scm: [
                    $class: 'GitSCM',
                    branches: [[name: "${gerritBranch}"]],
                    extensions: scmExtensions,
                    userRemoteConfigs: [scmUserRemoteConfigs]
                ]
            )
        }
    }
}