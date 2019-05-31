package com.mirantis.mk

/**
 *
 * Tests providing functions
 *
 */

/**
 * Get conformance pod statuses
 *
 * @param target   Any control node of k8s
 */
def getConformanceStatus(master, target) {
    def salt = new com.mirantis.mk.Salt()
    def status = salt.cmdRun(master, target, "kubectl get po conformance -n conformance | awk {'print \$3'} | tail -n +2")['return'][0].values()[0].replaceAll('Salt command execution success','').trim()
    return status
}

/**
 * Replace conformance image not relying on deployed version. Will be useful for testing a new k8s builds from docker-dev
 *
 * @param target      I@kubernetes:master target
 * @param image       Desired image for conformance
 * @param autodetect  Default behaviour - use version discovered on deployment. Non default - use image provided via image param
 */
def passCustomConformanceImage(LinkedHashMap config) {
    def salt = new com.mirantis.mk.Salt()

    // Listing defaults
    def master = config.get('master', 'pepperVenv')
    def target = config.get('target', 'I@kubernetes:master')
    def pod_path = config.get('pod_path', '/srv/kubernetes/conformance.yml')
    def autodetect = config.get('autodetect', true)
    def image = config.get('image', null)
    // End listing defaults

    if (!(autodetect.toBoolean()) && image) {
        print("Replacing conformance image with ${image}")
        salt.cmdRun(master, target, "sed -i 's|image: .*|image: ${image}|' ${pod_path}")
    }
}

/**
 * Run e2e conformance on ContainerD environments
 *
 * @param target   Any control node of k8s
 * @param pd_path  Conformance pod path to create
 * @param timeout  Test timeout
 */
def runConformanceTestsOnContainerD(LinkedHashMap config) {
    def salt = new com.mirantis.mk.Salt()

    // Listing defaults
    def master = config.get('master', 'pepperVenv')
    def target = config.get('target', 'I@kubernetes:master and ctl01*')
    def pod_path = config.get('pod_path', '/srv/kubernetes/conformance.yml')
    def timeout = config.get('timeout', 3600)
    // End listing defaults

    def status = ""
    salt.cmdRun(master, target, "kubectl delete -f ${pod_path}", false)
    salt.cmdRun(master, target, "kubectl create -f ${pod_path}")
    sleep(10)

    counter = timeout/60

    print("Waiting for results")
    for (i = 0; i < counter; i++) {
        current = getConformanceStatus(master, target)
        if (current == "Running" || current == "ContainerCreating") {
            sleep(60)
            print("Wait counter: $i . Cap is $counter")
        } else if (current == "Completed") {
            print("Conformance succeeded. Proceed with artifacts.")
            status = "OK"
            return status
        } else if (current == "Error") {
            status = "ERR"
            print("Tests failed. Proceed with artifacts")
            return status
        } else if (current == "ContainerCannotRun") {
            print("Container can not run. Please check executor logs")
            status = "NOTEXECUTED"
            salt.cmdRun(master, target, "kubectl describe po conformance -n conformance")
            return status
        } else if (current == "ImagePullBackOff" || current == "ErrImagePull") {
            print("Can not pull conformance image. Image is not exists or can not be accessed")
            status = "PULLERR"
            salt.cmdRun(master, target, "kubectl describe po conformance -n conformance")
            return status
        } else {
            print("Unexpected status: ${current}")
            status = "UNKNOWN"
            salt.cmdRun(master, target, "kubectl describe po conformance -n conformance")
            salt.cmdRun(master, target, "kubectl get cs")
            salt.cmdRun(master, target, "kubectl get po --all-namespaces -o wide")
            return status
        }
    }
    status = "TIMEDOUT"
    salt.cmdRun(master, target, "kubectl describe po conformance -n conformance")
    salt.cmdRun(master, target, "kubectl logs conformance -n conformance")
    return status
}


/**
 * Locate node where conformance pod runs
 *
 * @param target  Any control node of k8s
 */
def locateConformancePod(master, target) {
    def salt = new com.mirantis.mk.Salt()
    def node = salt.cmdRun(master, target, "kubectl get po conformance -n conformance -o wide -o=custom-columns=NODE:.spec.nodeName | tail -n +2")['return'][0].values()[0].replaceAll('Salt command execution success','').trim()
    return node
}

/**
 * Get conformance results and logs
 *
 * @param ctl_target            Target maps to all k8s masters
 * @param artifacts_dir         Artifacts_dir Local directory to push artifacts to
 * @param output_file           Output tar file that will be archived and (optional) published
 * @param status                Status of conformance run to react (if NOTEXECUTED - xml will never published)
 * @param junitResults          Whether or not build test graph
 */
