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
 * @param params         Other parameters for docker
 * @return "docker app" - result of docker.build
 */
def buildDockerImage(img, baseImg, dockerFile, timestamp, params=[]) {
    def imageDir = dockerFile.substring(0, dockerFile.lastIndexOf("/"))
    if (baseImg) {
        sh "git checkout -f ${dockerFile}; sed -i -e 's,^FROM.*,FROM ${baseImg},g' ${dockerFile}"
    }

    params << "--no-cache"
    params << "-f ${dockerFile}"
    params << imageDir

    return docker.build(
        "${img}:${timestamp}",
        params.join(' ')
    )
}

/**
 * Build step to build docker image.
 *
 * @param dockerHubImg     Name of image on dockerhub (ie: mirantis/salt-models-testing)
 * @param defaultImg       Image to use if dockerHubImg is not found
 * @return img             Docker image
 */

def getImage(dockerHubImg, defaultImg="ubuntu:latest") {

    def img

    try {
        img = docker.image(dockerHubImg)
        img.pull()
    } catch (Throwable e) {
        img = docker.image(defaultImg)
    }

    return img
}
