package com.mirantis.mk
/**
 * Tox functions
 */

/**
 * Run tox
 * @param args tox run arguments
 *
 */

def runTox(String args){
    image = params['image'] ?: "docker-prod-local.docker.mirantis.net/mirantis/external/tox"
    args = params['args'] ?: ""
    returnStdout = params['returnStdout'].toBoolean() ?: true
    return docker.image(image).inside {
        sh(script: "tox ${args}", returnStdout: returnStdout)
    }
}