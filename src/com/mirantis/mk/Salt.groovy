package com.mirantis.mk

import static groovy.json.JsonOutput.prettyPrint
import static groovy.json.JsonOutput.toJson

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
 * Run action using Salt API
 *
 * @param master   Salt connection object
 * @param client   Client type
 * @param target   Target specification, eg. for compound matches by Pillar
 *                 data: ['expression': 'I@openssh:server', 'type': 'compound'])
 * @param function Function to execute (eg. "state.sls")
 * @param batch
 * @param args     Additional arguments to function
 * @param kwargs   Additional key-value arguments to function
 */
@NonCPS
def runSaltCommand(master, client, target, function, batch = null, args = null, kwargs = null) {
    def http = new com.mirantis.mk.Http()

    data = [
        'tgt': target.expression,
        'fun': function,
        'client': client,
        'expr_form': target.type,
    ]

    if (batch == true) {
        data['batch'] = "local_batch"
    }

    if (args) {
        data['arg'] = args
    }

    if (kwargs) {
        data['kwarg'] = kwargs
    }

    headers = [
      'X-Auth-Token': "${master.authToken}"
    ]

    return http.sendHttpPostRequest("${master.url}/", data, headers)
}

/**
 * Return pillar for given master and target
 * @param master Salt connection object
 * @param target Get pillar target
 * @param pillar pillar name (optional)
 * @return output of salt command
 */
def getPillar(master, target, pillar = null) {
    if (pillar != null) {
        return runSaltCommand(master, 'local', ['expression': target, 'type': 'compound'], 'pillar.get', null, [pillar.replace('.', ':')])
    } else {
        return runSaltCommand(master, 'local', ['expression': target, 'type': 'compound'], 'pillar.data')
    }
}

/**
 * Return grain for given master and target
 * @param master Salt connection object
 * @param target Get grain target
 * @param grain grain name (optional)
 * @return output of salt command
 */
def getGrain(master, target, grain = null) {
    if(grain != null) {
        return runSaltCommand(master, 'local', ['expression': target, 'type': 'compound'], 'grain.item', null, [grain])
    } else {
        return runSaltCommand(master, 'local', ['expression': target, 'type': 'compound'], 'grain.items')
    }
}

/**
 * Enforces state on given master and target
 * @param master Salt connection object
 * @param target State enforcing target
 * @param state Salt state
 * @param output print output (optional, default true)
 * @param failOnError throw exception on salt state result:false (optional, default true)
 * @return output of salt command
 */
def enforceState(master, target, state, output = true, failOnError = true) {
    def common = new com.mirantis.mk.Common()
    def run_states

    if (state instanceof String) {
        run_states = state
    } else {
        run_states = state.join(',')
    }

    common.infoMsg("Enforcing state ${run_states} on ${target}")

    def out = runSaltCommand(master, 'local', ['expression': target, 'type': 'compound'], 'state.sls', null, [run_states])

    try {
        checkResult(out, failOnError)
    } finally {
        if (output == true) {
            printSaltStateResult(out)
        }
    }
    return out
}

/**
 * Run command on salt minion (salt cmd.run wrapper)
 * @param master Salt connection object
 * @param target Get pillar target
 * @param cmd command
 * @return output of salt command
 */
def cmdRun(master, target, cmd) {
    def common = new com.mirantis.mk.Common()

    common.infoMsg("Running command ${cmd} on ${target}")

    return runSaltCommand(master, 'local', ['expression': target, 'type': 'compound'], 'cmd.run', null, [cmd])
}

/**
 * Perform complete salt sync between master and target
 * @param master Salt connection object
 * @param target Get pillar target
 * @return output of salt command
 */
def syncAll(master, target) {
    return runSaltCommand(master, 'local', ['expression': target, 'type': 'compound'], 'saltutil.sync_all')
}

/**
 * Enforce highstate on given targets
 * @param master Salt connection object
 * @param target Highstate enforcing target
 * @param output print output (optional, default true)
 * @param failOnError throw exception on salt state result:false (optional, default true)
 * @return output of salt command
 */
def enforceHighstate(master, target, output = false, failOnError = true) {
    def out = runSaltCommand(master, 'local', ['expression': target, 'type': 'compound'], 'state.highstate')
    try {
        checkResult(out, failOnError)
    } finally {
        if (output == true) {
            printSaltStateResult(out)
        }
    }
    return out
}

/**
 * Get running minions IDs according to the target
 * @param master Salt connection object
 * @param target Get minions target
 * @return list of active minions fitin
 */
def getMinions(master, target) {
    def minionsRaw = runSaltCommand(master, 'local', target, 'test.ping')
    return new ArrayList<String>(minionsRaw['return'][0].keySet())
}


