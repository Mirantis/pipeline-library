package com.mirantis.mk

/**
 *
 * Openstack functions
 *
 */

/**
 * Install OpenStack service clients in isolated environment
 *
 * @param path        Path where virtualenv is created
 * @param version     Version of the OpenStack clients
 */

def setupOpenstackVirtualenv(path, version = 'kilo'){
    def python = new com.mirantis.mk.python()

    def openstack_kilo_packages = [
        'python-cinderclient>=1.3.1,<1.4.0',
        'python-glanceclient>=0.19.0,<0.20.0',
        'python-heatclient>=0.6.0,<0.7.0',
        'python-keystoneclient>=1.6.0,<1.7.0',
        'python-neutronclient>=2.2.6,<2.3.0',
        'python-novaclient>=2.19.0,<2.20.0',
        'python-swiftclient>=2.5.0,<2.6.0',
        'oslo.config>=2.2.0,<2.3.0',
        'oslo.i18n>=2.3.0,<2.4.0',
        'oslo.serialization>=1.8.0,<1.9.0',
        'oslo.utils>=1.4.0,<1.5.0',
    ]

    def openstack_latest_packages = openstack_kilo_packages

    if(version == 'kilo') {
        requirements = openstack_kilo_packages
    }
    else if(version == 'liberty') {
        requirements = openstack_kilo_packages
    }
    else if(version == 'mitaka') {
        requirements = openstack_kilo_packages
    }
    else {
        requirements = openstack_latest_packages
    }
    python.setupVirtualenv(path, 'python2', requirements)
}

/**
 * create connection to OpenStack API endpoint
 *
 * @param url             OpenStack API endpoint address
 * @param credentialsId   Credentials to the OpenStack API
 * @param project         OpenStack project to connect to
 */
@NonCPS
def createOpenstackEnv(url, credentialsId, project) {
    def common = new com.mirantis.mk.common()
    creds = common.getPasswordCredentials(credentialsId)
    params = [
        "OS_USERNAME": creds.username,
        "OS_PASSWORD": creds.password.toString(),
        "OS_TENANT_NAME": project,
        "OS_AUTH_URL": url,
        "OS_AUTH_STRATEGY": "keystone"
    ]
    res = ""
    for ( e in params ) {
        res = "${res}export ${e.key}=${e.value}\n"
    }
    writeFile file: "${env.WORKSPACE}/keystonerc", text: res
    return "${env.WORKSPACE}/keystonerc"
    //return res.substring(1)
}

/**
 * Run command with OpenStack env params and optional python env
 *
 * @param cmd    Command to be executed
 * @param env    Environmental parameters with endpoint credentials
 * @param path   Optional path to virtualenv with specific clients
 */
def runOpenstackCommand(cmd, venv, path = null) {
    def python = new com.mirantis.mk.python()
    openstackCmd = ". ${venv}; ${cmd}"
    if (path) {
        output = python.runVirtualenvCommand(path, openstackCmd)
    }
    else {
        echo("[Command]: ${openstackCmd}")
        output = sh (
            script: openstackCmd,
            returnStdout: true
        ).trim()
    }
    return output
}

/**
 * Get OpenStack Keystone token for current credentials
 *
 * @param env          Connection parameters for OpenStack API endpoint
 * @param path         Optional path to the custom virtualenv
 */
def getKeystoneToken(client, path = null) {
    def python = new com.mirantis.mk.python()
    cmd = "keystone token-get"
    outputTable = runOpenstackCommand(cmd, client, path)
    output = python.parseTextTable(outputTable, 'item', 'prettytable')
    return output
}

/**
 * Get OpenStack Keystone token for current credentials
 *
 * @param env          Connection parameters for OpenStack API endpoint
 * @param path         Optional path to the custom virtualenv
 */
def createHeatEnv(file, environment = [], original_file = null) {
    if (original_file) {
        envString = readFile file: original_file
    }
    else {
        envString = "parameters:\n"
    }
    for ( int i = 0; i < environment.size; i++ ) {
        envString = "${envString}  ${environment.get(i).get(0)}: ${environment.get(i).get(1)}\n"
    }
    writeFile file: file, text: envString
}

/**
 * Create new OpenStack Heat stack
 *
 * @param env          Connection parameters for OpenStack API endpoint
 * @param template     HOT template for the new Heat stack
 * @param environment  Environmentale parameters of the new Heat stack
 * @param name         Name of the new Heat stack
 * @param path         Optional path to the custom virtualenv
 */
