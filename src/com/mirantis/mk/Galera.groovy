package com.mirantis.mk

/**
 *
 * Galera functions
 *
 */


/**
 * Returns parameters from mysql.status output on given target node
 *
 * @param env           Salt Connection object or pepperEnv
 * @param target        Targeted node
 * @param parameters    Parameters to be retruned (String or list of Strings). If no parameters are provided or is set to '[]', it returns all of them.
 * @return result       List of parameters with its values
 */

def getWsrepParameters(env, target, parameters=[], print=false) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    result = [:]
    out = salt.runSaltProcessStep(env, "${target}", "mysql.status", [], null, false)
    outlist = out['return'][0]
    resultYaml = outlist.get(outlist.keySet()[0]).sort()
    if (print) {
        common.prettyPrint(resultYaml)
    }
    if (parameters instanceof String) {
        parameters = [parameters]
    }
    if (parameters == [] || parameters == ['']) {
        result = resultYaml
    } else {
        for (String param in parameters) {
            value = resultYaml[param]
            if (value instanceof String && value.isBigDecimal()) {
                value = value.toBigDecimal()
            }
            result[param] = value
        }
    }
    return result
}

/**
 * Verifies Galera database
 *
 * This function checks for Galera master, tests connection and if reachable, it obtains the result
 *      of Salt mysql.status function. The result is then parsed, validated and outputed to the user.
 *
 * @param env           Salt Connection object or pepperEnv
 * @param checkTimeSync Boolean value to enable time sync check
 * @return resultCode   int values used to determine exit status in the calling function
 */
