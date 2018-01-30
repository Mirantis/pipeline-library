package com.mirantis.mk

import static groovy.json.JsonOutput.prettyPrint
import static groovy.json.JsonOutput.toJson

import com.cloudbees.groovy.cps.NonCPS
import groovy.json.JsonSlurperClassic
/**
 *
 * Common functions
 *
 */

/**
 * Generate current timestamp
 *
 * @param format    Defaults to yyyyMMddHHmmss
 */
def getDatetime(format="yyyyMMddHHmmss") {
    def now = new Date();
    return now.format(format, TimeZone.getTimeZone('UTC'));
}

/**
 * Return workspace.
 * Currently implemented by calling pwd so it won't return relevant result in
 * dir context
 */
def getWorkspace(includeBuildNum=false) {
    def workspace = sh script: 'pwd', returnStdout: true
    workspace = workspace.trim()
    if(includeBuildNum){
       if(!workspace.endsWith("/")){
          workspace += "/"
       }
       workspace += env.BUILD_NUMBER
    }
    return workspace
}

/**
 * Get UID of jenkins user.
 * Must be run from context of node
 */
def getJenkinsUid() {
    return sh (
        script: 'id -u',
        returnStdout: true
    ).trim()
}

/**
 * Get GID of jenkins user.
 * Must be run from context of node
 */
def getJenkinsGid() {
    return sh (
        script: 'id -g',
        returnStdout: true
    ).trim()
}

/**
 * Returns Jenkins user uid and gid in one list (in that order)
 * Must be run from context of node
 */
def getJenkinsUserIds(){
    return sh(script: "id -u && id -g", returnStdout: true).tokenize("\n")
}

/**
 *
 * Find credentials by ID
 *
 * @param credsId    Credentials ID
 * @param credsType  Credentials type (optional)
 *
 */
def getCredentialsById(String credsId, String credsType = 'any') {
    def credClasses = [ // ordered by class name
        sshKey:     com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey.class,
        cert:       com.cloudbees.plugins.credentials.common.CertificateCredentials.class,
        password:   com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials.class,
        any:        com.cloudbees.plugins.credentials.impl.BaseStandardCredentials.class,
        dockerCert: org.jenkinsci.plugins.docker.commons.credentials.DockerServerCredentials.class,
        file:       org.jenkinsci.plugins.plaincredentials.FileCredentials.class,
        string:     org.jenkinsci.plugins.plaincredentials.StringCredentials.class,
    ]
    return com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials(
        credClasses[credsType],
        jenkins.model.Jenkins.instance
    ).findAll {cred -> cred.id == credsId}[0]
}

/**
 * Get credentials from store
 *
 * @param id    Credentials name
 */
def getCredentials(id, cred_type = "username_password") {
    warningMsg('You are using obsolete function. Please switch to use `getCredentialsById()`')

    type_map = [
        username_password: 'password',
        key:               'sshKey',
    ]

    return getCredentialsById(id, type_map[cred_type])
}

/**
 * Abort build, wait for some time and ensure we will terminate
 */
def abortBuild() {
    currentBuild.build().doStop()
    sleep(180)
    // just to be sure we will terminate
    throw new InterruptedException()
}

/**
 * Print pretty-printed string representation of given item
 * @param item item to be pretty-printed (list, map, whatever)
 */
def prettyPrint(item){
    println prettify(item)
}

/**
 * Return pretty-printed string representation of given item
 * @param item item to be pretty-printed (list, map, whatever)
 * @return pretty-printed string
 */
def prettify(item){
    return groovy.json.JsonOutput.prettyPrint(toJson(item)).replace('\\n', System.getProperty('line.separator'))
}

/**
 * Print informational message
 *
 * @param msg
 * @param color Colorful output or not
 */
def infoMsg(msg, color = true) {
    printMsg(msg, "cyan")
}

/**
 * Print error message
 *
 * @param msg
 * @param color Colorful output or not
 */
def errorMsg(msg, color = true) {
    printMsg(msg, "red")
}

/**
 * Print success message
 *
 * @param msg
 * @param color Colorful output or not
 */
def successMsg(msg, color = true) {
    printMsg(msg, "green")
}

/**
 * Print warning message
 *
 * @param msg
 * @param color Colorful output or not
 */
def warningMsg(msg, color = true) {
    printMsg(msg, "yellow")
}

/**
 * Print debug message, this message will show only if DEBUG global variable is present
 * @param msg
 * @param color Colorful output or not
 */
def debugMsg(msg, color = true){
    // if debug property exists on env, debug is enabled
    if(env.getEnvironment().containsKey('DEBUG') && env['DEBUG'] == "true"){
        printMsg("[DEBUG] ${msg}", "red")
    }
}

/**
 * Print message
 *
 * @param msg        Message to be printed
 * @param level      Level of message (default INFO)
 * @param color      Color to use for output or false (default)
 */
