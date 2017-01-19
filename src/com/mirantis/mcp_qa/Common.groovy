package com.mirantis.mcp_qa

/**
 * Get latest artifacts
 * @param imageRepoName is the repo name where image is located
 * @param imageTagName is the name of the image tag to be used
 */

def getLatestArtifacts(imageRepoName, imageTagName) {
    def imageRepo = env.getAt(imageRepoName)
    def imageTag = env.getAt(imageTagName)
    if ( imageTag != null && (! imageTag || imageTag.equals('latest')) ) {
        if ( imageRepo ) {
            def registry = imageRepo.replaceAll(/\/.*/, '')
            def image = imageRepo.minus(registry + '/')
            def hyperkubeImageTag = latestImageTagLookup(registry, image)
            return "${imageTagName}=${hyperkubeImageTag}"
        } else {
            echo "${imageRepoName} variable isn't set, can't inspect 'latest' image!"
            return null
        }
    }
}

def jsonParse(def json) {
    new groovy.json.JsonSlurperClassic().parseText(json)
}

/**
 * Get digest metadata
 * @param tag is the image tag to be used
 * @param registry is the url of registry
 * @param image is the image which info is looked for
 */

def get_digest(def tag, def registry, def image) {
    def digest_link = sprintf('https://%1$s/v2/%2$s/manifests/%3$s', [registry, image, tag])
    def digest_url = new URL(digest_link)
    def connection = digest_url.openConnection()
    connection.setRequestProperty('Accept', 'application/vnd.docker.distribution.manifest.v2+json')
    def digest = connection.getHeaderField("Docker-Content-Digest")
    return digest
}

/**
 * Get latest tag metadata
 * @param registry is the url of registry
 * @param image is the image which tags are looked for
 */

def latestImageTagLookup(registry, image) {
    def tags_link = sprintf('https://%1$s/v2/%2$s/tags/list', [registry, image])
    def tags_url = new URL(tags_link)
    def tags = jsonParse(tags_url.getText())['tags']
    def latest_digest = get_digest('latest', registry, image)
    def same_digest_tags = []

    for (tag in tags) {
        if (tag == 'latest') {
            continue
        }
        if (get_digest(tag, registry, image) == latest_digest) {
            same_digest_tags<< tag
        }
    }

    return same_digest_tags[0] ?: 'latest'
}


/**
 * Fetch custom refs
 * @param gerritUrl is url of gerrit
 * @param project is the name of project in gerrit
 * @param targetDir is dir where to fetch changes
 * @param refs is refs that need to be fetched
 */

def getCustomRefs(gerritUrl, project, targetDir, refs) {
    def remote = "${gerritUrl}/${project}"
    dir(targetDir) {
        for(int i=0; i<refs.size(); i++) {
            sh "git fetch ${remote} ${refs[i]} && git checkout FETCH_HEAD"
        }
    }
}

/**
 * Set downstream k8s artifacts
 * @param jobSetParameters are current job parameters that can be extended with kubernetes tag
 */

def set_downstream_k8s_artifacts(jobSetParameters) {
    def k8sTag = getLatestArtifacts('HYPERKUBE_IMAGE_REPO', 'HYPERKUBE_IMAGE_TAG')
    if (k8sTag) {
        jobSetParameters.add(k8sTag)
    }
    return jobSetParameters
}
