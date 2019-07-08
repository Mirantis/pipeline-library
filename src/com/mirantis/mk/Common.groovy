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
 * @param format Defaults to yyyyMMddHHmmss
 */
def getDatetime(format = "yyyyMMddHHmmss") {
    def now = new Date();
    return now.format(format, TimeZone.getTimeZone('UTC'));
}

/**
 * Return workspace.
 * Currently implemented by calling pwd so it won't return relevant result in
 * dir context
 */
def getWorkspace(includeBuildNum = false) {
    def workspace = sh script: 'pwd', returnStdout: true
    workspace = workspace.trim()
    if (includeBuildNum) {
        if (!workspace.endsWith("/")) {
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
    return sh(
        script: 'id -u',
        returnStdout: true
    ).trim()
}

/**
 * Get GID of jenkins user.
 * Must be run from context of node
 */
def getJenkinsGid() {
    return sh(
        script: 'id -g',
        returnStdout: true
    ).trim()
}

/**
 * Returns Jenkins user uid and gid in one list (in that order)
 * Must be run from context of node
 */
def getJenkinsUserIds() {
    return sh(script: "id -u && id -g", returnStdout: true).tokenize("\n")
}

/**
 *
 * Find credentials by ID
 *
 * @param credsId Credentials ID
 * @param credsType Credentials type (optional)
 *
 */
def getCredentialsById(String credsId, String credsType = 'any') {
    def credClasses = [ // ordered by class name
                        sshKey    : com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey.class,
                        cert      : com.cloudbees.plugins.credentials.common.CertificateCredentials.class,
                        password  : com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials.class,
                        any       : com.cloudbees.plugins.credentials.impl.BaseStandardCredentials.class,
                        dockerCert: org.jenkinsci.plugins.docker.commons.credentials.DockerServerCredentials.class,
                        file      : org.jenkinsci.plugins.plaincredentials.FileCredentials.class,
                        string    : org.jenkinsci.plugins.plaincredentials.StringCredentials.class,
    ]
    return com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials(
        credClasses[credsType],
        jenkins.model.Jenkins.instance
    ).findAll { cred -> cred.id == credsId }[0]
}

/**
 * Get credentials from store
 *
 * @param id Credentials name
 */
def getCredentials(id, cred_type = "username_password") {
    warningMsg('You are using obsolete function. Please switch to use `getCredentialsById()`')

    type_map = [
        username_password: 'password',
        key              : 'sshKey',
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
def prettyPrint(item) {
    println prettify(item)
}

/**
 * Return pretty-printed string representation of given item
 * @param item item to be pretty-printed (list, map, whatever)
 * @return pretty-printed string
 */
def prettify(item) {
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
def debugMsg(msg, color = true) {
    // if debug property exists on env, debug is enabled
    if (env.getEnvironment().containsKey('DEBUG') && env['DEBUG'] == "true") {
        printMsg("[DEBUG] ${msg}", "red")
    }
}

def getColorizedString(msg, color) {
    def colorMap = [
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

    return "${colorMap[color]}${msg}${colorMap.reset}"
}

/**
 * Print message
 *
 * @param msg Message to be printed
 * @param color Color to use for output
 */
def printMsg(msg, color) {
    print getColorizedString(msg, color)
}

/**
 * Traverse directory structure and return list of files
 *
 * @param path Path to search
 * @param type Type of files to search (groovy.io.FileType.FILES)
 */
@NonCPS
def getFiles(path, type = groovy.io.FileType.FILES) {
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
    m.collect { k, v -> [k, v] }
}

/**
 * Opposite of build-in parallel, run map of steps in serial
 *
 * @param steps Map of String<name>: CPSClosure2<step> (or list of closures)
 */
def serial(steps) {
    stepsArray = entries(steps)
    for (i = 0; i < stepsArray.size; i++) {
        def step = stepsArray[i]
        def dummySteps = [:]
        def stepKey
        if (step[1] instanceof List || step[1] instanceof Map) {
            for (j = 0; j < step[1].size(); j++) {
                if (step[1] instanceof List) {
                    stepKey = j
                } else if (step[1] instanceof Map) {
                    stepKey = step[1].keySet()[j]
                }
                dummySteps.put("step-${step[0]}-${stepKey}", step[1][stepKey])
            }
        } else {
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
def partitionList(inputList, partitionSize = 5) {
    List<List<String>> partitions = new ArrayList<>();
    for (int i = 0; i < inputList.size(); i += partitionSize) {
        partitions.add(new ArrayList<String>(inputList.subList(i, Math.min(i + partitionSize, inputList.size()))));
    }
    return partitions
}

/**
 * Get password credentials from store
 *
 * @param id Credentials name
 */
def getPasswordCredentials(id) {
    return getCredentialsById(id, 'password')
}

/**
 * Get SSH credentials from store
 *
 * @param id Credentials name
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
def jenkinsHasPlugin(pluginName) {
    return Jenkins.instance.pluginManager.plugins.collect { p -> p.shortName }.contains(pluginName)
}

@NonCPS
def _needNotification(notificatedTypes, buildStatus, jobName) {
    if (notificatedTypes && notificatedTypes.contains("onchange")) {
        if (jobName) {
            def job = Jenkins.instance.getItem(jobName)
            def numbuilds = job.builds.size()
            if (numbuilds > 0) {
                //actual build is first for some reasons, so last finished build is second
                def lastBuild = job.builds[1]
                if (lastBuild) {
                    if (lastBuild.result.toString().toLowerCase().equals(buildStatus)) {
                        println("Build status didn't changed since last build, not sending notifications")
                        return false;
                    }
                }
            }
        }
    } else if (!notificatedTypes.contains(buildStatus)) {
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
def sendNotification(buildStatus, msgText = "", enabledNotifications = [], notificatedTypes = ["onchange"], jobName = null, buildNumber = null, buildUrl = null, mailFrom = "jenkins", mailTo = null) {
    // Default values
    def colorName = 'blue'
    def colorCode = '#0000FF'
    def buildStatusParam = buildStatus != null && buildStatus != "" ? buildStatus : "SUCCESS"
    def jobNameParam = jobName != null && jobName != "" ? jobName : env.JOB_NAME
    def buildNumberParam = buildNumber != null && buildNumber != "" ? buildNumber : env.BUILD_NUMBER
    def buildUrlParam = buildUrl != null && buildUrl != "" ? buildUrl : env.BUILD_URL
    def subject = "${buildStatusParam}: Job '${jobNameParam} [${buildNumberParam}]'"
    def summary = "${subject} (${buildUrlParam})"

    if (msgText != null && msgText != "") {
        summary += "\n${msgText}"
    }
    if (buildStatusParam.toLowerCase().equals("success")) {
        colorCode = "#00FF00"
        colorName = "green"
    } else if (buildStatusParam.toLowerCase().equals("unstable")) {
        colorCode = "#FFFF00"
        colorName = "yellow"
    } else if (buildStatusParam.toLowerCase().equals("failure")) {
        colorCode = "#FF0000"
        colorName = "red"
    }
    if (_needNotification(notificatedTypes, buildStatusParam.toLowerCase(), jobNameParam)) {
        if (enabledNotifications.contains("slack") && jenkinsHasPlugin("slack")) {
            try {
                slackSend color: colorCode, message: summary
            } catch (Exception e) {
                println("Calling slack plugin failed")
                e.printStackTrace()
            }
        }
        if (enabledNotifications.contains("hipchat") && jenkinsHasPlugin("hipchat")) {
            try {
                hipchatSend color: colorName.toUpperCase(), message: summary
            } catch (Exception e) {
                println("Calling hipchat plugin failed")
                e.printStackTrace()
            }
        }
        if (enabledNotifications.contains("email") && mailTo != null && mailTo != "" && mailFrom != null && mailFrom != "") {
            try {
                mail body: summary, from: mailFrom, subject: subject, to: mailTo
            } catch (Exception e) {
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

def cutOrDie(cmd, index) {
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
    if (env.getEnvironment().containsKey(variable)) {
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
def parseJSON(jsonString) {
    def m = [:]
    def lazyMap = new JsonSlurperClassic().parseText(jsonString)
    m.putAll(lazyMap)
    return m
}

/**
 *
 * Deep merge of  Map items. Merges variable number of maps in to onto.
 *   Using the following rules:
 *     - Lists are appended
 *     - Maps are updated
 *     - other object types are replaced.
 *
 *
 * @param onto Map object to merge in
 * @param overrides Map objects to merge to onto
*/
def mergeMaps(Map onto, Map... overrides){
    if (!overrides){
        return onto
    }
    else if (overrides.length == 1) {
        overrides[0]?.each { k, v ->
            if (v in Map && onto[k] in Map){
                mergeMaps((Map) onto[k], (Map) v)
            } else if (v in List) {
                onto[k] += v
            } else {
                onto[k] = v
            }
        }
        return onto
    }
    return overrides.inject(onto, { acc, override -> mergeMaps(acc, override ?: [:]) })
}

/**
 * Test pipeline input parameter existence and validity (not null and not empty string)
 * @param paramName input parameter name (usually uppercase)
  */
def validInputParam(paramName) {
    if (paramName instanceof java.lang.String) {
        return env.getEnvironment().containsKey(paramName) && env[paramName] != null && env[paramName] != ""
    }
    return false
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
    return lm.stream().filter { i -> i[param].equals(eq) }.collect(java.util.stream.Collectors.counting())
}

/**
 * Execute shell command and return stdout, stderr and status
 *
 * @param cmd Command to execute
 * @return map with stdout, stderr, status keys
 */

def shCmdStatus(cmd) {
    // Set +x , to hide odd messages about temp file manipulations
    def res = [:]
    def stderr = sh(script: 'set +x ; mktemp', returnStdout: true).trim()
    def stdout = sh(script: 'set +x ; mktemp', returnStdout: true).trim()

    try {
        def status = sh(script: "${cmd} 1>${stdout} 2>${stderr}", returnStatus: true)
        res['stderr'] = sh(script: "set +x; cat ${stderr}", returnStdout: true).trim()
        res['stdout'] = sh(script: "set +x; cat ${stdout}", returnStdout: true).trim()
        res['status'] = status
    } finally {
        sh(script: "set +x; rm ${stderr}")
        sh(script: "set +x; rm ${stdout}")
    }

    return res
}

/**
 * Retry commands passed to body
 *
 * Don't use common.retry method for retrying salt.enforceState method. Use retries parameter
 * built-in the salt.enforceState method instead to ensure correct functionality.
 *
 * @param times Number of retries
 * @param delay Delay between retries (in seconds)
 * @param body Commands to be in retry block
 * @return calling commands in body
 * @example retry ( 3 , 5 ) { function body }*          retry{ function body }
 */

def retry(int times = 5, int delay = 0, Closure body) {
    int retries = 0
    while (retries++ < times) {
        try {
            return body.call()
        } catch (e) {
            errorMsg(e.toString())
            sleep(delay)
        }
    }
    throw new Exception("Failed after $times retries")
}

/**
 * Wait for user input with timeout
 *
 * @param timeoutInSeconds Timeout
 * @param options Options for input widget
 */
def waitForInputThenPass(timeoutInSeconds, options = [message: 'Ready to go?']) {
    def userInput = true
    try {
        timeout(time: timeoutInSeconds, unit: 'SECONDS') {
            userInput = input options
        }
    } catch (err) { // timeout reached or input false
        def user = err.getCauses()[0].getUser()
        if ('SYSTEM' == user.toString()) { // SYSTEM means timeout.
            println("Timeout, proceeding")
        } else {
            userInput = false
            println("Aborted by: [${user}]")
            throw err
        }
    }
    return userInput
}

/**
 * Function receives Map variable as input and sorts it
 * by values ascending. Returns sorted Map
 * @param _map Map variable
 */
@NonCPS
def SortMapByValueAsc(_map) {
    def sortedMap = _map.sort { it.value }
    return sortedMap
}

/**
 *  Compare 'old' and 'new' dir's recursively
 * @param diffData =' Only in new/XXX/infra: secrets.yml
 Files old/XXX/init.yml and new/XXX/init.yml differ
 Only in old/XXX/infra: secrets11.yml '
 *
 * @return
 *   - new:
 - XXX/secrets.yml
 - diff:
 - XXX/init.yml
 - removed:
 - XXX/secrets11.yml

 */
def diffCheckMultidir(diffData) {
    common = new com.mirantis.mk.Common()
    // Some global constants. Don't change\move them!
    keyNew = 'new'
    keyRemoved = 'removed'
    keyDiff = 'diff'
    def output = [
        new    : [],
        removed: [],
        diff   : [],
    ]
    String pathSep = '/'
    diffData.each { line ->
        def job_file = ''
        def job_type = ''
        if (line.startsWith('Files old/')) {
            job_file = new File(line.replace('Files old/', '').tokenize()[0])
            job_type = keyDiff
        } else if (line.startsWith('Only in new/')) {
            // get clean normalized filepath, under new/
            job_file = new File(line.replace('Only in new/', '').replace(': ', pathSep)).toString()
            job_type = keyNew
        } else if (line.startsWith('Only in old/')) {
            // get clean normalized filepath, under old/
            job_file = new File(line.replace('Only in old/', '').replace(': ', pathSep)).toString()
            job_type = keyRemoved
        } else {
            common.warningMsg("Not parsed diff line: ${line}!")
        }
        if (job_file != '') {
            output[job_type].push(job_file)
        }
    }
    return output
}

/**
 * Compare 2 folder, file by file
 * Structure should be:
 * ${compRoot}/
 └── diff - diff results will be save here
 ├── new  - input folder with data
 ├── old  - input folder with data
 ├── pillar.diff - globall diff will be saved here
 * b_url - usual env.BUILD_URL, to be add into description
 * grepOpts -   General grep cmdline; Could be used to pass some magic
 *              regexp into after-diff listing file(pillar.diff)
 *              Example: '-Ev infra/secrets.yml'
 * return - html-based string
 * TODO: allow to specify subdir for results?
 **/

def comparePillars(compRoot, b_url, grepOpts) {

    // Some global constants. Don't change\move them!
    keyNew = 'new'
    keyRemoved = 'removed'
    keyDiff = 'diff'
    def diff_status = 0
    // FIXME
    httpWS = b_url + '/artifact/'
    dir(compRoot) {
        // If diff empty - exit 0
        diff_status = sh(script: 'diff -q -r old/ new/  > pillar.diff',
            returnStatus: true,
        )
    }
    // Unfortunately, diff not able to work with dir-based regexp
    if (diff_status == 1 && grepOpts) {
        dir(compRoot) {
            grep_status = sh(script: """
                cp -v pillar.diff pillar_orig.diff
                grep ${grepOpts} pillar_orig.diff  > pillar.diff
                """,
                returnStatus: true
            )
            if (grep_status == 1) {
                warningMsg("Grep regexp ${grepOpts} removed all diff!")
                diff_status = 0
            }
        }
    }
    // Set job description
    description = ''
    if (diff_status == 1) {
        // Analyse output file and prepare array with results
        String data_ = readFile file: "${compRoot}/pillar.diff"
        def diff_list = diffCheckMultidir(data_.split("\\r?\\n"))
        infoMsg(diff_list)
        dir(compRoot) {
            if (diff_list[keyDiff].size() > 0) {
                if (!fileExists('diff')) {
                    sh('mkdir -p diff')
                }
                description += '<b>CHANGED</b><ul>'
                infoMsg('Changed items:')
                def stepsForParallel = [:]
                stepsForParallel.failFast = true
                diff_list[keyDiff].each {
                    stepsForParallel.put("Differ for:${it}",
                        {
                            // We don't want to handle sub-dirs structure. So, simply make diff 'flat'
                            def item_f = it.toString().replace('/', '_')
                            description += "<li><a href=\"${httpWS}/diff/${item_f}/*view*/\">${it}</a></li>"
                            // Generate diff file
                            def diff_exit_code = sh([
                                script      : "diff -U 50 old/${it} new/${it} > diff/${item_f}",
                                returnStdout: false,
                                returnStatus: true,
                            ])
                            // catch normal errors, diff should always return 1
                            if (diff_exit_code != 1) {
                                error 'Error with diff file generation'
                            }
                        })
                }

                parallel stepsForParallel
            }
            if (diff_list[keyNew].size() > 0) {
                description += '<b>ADDED</b><ul>'
                for (item in diff_list[keyNew]) {
                    description += "<li><a href=\"${httpWS}/new/${item}/*view*/\">${item}</a></li>"
                }
            }
            if (diff_list[keyRemoved].size() > 0) {
                description += '<b>DELETED</b><ul>'
                for (item in diff_list[keyRemoved]) {
                    description += "<li><a href=\"${httpWS}/old/${item}/*view*/\">${item}</a></li>"
                }
            }
            def cwd = sh(script: 'basename $(pwd)', returnStdout: true).trim()
            sh "tar -cf old_${cwd}.tar.gz old/ && rm -rf old/"
            sh "tar -cf new_${cwd}.tar.gz new/ && rm -rf new/"
        }
    }

    if (description != '') {
        dir(compRoot) {
            archiveArtifacts([
                artifacts        : '**',
                allowEmptyArchive: true,
            ])
        }
        return description.toString()
    } else {
        return '<b>No job changes</b>'
    }
}

/**
 * Simple function, to get basename from string.
 * line - path-string
 * remove_ext - string, optionl. Drop file extenstion.
 **/
def GetBaseName(line, remove_ext) {
    filename = line.toString().split('/').last()
    if (remove_ext && filename.endsWith(remove_ext.toString())) {
        filename = filename.take(filename.lastIndexOf(remove_ext.toString()))
    }
    return filename
}

/**
 * Return colored string of specific stage in stageMap
 *
 * @param stageMap LinkedHashMap object.
 * @param stageName The name of current stage we are going to execute.
 * @param color Text color
 * */
def getColoredStageView(stageMap, stageName, color) {
    def stage = stageMap[stageName]
    def banner = []
    def currentStageIndex = new ArrayList<String>(stageMap.keySet()).indexOf(stageName)
    def numberOfStages = stageMap.keySet().size() - 1

    banner.add(getColorizedString(
        "=========== Stage ${currentStageIndex}/${numberOfStages}: ${stageName} ===========", color))
    for (stage_item in stage.keySet()) {
        banner.add(getColorizedString(
            "${stage_item}: ${stage[stage_item]}", color))
    }
    banner.add('\n')

    return banner
}

/**
 * Pring stageMap to console with specified color
 *
 * @param stageMap LinkedHashMap object with stages information.
 * @param currentStage The name of current stage we are going to execute.
 *
 * */
def printCurrentStage(stageMap, currentStage) {
    print getColoredStageView(stageMap, currentStage, "cyan").join('\n')
}

/**
 * Pring stageMap to console with specified color
 *
 * @param stageMap LinkedHashMap object.
 * @param baseColor Text color (default white)
 * */
def printStageMap(stageMap, baseColor = "white") {
    def banner = []
    def index = 0
    for (stage_name in stageMap.keySet()) {
        banner.addAll(getColoredStageView(stageMap, stage_name, baseColor))
    }
    print banner.join('\n')
}

/**
 * Wrap provided code in stage, and do interactive retires if needed.
 *
 * @param stageMap LinkedHashMap object with stages information.
 * @param currentStage The name of current stage we are going to execute.
 * @param target Target host to execute stage on.
 * @param interactive Boolean flag to specify if interaction with user is enabled.
 * @param body Command to be in stage block.
 * */
def stageWrapper(stageMap, currentStage, target, interactive = true, Closure body) {
    def common = new com.mirantis.mk.Common()
    def banner = []

    printCurrentStage(stageMap, currentStage)

    stage(currentStage) {
      if (interactive){
        input message: getColorizedString("We are going to execute stage \'${currentStage}\' on the following target ${target}.\nPlease review stage information above.", "yellow")
      }
      try {
        stageMap[currentStage]['Status'] = "SUCCESS"
        return body.call()
      } catch (Exception err) {
        def msg = "Stage ${currentStage} failed with the following exception:\n${err}"
        print getColorizedString(msg, "yellow")
        common.errorMsg(err)
        if (interactive) {
          input message: getColorizedString("Please make sure problem is fixed to proceed with retry. Ready to proceed?", "yellow")
          stageMap[currentStage]['Status'] = "RETRYING"
          stageWrapper(stageMap, currentStage, target, interactive, body)
        } else {
          error(msg)
        }
      }
    }
}

/**
 *  Ugly transition solution for internal tests.
 *  1) Check input => transform to static result, based on runtime and input
 *  2) Check remote-binary repo for exact resource
 *  Return: changes each linux_system_* cto false, in case broken url in some of them
  */

def checkRemoteBinary(LinkedHashMap config, List extraScmExtensions = []) {
    def common = new com.mirantis.mk.Common()
    def res = [:]
    res['MirrorRoot'] = config.get('globalMirrorRoot', env["BIN_MIRROR_ROOT"] ? env["BIN_MIRROR_ROOT"] : "http://mirror.mirantis.com/")
    // Reclass-like format's. To make life eazy!
    res['mcp_version'] = config.get('mcp_version', env["BIN_APT_MCP_VERSION"] ? env["BIN_APT_MCP_VERSION"] : 'nightly')
    res['linux_system_repo_url'] = config.get('linux_system_repo_url', env['BIN_linux_system_repo_url'] ? env['BIN_linux_system_repo_url'] : "${res['MirrorRoot']}/${res['mcp_version']}/")
    res['linux_system_repo_ubuntu_url'] = config.get('linux_system_repo_ubuntu_url', env['BIN_linux_system_repo_ubuntu_url'] ? env['BIN_linux_system_repo_ubuntu_url'] : "${res['MirrorRoot']}/${res['mcp_version']}/ubuntu/")
    res['linux_system_repo_mcp_salt_url'] = config.get('linux_system_repo_mcp_salt_url', env['BIN_linux_system_repo_mcp_salt_url'] ? env['BIN_linux_system_repo_mcp_salt_url'] : "${res['MirrorRoot']}/${res['mcp_version']}/salt-formulas/")

    if (config.get('verify', true)) {
        res.each { key, val ->
            if (key.toString().startsWith('linux_system_repo')) {
                def MirrorRootStatus = sh(script: "wget  --auth-no-challenge --spider ${val} 2>/dev/null", returnStatus: true)
                if (MirrorRootStatus != 0) {
                    common.warningMsg("Resource: '${key}' at '${val}' not exist!")
                    res[key] = false
                }
            }
        }
    }
    return res
}

/**
 *  Workaround to update env properties, like GERRIT_* vars,
 *  which should be passed from upstream job to downstream.
 *  Will not fail entire job in case any issues.
 *  @param envVar - EnvActionImpl env job
 *  @param extraVars - Multiline YAML text with extra vars
 */
def mergeEnv(envVar, extraVars) {
    def common = new com.mirantis.mk.Common()
    try {
        def extraParams = readYaml text: extraVars
        for(String key in extraParams.keySet()) {
            envVar[key] = extraParams[key]
            common.warningMsg("Parameter ${key} is updated from EXTRA vars.")
        }
    } catch (Exception e) {
        common.errorMsg("Can't update env parameteres, because: ${e.toString()}")
    }
}

/**
 * Wrapper around parallel pipeline function
 * with ability to restrict number of parallel threads
 * running simultaneously
 *
 * @param branches - Map with Clousers to be executed
 * @param maxParallelJob - Integer number of parallel threads allowed
 *                         to run simultaneously
 */
def runParallel(branches, maxParallelJob = 10) {
    def runningSteps = 0
    branches.each { branchName, branchBody ->
        if (branchBody instanceof Closure) {
            branches[branchName] = {
                while (!(runningSteps < maxParallelJob)) {
                    continue
                }
                runningSteps += 1
                branchBody.call()
                runningSteps -= 1
            }
        }
    }
    if (branches) {
        parallel branches
    }
}

/**
 * Ugly processing basic funcs with /etc/apt
 * @param repoConfig YAML text or Map
 * Example :
 repoConfig = '''
 ---
 aprConfD: |-
   APT::Get::AllowUnauthenticated 'true';
 repo:
   mcp_saltstack:
     source: "deb [arch=amd64] http://mirror.mirantis.com/nightly/saltstack-2017.7/xenial xenial main"
     pin:
       - package: "libsodium18"
         pin: "release o=SaltStack"
         priority: 50
       - package: "*"
         pin: "release o=SaltStack"
         priority: "1100"
     repo_key: "http://mirror.mirantis.com/public.gpg"
 '''
 *
 */

def debianExtraRepos(repoConfig) {
    def config = null
    if (repoConfig instanceof Map) {
        config = repoConfig
    } else {
        config = readYaml text: repoConfig
    }
    if (config.get('repo', false)) {
        for (String repo in config['repo'].keySet()) {
            source = config['repo'][repo]['source']
            warningMsg("Write ${source} >  /etc/apt/sources.list.d/${repo}.list")
            sh("echo '${source}' > /etc/apt/sources.list.d/${repo}.list")
            if (config['repo'][repo].containsKey('repo_key')) {
                key = config['repo'][repo]['repo_key']
                sh("wget -O - '${key}' | apt-key add -")
            }
            if (config['repo'][repo]['pin']) {
                def repoPins = []
                for (Map pin in config['repo'][repo]['pin']) {
                    repoPins.add("Package: ${pin['package']}")
                    repoPins.add("Pin: ${pin['pin']}")
                    repoPins.add("Pin-Priority: ${pin['priority']}")
                    // additional empty line between pins
                    repoPins.add('\n')
                }
                if (repoPins) {
                    repoPins.add(0, "### Extra ${repo} repo pin start ###")
                    repoPins.add("### Extra ${repo} repo pin end ###")
                    repoPinning = repoPins.join('\n')
                    warningMsg("Adding pinning \n${repoPinning}\n => /etc/apt/preferences.d/${repo}")
                    sh("echo '${repoPinning}' > /etc/apt/preferences.d/${repo}")
                }
            }
        }
    }
    if (config.get('aprConfD', false)) {
        for (String pref in config['aprConfD'].tokenize('\n')) {
            warningMsg("Adding ${pref} => /etc/apt/apt.conf.d/99setupAndTestNode")
            sh("echo '${pref}' >> /etc/apt/apt.conf.d/99setupAndTestNode")
        }
        sh('cat /etc/apt/apt.conf.d/99setupAndTestNode')
    }
}

/**
 * Parse date from string
 * @param String date - date to parse
 * @param String format - date format in provided date string value
 *
 * return new Date() object
 */
Date parseDate(String date, String format) {
    return Date.parse(format, date)
}

/**
 * Generate Random Hash string
 * @param n Hash length
 * @param pool Pool to use for hash generation
*/
def generateRandomHashString(int n, ArrayList pool = []) {
    if (!pool) {
        pool = ['a'..'z','A'..'Z',0..9,'_','+','='].flatten()
    }
    Random rand = new Random(System.currentTimeMillis())
    return (1..n).collect { pool[rand.nextInt(pool.size())] }.join()
}