def verifyGaleraStatus(env, checkTimeSync=false) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def mysqlStatusReport = [
        'clusterMembersOnPower': [],
        'clusterMembersNotAvailable': [],
        'clusterMembersInClusterAlive': [],
        'clusterMembersNotAlive': [],
        'error': 0
    ]

    try {
        def clusterMembers = salt.getMinions(env, "I@galera:master or I@galera:slave")
        for (minion in clusterMembers) {
            try {
                salt.minionsReachable(env, "I@salt:master", minion)
                mysqlStatusReport['clusterMembersOnPower'] << minion
            } catch (Exception e) {
                common.warningMsg("Slave '${minion}' is not reachable.")
                mysqlStatusReport['clusterMembersNotAvailable'] << minion
            }
        }
    } catch (Exception e) {
        common.errorMsg('Cannot obtain Galera minions list.')
        common.errorMsg(e.getMessage())
        mysqlStatusReport['error'] = 128
        return mysqlStatusReport
    }

    if (!mysqlStatusReport['clusterMembersOnPower']) {
        common.errorMsg("No Galera member was reachable.")
        mysqlStatusReport['error'] = 130
        return mysqlStatusReport
    }

    def checkTargets = salt.getMinions(env, "I@xtrabackup:client or I@xtrabackup:server")
    for (checkTarget in checkTargets) {
        def nodeStatus = salt.minionsReachable(env, 'I@salt:master', checkTarget, null, 10, 5)
        if (nodeStatus != null) {
            def iostatRes = salt.getIostatValues(['saltId': env, 'target': checkTarget, 'parameterName': "%util", 'output': true])
            if (iostatRes == [:]) {
                common.errorMsg("Recevived empty response from iostat call on ${checkTarget}. Maybe 'sysstat' package is not installed?")
                mysqlStatusReport['error'] = 140
                return mysqlStatusReport
            }
            for (int i = 0; i < iostatRes.size(); i++) {
                def diskKey = iostatRes.keySet()[i]
                if (!(iostatRes[diskKey].toString().isBigDecimal() && (iostatRes[diskKey].toBigDecimal() < 50 ))) {
                    common.errorMsg("Disk ${diskKey} has to high i/o utilization. Maximum value is 50 and current value is ${iostatRes[diskKey]}.")
                    mysqlStatusReport['error'] = 141
                    return mysqlStatusReport
                }
            }
        }
    }
    common.infoMsg("Disk i/o utilization was checked and everything seems to be in order.")
    if (checkTimeSync && !salt.checkClusterTimeSync(env, "I@galera:master or I@galera:slave")) {
        common.errorMsg("Time in cluster is desynchronized or it couldn't be detemined. You should fix this issue manually before proceeding.")
        mysqlStatusReport['error'] = 131
        return mysqlStatusReport
    }

    for(member in mysqlStatusReport['clusterMembersOnPower']) {
        def clusterStatus = getWsrepParameters(env, member, 'wsrep_cluster_status')
        if (clusterStatus['wsrep_cluster_status']) {
            mysqlStatusReport['clusterMembersInClusterAlive'] << member
        } else {
            mysqlStatusReport['clusterMembersNotAlive'] << member
        }
    }
    if (!mysqlStatusReport['clusterMembersInClusterAlive']) {
        common.errorMsg("Could not determine mysql status, because all nodes are not connected to cluster.")
        mysqlStatusReport['error'] = 256
        return mysqlStatusReport
    }
    def testNode = mysqlStatusReport['clusterMembersInClusterAlive'].sort().first()

    try {
        mysqlStatusReport['statusRaw'] = salt.runSaltProcessStep(env, testNode, "mysql.status", [], null, false)
    } catch (Exception e) {
        common.errorMsg('Could not determine mysql status.')
        common.errorMsg(e.getMessage())
        mysqlStatusReport['error'] = 256
        return mysqlStatusReport
    }

    def status = "unknown"
    def galeraMasterNode = salt.getReturnValues(salt.getPillar(env, testNode, "galera:master:enabled")) ? true : false

    if (mysqlStatusReport['statusRaw']) {
        try {
            status = validateAndPrintGaleraStatusReport(env, mysqlStatusReport['statusRaw'], testNode, galeraMasterNode)
        } catch (Exception e) {
            common.errorMsg('Could not parse the mysql status output. Check it manually.')
            common.errorMsg(e.getMessage())
        }
    } else {
        common.errorMsg("Mysql status response unrecognized or is empty. Response: ${mysqlStatusReport['statusRaw']}")
    }
    if (mysqlStatusReport['clusterMembersNotAvailable']) {
        common.errorMsg("Next nodes are unavailable: ${mysqlStatusReport['clusterMembersNotAvailable'].join(',')}")
    }
    if (mysqlStatusReport['clusterMembersNotAlive']) {
        common.errorMsg("Next nodes are not connected to cluster: ${mysqlStatusReport['clusterMembersNotAlive'].join(',')}")
    }

    if (status == "OK") {
        common.infoMsg("No errors found - MySQL status is ${status}.")
        return mysqlStatusReport
    } else if (status == "unknown") {
        common.warningMsg('MySQL status cannot be detemined')
        mysqlStatusReport['error'] = 1
        return mysqlStatusReport
    } else {
        common.errorMsg("Errors found.")
        mysqlStatusReport['error'] = 2
        return mysqlStatusReport
    }
}

/** Validates and prints result of verifyGaleraStatus function
@param env      Salt Connection object or pepperEnv
@param out      Output of the mysql.status Salt function
@return status  "OK", "ERROR" or "uknown" depending on result of validation
*/

