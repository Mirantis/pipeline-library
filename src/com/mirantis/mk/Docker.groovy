package com.mirantis.mk

/**
 *
 * Docker functions
 *
 */

/**
 * Build step to build docker image.
 *
 * @param img           Image name
 * @param baseImg       Base image to use (can be empty)
 * @param dockerFile    Dockerfile to use
 * @param timestamp     Image tag
 * @return "docker app" - result of docker.build
 */
def buildDockerImage(img, baseImg, dockerFile, timestamp) {
    def imageDir = dockerFile.substring(0, dockerFile.lastIndexOf("/"))
    if (baseImg) {
        sh "git checkout -f ${dockerFile}; sed -i -e 's,^FROM.*,FROM ${baseImg},g' ${dockerFile}"
    }
    return docker.build(
        "${img}:${timestamp}",
        [
            "-f ${dockerFile}",
            imageDir
        ].join(' ')
    )
}
