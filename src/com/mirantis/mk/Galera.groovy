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
 * @param slave         Boolean value to enable slave checking (if master in unreachable)
 * @param checkTimeSync Boolean value to enable time sync check
 * @return resultCode   int values used to determine exit status in the calling function
 */
def verifyGaleraStatus(env, slave=false, checkTimeSync=false) {
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
            common.errorMsg(e.getMessage())
            return 128
        }
    } else {
        try {
            galeraSlaves = salt.getMinions(env, "I@galera:slave")
            common.infoMsg("Testing Galera slave minions: ${galeraSlaves}")
        } catch (Exception e) {
            common.errorMsg("Cannot obtain Galera slave minions list.")
            common.errorMsg(e.getMessage())
            return 129
        }
        for (minion in galeraSlaves) {
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
    def checkTargets = salt.getMinions(env, "I@xtrabackup:client or I@xtrabackup:server")
    for (checkTarget in checkTargets) {
        def nodeStatus = salt.minionsReachable(env, 'I@salt:master', checkTarget, null, 10, 5)
        if (nodeStatus != null) {
            def iostatRes = salt.getIostatValues(['saltId': env, 'target': checkTarget, 'parameterName': "%util", 'output': true])
            if (iostatRes == [:]) {
                common.errorMsg("Recevived empty response from iostat call on ${checkTarget}. Maybe 'sysstat' package is not installed?")
                return 140
            }
            for (int i = 0; i < iostatRes.size(); i++) {
                def diskKey = iostatRes.keySet()[i]
                if (!(iostatRes[diskKey].toString().isBigDecimal() && (iostatRes[diskKey].toBigDecimal() < 50 ))) {
                    common.errorMsg("Disk ${diskKey} has to high i/o utilization. Maximum value is 50 and current value is ${iostatRes[diskKey]}.")
                    return 141
                }
            }
        }
    }
    common.infoMsg("Disk i/o utilization was checked and everything seems to be in order.")
    if (checkTimeSync && !salt.checkClusterTimeSync(env, "I@galera:master or I@galera:slave")) {
        common.errorMsg("Time in cluster is desynchronized or it couldn't be detemined. You should fix this issue manually before proceeding.")
        return 131
    }
    try {
        out = salt.runSaltProcessStep(env, "${testNode}", "mysql.status", [], null, false)
    } catch (Exception e) {
        common.errorMsg('Could not determine mysql status.')
        common.errorMsg(e.getMessage())
        return 256
    }
    if (out) {
        try {
            status = validateAndPrintGaleraStatusReport(env, out, testNode)
        } catch (Exception e) {
            common.errorMsg('Could not parse the mysql status output. Check it manually.')
            common.errorMsg(e.getMessage())
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
                    seqno = -2
                }
                highestSeqno = lastNode.get('seqno')
                if (seqno > highestSeqno) {
                    lastNode << [ip: "${member.host}", seqno: seqno]
                }
            } catch (Exception e) {
                common.warningMsg("Could not determine 'seqno' value for node ${member.host} ")
                common.warningMsg(e.getMessage())
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
 * Restores Galera cluster
 * @param env           Salt Connection object or pepperEnv
 * @param runRestoreDb  Boolean to determine if the restoration of DB should be run as well
 * @return output of salt commands
 */
def restoreGaleraCluster(env, runRestoreDb=true) {
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

    if (runRestoreDb) {
        restoreGaleraDb(env, lastNodeTarget)
    }

    salt.enforceState(env, lastNodeTarget, 'galera')

    // wait until mysql service on galera master is up
    try {
        salt.commandStatus(env, lastNodeTarget, 'service mysql status', 'running')
    } catch (Exception er) {
        input message: "Database is not running please fix it first and only then click on PROCEED."
    }

    salt.runSaltProcessStep(env, "I@galera:master and not ${lastNodeTarget}", 'service.start', ['mysql'])
    salt.runSaltProcessStep(env, "I@galera:slave and not ${lastNodeTarget}", 'service.start', ['mysql'])
}

/**
 * Restores Galera database
 * @param env           Salt Connection object or pepperEnv
 * @param targetNode    Node to be targeted
 */
def restoreGaleraDb(env, targetNode) {
    def backup_dir = salt.getReturnValues(salt.getPillar(env, targetNode, 'xtrabackup:client:backup_dir'))
    if(backup_dir == null || backup_dir.isEmpty()) { backup_dir='/var/backups/mysql/xtrabackup' }
    salt.runSaltProcessStep(env, targetNode, 'file.remove', ["${backup_dir}/dbrestored"])
    salt.cmdRun(env, 'I@xtrabackup:client', "su root -c 'salt-call state.sls xtrabackup'")
}

def restoreGaleraDb(env) {
    common.warningMsg("This method was renamed to 'restoreGaleraCluster'. Please change your pipeline to use this call instead! If you think that you really wanted to call 'restoreGaleraDb' you may be missing 'targetNode' parameter in you call.")
    return restoreGaleraCluster(env)
}