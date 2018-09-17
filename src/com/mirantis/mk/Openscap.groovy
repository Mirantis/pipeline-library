package com.mirantis.mk

/**
 * Run salt oscap.eval xccdf
 *
 * @param target            the target where the benchmark will be evaluated
 * @param evaltype          what to evaluate (xccdf or oval)
 * @param benchmark         the benchmark which will be evaluated by openscap
 * @param results_dir       the directory where artifacts will be moved
 * @param profile           the XCCDF profile name
 * @param xccdf_version     XCCDF benchmark version (default 1.2)
 * @param tailoring_id      The id of your tailoring data (from the corresponding pillar)
 */
def openscapEval(master, target, evaltype, benchmark, results_dir, profile='default', xccdf_version='1.2', tailoring_id='None') {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    try {
        salt.runSaltProcessStep(master, target, 'oscap.eval', [evaltype, benchmark, results_dir="$results_dir", profile="$profile", xccdf_version="$xccdf_version", tailoring_id="$tailoring_id"])
    } catch (Throwable e) {
        common.errorMsg("Opescap evaluation have failed")
    }
}

/**
 * Upload results to the security dashboard
 *
 * @param url           the security dashboard url
 * @param file          the file to upload
 * @param cloud_name    the cloud_name
 * @param nodename      the scanned node name
 */
def uploadScanResultsToDashboard(url, file, cloud_name, nodename) {
    def common = new com.mirantis.mk.Common()
    try {
        withCredentials([
            [$class             : 'UsernamePasswordMultiBinding',
             credentialsId      : 'dashboard',
             passwordVariable   : 'DASHBOARD_PASSWORD',
             usernameVariable   : 'DASHBOARD_LOGIN']
        ]) {
            sh "bash -c \"curl -X PUT -d@${file} -u ${DASHBOARD_LOGIN}:${DASHBOARD_PASSWORD} \'${url}\'\""
        }
    } catch (Throwable e) {
        common.errorMsg("Can't upload scanning results to the security dashboard")
    }
}

/**
 * Copy evaluation results.xml to the master node
 *
 * @param master        the salt master
 * @param target        the salt target
 * @param source        the target source directory
 * @param destination   the destination directory on the master
 */
def copyResultsXml(master, target, source, destination) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    try {
        writeFile file: destination, text: salt.GetFileContent(master, target, source)
    } catch (Throwable e) {
        common.errorMsg("Can't upload scanning results to the security dashboard")
    }
}

/**
 * Archive evaluating results
 *
 * @param master        the salt master
 * @param target        the salt target
 * @param source        the directory with scanning results
 * @param destitation   the archive output file
 */
def archiveScanResults(master, target, source, destination) {
    def common = new com.mirantis.mk.Common()
    def salt = new com.mirantis.mk.Salt()
    def tempArchive = '/tmp/openscap-temp.tar'
    try {
        salt.runSaltProcessStep(master, target, 'file.remove', tempArchive)
        salt.runSaltProcessStep(master, target, 'archive.tar', ['cf', tempArchive, source])

        writeFile file: destination, text: salt.GetFileContent(master, target, tempArchive)

        salt.runSaltProcessStep(master, target, 'file.remove', source)
        salt.runSaltProcessStep(master, target, 'file.remove', tempArchive)
    } catch (Throwable e) {
        common.errorMsg("Can't archive results on ${target}")
    }
}

/**
 * Archive openscap scan results in Artifacts
 *
 * @param source    the artifacts dir path
 */
def archiveOpenscapArtifacts(source) {
    def common = new com.mirantis.mk.Common()
    try {
        archiveArtifacts artifacts: "${source}"
    } catch (Throwable e) {
        common.errorMsg("Can't archive artifacts")
    }
}