def uploadConformanceContainerdResults(LinkedHashMap config) {
    def salt = new com.mirantis.mk.Salt()

    // Listing defaults
    def master = config.get('master', 'pepperVenv')
    def target = config.get('target', 'I@kubernetes:master and ctl01*')
    def status = config.get('status')
    def ctl_target = config.get('ctl_target', 'I@kubernetes:master')
    def k8s_pool_target = config.get('k8s_pool_target', 'I@kubernetes:pool')
    def results_dir = config.get('results_dir', '/tmp/conformance')
    def artifacts_dir = config.get('artifacts_dir', '_artifacts/')
    def output_file = config.get('output_file', 'conformance.tar')
    def junitResults = config.get('junitResults', false)
    // End listing defaults

    def short_node = locateConformancePod(master, target)
    print("Pod located on $short_node")

    minions = salt.getMinionsSorted(master, k8s_pool_target)
    conformance_target = minions.find {it =~ short_node}

    if (status == 'NOTEXECUTED') {
        salt.cmdRun(master, conformance_target, "test -e ${results_dir}/conformance.log || kubectl logs conformance -n conformance > ${results_dir}/conformance.log")
    } else if (status == "PULLERR") {
        print("Conformance image failed to pull. Skipping logs publishing")
        return conformance_target
    } else if (status == "UNKNOWN") {
        print("Can not recognize pod status as acceptable. Skipping logs publishing")
        return conformance_target
    }

    print("Copy XML test results for junit artifacts and logs")
    salt.runSaltProcessStep(master, conformance_target, 'cmd.run', ["tar -cf /tmp/${output_file} -C ${results_dir}  ."])

    writeFile file: "${artifacts_dir}${output_file}", text: salt.getFileContent(master, conformance_target, "/tmp/${output_file}")
    sh "mkdir -p ${artifacts_dir}/conformance_tests"
    sh "tar -xf ${artifacts_dir}${output_file} -C ${artifacts_dir}/conformance_tests"
    sh "cat ${artifacts_dir}/conformance_tests/conformance.log"
    if (junitResults.toBoolean() && (status == 'OK' || status == 'ERR')) {
        archiveArtifacts artifacts: "${artifacts_dir}${output_file}"
        archiveArtifacts artifacts: "${artifacts_dir}conformance_tests/conformance.log"
        junit(keepLongStdio: true, testResults:  "${artifacts_dir}conformance_tests/**.xml")
    }
    return conformance_target
}


/**
 * Clean conformance pod and tmp files
 *
 * @param target       Node where conformance was executed\
 * @param results_dir  Directory to clean up
 */
def cleanUpConformancePod(LinkedHashMap config) {
    def salt = new com.mirantis.mk.Salt()

    // Listing defaults
    def master = config.get('master', 'pepperVenv')
    def target = config.get('target', 'I@kubernetes:master and ctl01*')
    def ctl_target = config.get('ctl_target', 'I@kubernetes:master and ctl01*')
    def pod_path = config.get('pod_path', '/srv/kubernetes/conformance.yml')
    def results_dir = config.get('results_dir', '/tmp/conformance')
    def output_file = config.get('output_file', )
    // End listing defaults

    salt.cmdRun(master, ctl_target, "kubectl delete -f ${pod_path}")
    salt.cmdRun(master, target, "rm -rf ${results_dir}", false)
    salt.cmdRun(master, target, "rm -f ${output_file}", false)
}

/**
 * Throw exception if any
 *
 * @param status  Conformance tests status
 */
def conformanceStatusReact(status) {
    if (status == "ERR" || status == "NOTEXECUTED") {
        throw new RuntimeException("Conformance tests failed")
    } else if (status == "TIMEDOUT") {
        throw new RuntimeException("Conformance tests timed out")
    } else if (status == "PULLERR")  {
        throw new RuntimeException("Image is not exists or can not reach repository")
    } else if (status == "UNKNOWN")  {
        throw new RuntimeException("Pod status unacceptable. Please check pipeline logs for more information")
    }
}

/**
 * Orchestrate conformance tests inside kubernetes cluster
 *
 * @param junitResults     Whether or not build junit graph
 * @param autodetect       Default behaviour - use version discovered on deployment. Non default - use image provided via image param
 * @param image            Can be used only if autodetection disabled. Overriding pod image.
 * @param ctl_target       Target maps to all k8s masters
 * @param pod_path         Path where conformance pod located
 * @param results_dir      Directory with results after conformance run
 * @param artifacts_dir    Local artifacts dir
 * @param output_file      Conformance tar output
 */
