package com.mirantis.mk

import com.cloudbees.groovy.cps.NonCPS
import java.util.stream.Collectors
/**
 * Salt functions
 *
*/

/**
 * Salt connection and context parameters
 *
 * @param url                 Salt API server URL
 * @param credentialsID       ID of credentials store entry
 */
def connection(url, credentialsId = "salt") {
    def common = new com.mirantis.mk.Common()
    params = [
        "url": url,
        "credentialsId": credentialsId,
        "authToken": null,
        "creds": common.getCredentials(credentialsId)
    ]
    params["authToken"] = saltLogin(params)
    return params
}

/**
 * Login to Salt API, return auth token
 *
 * @param master   Salt connection object
 */
def saltLogin(master) {
    def http = new com.mirantis.mk.Http()
    data = [
        'username': master.creds.username,
        'password': master.creds.password.toString(),
        'eauth': 'pam'
    ]
    authToken = http.restGet(master, '/login', data)['return'][0]['token']
    return authToken
}

/**
 * Run action using Salt API (using plain HTTP request from Jenkins master) or Pepper (from slave shell)
 *
 * @param saltId   Salt Connection object or pepperEnv (the command will be sent using the selected method) (determines if command will be sent with Pepper of Salt API )
 * @param client   Client type
 * @param target   Target specification, eg. for compound matches by Pillar
 *                 data: ['expression': 'I@openssh:server', 'type': 'compound'])
 * @param function Function to execute (eg. "state.sls")
 * @param batch    Batch param to salt (integer or string with percents)
 * @param args     Additional arguments to function
 * @param kwargs   Additional key-value arguments to function
 * @param timeout  Additional argument salt api timeout
 * @param read_timeout http session read timeout
 */
@NonCPS
def runSaltCommand(saltId, client, target, function, batch = null, args = null, kwargs = null, timeout = -1, read_timeout = -1) {

    data = [
        'tgt': target.expression,
        'fun': function,
        'client': client,
        'expr_form': target.type,
    ]
    if(batch != null && ( (batch instanceof Integer && batch > 0) || (batch instanceof String && batch.contains("%")))){
        data['client']= "local_batch"
        data['batch'] = batch
    }

    if (args) {
        data['arg'] = args
    }

    if (kwargs) {
        data['kwarg'] = kwargs
    }

    if (timeout != -1) {
        data['timeout'] = timeout
    }

    // Command will be sent using HttpRequest
    if (saltId instanceof HashMap && saltId.containsKey("authToken") ) {

        def headers = [
          'X-Auth-Token': "${saltId.authToken}"
        ]

        def http = new com.mirantis.mk.Http()
        return http.sendHttpPostRequest("${saltId.url}/", data, headers, read_timeout)
    } else if (saltId instanceof HashMap) {
        throw new Exception("Invalid saltId")
    }

    // Command will be sent using Pepper
    return runPepperCommand(data, saltId)
}

/**
 * Return pillar for given saltId and target
 * @param saltId Salt Connection object or pepperEnv (the command will be sent using the selected method)
 * @param target Get pillar target
 * @param pillar pillar name (optional)
 * @return output of salt command
 */
def getPillar(saltId, target, pillar = null) {
    if (pillar != null) {
        return runSaltCommand(saltId, 'local', ['expression': target, 'type': 'compound'], 'pillar.get', null, [pillar.replace('.', ':')])
    } else {
        return runSaltCommand(saltId, 'local', ['expression': target, 'type': 'compound'], 'pillar.data')
    }
}

/**
 * Return grain for given saltId and target
 * @param saltId Salt Connection object or pepperEnv (the command will be sent using the selected method)
 * @param target Get grain target
 * @param grain grain name (optional)
 * @return output of salt command
 */
def getGrain(saltId, target, grain = null) {
    if(grain != null) {
        return runSaltCommand(saltId, 'local', ['expression': target, 'type': 'compound'], 'grains.item', null, [grain])
    } else {
        return runSaltCommand(saltId, 'local', ['expression': target, 'type': 'compound'], 'grains.items')
    }
}

