package com.mirantis.mk

/**
 *
 * Openstack functions
 *
 */

/**
 * Convert maps
 *
 */

@NonCPS def entries(m) {
    return m.collect {k, v -> [k, v]}
}

/**
 * Install OpenStack service clients in isolated environment
 *
 * @param path        Path where virtualenv is created
 * @param version     Version of the OpenStack clients
 */

def setupOpenstackVirtualenv(path, version = 'latest') {
    def python = new com.mirantis.mk.Python()
    python.setupDocutilsVirtualenv(path)

    def openstack_kilo_packages = [
        //XXX: hack to fix https://bugs.launchpad.net/ubuntu/+source/python-pip/+bug/1635463
        'cliff==2.8',
        'python-cinderclient>=1.3.1,<1.4.0',
        'python-glanceclient>=0.19.0,<0.20.0',
        'python-heatclient>=0.6.0,<0.7.0',
        'python-keystoneclient>=1.6.0,<1.7.0',
        'python-neutronclient>=2.2.6,<2.3.0',
        'python-novaclient>=2.19.0,<2.20.0',
        'python-swiftclient>=2.5.0,<2.6.0',
        'python-openstackclient>=1.7.0,<1.8.0',
        'oslo.config>=2.2.0,<2.3.0',
        'oslo.i18n>=2.3.0,<2.4.0',
        'oslo.serialization>=1.8.0,<1.9.0',
        'oslo.utils>=1.4.0,<1.5.0',
        'docutils'
    ]

    def openstack_latest_packages = [
        //XXX: hack to fix https://bugs.launchpad.net/ubuntu/+source/python-pip/+bug/1635463
        'cliff==2.8',
        // NOTE(vsaienko): cmd2 is dependency for cliff, since we don't using upper-contstraints
        // we have to pin cmd2 < 0.9.0 as later versions are not compatible with python2.
        // the same for warlock package due: https://github.com/bcwaldon/warlock/commit/4241a7a9fbccfce7eb3298c2abdf00ca2dede64a
        // TODO(vsaienko): use upper-constraints here, as in requirements we set only lowest library
        //                 versions.
        'cmd2<0.9.0;python_version=="2.7"',
        'cmd2>=0.9.1;python_version=="3.4"',
        'cmd2>=0.9.1;python_version=="3.5"',
        'warlock<=1.3.1;python_version=="2.7"',
        'warlock>1.3.1;python_version=="3.4"',
        'warlock>1.3.1;python_version=="3.5"',
        'python-openstackclient',
        'python-octaviaclient',
        'python-heatclient',
        'docutils'
    ]

    if (version == 'kilo') {
        requirements = openstack_kilo_packages
    } else if (version == 'liberty') {
        requirements = openstack_kilo_packages
    } else if (version == 'mitaka') {
        requirements = openstack_kilo_packages
    } else {
        requirements = openstack_latest_packages
    }
    python.setupVirtualenv(path, 'python2', requirements, null, true)
}

/**
 * create connection to OpenStack API endpoint
 *
 * @param path            Path to created venv
 * @param url             OpenStack API endpoint address
 * @param credentialsId   Credentials to the OpenStack API
 * @param project         OpenStack project to connect to
 */
def createOpenstackEnv(path, url, credentialsId, project, project_domain="default",
    project_id="", user_domain="default", api_ver="2", cacert="/etc/ssl/certs/ca-certificates.crt") {
    def common = new com.mirantis.mk.Common()
    rcFile = "${path}/keystonerc"
    creds = common.getPasswordCredentials(credentialsId)
    rc = """set +x
export OS_USERNAME=${creds.username}
export OS_PASSWORD=${creds.password.toString()}
export OS_TENANT_NAME=${project}
export OS_AUTH_URL=${url}
export OS_AUTH_STRATEGY=keystone
export OS_PROJECT_NAME=${project}
export OS_PROJECT_ID=${project_id}
export OS_PROJECT_DOMAIN_ID=${project_domain}
export OS_USER_DOMAIN_NAME=${user_domain}
export OS_IDENTITY_API_VERSION=${api_ver}
export OS_CACERT=${cacert}
set -x
"""
    writeFile file: rcFile, text: rc
    return rcFile
}

