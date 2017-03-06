package com.mirantis.mk

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
def getWorkspace() {
    def workspace = sh script: 'pwd', returnStdout: true
    workspace = workspace.trim()
    return workspace
}

/**
 * Get credentials from store
 *
 * @param id    Credentials name
 */
def getCredentials(id) {
    def creds = com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials(
                    com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials.class,
                    jenkins.model.Jenkins.instance
                )

    for (Iterator<String> credsIter = creds.iterator(); credsIter.hasNext();) {
        c = credsIter.next();
        if ( c.id == id ) {
            return c;
        }
    }

    throw new Exception("Could not find credentials for ID ${id}")
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
    printMsg(msg, "blue")
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
        wrap([$class: 'AnsiColorBuildWrapper']) {
            print "${colors[color]}${msg}${colors.reset}"
        }
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
 * @param steps Map of String<name>: CPSClosure2<step>
 */
def serial(steps) {
    stepsArray = entries(steps)
    for (i=0; i < stepsArray.size; i++) {
        s = stepsArray[i]
        dummySteps = ["${s[0]}": s[1]]
        parallel dummySteps
    }
}

/**
 * Get password credentials from store
 *
 * @param id    Credentials name
 */
def getPasswordCredentials(id) {
    def creds = com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials(
                    com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials.class,
                    jenkins.model.Jenkins.instance
                )

    for (Iterator<String> credsIter = creds.iterator(); credsIter.hasNext();) {
        c = credsIter.next();
        if ( c.id == id ) {
            return c;
        }
    }

    throw new Exception("Could not find credentials for ID ${id}")
}

/**
 * Get SSH credentials from store
 *
 * @param id    Credentials name
 */
def getSshCredentials(id) {
    def creds = com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials(
                    com.cloudbees.plugins.credentials.common.StandardUsernameCredentials.class,
                    jenkins.model.Jenkins.instance
                )

    for (Iterator<String> credsIter = creds.iterator(); credsIter.hasNext();) {
        c = credsIter.next();
        if ( c.id == id ) {
            return c;
        }
    }

    throw new Exception("Could not find credentials for ID ${id}")
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
 * @param buildNumber build number param, if empty env.JOB_NAME will be used
 * @param buildUrl build url param, if empty env.JOB_NAME will be used
 * @param mailFrom mail FROM param, if empty "jenkins" will be used, it's mandatory for sending email notifications
 * @param mailTo mail TO param, it's mandatory for sending email notifications
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
