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
    salt = runSaltProcessStep(master, 'ctl01*', 'cmd.run', ["docker run --rm --net=host -e API_SERVER=${k8s_api} ${image} >> e2e-conformance.log"])
}