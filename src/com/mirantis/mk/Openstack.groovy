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
        // TODO(vsaienko): use upper-constraints here, as in requirements we set only lowest library
        //                 versions.
        'cmd2<0.9.0;python_version=="2.7"',
        'cmd2>=0.9.1;python_version=="3.4"',
        'cmd2>=0.9.1;python_version=="3.5"',
        'python-openstackclient',
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
 * Create new OpenStack Heat stack
 *
 * @param env          Connection parameters for OpenStack API endpoint
 * @param template     HOT template for the new Heat stack
 * @param environment  Environmentale parameters of the new Heat stack
 * @param name         Name of the new Heat stack
 * @param path         Optional path to the custom virtualenv
 */
def createHeatStack(client, name, template, params = [], environment = null, path = null, action="create") {
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
    def waitState

    if (action == "create") {
        cmd = "heat stack-create -f ${templateFile} -e ${envFile} ${name}"
        waitState = "CREATE_COMPLETE"
    } else {
        cmd = "heat stack-update -f ${templateFile} -e ${envFile} ${name}"
        waitState = "UPDATE_COMPLETE"
    }

    dir("${env.WORKSPACE}/template/template") {
        outputTable = runOpenstackCommand(cmd, client, path)
    }

    output = python.parseTextTable(outputTable, 'item', 'prettytable', path)

    def heatStatusCheckerCount = 1
    while (heatStatusCheckerCount <= 250) {
        status = getHeatStackStatus(client, name, path)
        echo("[Heat Stack] Status: ${status}, Check: ${heatStatusCheckerCount}")

        if (status.contains('CREATE_FAILED')) {
            info = getHeatStackInfo(client, name, path)
            throw new Exception(info.stack_status_reason)

        } else if (status.contains(waitState)) {
            info = getHeatStackInfo(client, name, path)
            echo(info.stack_status_reason)
            break
        }

        sleep(30)
        heatStatusCheckerCount++
    }
    echo("[Heat Stack] Status: ${status}")
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
 *
 * @param env     Salt Connection object or env
 * @param target  The target node to get list of apps for.
**/
def getOpenStackUpgradeServices(env, target){
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()

    def global_apps = salt.getConfig(env, 'I@salt:master:enabled:true', 'orchestration.upgrade.applications')
    def node_apps = salt.getPillar(env, target, '__reclass__:applications')['return'][0].values()[0]
    def node_sorted_apps = []
    if ( !global_apps['return'][0].values()[0].isEmpty() ) {
        Map<String,Integer> _sorted_apps = [:]
        for (k in global_apps['return'][0].values()[0].keySet()) {
            if (k in node_apps) {
              _sorted_apps[k] = global_apps['return'][0].values()[0][k].values()[0].toInteger()
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

/**
 * Verifies Galera database
 *
 * This function checks for Galera master, tests connection and if reachable, it obtains the result
 *      of Salt mysql.status function. The result is then parsed, validated and outputed to the user.
 *
 * @param env           Salt Connection object or pepperEnv
 * @return resultCode   int values used to determine exit status in the calling function
 */
def verifyGaleraStatus(env, slave=false) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def out = ""
    def status = "unknown"
    def testNode = ""
    if (!slave) {
        try {
            galeraMaster = salt.getMinions(env, "I@galera:master")
            common.infoMsg("Current Galera master is: ${galeraMaster}")
            salt.minionsReachable(env, "I@salt:master", "I@galera:master")
            testNode = "I@galera:master"
        } catch (Exception e) {
            common.errorMsg('Galera master is not reachable.')
            return 128
        }
    } else {
        try {
            galeraMinions = salt.getMinions(env, "I@galera:slave")
            common.infoMsg("Testing Galera slave minions: ${galeraMinions}")
        } catch (Exception e) {
            common.errorMsg("Cannot obtain Galera slave minions list.")
            return 129
        }
        for (minion in galeraMinions) {
            try {
                salt.minionsReachable(env, "I@salt:master", minion)
                testNode = minion
                break
            } catch (Exception e) {
                common.warningMsg("Slave '${minion}' is not reachable.")
            }
        }
    }
    if (!testNode) {
        common.errorMsg("No Galera slave was reachable.")
        return 130
    }
    try {
        out = salt.cmdRun(env, "I@salt:master", "salt -C '${testNode}' mysql.status")
    } catch (Exception e) {
        common.errorMsg('Could not determine mysql status.')
        return 256
    }
    if (out) {
        try {
            status = validateAndPrintGaleraStatusReport(env, out, testNode)
        } catch (Exception e) {
            common.errorMsg('Could not parse the mysql status output. Check it manually.')
            return 1
        }
    } else {
        common.errorMsg("Mysql status response unrecognized or is empty. Response: ${out}")
        return 1024
    }
    if (status == "OK") {
        common.infoMsg("No errors found - MySQL status is ${status}.")
        return 0
    } else if (status == "unknown") {
        common.warningMsg('MySQL status cannot be detemined')
        return 1
    } else {
        common.errorMsg("Errors found.")
        return 2
    }
}

/** Validates and prints result of verifyGaleraStatus function
@param env      Salt Connection object or pepperEnv
@param out      Output of the mysql.status Salt function
@return status  "OK", "ERROR" or "uknown" depending on result of validation
*/

def validateAndPrintGaleraStatusReport(env, out, minion) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    if (minion == "I@galera:master") {
        role = "master"
    } else {
        role = "slave"
    }
    sizeOut = salt.getReturnValues(salt.getPillar(env, minion, "galera:${role}:members"))
    expected_cluster_size = sizeOut.size()
    outlist = out['return'][0]
    resultString = outlist.get(outlist.keySet()[0]).replace("\n        ", " ").replace("    ", "").replace("Salt command execution success", "").replace("----------", "").replace(": \n", ": no value\n")
    resultYaml = readYaml text: resultString
    parameters = [
        wsrep_cluster_status: [title: 'Cluster status', expectedValues: ['Primary'], description: ''],
        wsrep_cluster_size: [title: 'Current cluster size', expectedValues: [expected_cluster_size], description: ''],
        wsrep_ready: [title: 'Node status', expectedValues: ['ON', true], description: ''],
        wsrep_local_state_comment: [title: 'Node status comment', expectedValues: ['Joining', 'Waiting on SST', 'Joined', 'Synced', 'Donor'], description: ''],
        wsrep_connected: [title: 'Node connectivity', expectedValues: ['ON', true], description: ''],
        wsrep_local_recv_queue_avg: [title: 'Average size of local reveived queue', expectedThreshold: [warn: 0.5, error: 1.0], description: '(Value above 0 means that the node cannot apply write-sets as fast as it receives them, which can lead to replication throttling)'],
        wsrep_local_send_queue_avg: [title: 'Average size of local send queue', expectedThreshold: [warn: 0.5, error: 1.0], description: '(Value above 0 indicate replication throttling or network throughput issues, such as a bottleneck on the network link.)']
        ]
    results = [:].withDefault {"unknown"}
    for (key in parameters.keySet()) {
        value = resultYaml[key]
        parameters.get(key) << [actualValue: value]
    }
    for (key in parameters.keySet()) {
        param = parameters.get(key)
        if (key == 'wsrep_local_recv_queue_avg' || key == 'wsrep_local_send_queue_avg') {
            if (param.get('actualValue') > param.get('expectedThreshold').get('error')) {
                param << [match: 'error']
            } else if (param.get('actualValue') > param.get('expectedThreshold').get('warn')) {
                param << [match: 'warn']
            } else {
                param << [match: 'ok']
            }
        } else {
            for (expValue in param.get('expectedValues')) {
                if (expValue == param.get('actualValue')) {
                    param << [match: 'ok']
                    break
                } else {
                    param << [match: 'error']
                }
            }
        }
    }
    cluster_info_report = []
    cluster_warning_report = []
    cluster_error_report = []
    for (key in parameters.keySet()) {
        param = parameters.get(key)
        if (param.containsKey('expectedThreshold')) {
            expValues = "below ${param.get('expectedThreshold').get('warn')}"
        } else {
            if (param.get('expectedValues').size() > 1) {
                expValues = param.get('expectedValues').join(' or ')
            } else {
                expValues = param.get('expectedValues')[0]
            }
        }
        reportString = "${param.title}: ${param.actualValue} (Expected: ${expValues}) ${param.description}"
        if (param.get('match').equals('ok')) {
            cluster_info_report.add("[OK     ] ${reportString}")
        } else if (param.get('match').equals('warn')) {
            cluster_warning_report.add("[WARNING] ${reportString}")
        } else {
            cluster_error_report.add("[  ERROR] ${reportString})")
        }
    }
    common.infoMsg("CLUSTER STATUS REPORT: ${cluster_info_report.size()} expected values, ${cluster_warning_report.size()} warnings and ${cluster_error_report.size()} error found:")
    if (cluster_info_report.size() > 0) {
        common.infoMsg(cluster_info_report.join('\n'))
    }
    if (cluster_warning_report.size() > 0) {
        common.warningMsg(cluster_warning_report.join('\n'))
    }
    if (cluster_error_report.size() > 0) {
        common.errorMsg(cluster_error_report.join('\n'))
        return "ERROR"
    } else {
        return "OK"
    }
}

def getGaleraLastShutdownNode(env) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    members = ''
    lastNode = [ip: '', seqno: -2]
    try {
        members = salt.getReturnValues(salt.getPillar(env, "I@galera:master", "galera:master:members"))
    } catch (Exception er) {
        common.errorMsg('Could not retrieve members list')
        return 'I@galera:master'
    }
    if (members) {
        for (member in members) {
            try {
                salt.minionsReachable(env, 'I@salt:master', "S@${member.host}")
                out = salt.getReturnValues(salt.cmdRun(env, "S@${member.host}", 'cat /var/lib/mysql/grastate.dat | grep "seqno" | cut -d ":" -f2', true, null, false))
                seqno = out.tokenize('\n')[0].trim()
                if (seqno.isNumber()) {
                    seqno = seqno.toInteger()
                } else {
                    seqno = -2
                }
                highestSeqno = lastNode.get('seqno')
                if (seqno > highestSeqno) {
                    lastNode << [ip: "${member.host}", seqno: seqno]
                }
            } catch (Exception er) {
                common.warningMsg("Could not determine 'seqno' value for node ${member.host} ")
            }
        }
    }
    if (lastNode.get('ip') != '') {
        return "S@${lastNode.ip}"
    } else {
        return "I@galera:master"
    }
}

/**
 * Restores Galera database
 * @param env Salt Connection object or pepperEnv
 * @return output of salt commands
 */
def restoreGaleraDb(env) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    try {
        salt.runSaltProcessStep(env, 'I@galera:slave', 'service.stop', ['mysql'])
    } catch (Exception er) {
        common.warningMsg('Mysql service already stopped')
    }
    try {
        salt.runSaltProcessStep(env, 'I@galera:master', 'service.stop', ['mysql'])
    } catch (Exception er) {
        common.warningMsg('Mysql service already stopped')
    }
    lastNodeTarget = getGaleraLastShutdownNode(env)
    try {
        salt.cmdRun(env, 'I@galera:slave', "rm /var/lib/mysql/ib_logfile*")
    } catch (Exception er) {
        common.warningMsg('Files are not present')
    }
    try {
        salt.cmdRun(env, 'I@galera:slave', "rm  /var/lib/mysql/grastate.dat")
    } catch (Exception er) {
        common.warningMsg('Files are not present')
    }
    try {
        salt.cmdRun(env, lastNodeTarget, "mkdir /root/mysql/mysql.bak")
    } catch (Exception er) {
        common.warningMsg('Directory already exists')
    }
    try {
        salt.cmdRun(env, lastNodeTarget, "rm -rf /root/mysql/mysql.bak/*")
    } catch (Exception er) {
        common.warningMsg('Directory already empty')
    }
    try {
        salt.cmdRun(env, lastNodeTarget, "mv /var/lib/mysql/* /root/mysql/mysql.bak")
    } catch (Exception er) {
        common.warningMsg('Files were already moved')
    }
    try {
        salt.runSaltProcessStep(env, lastNodeTarget, 'file.remove', ["/var/lib/mysql/.galera_bootstrap"])
    } catch (Exception er) {
        common.warningMsg('File is not present')
    }
    salt.cmdRun(env, lastNodeTarget, "sed -i '/gcomm/c\\wsrep_cluster_address=\"gcomm://\"' /etc/mysql/my.cnf")
    def backup_dir = salt.getReturnValues(salt.getPillar(env, lastNodeTarget, 'xtrabackup:client:backup_dir'))
    if(backup_dir == null || backup_dir.isEmpty()) { backup_dir='/var/backups/mysql/xtrabackup' }
    salt.runSaltProcessStep(env, lastNodeTarget, 'file.remove', ["${backup_dir}/dbrestored"])
    salt.cmdRun(env, 'I@xtrabackup:client', "su root -c 'salt-call state.sls xtrabackup'")
    salt.runSaltProcessStep(env, lastNodeTarget, 'service.start', ['mysql'])

    // wait until mysql service on galera master is up
    try {
        salt.commandStatus(env, lastNodeTarget, 'service mysql status', 'running')
    } catch (Exception er) {
        input message: "Database is not running please fix it first and only then click on PROCEED."
    }

    salt.runSaltProcessStep(env, "I@galera:master and not ${lastNodeTarget}", 'service.start', ['mysql'])
    salt.runSaltProcessStep(env, "I@galera:slave and not ${lastNodeTarget}", 'service.start', ['mysql'])
}