def executeConformance(LinkedHashMap config) {
    // Listing defaults
    def master = config.get('master', 'pepperVenv')
    def target = config.get('target', 'I@kubernetes:master and ctl01*')
    def junitResults = config.get('junitResults', false)
    def autodetect = config.get('autodetect', true)
    def image = config.get('image', null)
    def ctl_target = config.get('ctl_target', 'I@kubernetes:master')
    def pod_path = config.get('pod_path', '/srv/kubernetes/conformance.yml')
    def results_dir = config.get('results_dir', '/tmp/conformance')
    def artifacts_dir = config.get('artifacts_dir', '_artifacts/')
    def output_file = config.get('output_file', 'conformance.tar')
    // End listing defaults

    // Check whether or not custom image is defined and apply it
    passCustomConformanceImage(['master': master, 'ctl_target': ctl_target, 'pod_path': pod_path, 'autodetect': autodetect, 'image': image])

    // Start conformance pod and get its status
    status = runConformanceTestsOnContainerD('master': master, 'target': target, 'pod_path': pod_path)

    // Manage results
    cleanup_target = uploadConformanceContainerdResults('master': master, 'target': target, 'status': status, 'ctl_target': ctl_target, 'results_dir': results_dir, 'artifacts_dir': artifacts_dir, 'output_file': output_file, 'junitResults': junitResults)

    // Do cleanup
    cleanUpConformancePod('master': master, 'target': cleanup_target, 'pod_path': pod_path, 'results_dir': results_dir, 'output_file': output_file)

    // Throw exception to Jenkins if any
    conformanceStatusReact(status)
}

/**
 * Run e2e conformance tests
 *
 * @param target        Kubernetes node to run tests from
 * @param k8s_api       Kubernetes api address
 * @param image         Docker image with tests
 * @param timeout       Timeout waiting for e2e conformance tests
 */
def runConformanceTests(master, target, k8s_api, image, timeout=2400) {
    def salt = new com.mirantis.mk.Salt()
    def containerName = 'conformance_tests'
    def outfile = "/tmp/" + image.replaceAll('/', '-') + '.output'
    salt.cmdRun(master, target, "docker rm -f ${containerName}", false)
    salt.cmdRun(master, target, "docker run -d --name ${containerName} --net=host -e API_SERVER=${k8s_api} ${image}")
    sleep(10)

    print("Waiting for tests to run...")
    salt.runSaltProcessStep(master, target, 'cmd.run', ["docker wait ${containerName}"], null, false, timeout)

    print("Writing test results to output file...")
    salt.runSaltProcessStep(master, target, 'cmd.run', ["docker logs -t ${containerName} > ${outfile}"])
    print("Conformance test output saved in " + outfile)
}

/**
 * Upload conformance results to cfg node
 *
 * @param target        Kubernetes node for copy test results
 * @param artifacts_dir Path with test results
 */
def CopyConformanceResults(master, target, artifacts_dir, output_file) {
    def salt = new com.mirantis.mk.Salt()
    def containerName = 'conformance_tests'
    def test_node = target.replace("*", "")

    out = salt.runSaltProcessStep(master, target, 'cmd.run', ["docker cp ${containerName}:/report /tmp"])
    if (! out['return'][0].values()[0].contains('Error')) {
        print("Copy XML test results for junit artifacts...")
        salt.runSaltProcessStep(master, target, 'cmd.run', ["tar -cf /tmp/${output_file} -C /tmp/report  ."])

        writeFile file: "${artifacts_dir}${output_file}", text: salt.getFileContent(master,
                              target, "/tmp/${output_file}")

        sh "mkdir -p ${artifacts_dir}/conformance_tests"
        sh "tar -xf ${artifacts_dir}${output_file} -C ${artifacts_dir}/conformance_tests"

        // collect artifacts
        archiveArtifacts artifacts: "${artifacts_dir}${output_file}"

        junit(keepLongStdio: true, testResults:  "${artifacts_dir}conformance_tests/**.xml")
    }
}

/**
 * Copy test output to cfg node
 *
 * @param image      Docker image with tests
 */
def copyTestsOutput(master, image) {
    def salt = new com.mirantis.mk.Salt()
    salt.runSaltProcessStep(master, 'cfg01*', 'cmd.run', ["scp ctl01:/root/${image}.output /home/ubuntu/"])
}

