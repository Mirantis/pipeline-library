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

    if(batch != null){
        batch = batch.toString()
        if( (batch.isInteger() && batch.toInteger() > 0) || (batch.contains("%"))){
            data['client']= "local_batch"
            data['batch'] = batch
        }
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
 * Return config items for given saltId and target
 * @param saltId Salt Connection object or pepperEnv (the command will be sent using the selected method)
 * @param target Get grain target
 * @param config grain name (optional)
 * @return output of salt command
 */
def getConfig(saltId, target, config) {
    return runSaltCommand(saltId, 'local', ['expression': target, 'type': 'compound'], 'config.get', null, [config.replace('.', ':')], '--out=json')
}

/**
 * Enforces state on given saltId and target
 * @param saltId Salt Connection object or pepperEnv (the command will be sent using the selected method)
 * @param target State enforcing target
 * @param state Salt state
 * @param excludedStates states which will be excluded from main state (default empty string)
 * @param output print output (optional, default true)
 * @param failOnError throw exception on salt state result:false (optional, default true)
 * @param batch salt batch parameter integer or string with percents (optional, default null - disable batch)
 * @param optional Optional flag (if true pipeline will continue even if no minions for target found)
 * @param read_timeout http session read timeout (optional, default -1 - disabled)
 * @param retries Retry count for salt state. (optional, default -1 - no retries)
 * @param queue salt queue parameter for state.sls calls (optional, default true) - CANNOT BE USED WITH BATCH
 * @param saltArgs additional salt args eq. ["runas=aptly"]
 * @return output of salt command
 */
def enforceStateWithExclude(saltId, target, state, excludedStates = "", output = true, failOnError = true, batch = null, optional = false, read_timeout=-1, retries=-1, queue=true, saltArgs=[]) {
    saltArgs << "exclude=${excludedStates}"
    return enforceState(saltId, target, state, output, failOnError, batch, optional, read_timeout, retries, queue, saltArgs)
}

/**
 * Allows to test the given target for reachability and if reachable enforces the state
* @param saltId Salt Connection object or pepperEnv (the command will be sent using the selected method)
 * @param target State enforcing target
 * @param state Salt state
 * @param testTargetMatcher Salt compound matcher to be tested (default is empty string). If empty string, param `target` will be used for tests
 * @param output print output (optional, default true)
 * @param failOnError throw exception on salt state result:false (optional, default true)
 * @param batch salt batch parameter integer or string with percents (optional, default null - disable batch)
 * @param optional Optional flag (if true pipeline will continue even if no minions for target found)
 * @param read_timeout http session read timeout (optional, default -1 - disabled)
 * @param retries Retry count for salt state. (optional, default -1 - no retries)
 * @param queue salt queue parameter for state.sls calls (optional, default true) - CANNOT BE USED WITH BATCH
 * @param saltArgs additional salt args eq. ["runas=aptly"]
 * @return output of salt command
 */
def enforceStateWithTest(saltId, target, state, testTargetMatcher = "", output = true, failOnError = true, batch = null, optional = false, read_timeout=-1, retries=-1, queue=true, saltArgs=[]) {
    def common = new com.mirantis.mk.Common()
    if (!testTargetMatcher) {
        testTargetMatcher = target
    }
    if (testTarget(saltId, testTargetMatcher)) {
        return enforceState(saltId, target, state, output, failOnError, batch, false, read_timeout, retries, queue, saltArgs)
    } else {
        if (!optional) {
                throw new Exception("No Minions matched the target matcher: ${testTargetMatcher}.")
            } else {
                common.infoMsg("No Minions matched the target given, but 'optional' param was set to true - Pipeline continues. ")
            }
    }
}

/* Enforces state on given saltId and target
 * @param saltId Salt Connection object or pepperEnv (the command will be sent using the selected method)
 * @param target State enforcing target
 * @param state Salt state
 * @param output print output (optional, default true)
 * @param failOnError throw exception on salt state result:false (optional, default true)
 * @param batch salt batch parameter integer or string with percents (optional, default null - disable batch)
 * @param optional Optional flag (if true pipeline will continue even if no minions for target found)
 * @param read_timeout http session read timeout (optional, default -1 - disabled)
 * @param retries Retry count for salt state. (optional, default -1 - no retries)
 * @param queue salt queue parameter for state.sls calls (optional, default true) - CANNOT BE USED WITH BATCH
 * @param saltArgs additional salt args eq. ["runas=aptly", exclude="opencontrail.database"]
 * @param minionRestartWaitTimeout specifies timeout that we should wait after minion restart.
 * @return output of salt command
 */
def enforceState(saltId, target, state, output = true, failOnError = true, batch = null, optional = false, read_timeout=-1, retries=-1, queue=true, saltArgs = [], minionRestartWaitTimeout=10) {
    def common = new com.mirantis.mk.Common()
    // add state to salt args
    if (state instanceof String) {
        saltArgs << state
    } else {
        saltArgs << state.join(',')
    }

    common.infoMsg("Running state ${state} on ${target}")
    def out
    def kwargs = [:]

    if (queue && batch == null) {
      kwargs["queue"] = true
    }

    if (optional == false || testTarget(saltId, target)){
        if (retries > 0){
            def retriesCounter = 0
            retry(retries){
                retriesCounter++
                // we have to reverse order in saltArgs because salt state have to be first
                out = runSaltCommand(saltId, 'local', ['expression': target, 'type': 'compound'], 'state.sls', batch, saltArgs.reverse(), kwargs, -1, read_timeout)
                // failOnError should be passed as true because we need to throw exception for retry block handler
                checkResult(out, true, output, true, retriesCounter < retries) //disable ask on error for every interation except last one
            }
        } else {
            // we have to reverse order in saltArgs because salt state have to be first
            out = runSaltCommand(saltId, 'local', ['expression': target, 'type': 'compound'], 'state.sls', batch, saltArgs.reverse(), kwargs, -1, read_timeout)
            checkResult(out, failOnError, output)
        }
        waitForMinion(out, minionRestartWaitTimeout)
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
 * @param saltArgs additional salt args eq. ["runas=aptly"]
 * @return output of salt command
 */
def cmdRun(saltId, target, cmd, checkResponse = true, batch=null, output = true, saltArgs = []) {
    def common = new com.mirantis.mk.Common()
    def originalCmd = cmd
    common.infoMsg("Running command ${cmd} on ${target}")
    if (checkResponse) {
      cmd = cmd + " && echo Salt command execution success"
    }

    // add cmd name to salt args list
    saltArgs << cmd

    def out = runSaltCommand(saltId, 'local', ['expression': target, 'type': 'compound'], 'cmd.run', batch, saltArgs.reverse())
    if (checkResponse) {
        // iterate over all affected nodes and check success return code
        if (out["return"]){
            for(int i=0;i<out["return"].size();i++){
                def node = out["return"][i];
                for(int j=0;j<node.size();j++){
                    def nodeKey = node.keySet()[j]
                    if (node[nodeKey] instanceof String) {
                        if (!node[nodeKey].contains("Salt command execution success")) {
                            throw new Exception("Execution of cmd ${originalCmd} failed. Server returns: ${node[nodeKey]}")
                        }
                    } else if (node[nodeKey] instanceof Boolean) {
                        if (!node[nodeKey]) {
                            throw new Exception("Execution of cmd ${originalCmd} failed. Server returns: ${node[nodeKey]}")
                        }
                    } else {
                        throw new Exception("Execution of cmd ${originalCmd} failed. Server returns unexpected data type: ${node[nodeKey]}")
                    }
                }
            }
        } else {
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
def minionPresent(saltId, target, minion_name, waitUntilPresent = true, batch=null, output = true, maxRetries = 180, answers = 1) {
    minion_name = minion_name.replace("*", "")
    def common = new com.mirantis.mk.Common()
    common.infoMsg("Looking for minion: " + minion_name)
    def cmd = 'salt-key | grep ' + minion_name
    if (waitUntilPresent){
        def count = 0
        while(count < maxRetries) {
            try {
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
                sleep(time: 1000, unit: 'MILLISECONDS')
                common.infoMsg("Waiting for ${cmd} on ${target} to be in correct state")
            } catch (Exception er) {
                common.infoMsg('[WARNING]: runSaltCommand command read timeout within 5 seconds. You have very slow or broken environment')
            }
        }
    } else {
        try {
            def out = runSaltCommand(saltId, 'local', ['expression': target, 'type': 'compound'], 'cmd.shell', batch, [cmd], null, 5)
            if (output) {
                printSaltCommandResult(out)
            }
            return out
        } catch (Exception er) {
            common.infoMsg('[WARNING]: runSaltCommand command read timeout within 5 seconds. You have very slow or broken environment')
        }
    }
    // otherwise throw exception
    common.errorMsg("Status of command ${cmd} on ${target} failed, please check it.")
    throw new Exception("${cmd} signals failure of status check!")
}

/**
 * Checks if salt minions are in a list of salt master's accepted keys by matching compound
 * @usage minionsPresent(saltId, 'I@salt:master', 'I@salt:minion', true, null, true, 200, 3)
 * @param saltId Salt Connection object or pepperEnv (the command will be sent using the selected method)
 * @param target Performs tests on this target node
 * @param target_minions all targeted minions to test (for ex. I@salt:minion)
 * @param waitUntilPresent return after the minion becomes present (default true)
 * @param batch salt batch parameter integer or string with percents (optional, default null - disable batch)
 * @param output print salt command (default true)
 * @param maxRetries finite number of iterations to check status of a command (default 200)
 * @param answers how many minions should return (optional, default 1)
 * @return output of salt command
 */
def minionsPresent(saltId, target = 'I@salt:master', target_minions = '', waitUntilPresent = true, batch=null, output = true, maxRetries = 200, answers = 1) {
    def target_hosts = getMinionsSorted(saltId, target_minions)
    for (t in target_hosts) {
        def tgt = stripDomainName(t)
        minionPresent(saltId, target, tgt, waitUntilPresent, batch, output, maxRetries, answers)
    }
}

/**
 * Checks if salt minions are in a list of salt master's accepted keys by matching a list
 * @usage minionsPresentFromList(saltId, 'I@salt:master', ["cfg01.example.com", "bmk01.example.com"], true, null, true, 200, 3)
 * @param saltId Salt Connection object or pepperEnv (the command will be sent using the selected method)
 * @param target Performs tests on this target node
 * @param target_minions list to test (for ex. ["cfg01.example.com", "bmk01.example.com"])
 * @param waitUntilPresent return after the minion becomes present (default true)
 * @param batch salt batch parameter integer or string with percents (optional, default null - disable batch)
 * @param output print salt command (default true)
 * @param maxRetries finite number of iterations to check status of a command (default 200)
 * @param answers how many minions should return (optional, default 1)
 * @return output of salt command
 */
def minionsPresentFromList(saltId, target = 'I@salt:master', target_minions = [], waitUntilPresent = true, batch=null, output = true, maxRetries = 200, answers = 1) {
    def common = new com.mirantis.mk.Common()
    for (tgt in target_minions) {
        common.infoMsg("Checking if minion " + tgt + " is present")
        minionPresent(saltId, target, tgt, waitUntilPresent, batch, output, maxRetries, answers)
    }
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
 * Perform complete salt refresh between master and target
 * Method will call saltutil.refresh_pillar, saltutil.refresh_grains and saltutil.sync_all
 * @param saltId Salt Connection object or pepperEnv (the command will be sent using the selected method)
 * @param target Get pillar target
 * @return output of salt command
 */
def fullRefresh(saltId, target){
    runSaltProcessStep(saltId, target, 'saltutil.refresh_pillar', [], null, true)
    runSaltProcessStep(saltId, target, 'saltutil.refresh_grains', [], null, true)
    runSaltProcessStep(saltId, target, 'saltutil.sync_all', [], null, true)
}

/**
 * Enforce highstate on given targets
 * @param saltId Salt Connection object or pepperEnv (the command will be sent using the selected method)
 * @param target Highstate enforcing target
 * @param excludedStates states which will be excluded from main state (default empty string)
 * @param output print output (optional, default true)
 * @param failOnError throw exception on salt state result:false (optional, default true)
 * @param batch salt batch parameter integer or string with percents (optional, default null - disable batch)
 * @param saltArgs additional salt args eq. ["runas=aptly", exclude="opencontrail.database"]
 * @return output of salt command
 */
def enforceHighstateWithExclude(saltId, target, excludedStates = "", output = false, failOnError = true, batch = null, saltArgs = []) {
    saltArgs << "exclude=${excludedStates}"
    return enforceHighstate(saltId, target, output, failOnError, batch, saltArgs)
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
def enforceHighstate(saltId, target, output = false, failOnError = true, batch = null, saltArgs = []) {
    def out = runSaltCommand(saltId, 'local', ['expression': target, 'type': 'compound'], 'state.highstate', batch, saltArgs)
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
 * Get sorted running minions IDs according to the target
 * @param saltId Salt Connection object or pepperEnv
 * @param target Get minions target
 * @return list of sorted active minions fitin
 */
def getMinionsSorted(saltId, target) {
    return getMinions(saltId, target).sort()
}

/**
 * Get first out of running minions IDs according to the target
 * @param saltId Salt Connection object or pepperEnv
 * @param target Get minions target
 * @return first of active minions fitin
 */
def getFirstMinion(saltId, target) {
    def minionsSorted = getMinionsSorted(saltId, target)
    return minionsSorted[0]
}

/**
 * Get running salt minions IDs without it's domain name part and its numbering identifications
 * @param saltId Salt Connection object or pepperEnv
 * @param target Get minions target
 * @return list of active minions fitin without it's domain name part name numbering
 */
def getMinionsGeneralName(saltId, target) {
    def minionsSorted = getMinionsSorted(saltId, target)
    return stripDomainName(minionsSorted[0]).replaceAll('\\d+$', "")
}

/**
 * Get domain name of the env
 * @param saltId Salt Connection object or pepperEnv
 * @return domain name
 */
def getDomainName(saltId) {
    return getReturnValues(getPillar(saltId, 'I@salt:master', '_param:cluster_domain'))
}

/**
 * Remove domain name from Salt minion ID
 * @param name String of Salt minion ID
 * @return Salt minion ID without its domain name
 */
def stripDomainName(name) {
    return name.split("\\.")[0]
}

/**
 * Gets return values of a salt command
 * @param output String of Salt minion ID
 * @return Return values of a salt command
 */
def getReturnValues(output) {
    if(output.containsKey("return") && !output.get("return").isEmpty()) {
        return output['return'][0].values()[0]
    }
    def common = new com.mirantis.mk.Common()
    common.errorMsg('output does not contain return key')
    return ''
}

/**
 * Get minion ID of one of KVM nodes
 * @param saltId Salt Connection object or pepperEnv (the command will be sent using the selected method)
 * @return Salt minion ID of one of KVM nodes in env
 */
def getKvmMinionId(saltId) {
    return getReturnValues(getGrain(saltId, 'I@salt:control', 'id')).values()[0]
}

/**
 * Get Salt minion ID of KVM node hosting 'name' VM
 * @param saltId Salt Connection object or pepperEnv
 * @param name Name of the VM (for ex. ctl01)
 * @return Salt minion ID of KVM node hosting 'name' VM
 */
def getNodeProvider(saltId, nodeName) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def kvms = salt.getMinions(saltId, 'I@salt:control')
    for (kvm in kvms) {
        try {
            vms = salt.getReturnValues(salt.runSaltProcessStep(saltId, kvm, 'virt.list_domains', [], null, true))
            if (vms.toString().contains(nodeName)) {
                return kvm
            }
        } catch (Exception er) {
            common.infoMsg("${nodeName} not present on ${kvm}")
        }
    }
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
 * @param kwargs Salt orchestrate params
 * @return output of salt command
 */
def orchestrateSystem(saltId, target, orchestrate=[], kwargs = null) {
    //Since the runSaltCommand uses "arg" (singular) for "runner" client this won`t work correctly on old salt 2016
    //cause this version of salt used "args" (plural) for "runner" client, see following link for reference:
    //https://github.com/saltstack/salt/pull/32938
    def common = new com.mirantis.mk.Common()
    def result = runSaltCommand(saltId, 'runner', target, 'state.orchestrate', true, orchestrate, kwargs, 7200, 7200)
        if(result != null){
            if(result['return']){
                def retcode = result['return'][0].get('retcode')
                if (retcode != 0) {
                    throw new Exception("Orchestration state failed while running: "+orchestrate)
                }else{
                    common.infoMsg("Orchestrate state "+orchestrate+" succeeded")
                }
            }else{
                common.errorMsg("Salt result has no return attribute! Result: ${result}")
            }
        }else{
            common.errorMsg("Cannot check salt result, given result is null")
        }
}

/**
 * Run salt pre or post orchestrate tasks
 *
 * @param  saltId       Salt Connection object or pepperEnv (the command will be sent using the selected method)
 * @param  pillar_tree  Reclass pillar that has orchestrate pillar for desired stage
 * @param  extra_tgt    Extra targets for compound
 *
 * @return              output of salt command
 */
def orchestratePrePost(saltId, pillar_tree, extra_tgt = '') {

    def common = new com.mirantis.mk.Common()
    def salt = new com.mirantis.mk.Salt()
    def compound = 'I@' + pillar_tree + " " + extra_tgt

    common.infoMsg("Refreshing pillars")
    runSaltProcessStep(saltId, '*', 'saltutil.refresh_pillar', [], null, true)

    common.infoMsg("Looking for orchestrate pillars")
    if (salt.testTarget(saltId, compound)) {
        for ( node in salt.getMinionsSorted(saltId, compound) ) {
            def pillar = salt.getPillar(saltId, node, pillar_tree)
            if ( !pillar['return'].isEmpty() ) {
                for ( orch_id in pillar['return'][0].values() ) {
                    def orchestrator = orch_id.values()['orchestrator']
                    def orch_enabled = orch_id.values()['enabled']
                    if ( orch_enabled ) {
                        common.infoMsg("Orchestrating: ${orchestrator}")
                        salt.printSaltCommandResult(salt.orchestrateSystem(saltId, ['expression': node], [orchestrator]))
                    }
                }
            }
        }
    }
}

/**
 * Run salt process step
 * @param saltId Salt Connection object or pepperEnv (the command will be sent using the selected method)
 * @param tgt Salt process step target
 * @param fun Salt process step function
 * @param arg process step arguments (optional, default [])
 * @param batch salt batch parameter integer or string with percents (optional, default null - disable batch)
 * @param output print output (optional, default true)
 * @param timeout  Additional argument salt api timeout
 * @return output of salt command
 */
def runSaltProcessStep(saltId, tgt, fun, arg = [], batch = null, output = true, timeout = -1, kwargs = null) {
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
 * @param disableAskOnError Flag for disabling ASK_ON_ERROR feature (optional, default false)
 */
def checkResult(result, failOnError = true, printResults = true, printOnlyChanges = true, disableAskOnError = false) {
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
                    def errorResources = []
                    common.infoMsg("Node ${nodeKey} changes:")
                    if(node instanceof Map || node instanceof List){
                        for (int k=0;k<node.size();k++) {
                            def resource;
                            def resKey;
                            if(node instanceof Map){
                                resKey = node.keySet()[k]
                                if (resKey == "retcode")
                                    continue
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
                                errorResources.add(resource)
                            }
                        }
                    }else if(node!=null && node!=""){
                        outputResources.add(String.format("Resource: %s\n\u001B[36m%s\u001B[0m", nodeKey, common.prettify(node)))
                    }
                    if(printResults && !outputResources.isEmpty()){
                        println outputResources.stream().collect(Collectors.joining("\n"))
                    }
                    if(!errorResources.isEmpty()){
                        for(resource in errorResources){
                            def prettyResource = common.prettify(resource)
                            if (!disableAskOnError && env["ASK_ON_ERROR"] && env["ASK_ON_ERROR"] == "true") {
                                timeout(time:1, unit:'HOURS') {
                                   input message: "False result on ${nodeKey} found, resource ${prettyResource}. \nDo you want to continue?"
                                }
                            } else {
                                def errorMsg = "Salt state on node ${nodeKey} failed. Resource: ${prettyResource}"
                                if (failOnError) {
                                    throw new Exception(errorMsg)
                                } else {
                                    common.errorMsg(errorMsg)
                                }
                            }
                        }
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
* Parse salt API output to check minion restart and wait some time to be sure minion is up.
* See https://mirantis.jira.com/browse/PROD-16258 for more details
* TODO: change sleep to more tricky procedure.
*
* @param result    Parsed response of Salt API
*/
def waitForMinion(result, minionRestartWaitTimeout=10) {
    def common = new com.mirantis.mk.Common()
    //In order to prevent multiple sleeps use bool variable to catch restart for any minion.
    def isMinionRestarted = false
    if(result != null){
        if(result['return']){
            for (int i=0;i<result['return'].size();i++) {
                def entry = result['return'][i]
                // exit in case of empty response.
                if (!entry) {
                    return
                }
                // Loop for nodes
                for (int j=0;j<entry.size();j++) {
                    def nodeKey = entry.keySet()[j]
                    def node=entry[nodeKey]
                    if(node instanceof Map || node instanceof List){
                        // Loop for node resources
                        for (int k=0;k<node.size();k++) {
                            def resource;
                            def resKey;
                            if(node instanceof Map){
                                resKey = node.keySet()[k]
                            }else if(node instanceof List){
                                resKey = k
                            }
                            resource = node[resKey]
                            // try to find if salt_minion service was restarted
                            if(resKey instanceof String && resKey.contains("salt_minion_service_restart") && resource instanceof Map && resource.keySet().contains("result")){
                                if((resource["result"] instanceof Boolean && resource["result"]) || (resource["result"] instanceof String && resource["result"] == "true")){
                                    if(resource.changes.size() > 0){
                                        isMinionRestarted=true
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (isMinionRestarted){
      common.infoMsg("Salt minion service restart detected. Sleep ${minionRestartWaitTimeout} seconds to wait minion restart")
        sleep(minionRestartWaitTimeout)
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
 * @param extra_tgt      Extra targets for compound
 */

def setSaltOverrides(saltId, salt_overrides, reclass_dir="/srv/salt/reclass", extra_tgt = '') {
    def common = new com.mirantis.mk.Common()
    def salt_overrides_map = readYaml text: salt_overrides
    for (entry in common.entries(salt_overrides_map)) {
         def key = entry[0]
         def value = entry[1]

         common.debugMsg("Set salt override ${key}=${value}")
         runSaltProcessStep(saltId, "I@salt:master ${extra_tgt}", 'reclass.cluster_meta_set', [key, value], false)
    }
    runSaltProcessStep(saltId, "I@salt:master ${extra_tgt}", 'cmd.run', ["git -C ${reclass_dir} update-index --skip-worktree classes/cluster/overrides.yml"])
}

/**
* Execute salt commands via salt-api with
* CLI client salt-pepper
*
* @param data   Salt command map
* @param venv   Path to virtualenv with
*/

def runPepperCommand(data, venv)   {
    def common = new com.mirantis.mk.Common()
    def python = new com.mirantis.mk.Python()
    def dataStr = new groovy.json.JsonBuilder(data).toString()

    def pepperCmdFile = "${venv}/pepper-cmd.json"
    writeFile file: pepperCmdFile, text: dataStr
    def pepperCmd = "pepper -c ${venv}/pepperrc --make-token -x ${venv}/.peppercache --json-file ${pepperCmdFile}"

    if (venv) {
        output = python.runVirtualenvCommand(venv, pepperCmd, true)
    } else {
        echo("[Command]: ${pepperCmd}")
        output = sh (
            script: pepperCmd,
            returnStdout: true
        ).trim()
    }

    def outputObj
    try {
       outputObj = new groovy.json.JsonSlurperClassic().parseText(output)
    } catch(Exception e) {
       common.errorMsg("Parsing Salt API JSON response failed! Response: " + output)
       throw e
    }
    return outputObj
}