/**
 * Enforces state on given saltId and target
 * @param saltId Salt Connection object or pepperEnv (the command will be sent using the selected method)
 * @param target State enforcing target
 * @param state Salt state
 * @param output print output (optional, default true)
 * @param failOnError throw exception on salt state result:false (optional, default true)
 * @param batch salt batch parameter integer or string with percents (optional, default null - disable batch)
 * @param read_timeout http session read timeout (optional, default -1 - disabled)
 * @param retries Retry count for salt state. (optional, default -1 - no retries)
 * @param queue salt queue parameter for state.sls calls (optional, default true) - CANNOT BE USED WITH BATCH
 * @return output of salt command
 */
def enforceState(saltId, target, state, output = true, failOnError = true, batch = null, optional = false, read_timeout=-1, retries=-1, queue=true) {
    def common = new com.mirantis.mk.Common()
    def run_states

    if (state instanceof String) {
        run_states = state
    } else {
        run_states = state.join(',')
    }

    common.infoMsg("Running state ${run_states} on ${target}")
    def out
    def kwargs = [:]

    if (queue && batch == null) {
      kwargs["queue"] = true
    }

    if (optional == false || testTarget(saltId, target)){
        if (retries > 0){
            failOnError = true
            retry(retries){
                out = runSaltCommand(saltId, 'local', ['expression': target, 'type': 'compound'], 'state.sls', batch, [run_states], kwargs, -1, read_timeout)
                checkResult(out, failOnError, output)
            }
        } else {
            out = runSaltCommand(saltId, 'local', ['expression': target, 'type': 'compound'], 'state.sls', batch, [run_states], kwargs, -1, read_timeout)
            checkResult(out, failOnError, output)
        }
        return out
    } else {
        common.infoMsg("No Minions matched the target given, but 'optional' param was set to true - Pipeline continues. ")
    }
}

/**
 * Run command on salt minion (salt cmd.run wrapper)
 * @param saltId Salt Connection object or pepperEnv (the command will be sent using the selected method)
 * @param target Get pillar target
 * @param cmd command
 * @param checkResponse test command success execution (default true)
 * @param batch salt batch parameter integer or string with percents (optional, default null - disable batch)
 * @param output do you want to print output
 * @return output of salt command
 */
def cmdRun(saltId, target, cmd, checkResponse = true, batch=null, output = true) {
    def common = new com.mirantis.mk.Common()
    def originalCmd = cmd
    common.infoMsg("Running command ${cmd} on ${target}")
    if (checkResponse) {
      cmd = cmd + " && echo Salt command execution success"
    }
    def out = runSaltCommand(saltId, 'local', ['expression': target, 'type': 'compound'], 'cmd.run', batch, [cmd])
    if (checkResponse) {
        // iterate over all affected nodes and check success return code
        if (out["return"]){
            for(int i=0;i<out["return"].size();i++){
                def node = out["return"][i];
                for(int j=0;j<node.size();j++){
                    def nodeKey = node.keySet()[j]
                    if (!node[nodeKey].contains("Salt command execution success")) {
                        throw new Exception("Execution of cmd ${originalCmd} failed. Server returns: ${node[nodeKey]}")
                    }
                }
            }
        }else{
            throw new Exception("Salt Api response doesn't have return param!")
        }
    }
    if (output == true) {
        printSaltCommandResult(out)
    }
    return out
}

/**
 * Checks if salt minion is in a list of salt master's accepted keys
 * @usage minionPresent(saltId, 'I@salt:master', 'ntw', true, null, true, 200, 3)
 * @param saltId Salt Connection object or pepperEnv (the command will be sent using the selected method)
 * @param target Get pillar target
 * @param minion_name unique identification of a minion in salt-key command output
 * @param waitUntilPresent return after the minion becomes present (default true)
 * @param batch salt batch parameter integer or string with percents (optional, default null - disable batch)
 * @param output print salt command (default true)
 * @param maxRetries finite number of iterations to check status of a command (default 200)
 * @param answers how many minions should return (optional, default 1)
 * @return output of salt command
 */
