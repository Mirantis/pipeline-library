package com.mirantis.mk

/**
 *
 * Tests providing functions
 *
 */

/**
 * Run e2e conformance tests
 *
 * @param k8s_api    Kubernetes api address
 * @param image      Docker image with tests
 * @param timeout    Timeout waiting for e2e conformance tests
 */
def runConformanceTests(master, k8s_api, image, timeout=2400) {
    def salt = new com.mirantis.mk.Salt()
    def containerName = 'conformance_tests'
    def outfile = "/tmp/" + image.replaceAll('/', '-') + '.output'
    salt.cmdRun(master, 'ctl01*', "docker rm -f ${containerName}", false)
    salt.cmdRun(master, 'ctl01*', "docker run -d --name ${containerName} --net=host -e API_SERVER=${k8s_api} ${image}")
    sleep(10)

    print("Waiting for tests to run...")
    salt.runSaltProcessStep(master, 'ctl01*', 'cmd.run', ["docker wait ${containerName}"], null, false, timeout)

    print("Writing test results to output file...")
    salt.runSaltProcessStep(master, 'ctl01*', 'cmd.run', ["docker logs -t ${containerName} &> ${outfile}"])

    print("Conformance test output saved in " + outfile)
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
 * Execute tempest tests
 *
 * @param dockerImageLink   Docker image link with rally and tempest
 * @param target            Host to run tests
 * @param pattern            If not false, will run only tests matched the pattern
 */
def runTempestTests(master, dockerImageLink, target, pattern = "false") {
    def salt = new com.mirantis.mk.Salt()
    if (pattern == "false") {
        salt.cmdRun(master, "${target}", "docker run --rm --net=host " +
                                         "-e TEMPEST_CONF=mcp.conf " +
                                         "-e SKIP_LIST=mcp_skip.list " +
                                         "-e SOURCE_FILE=keystonercv3 " +
                                         "-v /root/:/home/rally ${dockerImageLink} >> docker-tempest.log")
    }
    else {
        salt.cmdRun(master, "${target}", "docker run --rm --net=host " +
                                         "-e TEMPEST_CONF=mcp.conf " +
                                         "-e SKIP_LIST=mcp_skip.list " +
                                         "-e SOURCE_FILE=keystonercv3 " +
                                         "-e CUSTOM='--pattern ${pattern}' " +
                                         "-v /root/:/home/rally ${dockerImageLink} >> docker-tempest.log")
    }
}

/**
 * Upload results to cfg01 node
 *
 */
def copyTempestResults(master, target) {
    def salt = new com.mirantis.mk.Salt()
    if (! target.contains('cfg')) {
        salt.runSaltProcessStep(master, "${target}", 'cmd.run', ["mkdir /root/rally_reports/ && " +
                                                                 "rsync -av /root/rally_reports/ cfg01:/root/rally_reports/"])
    }
}


/** Store tests results on host
 *
 * @param image      Docker image name
 */
def catTestsOutput(master, image) {
    def salt = new com.mirantis.mk.Salt()
    salt.runSaltProcessStep(master, 'cfg01*', 'cmd.run', ["cat /home/ubuntu/${image}.output"])
}


/** Install docker if needed
 *
 * @param target              Target node to install docker pkg
 */
def install_docker(master, target) {
    def salt = new com.mirantis.mk.Salt()
    salt.runSaltProcessStep(master, "${target}", 'pkg.install', ["docker.io"])
}
