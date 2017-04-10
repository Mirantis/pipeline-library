package com.mirantis.mk

import java.util.regex.Pattern
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
 * @return boolean result
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

    if(_validGerritConfig(config)){
        // default parameters
        def scmExtensions = [
            [$class: 'CleanCheckout'],
            [$class: 'BuildChooserSetting', buildChooser: [$class: 'GerritTriggerBuildChooser']],
            [$class: 'CheckoutOption', timeout: timeout],
            [$class: 'CloneOption', depth: depth, noTags: false, reference: '', shallow: depth > 0, timeout: timeout]
        ]
        def scmUserRemoteConfigs = [
            name: 'gerrit',
        ]
        if(gerritRefSpec && gerritRefSpec != ""){
            scmUserRemoteConfigs.put('refspec', gerritRefSpec)
        }

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
        return true
    }else{
        throw new Exception("Cannot perform gerrit checkout, given config file is not valid")
    }
    return false
}
/**
 * Execute git clone and checkout stage from gerrit review
 *
 * @param gerritUrl gerrit url with scheme
 *    "${GERRIT_SCHEME}://${GERRIT_NAME}@${GERRIT_HOST}:${GERRIT_PORT}/${GERRIT_PROJECT}.git
 * @param gerritRef gerrit ref spec
 * @param gerritBranch gerrit branch
 * @param credentialsId jenkins credentials id
 * @return boolean result
 */
def gerritPatchsetCheckout(gerritUrl, gerritRef, gerritBranch, credentialsId) {
    def gerritParams = _getGerritParamsFromUrl(gerritUrl)
    if(gerritParams.size() == 5){
        gerritPatchsetCheckout([
          credentialsId : credentialsId,
          gerritBranch: gerritBranch,
          gerritRefSpec: gerritRef,
          gerritScheme: gerritParams[0],
          gerritName: gerritParams[1],
          gerritHost: gerritParams[2],
          gerritPort: gerritParams[3],
          gerritProject: gerritParams[4]
        ])
        return true
    }
    return false
}

/**
 * Return gerrit change object from gerrit API
 * @param gerritName gerrit user name (usually GERRIT_NAME property)
 * @param gerritHost gerrit host (usually GERRIT_HOST property)
 * @param gerritChangeNumber gerrit change number (usually GERRIT_CHANGE_NUMBER property)
 * @param credentialsId jenkins credentials id for gerrit
 * @return gerrit change object
 */
def getGerritChange(gerritName, gerritHost, gerritChangeNumber, credentialsId){
    def common = new com.mirantis.mk.Common()
    def ssh = new com.mirantis.mk.Ssh()
    ssh.prepareSshAgentKey(credentialsId)
    ssh.ensureKnownHosts(gerritHost)
    return common.parseJSON(ssh.agentSh(String.format("ssh -p 29418 %s@%s gerrit query --format=JSON change:%s", gerritName, gerritHost, gerritChangeNumber)))
}

@NonCPS
def _getGerritParamsFromUrl(gitUrl){
    def gitUrlPattern = Pattern.compile("(.+):\\/\\/(.+)@(.+):(.+)\\/(.+)")
    def gitUrlMatcher = gitUrlPattern.matcher(gitUrl)
    if(gitUrlMatcher.find() && gitUrlMatcher.groupCount() == 5){
        return [gitUrlMatcher.group(1),gitUrlMatcher.group(2),gitUrlMatcher.group(3),gitUrlMatcher.group(4),gitUrlMatcher.group(5)]
    }
    return []
}

def _validGerritConfig(LinkedHashMap config){
    return config.get("gerritScheme","") != null && config.get("gerritScheme","") != "" &&
           config.get("gerritName","") != null && config.get("gerritName","") != "" &&
           config.get("gerritHost","") != null && config.get("gerritHost","") != "" &&
           config.get("gerritPort","") != null && config.get("gerritPort","") != "" &&
           config.get("gerritProject","") != null && config.get("gerritProject","") != "" &&
           config.get("gerritBranch","") != null && config.get("gerritBranch","") != ""
}