def minionPresent(saltId, target, minion_name, waitUntilPresent = true, batch=null, output = true, maxRetries = 200, answers = 1) {
    minion_name = minion_name.replace("*", "")
    def common = new com.mirantis.mk.Common()
    def cmd = 'salt-key | grep ' + minion_name
    if (waitUntilPresent){
        def count = 0
        while(count < maxRetries) {
            def out = runSaltCommand(saltId, 'local', ['expression': target, 'type': 'compound'], 'cmd.shell', batch, [cmd], null, 5)
            if (output) {
                printSaltCommandResult(out)
            }
            def valueMap = out["return"][0]
            def result = valueMap.get(valueMap.keySet()[0])
            def resultsArray = result.tokenize("\n")
            def size = resultsArray.size()
            if (size >= answers) {
                return out
            }
            count++
            sleep(time: 500, unit: 'MILLISECONDS')
            common.infoMsg("Waiting for ${cmd} on ${target} to be in correct state")
        }
    } else {
        def out = runSaltCommand(saltId, 'local', ['expression': target, 'type': 'compound'], 'cmd.shell', batch, [cmd], null, 5)
        if (output) {
            printSaltCommandResult(out)
        }
        return out
    }
    // otherwise throw exception
    common.errorMsg("Status of command ${cmd} on ${target} failed, please check it.")
    throw new Exception("${cmd} signals failure of status check!")
}

/**
 * You can call this function when salt-master already contains salt keys of the target_nodes
 * @param saltId Salt Connection object or pepperEnv (the command will be sent using the selected method)
 * @param target Should always be salt-master
 * @param target_nodes unique identification of a minion or group of salt minions
 * @param batch salt batch parameter integer or string with percents (optional, default null - disable batch)
 * @param wait timeout for the salt command if minions do not return (default 10)
 * @param maxRetries finite number of iterations to check status of a command (default 200)
 * @return output of salt command
 */
def minionsReachable(saltId, target, target_nodes, batch=null, wait = 10, maxRetries = 200) {
    def common = new com.mirantis.mk.Common()
    def cmd = "salt -t${wait} -C '${target_nodes}' test.ping"
    common.infoMsg("Checking if all ${target_nodes} minions are reachable")
    def count = 0
    while(count < maxRetries) {
        Calendar timeout = Calendar.getInstance();
        timeout.add(Calendar.SECOND, wait);
        def out = runSaltCommand(saltId, 'local', ['expression': target, 'type': 'compound'], 'cmd.shell', batch, [cmd], null, wait)
        Calendar current = Calendar.getInstance();
        if (current.getTime().before(timeout.getTime())) {
           printSaltCommandResult(out)
           return out
        }
        common.infoMsg("Not all of the targeted '${target_nodes}' minions returned yet. Waiting ...")
        count++
        sleep(time: 500, unit: 'MILLISECONDS')
    }
}

/**
 * Run command on salt minion (salt cmd.run wrapper)
 * @param saltId Salt Connection object or pepperEnv (the command will be sent using the selected method)
 * @param target Get pillar target
 * @param cmd name of a service
 * @param correct_state string that command must contain if status is in correct state (optional, default 'running')
 * @param find bool value if it is suppose to find some string in the output or the cmd should return empty string (optional, default true)
 * @param waitUntilOk return after the minion becomes present (optional, default true)
 * @param batch salt batch parameter integer or string with percents (optional, default null - disable batch)
 * @param output print salt command (default true)
 * @param maxRetries finite number of iterations to check status of a command (default 200)
 * @param answers how many minions should return (optional, default 0)
 * @return output of salt command
 */
def commandStatus(saltId, target, cmd, correct_state='running', find = true, waitUntilOk = true, batch=null, output = true, maxRetries = 200, answers = 0) {
    def common = new com.mirantis.mk.Common()
    common.infoMsg("Checking if status of verification command ${cmd} on ${target} is in correct state")
    if (waitUntilOk){
        def count = 0
        while(count < maxRetries) {
            def out = runSaltCommand(saltId, 'local', ['expression': target, 'type': 'compound'], 'cmd.shell', batch, [cmd], null, 5)
            if (output) {
                printSaltCommandResult(out)
            }
            def resultMap = out["return"][0]
            def success = 0
            if (answers == 0){
                answers = resultMap.size()
            }
            for (int i=0;i<answers;i++) {
                result = resultMap.get(resultMap.keySet()[i])
                // if the goal is to find some string in output of the command
                if (find) {
                    if(result == null || result instanceof Boolean || result.isEmpty()) { result='' }
                    if (result.toLowerCase().contains(correct_state.toLowerCase())) {
                        success++
                        if (success == answers) {
                            return out
                        }
                    }
                // else the goal is to not find any string in output of the command
                } else {
                    if(result instanceof String && result.isEmpty()) {
                        success++
                        if (success == answers) {
                            return out
                        }
                    }
                }
            }
            count++
            sleep(time: 500, unit: 'MILLISECONDS')
            common.infoMsg("Waiting for ${cmd} on ${target} to be in correct state")
        }
    } else {
        def out = runSaltCommand(saltId, 'local', ['expression': target, 'type': 'compound'], 'cmd.shell', batch, [cmd], null, 5)
        def resultMap = out["return"][0]
        if (output) {
            printSaltCommandResult(out)
        }
        for (int i=0;i<resultMap.size();i++) {
            result = resultMap.get(resultMap.keySet()[i])
        // if the goal is to find some string in output of the command
            if (find) {
                if(result == null || result instanceof Boolean || result.isEmpty()) { result='' }
                if (result.toLowerCase().contains(correct_state.toLowerCase())) {
                    return out
                }

            // else the goal is to not find any string in output of the command
            } else {
                if(result instanceof String && result.isEmpty()) {
                    return out
                }
            }
        }
    }
    // otherwise throw exception
    common.errorMsg("Status of command ${cmd} on ${target} failed, please check it.")
    throw new Exception("${cmd} signals failure of status check!")
}