/**
 * DEPRECATED
 * Execute tempest tests
 *
 * @param dockerImageLink   Docker image link with rally and tempest
 * @param target            Host to run tests
 * @param pattern           If not false, will run only tests matched the pattern
 * @param logDir            Directory to store tempest/rally reports
 * @param sourceFile        Path to the keystonerc file in the container
 * @param set               Predefined set for tempest tests
 * @param concurrency       How many processes to use to run Tempest tests
 * @param tempestConf       A tempest.conf's file name
 * @param skipList          A skip.list's file name
 * @param localKeystone     Path to the keystonerc file in the local host
 * @param localLogDir       Path to local destination folder for logs
 */
def runTempestTests(master, dockerImageLink, target, pattern = "", logDir = "/home/rally/rally_reports/",
                    sourceFile="/home/rally/keystonercv3", set="full", concurrency="0", tempestConf="mcp.conf",
                    skipList="mcp_skip.list", localKeystone="/root/keystonercv3" , localLogDir="/root/rally_reports",
                    doCleanupResources = "false") {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    common.errorMsg('You are using deprecated method! This method will be removed')
    error('You are using deprecated method! This method will be removed')
    salt.runSaltProcessStep(master, target, 'file.mkdir', ["${localLogDir}"])
    def custom = ''
    if (pattern) {
        custom = "--pattern " + pattern
    }
    salt.cmdRun(master, "${target}", "docker run --rm --net=host " +
                                    "-e SOURCE_FILE=${sourceFile} " +
                                    "-e LOG_DIR=${logDir} " +
                                    "-e SET=${set} " +
                                    "-e CUSTOM='${custom}' " +
                                    "-e CONCURRENCY=${concurrency} " +
                                    "-e TEMPEST_CONF=${tempestConf} " +
                                    "-e SKIP_LIST=${skipList} " +
                                    "-e DO_CLEANUP_RESOURCES=${doCleanupResources} " +
                                    "-v ${localKeystone}:${sourceFile} " +
                                    "-v ${localLogDir}:/home/rally/rally_reports " +
                                    "-v /etc/ssl/certs/:/etc/ssl/certs/ " +
                                    "${dockerImageLink} >> docker-tempest.log")
}


/**
 * DEPRECATED
 * Execute Rally scenarios
 *
 * @param dockerImageLink      Docker image link with rally and tempest
 * @param target               Host to run scenarios
 * @param scenario             Specify the scenario as a string
 * @param containerName        Docker container name
 * @param doCleanupResources   Do run clean-up script after tests? Cleans up OpenStack test resources
 */
def runRallyScenarios(master, dockerImageLink, target, scenario, logDir = "/home/rally/rally_reports/",
                      doCleanupResources = "false", containerName = "rally_ci") {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    common.errorMsg('You are using deprecated method! This method will be removed')
    error('You are using deprecated method! This method will be removed')
    salt.runSaltProcessStep(master, target, 'file.mkdir', ["/root/rally_reports"])
    salt.cmdRun(master, target, "docker run --net=host -dit " +
                                "--name ${containerName} " +
                                "-e SOURCE_FILE=keystonercv3 " +
                                "-e SCENARIO=${scenario} " +
                                "-e DO_CLEANUP_RESOURCES=${doCleanupResources} " +
                                "-e LOG_DIR=${logDir} " +
                                "--entrypoint /bin/bash -v /root/:/home/rally ${dockerImageLink}")
    salt.cmdRun(master, target, "docker exec ${containerName} " +
                                "bash -c /usr/bin/run-rally | tee -a docker-rally.log")
}


/**
 * DEPRECATED
 * Upload results to cfg01 node
 *
 */
def copyTempestResults(master, target) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    common.errorMsg('You are using deprecated method! Use validate.addFiles instead. This method will be removed')
    error('You are using deprecated method! Use validate.addFiles instead. This method will be removed')
    if (! target.contains('cfg')) {
        salt.cmdRun(master, target, "mkdir -p /root/rally_reports/ && rsync -av /root/rally_reports/ cfg01:/root/rally_reports/")
    }
}


/** Store tests results on host
 *
 * @param image      Docker image name
 */
def catTestsOutput(master, image) {
    def salt = new com.mirantis.mk.Salt()
    salt.cmdRun(master, 'cfg01*', "cat /home/ubuntu/${image}.output")
}


/** Install docker if needed
 *
 * @param target              Target node to install docker pkg
 */
def install_docker(master, target) {
    def salt = new com.mirantis.mk.Salt()
    def dockerPackagesPillar = salt.getPillar(master, target, 'docker:host:pkgs')
    def dockerPackages = salt.getReturnValues(dockerPackagesPillar) ?: ['docker.io']
    salt.runSaltProcessStep(master, target, 'pkg.install', [dockerPackages.join(',')])
}