/**
 * Run command with OpenStack env params and optional python env
 *
 * @param cmd    Command to be executed
 * @param env    Environmental parameters with endpoint credentials
 * @param path   Optional path to virtualenv with specific clients
 */
def runOpenstackCommand(cmd, venv, path = null) {
    def python = new com.mirantis.mk.Python()
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
    def python = new com.mirantis.mk.Python()
    cmd = "openstack token issue"
    outputTable = runOpenstackCommand(cmd, client, path)
    output = python.parseTextTable(outputTable, 'item', 'prettytable', path)
    return output
}

/**
 * Create OpenStack environment file
 *
 * @param env          Connection parameters for OpenStack API endpoint
 * @param path         Optional path to the custom virtualenv
 */
def createHeatEnv(file, environment = [], original_file = null) {
    if (original_file) {
        envString = readFile file: original_file
    } else {
        envString = "parameters:\n"
    }

    p = entries(environment)
    for (int i = 0; i < p.size(); i++) {
        envString = "${envString}  ${p.get(i)[0]}: ${p.get(i)[1]}\n"
    }

    echo("writing to env file:\n${envString}")
    writeFile file: file, text: envString
}

/**
 * Create new OpenStack Heat stack. Will wait for action to be complited in
 * specified amount of time (by default 120min)
 *
 * @param env          Connection parameters for OpenStack API endpoint
 * @param template     HOT template for the new Heat stack
 * @param environment  Environmentale parameters of the new Heat stack
 * @param name         Name of the new Heat stack
 * @param path         Optional path to the custom virtualenv
 * @param timeout      Optional number in minutes to wait for stack action is applied.
 */
def createHeatStack(client, name, template, params = [], environment = null, path = null, action="create", timeout=120) {
    def python = new com.mirantis.mk.Python()
    def templateFile = "${env.WORKSPACE}/template/template/${template}.hot"
    def envFile
    def envSource
    if (environment) {
        envFile = "${env.WORKSPACE}/template/env/${name}.env"
        if (environment.contains("/")) {
          //init() returns all elements but the last in a collection.
          def envPath = environment.tokenize("/").init().join("/")
          if (envPath) {
            envFile = "${env.WORKSPACE}/template/env/${envPath}/${name}.env"
          }
        }
        envSource = "${env.WORKSPACE}/template/env/${environment}.env"
        createHeatEnv(envFile, params, envSource)
    } else {
        envFile = "${env.WORKSPACE}/template/${name}.env"
        createHeatEnv(envFile, params)
    }

    def cmd
    def cmd_args = "-t ${templateFile} -e ${envFile} --timeout ${timeout} --wait ${name}"

    if (action == "create") {
        cmd = "openstack stack create ${cmd_args}"
    } else {
        cmd = "openstack stack update ${cmd_args}"
    }

    dir("${env.WORKSPACE}/template/template") {
        def out = runOpenstackCommand(cmd, client, path)
    }
}

/**
 * Returns list of stacks for stack name filter
 *
 * @param client       Connection parameters for OpenStack API endpoint
 * @param filter       Stack name filter
 * @param path         Optional path to the custom virtualenv
 */
def getStacksForNameContains(client, filter, path = null){
    cmd = 'heat stack-list | awk \'NR>3 {print $4}\' | sed \'$ d\' | grep ' + filter + '|| true'
    return runOpenstackCommand(cmd, client, path).trim().tokenize("\n")
}


/**
 * Get list of stack names with given stack status
 *
 * @param client       Connection parameters for OpenStack API endpoint
 * @param status       Stack status
 * @param path         Optional path to the custom virtualenv
 */
 def getStacksWithStatus(client, status, path = null) {
    cmd = 'heat stack-list -f stack_status='+status+' | awk \'NR>3 {print $4}\' | sed \'$ d\''
    return runOpenstackCommand(cmd, client, path).trim().tokenize("\n")
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
    def python = new com.mirantis.mk.Python()
    cmd = "heat stack-show ${name}"
    outputTable = runOpenstackCommand(cmd, env, path)
    output = python.parseTextTable(outputTable, 'item', 'prettytable', path)
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
    echo("${cmd}: ${output}")
    // NOTE(vsaienko) heatclient 1.5.1 returns output in "", while later
    // versions returns string without "".
    // TODO Use openstack 'stack output show' when all jobs using at least Mitaka heatclient
    return "${output}".replaceAll('"', '')
}