def printMsg(msg, color = false) {
    colors = [
        'red'   : '\u001B[31m',
        'black' : '\u001B[30m',
        'green' : '\u001B[32m',
        'yellow': '\u001B[33m',
        'blue'  : '\u001B[34m',
        'purple': '\u001B[35m',
        'cyan'  : '\u001B[36m',
        'white' : '\u001B[37m',
        'reset' : '\u001B[0m'
    ]
    if (color != false) {
        print "${colors[color]}${msg}${colors.reset}"
    } else {
        print "[${level}] ${msg}"
    }
}

/**
 * Traverse directory structure and return list of files
 *
 * @param path Path to search
 * @param type Type of files to search (groovy.io.FileType.FILES)
 */
@NonCPS
def getFiles(path, type=groovy.io.FileType.FILES) {
    files = []
    new File(path).eachFile(type) {
        files[] = it
    }
    return files
}

/**
 * Helper method to convert map into form of list of [key,value] to avoid
 * unserializable exceptions
 *
 * @param m Map
 */
@NonCPS
def entries(m) {
    m.collect {k, v -> [k, v]}
}

/**
 * Opposite of build-in parallel, run map of steps in serial
 *
 * @param steps Map of String<name>: CPSClosure2<step> (or list of closures)
 */
def serial(steps) {
    stepsArray = entries(steps)
    for (i=0; i < stepsArray.size; i++) {
        def step = stepsArray[i]
        def dummySteps = [:]
        def stepKey
        if(step[1] instanceof List || step[1] instanceof Map){
            for(j=0;j < step[1].size(); j++){
                if(step[1] instanceof List){
                    stepKey = j
                }else if(step[1] instanceof Map){
                    stepKey = step[1].keySet()[j]
                }
                dummySteps.put("step-${step[0]}-${stepKey}",step[1][stepKey])
            }
        }else{
            dummySteps.put(step[0], step[1])
        }
        parallel dummySteps
    }
}

/**
 * Partition given list to list of small lists
 * @param inputList input list
 * @param partitionSize (partition size, optional, default 5)
 */
def partitionList(inputList, partitionSize=5){
  List<List<String>> partitions = new ArrayList<>();
  for (int i=0; i<inputList.size(); i += partitionSize) {
      partitions.add(new ArrayList<String>(inputList.subList(i, Math.min(i + partitionSize, inputList.size()))));
  }
  return partitions
}

/**
 * Get password credentials from store
 *
 * @param id    Credentials name
 */
def getPasswordCredentials(id) {
    return getCredentialsById(id, 'password')
}

/**
 * Get SSH credentials from store
 *
 * @param id    Credentials name
 */
def getSshCredentials(id) {
    return getCredentialsById(id, 'sshKey')
}

/**
 * Tests Jenkins instance for existence of plugin with given name
 * @param pluginName plugin short name to test
 * @return boolean result
 */
@NonCPS
def jenkinsHasPlugin(pluginName){
    return Jenkins.instance.pluginManager.plugins.collect{p -> p.shortName}.contains(pluginName)
}

@NonCPS
def _needNotification(notificatedTypes, buildStatus, jobName) {
    if(notificatedTypes && notificatedTypes.contains("onchange")){
        if(jobName){
            def job = Jenkins.instance.getItem(jobName)
            def numbuilds = job.builds.size()
            if (numbuilds > 0){
                //actual build is first for some reasons, so last finished build is second
                def lastBuild = job.builds[1]
                if(lastBuild){
                    if(lastBuild.result.toString().toLowerCase().equals(buildStatus)){
                        println("Build status didn't changed since last build, not sending notifications")
                        return false;
                    }
                }
            }
        }
    }else if(!notificatedTypes.contains(buildStatus)){
        return false;
    }
    return true;
}

/**
 * Send notification to all enabled notifications services
 * @param buildStatus message type (success, warning, error), null means SUCCESSFUL
 * @param msgText message text
 * @param enabledNotifications list of enabled notification types, types: slack, hipchat, email, default empty
 * @param notificatedTypes types of notifications will be sent, default onchange - notificate if current build result not equal last result;
 *                         otherwise use - ["success","unstable","failed"]
 * @param jobName optional job name param, if empty env.JOB_NAME will be used
 * @param buildNumber build number param, if empty env.BUILD_NUM will be used
 * @param buildUrl build url param, if empty env.BUILD_URL will be used
 * @param mailFrom mail FROM param, if empty "jenkins" will be used, it's mandatory for sending email notifications
 * @param mailTo mail TO param, it's mandatory for sending email notifications, this option enable mail notification
 */