/**
 * Generates node key using key.gen_accept call
 * @param master Salt connection object
 * @param target Key generating target
 * @param host Key generating host
 * @param keysize generated key size (optional, default 4096)
 * @return output of salt command
 */
def generateNodeKey(master, target, host, keysize = 4096) {
    return runSaltCommand(master, 'wheel', target, 'key.gen_accept', [host], ['keysize': keysize])
}

/**
 * Generates node reclass metadata 
 * @param master Salt connection object
 * @param target Metadata generating target
 * @param host Metadata generating host
 * @param classes Reclass classes
 * @param parameters Reclass parameters
 * @return output of salt command
 */
def generateNodeMetadata(master, target, host, classes, parameters) {
    return runSaltCommand(master, 'local', target, 'reclass.node_create', [host, '_generated'], ['classes': classes, 'parameters': parameters])
}

/**
 * Run salt orchestrate on given targets
 * @param master Salt connection object
 * @param target Orchestration target
 * @param orchestrate Salt orchestrate params
 * @return output of salt command
 */
def orchestrateSystem(master, target, orchestrate) {
    return runSaltCommand(master, 'runner', target, 'state.orchestrate', [orchestrate])
}

/**
 * Run salt process step
 * @param master Salt connection object
 * @param tgt Salt process step target
 * @param fun Salt process step function
 * @param arg process step arguments (optional, default [])
 * @param batch using batch (optional, default false)
 * @param output print output (optional, default false)
 * @return output of salt command
 */
def runSaltProcessStep(master, tgt, fun, arg = [], batch = null, output = false) {
    def common = new com.mirantis.mk.Common()
    def out

    common.infoMsg("Running step ${fun} on ${tgt}")

    if (batch == true) {
        out = runSaltCommand(master, 'local_batch', ['expression': tgt, 'type': 'compound'], fun, String.valueOf(batch), arg)
    } else {
        out = runSaltCommand(master, 'local', ['expression': tgt, 'type': 'compound'], fun, batch, arg)
    }

    if (output == true) {
        printSaltCommandResult(out)
    }
}

/**
 * Check result for errors and throw exception if any found
 *
 * @param result    Parsed response of Salt API
 * @param failOnError Do you want to throw exception if salt-call fails
 */
def checkResult(result, failOnError = true) {
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
                    for (int k=0;k<node.size();k++) {
                        def resource;
                        def resKey;
                        if(node instanceof Map){
                            resKey = node.keySet()[k]
                        }else if(node instanceof List){
                            resKey = k
                        }
                        resource = node[resKey]
                        common.debugMsg("checkResult: checking resource: ${resource}")
                        if(resource instanceof String || !resource["result"] || (resource["result"] instanceof String && resource["result"] != "true")){
                            if (failOnError) {
                                throw new Exception("Salt state on node ${nodeKey} failed: ${resource}. State output: ${node}")
                            } else {
                                common.errorMsg("Salt state on node ${nodeKey} failed: ${resource}. State output: ${node}")
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
 * Print Salt state run results in human-friendly form
 *
 * @param result        Parsed response of Salt API
  * @param onlyChanges   If true (default), print only changed resources
 */
def printSaltStateResult(result, onlyChanges = true) {
    def common = new com.mirantis.mk.Common()
    if(result != null){
        if(result['return']){
            for (int i=0; i<result['return'].size(); i++) {
                def entry = result['return'][i]
                for (int j=0; j<entry.size(); j++) {
                    common.debugMsg("printSaltStateResult: printing salt command entry: ${entry}")
                    def nodeKey = entry.keySet()[j]
                    def node=entry[nodeKey]
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
                                    common.errorMsg(String.format("Resource: %s\n%s", resKey, prettyPrint(toJson(resource)).replace('\\n', System.getProperty('line.separator'))))
                                }else{
                                    if(!onlyChanges || resource.changes.size() > 0){
                                        common.successMsg(String.format("Resource: %s\n%s", resKey, prettyPrint(toJson(resource)).replace('\\n', System.getProperty('line.separator'))))
                                    }
                                }
                            }else{
                                common.infoMsg(String.format("Resource: %s\n%s", resKey, prettyPrint(toJson(resource)).replace('\\n', System.getProperty('line.separator'))))
                            }
                        }
                    }else{
                        common.infoMsg(prettyPrint(toJson(node)))
                    }
                }
            }
        }else{
            common.errorMsg("Salt result hasn't return attribute! Result: ${result}")
        }
    }else{
        common.errorMsg("Cannot print salt state result, given result is null")
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
                    common.infoMsg(String.format("Node %s changes:\n%s",nodeKey,prettyPrint(toJson(node)).replace('\\n', System.getProperty('line.separator'))))
                }
            }
        }else{
            common.errorMsg("Salt result hasn't return attribute! Result: ${result}")
        }
    }else{
        common.errorMsg("Cannot print salt command result, given result is null")
    }
}
