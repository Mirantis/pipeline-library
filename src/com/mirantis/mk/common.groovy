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