/**
 * Perform complete salt sync between master and target
 * @param saltId Salt Connection object or pepperEnv (the command will be sent using the selected method)
 * @param target Get pillar target
 * @return output of salt command
 */
def syncAll(saltId, target) {
    return runSaltCommand(saltId, 'local', ['expression': target, 'type': 'compound'], 'saltutil.sync_all')
}

/**
 * Enforce highstate on given targets
 * @param saltId Salt Connection object or pepperEnv (the command will be sent using the selected method)
 * @param target Highstate enforcing target
 * @param output print output (optional, default true)
 * @param failOnError throw exception on salt state result:false (optional, default true)
 * @param batch salt batch parameter integer or string with percents (optional, default null - disable batch)
 * @return output of salt command
 */
def enforceHighstate(saltId, target, output = false, failOnError = true, batch = null) {
    def out = runSaltCommand(saltId, 'local', ['expression': target, 'type': 'compound'], 'state.highstate', batch)
    def common = new com.mirantis.mk.Common()

    common.infoMsg("Running state highstate on ${target}")

    checkResult(out, failOnError, output)
    return out
}

/**
 * Get running minions IDs according to the target
 * @param saltId Salt Connection object or pepperEnv (the command will be sent using the selected method)
 * @param target Get minions target
 * @return list of active minions fitin
 */
def getMinions(saltId, target) {
    def minionsRaw = runSaltCommand(saltId, 'local', ['expression': target, 'type': 'compound'], 'test.ping')
    return new ArrayList<String>(minionsRaw['return'][0].keySet())
}


/**
 * Test if there are any minions to target
 * @param saltId Salt Connection object or pepperEnv (the command will be sent using the selected method)
 * @param target Target to test
 * @return bool indicating if target was succesful
 */

def testTarget(saltId, target) {
    return getMinions(saltId, target).size() > 0
}

/**
 * Generates node key using key.gen_accept call
 * @param saltId Salt Connection object or pepperEnv (the command will be sent using the selected method)
 * @param target Key generating target
 * @param host Key generating host
 * @param keysize generated key size (optional, default 4096)
 * @return output of salt command
 */
def generateNodeKey(saltId, target, host, keysize = 4096) {
    return runSaltCommand(saltId, 'wheel', target, 'key.gen_accept', [host], ['keysize': keysize])
}

/**
 * Generates node reclass metadata
 * @param saltId Salt Connection object or pepperEnv (the command will be sent using the selected method)
 * @param target Metadata generating target
 * @param host Metadata generating host
 * @param classes Reclass classes
 * @param parameters Reclass parameters
 * @return output of salt command
 */
def generateNodeMetadata(saltId, target, host, classes, parameters) {
    return runSaltCommand(saltId, 'local', target, 'reclass.node_create', [host, '_generated'], ['classes': classes, 'parameters': parameters])
}

/**
 * Run salt orchestrate on given targets
 * @param saltId Salt Connection object or pepperEnv (the command will be sent using the selected method)
 * @param target Orchestration target
 * @param orchestrate Salt orchestrate params
 * @return output of salt command
 */