def createHeatStack(client, name, template, params = [], environment = null, path = null) {
    def python = new com.mirantis.mk.python()
    templateFile = "${env.WORKSPACE}/template/template/${template}.hot"
    if (environment) {
        envFile = "${env.WORKSPACE}/template/env/${template}/${name}.env"
        envSource = "${env.WORKSPACE}/template/env/${template}/${environment}.env"
        createHeatEnv(envFile, params, envSource)
    }
    else {
        envFile = "${env.WORKSPACE}/template/${name}.env"
        createHeatEnv(envFile, params)
    }
    cmd = "heat stack-create -f ${templateFile} -e ${envFile} ${name}"
    dir("${env.WORKSPACE}/template/template") {
        outputTable = runOpenstackCommand(cmd, client, path)
    }
    output = python.parseTextTable(outputTable, 'item', 'prettytable')

    i = 1
    while (true) {
        status = getHeatStackStatus(client, name, path)
        echo("[Heat Stack] Status: ${status}, Check: ${i}")
        if (status == 'CREATE_FAILED') {
            info = getHeatStackInfo(client, name, path)
            throw new Exception(info.stack_status_reason)
        }
        else if (status == 'CREATE_COMPLETE') {
            info = getHeatStackInfo(client, name, path)
            echo(info.stack_status_reason)
            break
        }
        sh('sleep 5s')
        i++
    }
    echo("[Heat Stack] Status: ${status}")
}

/**
 * Get life cycle status for existing OpenStack Heat stack
 *
 * @param env          Connection parameters for OpenStack API endpoint
 * @param name         Name of the managed Heat stack instance
 * @param path         Optional path to the custom virtualenv
 */
def getHeatStackStatus(client, name, path = null) {
    cmd = 'heat stack-list | awk -v stack='+name+' \'{if ($4==stack) print $6}\''
    return runOpenstackCommand(cmd, client, path)
}

/**
 * Get info about existing OpenStack Heat stack
 *
 * @param env          Connection parameters for OpenStack API endpoint
 * @param name         Name of the managed Heat stack instance
 * @param path         Optional path to the custom virtualenv
 */
def getHeatStackInfo(env, name, path = null) {
    def python = new com.mirantis.mk.python()
    cmd = "heat stack-show ${name}"
    outputTable = runOpenstackCommand(cmd, env, path)
    output = python.parseTextTable(outputTable, 'item', 'prettytable')
    return output
}

/**
 * Get existing OpenStack Heat stack output parameter
 *
 * @param env          Connection parameters for OpenStack API endpoint
 * @param name         Name of the managed Heat stack
 * @param parameter    Name of the output parameter
 * @param path         Optional path to the custom virtualenv
 */
def getHeatStackOutputParam(env, name, outputParam, path = null) {
    cmd = "heat output-show ${name} ${outputParam}"
    output = runOpenstackCommand(cmd, env, path)
    return output.substring(1, output.length()-1)
}

/**
 * List all resources from existing OpenStack Heat stack
 *
 * @param env          Connection parameters for OpenStack API endpoint
 * @param name         Name of the managed Heat stack instance
 * @param path         Optional path to the custom virtualenv
 */
def getHeatStackResources(env, name, path = null) {
    def python = new com.mirantis.mk.python()
    cmd = "heat resource-list ${name}"
    outputTable = runOpenstackCommand(cmd, env, path)
    output = python.parseTextTable(outputTable, 'list', 'prettytable')
    return output
}

/**
 * Get info about resource from existing OpenStack Heat stack
 *
 * @param env          Connection parameters for OpenStack API endpoint
 * @param name         Name of the managed Heat stack instance
 * @param path         Optional path to the custom virtualenv
 */
def getHeatStackResourceInfo(env, name, resource, path = null) {
    def python = new com.mirantis.mk.python()
    cmd = "heat resource-show ${name} ${resource}"
    outputTable = runOpenstackCommand(cmd, env, path)
    output = python.parseTextTable(outputTable, 'item', 'prettytable')
    return output
}

/**
 * Update existing OpenStack Heat stack
 *
 * @param env          Connection parameters for OpenStack API endpoint
 * @param name         Name of the managed Heat stack instance
 * @param path         Optional path to the custom virtualenv
 */
def updateHeatStack(env, name, path = null) {
    def python = new com.mirantis.mk.python()
    cmd = "heat stack-update ${name}"
    outputTable = runOpenstackCommand(cmd, env, path)
    output = python.parseTextTable(outputTable, 'item', 'prettytable')
    return output
}

/**
 * Delete existing OpenStack Heat stack
 *
 * @param env          Connection parameters for OpenStack API endpoint
 * @param name         Name of the managed Heat stack instance
 * @param path         Optional path to the custom virtualenv
 */
def deleteHeatStack(env, name, path = null) {
    cmd = "heat stack-delete ${name}"
    outputTable = runOpenstackCommand(cmd, env, path)
}

/**
 * Return list of servers from OpenStack Heat stack
 *
 * @param env          Connection parameters for OpenStack API endpoint
 * @param name         Name of the managed Heat stack instance
 * @param path         Optional path to the custom virtualenv
 */
def getHeatStackServers(env, name, path = null) {
    resources = heatGetStackResources(env, name, path)
    servers = []
    for (resource in resources) {
        if (resource.resource_type == 'OS::Nova::Server') {
            resourceName = resource.resource_name
            server = heatGetStackResourceInfo(env, name, resourceName, path)
            servers.add(server.attributes.name)
        }
    }
    echo("[Stack ${name}] Servers: ${servers}")
    return servers
}