def validateAndPrintGaleraStatusReport(env, out, minion, nodeRoleMaster=false) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def role = 'slave'
    if (nodeRoleMaster) {
        role = 'master'
    }
    sizeOut = salt.getReturnValues(salt.getPillar(env, minion, "galera:${role}:members"))
    expected_cluster_size = sizeOut.size()
    outlist = out['return'][0]
    resultYaml = outlist.get(outlist.keySet()[0]).sort()
    common.prettyPrint(resultYaml)
    parameters = [
        wsrep_cluster_status: [title: 'Cluster status', expectedValues: ['Primary'], description: ''],
        wsrep_cluster_size: [title: 'Current cluster size', expectedValues: [expected_cluster_size], description: ''],
        wsrep_ready: [title: 'Node status', expectedValues: ['ON', true], description: ''],
        wsrep_local_state_comment: [title: 'Node status comment', expectedValues: ['Joining', 'Waiting on SST', 'Joined', 'Synced', 'Donor'], description: ''],
        wsrep_connected: [title: 'Node connectivity', expectedValues: ['ON', true], description: ''],
        wsrep_local_recv_queue_avg: [title: 'Average size of local reveived queue', expectedThreshold: [warn: 0.5, error: 1.0], description: '(Value above 0 means that the node cannot apply write-sets as fast as it receives them, which can lead to replication throttling)'],
        wsrep_local_send_queue_avg: [title: 'Average size of local send queue', expectedThreshold: [warn: 0.5, error: 1.0], description: '(Value above 0 indicate replication throttling or network throughput issues, such as a bottleneck on the network link.)']
        ]
    for (key in parameters.keySet()) {
        value = resultYaml[key]
        if (value instanceof String && value.isBigDecimal()) {
            value = value.toBigDecimal()
        }
        parameters.get(key) << [actualValue: value]
    }
    for (key in parameters.keySet()) {
        param = parameters.get(key)
        if (key == 'wsrep_local_recv_queue_avg' || key == 'wsrep_local_send_queue_avg') {
            if (param.get('actualValue') == null || (param.get('actualValue') > param.get('expectedThreshold').get('error'))) {
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

/** Returns last shutdown node of Galera cluster
@param env      Salt Connection object or pepperEnv
@param nodes    List of nodes to check only (defaults to []). If not provided, it will check all nodes.
                Use this parameter if the cluster splits to several components and you only want to check one fo them.
@return status  ip address or hostname of last shutdown node
*/

def getGaleraLastShutdownNode(env, nodes = []) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    members = []
    lastNode = [ip: '', seqno: -2]
    try {
        if (nodes) {
            nodes = salt.getIPAddressesForNodenames(env, nodes)
            for (node in nodes) {
                members = [host: "${node.get(node.keySet()[0])}"] + members
            }
        } else {
            members = salt.getReturnValues(salt.getPillar(env, "I@galera:master", "galera:master:members"))
        }
    } catch (Exception e) {
        common.errorMsg('Could not retrieve members list')
        common.errorMsg(e.getMessage())
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
                    // in case if /var/lib/mysql/grastate.dat has no any seqno - set it to 0
                    // thus node will be recovered if no other failed found
                    seqno = 0
                }
            } catch (Exception e) {
                common.warningMsg("Could not determine 'seqno' value for node ${member.host} ")
                common.warningMsg(e.getMessage())
                seqno = 0
            }
            highestSeqno = lastNode.get('seqno')
            if (seqno > highestSeqno) {
                lastNode << [ip: "${member.host}", seqno: seqno]
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
 * Wrapper around Mysql systemd service
 * @param env           Salt Connection object or pepperEnv
 * @param targetNode    Node to apply changes
 * @param checkStatus   Whether to check status of Mysql
 * @param checkState    State of service to check
*/
def manageServiceMysql(env, targetNode, action, checkStatus=true, checkState='running') {
    def salt = new com.mirantis.mk.Salt()
    salt.runSaltProcessStep(env, targetNode, "service.${action}", ['mysql'])
    if (checkStatus) {
        try {
            salt.commandStatus(env, targetNode, 'service mysql status', checkState)
        } catch (Exception er) {
            input message: "Database is not running please fix it first and only then click on PROCEED."
        }
    }
}

/**
 * Restores Galera cluster
 * @param env           Salt Connection object or pepperEnv
 * @param galeraStatus  Map, Status of Galera cluster output  from verifyGaleraStatus func
 * @param restoreDb     Run restore DB procedure
 * @return output of salt commands
 */
def restoreGaleraCluster(env, galeraStatus, restoreDb=true) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def nodesToRecover = []
    def total = false // whole cluster
    if (galeraStatus['clusterMembersNotAlive']) {
        nodesToRecover = galeraStatus['clusterMembersNotAlive']
        if (galeraStatus['clusterMembersInClusterAlive'].size() == 0) {
            total = true
        }
    } else {
        nodesToRecover = galeraStatus['clusterMembersInClusterAlive']
        total = true
    }

    def lastNodeTarget = ''
    if (total) {
        manageServiceMysql(env, 'I@galera:slave', 'stop', true, 'inactive')
        manageServiceMysql(env, 'I@galera:master', 'stop', true, 'inactive')
        lastNodeTarget = getGaleraLastShutdownNode(env) // in case if master was already down before
        salt.cmdRun(env, "( I@galera:master or I@galera:slave ) and not ${lastNodeTarget}", "rm -f /var/lib/mysql/ib_logfile*")
        salt.cmdRun(env, "( I@galera:master or I@galera:slave ) and not ${lastNodeTarget}", "rm -f /var/lib/mysql/grastate.dat")
    } else {
        lastNodeTarget = nodesToRecover.join(' or ')
        manageServiceMysql(env, lastNodeTarget, 'stop', true, 'inactive')
    }

    if (restoreDb) {
        def timestamp = common.getDatetime()
        def bakDir = "/root/mysql/mysql.bak.${timestamp}".toString()
        salt.cmdRun(env, lastNodeTarget, "mkdir -p ${bakDir}")
        salt.cmdRun(env, lastNodeTarget, "mv /var/lib/mysql/* ${bakDir} || echo 'Nothing to backup from directory /var/lib/mysql/'")
    }
    if (total) {
        // make sure that gcom parameter is empty
        salt.cmdRun(env, lastNodeTarget, "sed -i '/gcomm/c\\wsrep_cluster_address=\"gcomm://\"' /etc/mysql/my.cnf")
    } else if (!restoreDb) {
        // node rejoin
        salt.cmdRun(env, lastNodeTarget, "rm -f /var/lib/mysql/ib_logfile*")
        salt.cmdRun(env, lastNodeTarget, "rm -f /var/lib/mysql/grastate.dat")
    }

    if (restoreDb) {
        restoreGaleraDb(env, lastNodeTarget)
    }

    manageServiceMysql(env, lastNodeTarget, 'start')

    if (total) {
        manageServiceMysql(env, "( I@galera:master or I@galera:slave ) and not ${lastNodeTarget}", 'start')
        salt.runSaltProcessStep(env, lastNodeTarget, 'state.sls_id', ['galera_config', 'galera'])
    }
}

/**
 * Restores Galera database
 * @param env           Salt Connection object or pepperEnv
 * @param targetNode    Node to be targeted
 */
def restoreGaleraDb(env, targetNode) {
    def salt = new com.mirantis.mk.Salt()
    def backup_dir = salt.getReturnValues(salt.getPillar(env, targetNode, 'xtrabackup:client:backup_dir'))
    if(backup_dir == null || backup_dir.isEmpty()) { backup_dir='/var/backups/mysql/xtrabackup' }
    salt.runSaltProcessStep(env, targetNode, 'file.remove', ["${backup_dir}/dbrestored"])
    salt.enforceState(['saltId': env, 'target': targetNode, 'state': 'xtrabackup.client'])
    salt.enforceState(['saltId': env, 'target': targetNode, 'state': 'xtrabackup.client.restore'])
}

def restoreGaleraDb(env) {
    def common = new com.mirantis.mk.Common()
    common.warningMsg("This method was renamed to 'restoreGaleraCluster'. Please change your pipeline to use this call instead! If you think that you really wanted to call 'restoreGaleraDb' you may be missing 'targetNode' parameter in you call.")
    return restoreGaleraCluster(env)
}

/**
 * Start first node in mysql cluster. Cluster members stay removed in mysql config, additional service restart will be needed once all nodes are up.
 * https://docs.mirantis.com/mcp/q4-18/mcp-operations-guide/tshooting/
 * tshoot-mcp-openstack/tshoot-galera/restore-galera-cluster/
 * restore-galera-manually.html#restore-galera-manually
 *
 * @param env             Salt Connection object or pepperEnv
 * @param target  last stopped Galera node
 * @return                output of salt commands
 */
def startFirstNode(env, target) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()

    // make sure that gcom parameter is empty
    salt.cmdRun(env, target, "sed -i '/wsrep_cluster_address/ s/^#*/#/' /etc/mysql/my.cnf")
    salt.cmdRun(env, target, "sed -i '/wsrep_cluster_address/a wsrep_cluster_address=\"gcomm://\"' /etc/mysql/my.cnf")

    // start mysql service on the last node
    salt.runSaltProcessStep(env, target, 'service.start', ['mysql'])

    // wait until mysql service on the last node is up

    common.retry(30, 10) {
        value = getWsrepParameters(env, target, 'wsrep_evs_state')
        if (value['wsrep_evs_state'] == 'OPERATIONAL') {
            common.infoMsg('WSREP state: OPERATIONAL')
        } else {
            throw new Exception("Mysql service is not running please fix it.")
        }
    }
}