def orchestrateSystem(saltId, target, orchestrate) {
    return runSaltCommand(saltId, 'runner', target, 'state.orchestrate', [orchestrate])
}

/**
 * Run salt process step
 * @param saltId Salt Connection object or pepperEnv (the command will be sent using the selected method)
 * @param tgt Salt process step target
 * @param fun Salt process step function
 * @param arg process step arguments (optional, default [])
 * @param batch salt batch parameter integer or string with percents (optional, default null - disable batch)
 * @param output print output (optional, default false)
 * @param timeout  Additional argument salt api timeout
 * @return output of salt command
 */
def runSaltProcessStep(saltId, tgt, fun, arg = [], batch = null, output = false, timeout = -1, kwargs = null) {
    def common = new com.mirantis.mk.Common()
    def salt = new com.mirantis.mk.Salt()
    def out

    common.infoMsg("Running step ${fun} ${arg} on ${tgt}")

    if (batch == true) {
        out = runSaltCommand(saltId, 'local_batch', ['expression': tgt, 'type': 'compound'], fun, String.valueOf(batch), arg, kwargs, timeout)
    } else {
        out = runSaltCommand(saltId, 'local', ['expression': tgt, 'type': 'compound'], fun, batch, arg, kwargs, timeout)
    }

    if (output == true) {
        salt.printSaltCommandResult(out)
    }
    return out
}

/**
 * Check result for errors and throw exception if any found
 *
 * @param result    Parsed response of Salt API
 * @param failOnError Do you want to throw exception if salt-call fails (optional, default true)
 * @param printResults Do you want to print salt results (optional, default true)
 * @param printOnlyChanges If true (default), print only changed resources
 */
def checkResult(result, failOnError = true, printResults = true, printOnlyChanges = true) {
    def common = new com.mirantis.mk.Common()
    if(result != null){
        if(result['return']){
            for (int i=0;i<result['return'].size();i++) {
                def entry = result['return'][i]
                if (!entry) {
                    if (failOnError) {
                        throw new Exception("Salt API returned empty response: ${result}")
                    } else {
                        common.errorMsg("Salt API returned empty response: ${result}")
                    }
                }
                for (int j=0;j<entry.size();j++) {
                    def nodeKey = entry.keySet()[j]
                    def node=entry[nodeKey]
                    def outputResources = []
                    common.infoMsg("Node ${nodeKey} changes:")
                    if(node instanceof Map || node instanceof List){
                        for (int k=0;k<node.size();k++) {
                            def resource;
                            def resKey;
                            if(node instanceof Map){
                                resKey = node.keySet()[k]
                            }else if(node instanceof List){
                                resKey = k
                            }
                            resource = node[resKey]
                           // print
                            if(printResults){
                                if(resource instanceof Map && resource.keySet().contains("result")){
                                    //clean unnesaccary fields
                                    if(resource.keySet().contains("__run_num__")){
                                        resource.remove("__run_num__")
                                    }
                                    if(resource.keySet().contains("__id__")){
                                        resource.remove("__id__")
                                    }
                                    if(resource.keySet().contains("pchanges")){
                                        resource.remove("pchanges")
                                    }
                                    if(!resource["result"] || (resource["result"] instanceof String && resource["result"] != "true")){
                                        if(resource["result"] != null){
                                            outputResources.add(String.format("Resource: %s\n\u001B[31m%s\u001B[0m", resKey, common.prettify(resource)))
                                        }else{
                                            outputResources.add(String.format("Resource: %s\n\u001B[33m%s\u001B[0m", resKey, common.prettify(resource)))
                                        }
                                    }else{
                                        if(!printOnlyChanges || resource.changes.size() > 0){
                                            outputResources.add(String.format("Resource: %s\n\u001B[32m%s\u001B[0m", resKey, common.prettify(resource)))
                                        }
                                    }
                                }else{
                                    outputResources.add(String.format("Resource: %s\n\u001B[36m%s\u001B[0m", resKey, common.prettify(resource)))
                                }
                            }
                            common.debugMsg("checkResult: checking resource: ${resource}")
                            if(resource instanceof String || (resource["result"] != null && !resource["result"]) || (resource["result"] instanceof String && resource["result"] == "false")){
                                def prettyResource = common.prettify(resource)
                                if(env["ASK_ON_ERROR"] && env["ASK_ON_ERROR"] == "true"){
                                    timeout(time:1, unit:'HOURS') {
                                       input message: "False result on ${nodeKey} found, resource ${prettyResource}. \nDo you want to continue?"
                                    }
                                }else{
                                    common.errorMsg(String.format("Resource: %s\n%s", resKey, prettyResource))
                                    def errorMsg = "Salt state on node ${nodeKey} failed: ${prettyResource}."
                                    if (failOnError) {
                                        throw new Exception(errorMsg)
                                    } else {
                                        common.errorMsg(errorMsg)
                                    }
                                }
                            }
                        }
                    }else if(node!=null && node!=""){
                        outputResources.add(String.format("Resource: %s\n\u001B[36m%s\u001B[0m", nodeKey, common.prettify(node)))
                    }
                    if(printResults && !outputResources.isEmpty()){
                        print outputResources.stream().collect(Collectors.joining("\n"))
                    }
                }
            }
        }else{
            common.errorMsg("Salt result hasn't return attribute! Result: ${result}")
        }
    }else{
        common.errorMsg("Cannot check salt result, given result is null")
    }
}

