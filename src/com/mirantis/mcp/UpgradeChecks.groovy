package com.mirantis.mcp

/**
 * Run check with parameters
 *
 * @param salt                com.mirantis.mk.Salt object
 * @param venvPepper          venvPepper
 * @param cluster_name        MCP cluster name
 * @param raise_exc           Raise exception or return status of check
**/

def check_34406(salt, venvPepper, String cluster_name, Boolean raise_exc) {
    def sphinxpasswordPillar = salt.getPillar(venvPepper, 'I@salt:master', '_param:sphinx_proxy_password_generated').get("return")[0].values()[0]
    def waStatus = [prodId: "PROD-34406", isFixed: "", waInfo: ""]
    if (sphinxpasswordPillar == '' || sphinxpasswordPillar == 'null' || sphinxpasswordPillar == null) {
        waStatus.isFixed = "Work-around should be applied manually"
        waStatus.waInfo = "See https://docs.mirantis.com/mcp/q4-18/mcp-release-notes/mu/mu-9/mu-9-addressed/mu-9-dtrain/mu-9-dt-manual.html#i-34406 for more info"
        if (raise_exc) {
            error('Sphinx password is not defined.\n' +
            waStatus.waInfo)
        }
        return waStatus
    }
    waStatus.isFixed = "Work-around for PROD-34406 already applied, nothing todo"
    return waStatus
}

def check_34645(salt, venvPepper, String cluster_name, Boolean raise_exc) {
    def updatecellsPillar = salt.getPillar(venvPepper, 'I@nova:controller', 'nova:controller:update_cells').get("return")[0].values()[0]
    def waStatus = [prodId: "PROD-34645", isFixed: "", waInfo: ""]
    if (updatecellsPillar.toString().toLowerCase() == 'false') {
        waStatus.isFixed = "Work-around should be applied manually"
        waStatus.waInfo = "See https://docs.mirantis.com/mcp/q4-18/mcp-operations-guide/openstack-operations/disable-nova-cell-mapping.html for more info"
        if (raise_exc) {
            error('Update cells disabled.\n' +
            waStatus.waInfo)
        }
        return waStatus
    }
    waStatus.isFixed = "Work-around for PROD-34645 already applied, nothing todo"
    return waStatus
}

def check_35705(salt, venvPepper, String cluster_name, Boolean raise_exc) {
    def galeracheckpasswordPillar = salt.getPillar(venvPepper, 'I@salt:master', '_param:galera_clustercheck_password').get("return")[0].values()[0]
    def waStatus = [prodId: "PROD-35705", isFixed: "", waInfo: ""]
    if (galeracheckpasswordPillar == '' || galeracheckpasswordPillar == 'null' || galeracheckpasswordPillar == null) {
        waStatus.isFixed = "Work-around should be applied manually"
        waStatus.waInfo = "See https://docs.mirantis.com/mcp/q4-18/mcp-release-notes/mu/mu-12/mu-12-addressed/mu-12-dtrain/mu-12-dt-manual.html#improper-operation-of-galera-ha for more info"
        if (raise_exc) {
            error('Galera clustercheck password is not defined.\n' +
            waStatus.waInfo)
        }
        return waStatus
    }
    waStatus.isFixed = "Work-around for PROD-35705 already applied, nothing todo"
    return waStatus
}

def check_35884(salt, venvPepper, String cluster_name, Boolean raise_exc) {
    def waStatus = [prodId: "PROD-35884", isFixed: "", waInfo: ""]
    if (salt.getMinions(venvPepper, 'I@prometheus:alerta or I@prometheus:alertmanager')) {
        def alertaApiKeyGenPillar = salt.getPillar(venvPepper, 'I@salt:master', '_param:alerta_admin_api_key_generated').get("return")[0].values()[0]
        def alertaApiKeyPillar = salt.getPillar(venvPepper, 'I@prometheus:alerta or I@prometheus:alertmanager', '_param:alerta_admin_key').get("return")[0].values()[0]
        if (alertaApiKeyGenPillar == '' || alertaApiKeyGenPillar == 'null' || alertaApiKeyGenPillar == null || alertaApiKeyPillar == '' || alertaApiKeyPillar == 'null' || alertaApiKeyPillar == null) {
            waStatus.isFixed = "Work-around should be applied manually"
            waStatus.waInfo = "See https://docs.mirantis.com/mcp/q4-18/mcp-release-notes/mu/mu-12/mu-12-addressed/mu-12-dtrain/mu-12-dt-manual.html#i-35884 for more info"
            if (raise_exc) {
                error('Alerta admin API key not defined.\n' +
                waStatus.waInfo)
            }
            return waStatus
        }
    }
    waStatus.isFixed = "Work-around for PROD-35884 already applied, nothing todo"
    return waStatus
}

