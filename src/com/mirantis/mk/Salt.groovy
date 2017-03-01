package com.mirantis.mk

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

    if (batch) {
        data['batch'] = batch
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

def pillarGet(master, target, pillar) {
    def out = runSaltCommand(master, 'local', ['expression': target, 'type': 'compound'], 'pillar.get', null, [pillar.replace('.', ':')])
    return out
}

def enforceState(master, target, state, output = false) {
    def common = new com.mirantis.mk.Common()
    def run_states

    if (state instanceof String) {
        run_states = state
    } else {
        run_states = state.join(',')
    }

    common.infoMsg('Enforcing state ${run_states} on ${target}')

    def out = runSaltCommand(master, 'local', ['expression': target, 'type': 'compound'], 'state.sls', null, [run_states])

    try {
        checkResult(out)
    } finally {
        if (output == true) {
            printSaltStateResult(out)
        }
    }
    return out
}

def cmdRun(master, target, cmd) {
    def common = new com.mirantis.mk.Common()

    common.infoMsg('Running command ${cmd} on ${target}')

    def out = runSaltCommand(master, 'local', ['expression': target, 'type': 'compound'], 'cmd.run', null, [cmd])
    return out
}

def syncAll(master, target) {
    return runSaltCommand(master, 'local', ['expression': target, 'type': 'compound'], 'saltutil.sync_all')
}

def enforceHighstate(master, target, output = false) {
    def out = runSaltCommand(master, 'local', ['expression': target, 'type': 'compound'], 'state.highstate')
    try {
        checkResult(out)
    } finally {
        if (output == true) {
            printSaltStateResult(out)
        }
    }
    return out
}

def generateNodeKey(master, target, host, keysize = 4096) {
    args = [host]
    kwargs = ['keysize': keysize]
    return runSaltCommand(master, 'wheel', target, 'key.gen_accept', args, kwargs)
}

def generateNodeMetadata(master, target, host, classes, parameters) {
    args = [host, '_generated']
    kwargs = ['classes': classes, 'parameters': parameters]
    return runSaltCommand(master, 'local', target, 'reclass.node_create', args, kwargs)
}

def orchestrateSystem(master, target, orchestrate) {
    return runSaltCommand(master, 'runner', target, 'state.orchestrate', [orchestrate])
}

def runSaltProcessStep(master, tgt, fun, arg = [], batch = null, output = true) {
    def common = new com.mirantis.mk.Common()
    def out

    common.infoMsg('Running step ${fun} on ${tgt}')

    if (batch) {
        out = runSaltCommand(master, 'local_batch', ['expression': tgt, 'type': 'compound'], fun, String.valueOf(batch), arg)
    } else {
        out = runSaltCommand(master, 'local', ['expression': tgt, 'type': 'compound'], fun, batch, arg)
    }

    try {
        checkResult(out)
    } finally {
        if (output == true) {
            printSaltCommandResult(out)
        }
    }
}

/**
 * Check result for errors and throw exception if any found
 *
 * @param result    Parsed response of Salt API
 */
def checkResult(result) {
    for (entry in result['return']) {
        if (!entry) {
            throw new Exception("Salt API returned empty response: ${result}")
        }
        for (node in entry) {
            for (resource in node.value) {
                if (resource instanceof String || resource.value.result.toString().toBoolean() != true) {
                    throw new Exception("Salt state on node ${node.key} failed: ${node.value}")
                }
            }
        }
    }
}

/**
 * Print Salt state run results in human-friendly form
 *
 * @param result        Parsed response of Salt API
 * @param onlyChanges   If true (default), print only changed resources
 *                      parsing
 */
def printSaltStateResult(result, onlyChanges = true) {
    def out = [:]
    for (entry in result['return']) {
        for (node in entry) {
            out[node.key] = [:]
            for (resource in node.value) {
                if (resource instanceof String) {
                    out[node.key] = node.value
                } else if (resource.value.result.toString().toBoolean() == false || resource.value.changes || onlyChanges == false) {
                    out[node.key][resource.key] = resource.value
                }
            }
        }
    }

    for (node in out) {
        if (node.value) {
            println "Node ${node.key} changes:"
            print new groovy.json.JsonBuilder(node.value).toPrettyString()
        } else {
            println "No changes for node ${node.key}"
        }
    }
}

/**
 * Print Salt state run results in human-friendly form
 *
 * @param result        Parsed response of Salt API
 */
def printSaltCommandResult(result) {
    def out = [:]
    for (entry in result['return']) {
        for (node in entry) {
            out[node.key] = [:]
            for (resource in node.value) {
                out[node.key] = node.value
            }
        }
    }

    for (node in out) {
        if (node.value) {
            println "Node ${node.key} changes:"
            print new groovy.json.JsonBuilder(node.value).toPrettyString()
        } else {
            println "No changes for node ${node.key}"
        }
    }
}