/**
 * Print salt command run results in human-friendly form
 *
 * @param result        Parsed response of Salt API
 */
def printSaltCommandResult(result) {
    def common = new com.mirantis.mk.Common()
    if(result != null){
        if(result['return']){
            for (int i=0; i<result['return'].size(); i++) {
                def entry = result['return'][i]
                for (int j=0; j<entry.size(); j++) {
                    common.debugMsg("printSaltCommandResult: printing salt command entry: ${entry}")
                    def nodeKey = entry.keySet()[j]
                    def node=entry[nodeKey]
                    common.infoMsg(String.format("Node %s changes:\n%s",nodeKey, common.prettify(node)))
                }
            }
        }else{
            common.errorMsg("Salt result hasn't return attribute! Result: ${result}")
        }
    }else{
        common.errorMsg("Cannot print salt command result, given result is null")
    }
}


/**
 * Return content of file target
 *
 * @param saltId    Salt Connection object or pepperEnv (the command will be sent using the selected method)
 * @param target    Compound target (should target only one host)
 * @param file      File path to read (/etc/hosts for example)
 */

def getFileContent(saltId, target, file) {
    result = cmdRun(saltId, target, "cat ${file}")
    return result['return'][0].values()[0].replaceAll('Salt command execution success','')
}

/**
 * Set override parameters in Salt cluster metadata
 *
 * @param saltId         Salt Connection object or pepperEnv (the command will be sent using the selected method)
 * @param salt_overrides YAML formatted string containing key: value, one per line
 * @param reclass_dir    Directory where Reclass git repo is located
 */

def setSaltOverrides(saltId, salt_overrides, reclass_dir="/srv/salt/reclass") {
    def common = new com.mirantis.mk.Common()
    def salt_overrides_map = readYaml text: salt_overrides
    for (entry in common.entries(salt_overrides_map)) {
         def key = entry[0]
         def value = entry[1]

         common.debugMsg("Set salt override ${key}=${value}")
         runSaltProcessStep(saltId, 'I@salt:master', 'reclass.cluster_meta_set', ["${key}", "${value}"], false)
    }
    runSaltProcessStep(saltId, 'I@salt:master', 'cmd.run', ["git -C ${reclass_dir} update-index --skip-worktree classes/cluster/overrides.yml"])
}

/**
* Execute salt commands via salt-api with
* CLI client salt-pepper
*
* @param data   Salt command map
* @param venv   Path to virtualenv with
*/

def runPepperCommand(data, venv)   {
    def python = new com.mirantis.mk.Python()
    def dataStr = new groovy.json.JsonBuilder(data).toString()

    pepperCmdFile = "${venv}/pepper-cmd.json"
    writeFile file: pepperCmdFile, text: dataStr
    def pepperCmd = "pepper -c ${venv}/pepperrc --make-token -x ${venv}/.peppercache --json-file ${pepperCmdFile}"

    if (venv) {
        output = python.runVirtualenvCommand(venv, pepperCmd)
    } else {
        echo("[Command]: ${pepperCmd}")
        output = sh (
            script: pepperCmd,
            returnStdout: true
        ).trim()
    }

    return new groovy.json.JsonSlurperClassic().parseText(output)
}