/**
 * List all resources from existing OpenStack Heat stack
 *
 * @param env          Connection parameters for OpenStack API endpoint
 * @param name         Name of the managed Heat stack instance
 * @param path         Optional path to the custom virtualenv
 * @param depth        Optional depth of stack for listing resources,
 *                     0 - do not list nested resources
 */
def getHeatStackResources(env, name, path = null, depth = 0) {
    def python = new com.mirantis.mk.Python()
    cmd = "heat resource-list --nested-depth ${depth} ${name}"
    outputTable = runOpenstackCommand(cmd, env, path)
    output = python.parseTextTable(outputTable, 'list', 'prettytable', path)
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
    def python = new com.mirantis.mk.Python()
    cmd = "heat resource-show ${name} ${resource}"
    outputTable = runOpenstackCommand(cmd, env, path)
    output = python.parseTextTable(outputTable, 'item', 'prettytable', path)
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
    def python = new com.mirantis.mk.Python()
    cmd = "heat stack-update ${name}"
    outputTable = runOpenstackCommand(cmd, env, path)
    output = python.parseTextTable(outputTable, 'item', 'prettytable', path)
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
 * Return hashmap of hashes server_id:server_name of servers from OpenStack Heat stack
 *
 * @param env          Connection parameters for OpenStack API endpoint
 * @param name         Name of the managed Heat stack instance
 * @param path         Optional path to the custom virtualenv
 */
def getHeatStackServers(env, name, path = null) {
    // set depth to 1000 to ensure all nested resources are shown
    resources = getHeatStackResources(env, name, path, 1000)
    servers = [:]
    for (resource in resources) {
        if (resource.resource_type == 'OS::Nova::Server') {
            server = getHeatStackResourceInfo(env, resource.stack_name, resource.resource_name, path)
            servers[server.attributes.id] = server.attributes.name
        }
    }
    echo("[Stack ${name}] Servers: ${servers}")
    return servers
}

/**
 * Delete nova key pair
 *
 * @param env          Connection parameters for OpenStack API endpoint
 * @param name         Name of the key pair to delete
 * @param path         Optional path to the custom virtualenv
 */
def deleteKeyPair(env, name, path = null) {
    def common = new com.mirantis.mk.Common()
    common.infoMsg("Removing key pair ${name}")
    def cmd = "openstack keypair delete ${name}"
    runOpenstackCommand(cmd, env, path)
}

/**
 * Get nova key pair
 *
 * @param env          Connection parameters for OpenStack API endpoint
 * @param name         Name of the key pair to show
 * @param path         Optional path to the custom virtualenv
 */

def getKeyPair(env, name, path = null) {
    def common = new com.mirantis.mk.Common()
    def cmd = "openstack keypair show ${name}"
    def outputTable
    try {
        outputTable = runOpenstackCommand(cmd, env, path)
    } catch (Exception e) {
        common.infoMsg("Key pair ${name} not found")
    }
    return outputTable
}

/**
 * Stops all services that contain specific string (for example nova,heat, etc.)
 * @param env Salt Connection object or pepperEnv
 * @param probe single node on which to list service names
 * @param target all targeted nodes
 * @param services  lists of type of services to be stopped
 * @param confirm enable/disable manual service stop confirmation
 * @return output of salt commands
 */
def stopServices(env, probe, target, services=[], confirm=false) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    for (s in services) {
        def outputServicesStr = salt.getReturnValues(salt.cmdRun(env, probe, "service --status-all | grep ${s} | awk \'{print \$4}\'"))
        def servicesList = outputServicesStr.tokenize("\n").init()
        if (confirm) {
            if (servicesList) {
                try {
                    input message: "Click PROCEED to stop ${servicesList}. Otherwise click ABORT to skip stopping them."
                    for (name in servicesList) {
                        if (!name.contains('Salt command')) {
                            salt.runSaltProcessStep(env, target, 'service.stop', ["${name}"])
                        }
                    }
                } catch (Exception er) {
                    common.infoMsg("skipping stopping ${servicesList} services")
                }
            }
        } else {
            if (servicesList) {
                for (name in servicesList) {
                    if (!name.contains('Salt command')) {
                        salt.runSaltProcessStep(env, target, 'service.stop', ["${name}"])
                    }
                }
            }
        }
    }
}

