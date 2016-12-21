package com.mirantis.mk

/**
 *
 * Docker functions
 *
 */

/**
 * Build step to build docker image. For use with eg. parallel
 *
 * @param img           Image name
 * @param baseImg       Base image to use (can be empty)
 * @param dockerFile    Dockerfile to use
 * @param timestamp     Image tag
 */
def buildDockerImageStep(img, baseImg, dockerFile, timestamp) {
    File df = new File(dockerfile);
    return {
        if (baseImg) {
            sh "git checkout -f ${dockerfile}; sed -i -e 's,^FROM.*,FROM ${baseImg},g' ${dockerFile}"
        }
        docker.build(
            "${img}:${timestamp}",
            [
                "-f ${dockerFile}",
                df.getParent()
            ].join(' ')
        )
    }
}
