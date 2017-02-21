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
 */
def runConformanceTests(master, k8s_api, image) {
    def salt = new com.mirantis.mk.Salt()
    salt.runSaltProcessStep(master, 'ctl01*', 'cmd.run', ["docker run --rm --net=host -e API_SERVER=${k8s_api} ${image} >> ${image}.output"])
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
 * @param tempestLink   Docker image link with rally and tempest
 */
def runTempestTests(master, tempestLink) {
    def salt = new com.mirantis.mk.Salt()
    salt.runSaltProcessStep(master, 'ctl01*', 'cmd.run', ["docker run --rm --net=host " +
                                                          "-e TEMPEST_CONF=mcp.conf " +
                                                          "-e SKIP_LIST=mcp_skip.list " +
                                                          "-e SOURCE_FILE=keystonercv3 " +
                                                          "-v /root/:/home/rally ${tempestLink} >> docker-tempest.log"])
}

/**
 * Upload results to worker
 *
 */
def copyTempestResults(master) {
    def salt = new com.mirantis.mk.Salt()
    salt.runSaltProcessStep(master, 'ctl01*', 'cmd.run', ["scp /root/docker-tempest.log cfg01:/home/ubuntu/ && " +
                                                          "find /root -name result.xml -exec scp {} cfg01:/home/ubuntu \\;"])
}


/**
 * Upload results to testrail
 *
 */