/**
 * Return intersection of globally installed services and those are
 * defined on specific target according to theirs priorities.
 * By default services are added to the result list only if
 * <service>.upgrade.enabled pillar is set to "True". However if it
 * is needed to obtain list of upgrade services regardless of
 * <service>.upgrade.enabled pillar value it is needed to set
 * "upgrade_condition" param to "False".
 *
 * @param env     Salt Connection object or env
 * @param target  The target node to get list of apps for
 * @param upgrade_condition  Whether to take "upgrade:enabled"
 *                           service pillar into consideration
 *                           when obtaining list of upgrade services
**/
def getOpenStackUpgradeServices(env, target, upgrade_condition=true){
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()

    def global_apps = salt.getConfig(env, 'I@salt:master:enabled:true', 'orchestration.upgrade.applications')
    def node_apps = salt.getPillar(env, target, '__reclass__:applications')['return'][0].values()[0]
    if (upgrade_condition) {
        node_pillar = salt.getPillar(env, target)
    }
    def node_sorted_apps = []
    if ( !global_apps['return'][0].values()[0].isEmpty() ) {
        Map<String,Integer> _sorted_apps = [:]
        for (k in global_apps['return'][0].values()[0].keySet()) {
            if (k in node_apps) {
                if (upgrade_condition) {
                    if (node_pillar['return'][0].values()[k]['upgrade']['enabled'][0] != null) {
                        if (node_pillar['return'][0].values()[k]['upgrade']['enabled'][0].toBoolean()) {
                            _sorted_apps[k] = global_apps['return'][0].values()[0][k].values()[0].toInteger()
                        }
                    }
                } else {
                    _sorted_apps[k] = global_apps['return'][0].values()[0][k].values()[0].toInteger()
                }
            }
        }
        node_sorted_apps = common.SortMapByValueAsc(_sorted_apps).keySet()
        common.infoMsg("Applications are placed in following order:"+node_sorted_apps)
    } else {
        common.errorMsg("No applications found.")
    }

  return node_sorted_apps
}

/**
 * Run specified upgrade phase for all services on given node.
 *
 * @param env     Salt Connection object or env
 * @param target  The target node to run states on.
 * @param phase   The phase name to run.
**/
def runOpenStackUpgradePhase(env, target, phase){
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()

    services = getOpenStackUpgradeServices(env, target)
    def st

    for (service in services){
        st = "${service}.upgrade.${phase}".trim()
        common.infoMsg("Running ${phase} for service ${st} on ${target}")
        salt.enforceState(env, target, st)
    }
}


/**
 * Run OpenStack states on specified node.
 *
 * @param env     Salt Connection object or env
 * @param target  The target node to run states on.
**/
def applyOpenstackAppsStates(env, target){
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()

    services = getOpenStackUpgradeServices(env, target)
    def st

    for (service in services){
        st = "${service}".trim()
        common.infoMsg("Running ${st} on ${target}")
        salt.enforceState(env, target, st)
    }
}

def verifyGaleraStatus(env, slave=false, checkTimeSync=false) {
    def common = new com.mirantis.mk.Common()
    def galera = new com.mirantis.mk.Galera()
    common.warningMsg("verifyGaleraStatus method was moved to Galera class. Please change your calls accordingly.")
    return galera.verifyGaleraStatus(env, slave, checkTimeSync)
}

def validateAndPrintGaleraStatusReport(env, out, minion) {
    def common = new com.mirantis.mk.Common()
    def galera = new com.mirantis.mk.Galera()
    common.warningMsg("validateAndPrintGaleraStatusReport method was moved to Galera class. Please change your calls accordingly.")
    return galera.validateAndPrintGaleraStatusReport(env, out, minion)
}

def getGaleraLastShutdownNode(env) {
    def common = new com.mirantis.mk.Common()
    def galera = new com.mirantis.mk.Galera()
    common.warningMsg("getGaleraLastShutdownNode method was moved to Galera class. Please change your calls accordingly.")
    return galera.getGaleraLastShutdownNode(env)
}

def restoreGaleraDb(env) {
    def common = new com.mirantis.mk.Common()
    def galera = new com.mirantis.mk.Galera()
    common.warningMsg("restoreGaleraDb method was moved to Galera class. Please change your calls accordingly.")
    return galera.restoreGaleraDb(env)
}
