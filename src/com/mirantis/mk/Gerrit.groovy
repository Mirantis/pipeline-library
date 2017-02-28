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

    // default parameters
    def scmExtensions = [
        [$class: 'CleanCheckout'],
        [$class: 'BuildChooserSetting', buildChooser: [$class: 'GerritTriggerBuildChooser']]
    ]
    def scmUserRemoteConfigs = [
        name: 'gerrit',
        refspec: "${GERRIT_REFSPEC}"
    ]

    if (credentials == '') {
        // then try to checkout in anonymous mode
        scmUserRemoteConfigs.put('url',"https://${GERRIT_HOST}/${GERRIT_PROJECT}")
    } else {
        // else use ssh checkout
        scmUserRemoteConfigs.put('url',"ssh://${GERRIT_NAME}@${GERRIT_HOST}:${GERRIT_PORT}/${GERRIT_PROJECT}.git")
        scmUserRemoteConfigs.put('credentialsId',credentials)
    }

    // if we need to "merge" code from patchset to GERRIT_BRANCH branch
    if (merge) {
        scmExtensions.add([$class: 'LocalBranch', localBranch: "${GERRIT_BRANCH}"])
    }
    // we need wipe workspace before checkout
    if (wipe) {
        scmExtensions.add([$class: 'WipeWorkspace'])
    }

    checkout(
        scm: [
            $class: 'GitSCM',
            branches: [[name: "${GERRIT_BRANCH}"]],
            extensions: scmExtensions,
            userRemoteConfigs: [scmUserRemoteConfigs]
        ]
    )
}