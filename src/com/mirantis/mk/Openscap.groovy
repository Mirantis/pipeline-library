package com.mirantis.mk

/**
 * Run salt oscap.eval xccdf
 *
 * @param target            the target where the benchmark will be evaluated
 * @param evaltype          what to evaluate (xccdf or oval)
 * @param benchmark         the benchmark which will be evaluated by openscap
 * @param resultsDir        the directory where artifacts will be moved
 * @param profile           the XCCDF profile name
 * @param xccdfVersion      XCCDF benchmark version (default 1.2)
 * @param tailoringId       The id of your tailoring data (from the corresponding pillar)
 */
def openscapEval(master, target, evaltype, benchmark, resultsDir, profile = 'default', xccdfVersion = '1.2', tailoringId = 'None') {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    salt.runSaltProcessStep(master, target, 'oscap.eval', [evaltype, benchmark, results_dir = resultsDir, profile = profile, xccdf_version = xccdfVersion, tailoring_id= tailoringId])
}

/**
 * Upload results to the security dashboard
 *
 * @param apiUrl        the security dashboard url
 * @param file          the file to upload
 * @param cloud_name    the cloud_name
 * @param nodename      the scanned node name
 */
def uploadScanResultsToDashboard(apiUrl, results, cloud_name, nodename) {
    def common = new com.mirantis.mk.Common()
    def http = new com.mirantis.mk.Http()
    def data = [:]

    // Skip authorization until there is no authorization in the worp

    // Get cloud_id
    data['name'] = cloud_name
    def cloudId = common.parseJSON(http.sendHttpPostRequest(apiUrl+'/environment', data))['id']
    // Get report_id
    data['env_uuid'] = cloudId
    def reportId = common.parseJSON(http.sendHttpPostRequest(apiUrl+'/reports/openscap/', data))['id']

    // Create node
    def nodes = []
    nodes.add[nodename]
    data['nodes'] = nodes
    http.sendHttpPostRequest(apiUrl+'/environment/'+cloudId+'/nodes', data)

    // Upload results
    data['results'] = results
    data['node'] = nodename
    http.sendHttpPostRequest(apiUrl+'/reports/openscap/'+reportId, data)
}
