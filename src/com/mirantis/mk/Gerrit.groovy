package com.mirantis.mk
import java.util.regex.Pattern
import com.cloudbees.groovy.cps.NonCPS
import com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.GerritCause
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
 *          - withMerge, merge master before build
 *          - withLocalBranch, prevent detached mode in repo
 *          - withWipeOut, wipe repository and force clone
 *          - GerritTriggerBuildChooser - use magic GerritTriggerBuildChooser class from gerrit-trigger-plugin.
 *            By default,enabled.
 *        Gerrit properties like GERRIT_SCHEMA can be passed in config as gerritSchema or will be obtained from env
 * @param extraScmExtensions list of extra scm extensions which will be used for checkout (optional)
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
def gerritPatchsetCheckout(LinkedHashMap config, List extraScmExtensions = []) {
    def merge = config.get('withMerge', false)
    def wipe = config.get('withWipeOut', false)
    def localBranch = config.get('withLocalBranch', false)
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
    def GerritTriggerBuildChooser = config.get('useGerritTriggerBuildChooser', true)

    def invalidParams = _getInvalidGerritParams(config)
    if (invalidParams.isEmpty()) {
        // default parameters
        def scmExtensions = [
            [$class: 'CleanCheckout'],
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

        // Usefull, if we only need to clone branch. W\o any refspec magic
        if (GerritTriggerBuildChooser) {
            scmExtensions.add([$class: 'BuildChooserSetting', buildChooser: [$class: 'GerritTriggerBuildChooser']],)
        }

        // if we need to "merge" code from patchset to GERRIT_BRANCH branch
        if (merge) {
            scmExtensions.add([$class: 'PreBuildMerge', options: [fastForwardMode: 'FF', mergeRemote: 'gerrit', mergeStrategy: 'DEFAULT', mergeTarget: gerritBranch]])
        }
        // we need wipe workspace before checkout
        if (wipe) {
            scmExtensions.add([$class: 'WipeWorkspace'])
        }

        if(localBranch){
            scmExtensions.add([$class: 'LocalBranch', localBranch: gerritBranch])
        }

        if(!extraScmExtensions.isEmpty()){
            scmExtensions.addAll(extraScmExtensions)
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
        throw new Exception("Cannot perform gerrit checkout, missed config options: " + invalidParams)
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
 * @param path checkout path, optional, default is empty string which means workspace root
 * @return boolean result
 */
def gerritPatchsetCheckout(gerritUrl, gerritRef, gerritBranch, credentialsId, path="") {
    def gerritParams = _getGerritParamsFromUrl(gerritUrl)
    if(gerritParams.size() == 5){
        if (path==""){
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
        } else {
            dir(path) {
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
        }
    }
    return false
}

/**
 * Return gerrit change object from gerrit API
 * @param gerritName gerrit user name (usually GERRIT_NAME property)
 * @param gerritHost gerrit host (usually GERRIT_HOST property)
 * @param gerritChangeNumber gerrit change number (usually GERRIT_CHANGE_NUMBER property)
 * @param credentialsId jenkins credentials id for gerrit
 * @param includeCurrentPatchset do you want to include current (last) patchset
 * @return gerrit change object
 */
def getGerritChange(gerritName, gerritHost, gerritChangeNumber, credentialsId, includeCurrentPatchset = false){
    def common = new com.mirantis.mk.Common()
    def ssh = new com.mirantis.mk.Ssh()
    ssh.prepareSshAgentKey(credentialsId)
    ssh.ensureKnownHosts(gerritHost)
    def curPatchset = "";
    if(includeCurrentPatchset){
        curPatchset = "--current-patch-set"
    }
    return common.parseJSON(ssh.agentSh(String.format("ssh -p 29418 %s@%s gerrit query ${curPatchset} --format=JSON change:%s", gerritName, gerritHost, gerritChangeNumber)))
}

/**
 * Returns list of Gerrit trigger requested builds
 * @param allBuilds list of all builds of some job
 * @param gerritChange gerrit change number
 * @param excludePatchset gerrit patchset number which will be excluded from builds, optional null
 */
@NonCPS
def getGerritTriggeredBuilds(allBuilds, gerritChange, excludePatchset = null){
    return allBuilds.findAll{job ->
        def cause = job.causes[0]
        if(cause instanceof GerritCause &&
           (cause.getEvent() instanceof com.sonymobile.tools.gerrit.gerritevents.dto.events.PatchsetCreated ||
            cause.getEvent() instanceof com.sonymobile.tools.gerrit.gerritevents.dto.events.CommentAdded)) {
            if(excludePatchset == null || excludePatchset == 0){
                return cause.event.change.number.equals(String.valueOf(gerritChange))
            }else{
                return cause.event.change.number.equals(String.valueOf(gerritChange)) && !cause.event.patchSet.number.equals(String.valueOf(excludePatchset))
            }
        }
        return false
    }
}
/**
 * Returns boolean result of test given gerrit patchset for given approval type and value
 * @param patchset gerrit patchset
 * @param approvalType type of tested approval (optional, default Verified)
 * @param approvalValue value of tested approval (optional, default empty string which means any value)
 * @return boolean result
 * @example patchsetHasApproval(gerrit.getGerritChange(*,*,*,*, true).currentPatchSet)
 */
@NonCPS
def patchsetHasApproval(patchSet, approvalType="Verified", approvalValue = ""){
  if(patchSet && patchSet.approvals){
    for(int i=0; i < patchSet.approvals.size();i++){
      def approval = patchSet.approvals.get(i)
      if(approval.type.equals(approvalType)){
        if(approvalValue.equals("") || approval.value.equals(approvalValue)){
            return true
        }else if(approvalValue.equals("+") && Integer.parseInt(approval.value) > 0) {
            return true
        }else if(approvalValue.equals("-") && Integer.parseInt(approval.value) < 0) {
            return true
        }
      }
    }
  }
  return false
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

def _getInvalidGerritParams(LinkedHashMap config){
    def requiredParams = ["gerritScheme", "gerritName", "gerritHost", "gerritPort", "gerritProject", "gerritBranch"]
    def missedParams = requiredParams - config.keySet()
    def badParams = config.subMap(requiredParams).findAll{it.value in [null, '']}.keySet()
    return badParams + missedParams
}

/**
 * Post Gerrit comment from CI user
 *
 * @param config map which contains next params:
 *  gerritName - gerrit user name (usually GERRIT_NAME property)
 *  gerritHost - gerrit host (usually GERRIT_HOST property)
 *  gerritChangeNumber - gerrit change number (usually GERRIT_CHANGE_NUMBER property)
 *  gerritPatchSetNumber - gerrit patch set number (usually GERRIT_PATCHSET_NUMBER property)
 *  message - message to send to gerrit review patch
 *  credentialsId - jenkins credentials id for gerrit
 */
def postGerritComment(LinkedHashMap config) {
    def common = new com.mirantis.mk.Common()
    def ssh = new com.mirantis.mk.Ssh()
    String gerritName = config.get('gerritName')
    String gerritHost = config.get('gerritHost')
    String gerritChangeNumber = config.get('gerritChangeNumber')
    String gerritPatchSetNumber = config.get('gerritPatchSetNumber')
    String message = config.get('message')
    String credentialsId = config.get('credentialsId')

    ssh.prepareSshAgentKey(credentialsId)
    ssh.ensureKnownHosts(gerritHost)
    ssh.agentSh(String.format("ssh -p 29418 %s@%s gerrit review %s,%s -m \"'%s'\" --code-review 0", gerritName, gerritHost, gerritChangeNumber, gerritPatchSetNumber, message))
}

/**
 * Return map of dependent patches info for current patch set
 * based on commit message hints: Depends-On: https://gerrit_address/_CHANGE_NUMBER_
 * @param changeInfo Map Info about current patch set, such as:
 *   gerritName Gerrit user name (usually GERRIT_NAME property)
 *   gerritHost Gerrit host (usually GERRIT_HOST property)
 *   gerritChangeNumber Gerrit change number (usually GERRIT_CHANGE_NUMBER property)
 *   credentialsId Jenkins credentials id for gerrit
 * @return map of dependent patches info
 */
LinkedHashMap getDependentPatches(LinkedHashMap changeInfo) {
    def dependentPatches = [:]
    def currentChange = getGerritChange(changeInfo.gerritName, changeInfo.gerritHost, changeInfo.gerritChangeNumber, changeInfo.credentialsId, true)
    def dependentCommits = currentChange.commitMessage.tokenize('\n').findAll { it  ==~ /Depends-On: \b[^ ]+\b(\/)?/  }
    if (dependentCommits) {
        dependentCommits.each { commit ->
            def patchLink = commit.tokenize(' ')[1]
            def changeNumber = patchLink.tokenize('/')[-1].trim()
            def dependentCommit = getGerritChange(changeInfo.gerritName, changeInfo.gerritHost, changeNumber, changeInfo.credentialsId, true)
            if (dependentCommit.status == "NEW") {
                dependentPatches[dependentCommit.project] = [
                    'number': dependentCommit.number,
                    'ref': dependentCommit.currentPatchSet.ref,
                    'branch': dependentCommit.branch,
                ]
            }
        }
    }
    return dependentPatches
}

/**
 * Find Gerrit change(s) according to various input parameters like owner, topic, etc.
 * @param gerritAuth        A map containing information about Gerrit. Should include
 *                          HOST, PORT and USER
 * @param changeParams      Parameters to identify Geriit change e.g.: owner, topic,
 *                          status, branch, project
 */
def findGerritChange(credentialsId, LinkedHashMap gerritAuth, LinkedHashMap changeParams) {
    scriptText = """
                 ssh -p ${gerritAuth['PORT']} ${gerritAuth['USER']}@${gerritAuth['HOST']} \
                 gerrit query \
                 --format JSON \
                 """
    changeParams.each {
        scriptText += " ${it.key}:${it.value}"
    }
    scriptText += " | fgrep -v runTimeMilliseconds || :"
    sshagent([credentialsId]) {
        jsonChange = sh(
             script:scriptText,
             returnStdout: true,
           ).trim()
    }
    return jsonChange
}

/**
 * Download Gerrit review by number
 *
 * @param credentialsId            credentials ID
 * @param virtualenv               virtualenv path
 * @param repoDir                  repository directory
 * @param gitRemote                the value of git remote
 * @param changeNum                the number of change to download
 */
def getGerritChangeByNum(credentialsId, virtualEnv, repoDir, gitRemote, changeNum) {
    def python = new com.mirantis.mk.Python()
    sshagent([credentialsId]) {
        dir(repoDir) {
            python.runVirtualenvCommand(virtualEnv, "git review -r ${gitRemote} -d ${changeNum}")
        }
    }
}

/**
 * Post Gerrit review
 * @param credentialsId            credentials ID
 * @param virtualenv               virtualenv path
 * @param repoDir                  repository directory
 * @param gitName                  committer name
 * @param gitEmail                 committer email
 * @param gitRemote                the value of git remote
 * @param gitTopic                 the name of the topic
 * @param gitBranch                the name of git branch
 */
def postGerritReview(credentialsId, virtualEnv, repoDir, gitName, gitEmail, gitRemote, gitTopic, gitBranch) {
    def python = new com.mirantis.mk.Python()
    def cmdText = """
                    GIT_COMMITTER_NAME=${gitName} \
                    GIT_COMMITTER_EMAIL=${gitEmail} \
                    git review -r ${gitRemote} \
                    -t ${gitTopic} \
                    ${gitBranch}
                  """
    sshagent([credentialsId]) {
        dir(repoDir) {
            python.runVirtualenvCommand(virtualEnv, cmdText)
        }
    }
}