def sendNotification(buildStatus, msgText="", enabledNotifications = [], notificatedTypes=["onchange"], jobName=null, buildNumber=null, buildUrl=null, mailFrom="jenkins", mailTo=null){
    // Default values
    def colorName = 'blue'
    def colorCode = '#0000FF'
    def buildStatusParam = buildStatus != null && buildStatus != "" ? buildStatus : "SUCCESS"
    def jobNameParam = jobName != null && jobName != "" ? jobName : env.JOB_NAME
    def buildNumberParam = buildNumber != null && buildNumber != "" ? buildNumber : env.BUILD_NUMBER
    def buildUrlParam = buildUrl != null && buildUrl != "" ? buildUrl : env.BUILD_URL
    def subject = "${buildStatusParam}: Job '${jobNameParam} [${buildNumberParam}]'"
    def summary = "${subject} (${buildUrlParam})"

    if(msgText != null && msgText != ""){
        summary+="\n${msgText}"
    }
    if(buildStatusParam.toLowerCase().equals("success")){
        colorCode = "#00FF00"
        colorName = "green"
    }else if(buildStatusParam.toLowerCase().equals("unstable")){
        colorCode = "#FFFF00"
        colorName = "yellow"
    }else if(buildStatusParam.toLowerCase().equals("failure")){
        colorCode = "#FF0000"
        colorName = "red"
    }
    if(_needNotification(notificatedTypes, buildStatusParam.toLowerCase(), jobNameParam)){
        if(enabledNotifications.contains("slack") && jenkinsHasPlugin("slack")){
            try{
                slackSend color: colorCode, message: summary
            }catch(Exception e){
                println("Calling slack plugin failed")
                e.printStackTrace()
            }
        }
        if(enabledNotifications.contains("hipchat") && jenkinsHasPlugin("hipchat")){
            try{
                hipchatSend color: colorName.toUpperCase(), message: summary
            }catch(Exception e){
                println("Calling hipchat plugin failed")
                e.printStackTrace()
            }
        }
        if(enabledNotifications.contains("email") && mailTo != null && mailTo != "" && mailFrom != null && mailFrom != ""){
            try{
                mail body: summary, from: mailFrom, subject: subject, to: mailTo
            }catch(Exception e){
                println("Sending mail plugin failed")
                e.printStackTrace()
            }
        }
    }
}

/**
 * Execute linux command and catch nth element
 * @param cmd command to execute
 * @param index index to retrieve
 * @return index-th element
 */

def cutOrDie(cmd, index)
{
    def common = new com.mirantis.mk.Common()
    def output
    try {
      output = sh(script: cmd, returnStdout: true)
      def result = output.tokenize(" ")[index]
      return result;
    } catch (Exception e) {
      common.errorMsg("Failed to execute cmd: ${cmd}\n output: ${output}")
    }
}

/**
 * Check variable contains keyword
 * @param variable keywork is searched (contains) here
 * @param keyword string to look for
 * @return True if variable contains keyword (case insensitive), False if do not contains or any of input isn't a string
 */

def checkContains(variable, keyword) {
    if(env.getEnvironment().containsKey(variable)){
        return env[variable] && env[variable].toLowerCase().contains(keyword.toLowerCase())
    } else {
        return false
    }
}

/**
 * Parse JSON string to hashmap
 * @param jsonString input JSON string
 * @return created hashmap
 */
def parseJSON(jsonString){
   def m = [:]
   def lazyMap = new JsonSlurperClassic().parseText(jsonString)
   m.putAll(lazyMap)
   return m
}

/**
 * Test pipeline input parameter existence and validity (not null and not empty string)
 * @param paramName input parameter name (usually uppercase)
 */
def validInputParam(paramName){
    return env.getEnvironment().containsKey(paramName) && env[paramName] != null && env[paramName] != ""
}

/**
 * Take list of hashmaps and count number of hashmaps with parameter equals eq
 * @param lm list of hashmaps
 * @param param define parameter of hashmap to read and compare
 * @param eq desired value of hashmap parameter
 * @return count of hashmaps meeting defined condition
 */

@NonCPS
def countHashMapEquals(lm, param, eq) {
    return lm.stream().filter{i -> i[param].equals(eq)}.collect(java.util.stream.Collectors.counting())
}

/**
 * Execute shell command and return stdout, stderr and status
 *
 * @param cmd Command to execute
 * @return map with stdout, stderr, status keys
 */

def shCmdStatus(cmd) {
    def res = [:]
    def stderr = sh(script: 'mktemp', returnStdout: true).trim()
    def stdout = sh(script: 'mktemp', returnStdout: true).trim()

    try {
        def status = sh(script:"${cmd} 1>${stdout} 2>${stderr}", returnStatus: true)
        res['stderr'] = sh(script: "cat ${stderr}", returnStdout: true)
        res['stdout'] = sh(script: "cat ${stdout}", returnStdout: true)
        res['status'] = status
    } finally {
        sh(script: "rm ${stderr}", returnStdout: true)
        sh(script: "rm ${stdout}", returnStdout: true)
    }

    return res
}


/**
 * Retry commands passed to body
 *
 * @param times Number of retries
 * @param delay Delay between retries
 * @param body Commands to be in retry block
 * @return calling commands in body
 * @example retry(3,5){ function body }
 *          retry{ function body }
 */

def retry(int times = 5, int delay = 0, Closure body) {
    int retries = 0
    def exceptions = []
    while(retries++ < times) {
        try {
            return body.call()
        } catch(e) {
            sleep(delay)
        }
    }
    currentBuild.result = "FAILURE"
    throw new Exception("Failed after $times retries")
}