def check_36461(salt, venvPepper, String cluster_name, Boolean raise_exc){
    def common = new com.mirantis.mk.Common()
    def waStatus = [prodId: "PROD-36461", isFixed: "", waInfo: ""]
    if (!salt.testTarget(venvPepper, 'I@ceph:radosgw')) {
        return
    }
    def clusterModelPath = "/srv/salt/reclass/classes/cluster/${cluster_name}"
    def checkFile = "${clusterModelPath}/ceph/rgw.yml"
    def saltTarget = "I@salt:master"
    try {
        salt.cmdRun(venvPepper, saltTarget, "test -f ${checkFile}")
    }
    catch (Exception e) {
        waStatus.isFixed = "Check skipped"
        waStatus.waInfo = "Unable to check ordering of RadosGW imports, file ${checkFile} not found, skipping"
        if (raise_exc) {
            common.warningMsg(waStatus.waInfo)
            return
        }
        return waStatus
    }
    def fileContent = salt.cmdRun(venvPepper, saltTarget, "cat ${checkFile}").get('return')[0].values()[0].replaceAll('Salt command execution success', '').trim()
    def yamlData = readYaml text: fileContent
    def infraClassImport = "cluster.${cluster_name}.infra"
    def cephClassImport = "cluster.${cluster_name}.ceph"
    def cephCommonClassImport = "cluster.${cluster_name}.ceph.common"
    def infraClassFound = false
    def importErrorDetected = false
    def importErrorMessage = """Ceph classes in '${checkFile}' are used in wrong order! Please reorder it:
'${infraClassImport}' should be placed before '${cephClassImport}' and '${cephCommonClassImport}'.
For additional information please see https://docs.mirantis.com/mcp/q4-18/mcp-release-notes/mu/mu-15/mu-15-addressed/mu-15-dtrain/mu-15-dtrain-manual.html"""
    for (yamlClass in yamlData.classes) {
        switch(yamlClass){
          case infraClassImport:
            infraClassFound = true;
            break;
          case cephClassImport:
            if (!infraClassFound) {
              importErrorDetected = true
            };
            break;
          case cephCommonClassImport:
            if (!infraClassFound) {
              importErrorDetected = true
            };
            break;
        }
    }
    if (importErrorDetected) {
        waStatus.isFixed = "Work-around should be applied manually"
        waStatus.waInfo = importErrorMessage
        if (raise_exc) {
            common.errorMsg(importErrorMessage)
            error(importErrorMessage)
        }
        return waStatus
    }
    waStatus.isFixed = "Work-around for PROD-36461 already applied, nothing todo"
    return waStatus
}

def check_36461_2 (salt, venvPepper, String cluster_name, Boolean raise_exc) {
    def saltTarget = salt.getFirstMinion(venvPepper, 'I@ceph:mon')
    def cephVersionNum = salt.cmdRun(venvPepper, saltTarget, "ceph version | awk '{print \$3}'").get('return')[0].values()[0].replaceAll('Salt command execution success', '').trim()
    List cephVersion = cephVersionNum.tokenize('.')

    def majorVersion = cephVersion[0].toInteger()
    def minorVersion = cephVersion[1].toInteger()
    def minorSubversion = cephVersion[2].toInteger()

    def waStatus = [prodId: "PROD-36461,PROD-36942", isFixed: "", waInfo: ""]

    def allowInsecureReclaimIdPillar = salt.getPillar(venvPepper, 'I@ceph:mon', 'ceph:common:config:mon:auth_allow_insecure_global_id_reclaim').get("return")[0].values()[0]
    allowInsecureReclaimIdPillar = allowInsecureReclaimIdPillar.toString().toLowerCase().trim()

    if (majorVersion >= 14 && minorVersion >= 2 && minorSubversion >= 20) {
        if ( allowInsecureReclaimIdPillar == 'false' ){
            waStatus.isFixed = "Installed ceph version is 14.2.20+ and insecure global reclaim_id is disabled. Nothing to do."
            return waStatus
        }
        waStatus.isFixed = "Work-around should be applied manually"
        waStatus.waInfo = "Ceph is vulnerable for CVE-2021-20288. See https://docs.mirantis.com/mcp/q4-18/mcp-release-notes/single/index.html#i-cve-2021-20288 for more info"
        if (raise_exc) {
            error('Option is not set to required value.\n' + waStatus.waInfo)
        }
        return waStatus
    }

    if ( allowInsecureReclaimIdPillar == 'false' ) {
        waStatus.isFixed = "Work-around should be applied manually"
        waStatus.waInfo = "To upgrade ceph from version below 14.2.20 you MUST set ceph:common:config:mon:auth_allow_insecure_global_id_reclaim pillar to \"true\"."
        if (raise_exc) {
            error('Option is not set to required value.\n' + waStatus.waInfo)
        }
        return waStatus
    }
    return waStatus
}

def check_36960 (salt, venvPepper, String cluster_name, Boolean raise_exc) {
    def waStatus = [prodId: "PROD-36960", isFixed: "", waInfo: ""]

    if (!salt.testTarget(venvPepper, 'I@redis:server')) {
        waStatus.isFixed = 'Nothing to do. There are no redis-servers.'
        return waStatus
    }

    def redisVersionPillar = salt.getPillar(venvPepper, 'I@redis:server', 'redis:server:version').get("return")[0].values()[0]

    List redisVersion = redisVersionPillar.toString().tokenize('.')

    def majorVersion = redisVersion[0].toInteger()
    def minorVersion = redisVersion[1].toInteger()

    if (majorVersion >= 5 && minorVersion >= 0) {
        waStatus.isFixed = 'Nothing to do. Redis-server version pillar is set to required version (5.0+).'
        return waStatus
    }
    waStatus.isFixed = "Fix should be applied manually"
    waStatus.waInfo = """To apply latest MU to openstack control plane you MUST set correct version for redis-server package. \n
Please set pillar "redis:server:version" to "5.0" to openstack/telemetry.yml and refresh pillars."""
    if (raise_exc) {
        error('Option is not set to required value.\n' + waStatus.waInfo)
    }
    return waStatus
}