/** Upload Tempest test results to Testrail
 *
 * DEPRECATED
 * @param report              Source report to upload
 * @param image               Testrail reporter image
 * @param testGroup           Testrail test group
 * @param credentialsId       Testrail credentials id
 * @param plan                Testrail test plan
 * @param milestone           Testrail test milestone
 * @param suite               Testrail test suite
 * @param type                Use local shell or remote salt connection
 * @param master              Salt connection.
 * @param target              Target node to install docker pkg
 */

def uploadResultsTestrail(report, image, testGroup, credentialsId, plan, milestone, suite, master = null, target = 'cfg01*') {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    common.errorMsg('We may deprecated this method! Check tcp_qa Common.uploadResultsTestRail instead.')
    creds = common.getPasswordCredentials(credentialsId)
    command =  "docker run --rm --net=host " +
                           "-v ${report}:/srv/report.xml " +
                           "-e TESTRAIL_USER=${creds.username} " +
                           "-e PASS=${creds.password.toString()} " +
                           "-e TESTRAIL_PLAN_NAME=${plan} " +
                           "-e TESTRAIL_MILESTONE=${milestone} " +
                           "-e TESTRAIL_SUITE=${suite} " +
                           "-e TEST_GROUP=${testGroup} " +
                           "${image}"
    if (master == null) {
      sh(command)
    } else {
      salt.cmdRun(master, target, command)
    }
}

/** Archive Rally results in Artifacts
 *
 * DEPRECATED
 * @param master              Salt connection.
 * @param target              Target node to install docker pkg
 * @param reports_dir         Source directory to archive
 */

def archiveRallyArtifacts(master, target, reports_dir='/root/rally_reports') {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    common.errorMsg('You are using deprecated method! This method will be removed')
    error('You are using deprecated method! This method will be removed')
    def artifacts_dir = '_artifacts/'
    def output_file = 'rally_reports.tar'

    salt.cmdRun(master, target, "tar -cf /root/${output_file} -C ${reports_dir} .")
    sh "mkdir -p ${artifacts_dir}"

    encoded = salt.cmdRun(master, target, "cat /root/${output_file}", true, null, false)['return'][0].values()[0].replaceAll('Salt command execution success','')

    writeFile file: "${artifacts_dir}${output_file}", text: encoded

    // collect artifacts
    archiveArtifacts artifacts: "${artifacts_dir}${output_file}"
}
/**
 * Helper function for collecting junit tests results
 * @param testResultAction - test result from build - use: currentBuild.rawBuild.getAction(AbstractTestResultAction.class)
 * @return resultMap with structure ["total": total, "passed": passed, "skipped": skipped, "failed": failed]
 */
@NonCPS
def collectJUnitResults(testResultAction) {
    if (testResultAction != null) {
       def total = testResultAction.totalCount
       def failed = testResultAction.failCount
       def skipped = testResultAction.skipCount
       def passed = total - failed - skipped
       return ["total": total, "passed": passed, "skipped": skipped, "failed": failed]
    }else{
        def common = new com.mirantis.mk.Common()
        common.errorMsg("Cannot collect jUnit tests results, given result is null")
    }
    return [:]
}


/** Cleanup: Remove reports directory
 *
 * DEPRECATED
 * @param target                   Target node to remove repo
 * @param reports_dir_name         Reports directory name to be removed (that is in /root/ on target node)
 * @param archive_artifacts_name   Archive of the artifacts
 */
def removeReports(master, target, reports_dir_name = 'rally_reports', archive_artifacts_name = 'rally_reports.tar') {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    common.errorMsg('You are using deprecated method! This method will be removed')
    error('You are using deprecated method! This method will be removed')
    salt.runSaltProcessStep(master, target, 'file.find', ["/root/${reports_dir_name}", '\\*', 'delete'])
    salt.runSaltProcessStep(master, target, 'file.remove', ["/root/${archive_artifacts_name}"])
}


/** Cleanup: Remove Docker container
 *
 * DEPREACTED
 * @param target              Target node to remove Docker container
 * @param image_link          The link of the Docker image that was used for the container
 */
def removeDockerContainer(master, target, containerName) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    common.errorMsg('You are using deprecated method! Use validate.runCleanup instead. This method will be removed')
    error('You are using deprecated method! Use validate.runCleanup instead. This method will be removed')
    salt.cmdRun(master, target, "docker rm -f ${containerName}")
}
