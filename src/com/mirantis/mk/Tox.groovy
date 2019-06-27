package com.mirantis.mk
/**
 * Tox functions
 */

/**
 * Run tox
 *
 * @param args string with tox arguments
 * @param returnStdout return stdout from tox
 */

def runTox(String args, boolean returnStdout = true){
    return docker.image('docker-prod-local.docker.mirantis.net/mirantis/external/tox').inside {
        sh(script: "tox ${args}", returnStdout: returnStdout)
    }
}