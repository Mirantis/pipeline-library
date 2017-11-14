package com.mirantis.mcp

import org.jfrog.hudson.pipeline.types.ArtifactoryServer
import org.jfrog.hudson.pipeline.types.buildInfo.BuildInfo

/**
 * Return string of mandatory build properties for binaries
 * User can also add some custom properties.
 *
 * @param customProperties a Array of Strings that should be added to mandatory props
 *        in format ["prop1=value1", "prop2=value2"]
 * */
def getBinaryBuildProperties(ArrayList customProperties) {
    def namespace = "com.mirantis."
    def properties = [
            "buildName=${env.JOB_NAME}",
            "buildNumber=${env.BUILD_NUMBER}",
            "gerritProject=${env.GERRIT_PROJECT}",
            "gerritChangeNumber=${env.GERRIT_CHANGE_NUMBER}",
            "gerritPatchsetNumber=${env.GERRIT_PATCHSET_NUMBER}",
            "gerritChangeId=${env.GERRIT_CHANGE_ID}",
            "gerritPatchsetRevision=${env.GERRIT_PATCHSET_REVISION}"
    ]

    if (customProperties) {
        properties.addAll(customProperties)
    }

    def common = new com.mirantis.mcp.Common()

    return common.constructString(properties, namespace, ";")
}

/**
 * Get URL to artifact by properties
 * Returns String with URL to found artifact or null if nothing
 *
 * @param artifactoryURL String, an URL to Artifactory
 * @param properties LinkedHashMap, a Hash of properties (key-value) which
 *        which should determine artifact in Artifactory
 */
def uriByProperties(String artifactoryURL, LinkedHashMap properties) {
    def key, value
    def properties_str = ''
    for (int i = 0; i < properties.size(); i++) {
        // avoid serialization errors
        key = properties.entrySet().toArray()[i].key
        value = properties.entrySet().toArray()[i].value
        properties_str += "${key}=${value}&"
    }
    def search_url = "${artifactoryURL}/api/search/prop?${properties_str}"

    def result = sh(script: "bash -c \"curl -X GET \'${search_url}\'\"",
            returnStdout: true).trim()
    def content = new groovy.json.JsonSlurperClassic().parseText(result)
    def uri = content.get("results")
    if (uri) {
        return uri.last().get("uri")
    } else {
        return null
    }
}

/**
 * Set properties for artifact in Artifactory repo
 *
 * @param artifactUrl String, an URL to artifact in Artifactory repo
 * @param properties LinkedHashMap, a Hash of properties (key-value) which
 *        should be assigned for choosen artifact
 * @param recursive Boolean, if artifact_url is a directory, whether to set
 *        properties recursively or not
 */
def setProperties(String artifactUrl, LinkedHashMap properties, Boolean recursive = false) {
    def properties_str = 'properties='
    def key, value
    if (recursive) {
        recursive = 'recursive=1'
    } else {
        recursive = 'recursive=0'
    }
    properties_str += properties.collect({"${it.key}=${it.value}"}).join(';')
    def url = "${artifactUrl}?${properties_str}&${recursive}"
    withCredentials([
            [$class          : 'UsernamePasswordMultiBinding',
             credentialsId   : 'artifactory',
             passwordVariable: 'ARTIFACTORY_PASSWORD',
             usernameVariable: 'ARTIFACTORY_LOGIN']
    ]) {
        sh "bash -c \"curl -X PUT -u ${ARTIFACTORY_LOGIN}:${ARTIFACTORY_PASSWORD} \'${url}\'\""
    }
}

/**
 * Get properties for specified artifact in Artifactory
 * Returns LinkedHashMap of properties
 *
 * @param artifactUrl String, an URL to artifact in Artifactory repo
 */
def getPropertiesForArtifact(String artifactUrl) {
    def url = "${artifactUrl}?properties"
    def result
    withCredentials([
            [$class          : 'UsernamePasswordMultiBinding',
             credentialsId   : 'artifactory',
             passwordVariable: 'ARTIFACTORY_PASSWORD',
             usernameVariable: 'ARTIFACTORY_LOGIN']
    ]) {
        result = sh(script: "bash -c \"curl -X GET -u ${ARTIFACTORY_LOGIN}:${ARTIFACTORY_PASSWORD} \'${url}\'\"",
                returnStdout: true).trim()
    }
    def properties = new groovy.json.JsonSlurperClassic().parseText(result)
    return properties.get("properties")
}

/**
 * Find docker images by tag
 * Returns Array of image' hashes with names as full path in @repo
 *
 * Example:
 *
 *   [ {
 *       "path" : "mirantis/ccp/ci-cd/gerrit-manage/test"
 *     },
 *     {
 *       "path" : "mirantis/ccp/ci-cd/gerrit/test"
 *     }
 *   ]
 *
 * @param artifactoryURL String, an URL to Artifactory
 * @param repo String, a name of repo where should be executed search
 * @param tag String, tag of searched image
 */
def getImagesByTag(String artifactoryURL, String repo, String tag) {
    def url = "${artifactoryURL}/api/search/aql"
    def result
    writeFile file: "query",
            text: """\
                   items.find(
                     {
                       \"repo\": \"${repo}\",
                       \"@docker.manifest\": { \"\$match\" : \"${tag}*\" }
                     }
                   ).
                   include(\"path\")
            """.stripIndent()
    withCredentials([
        [$class: 'UsernamePasswordMultiBinding',
         credentialsId: 'artifactory',
         passwordVariable: 'ARTIFACTORY_PASSWORD',
         usernameVariable: 'ARTIFACTORY_LOGIN']
    ]) {
       result = sh(script: "bash -c \"curl -X POST -u ${ARTIFACTORY_LOGIN}:${ARTIFACTORY_PASSWORD} -d @query  \'${url}\'\"",
                   returnStdout: true).trim()
    }
    def images = new groovy.json.JsonSlurperClassic().parseText(result)
    return images.get("results")
}

/**
 * Upload docker image to Artifactory
 *
 * @param server ArtifactoryServer, the instance of Artifactory server
 * @param registry String, the name of Docker registry
 * @param image String, Docker image name
 * @param version String, Docker image version
 * @param repository String, The name of Artifactory Docker repository
 * @param buildInfo BuildInfo, the instance of a build-info object which can be published,
 *                              if defined, then we publish BuildInfo
 */
def uploadImageToArtifactory (ArtifactoryServer server, String registry, String image,
                              String version, String repository,
                              BuildInfo buildInfo = null,
                              LinkedHashMap properties = null) {
    // TODO Switch to Artifactoy image' pushing mechanism once we will
    // prepare automatical way for enabling artifactory build-proxy
    //def artDocker
    withCredentials([
            [$class: 'UsernamePasswordMultiBinding',
             credentialsId: 'artifactory',
             passwordVariable: 'ARTIFACTORY_PASSWORD',
             usernameVariable: 'ARTIFACTORY_LOGIN']
    ]) {
        sh ("docker login -u ${ARTIFACTORY_LOGIN} -p ${ARTIFACTORY_PASSWORD} ${registry}")
        //artDocker = Artifactory.docker("${env.ARTIFACTORY_LOGIN}", "${env.ARTIFACTORY_PASSWORD}")
    }

    sh ("docker push ${registry}/${image}:${version}")
    //artDocker.push("${registry}/${image}:${version}", "${repository}")
    def image_url = server.getUrl() + "/api/storage/${repository}/${image}/${version}"
    if ( ! properties ) {
        properties = [
            'com.mirantis.buildName':"${env.JOB_NAME}",
            'com.mirantis.buildNumber': "${env.BUILD_NUMBER}",
            'com.mirantis.gerritProject': "${env.GERRIT_PROJECT}",
            'com.mirantis.gerritChangeNumber': "${env.GERRIT_CHANGE_NUMBER}",
            'com.mirantis.gerritPatchsetNumber': "${env.GERRIT_PATCHSET_NUMBER}",
            'com.mirantis.gerritChangeId': "${env.GERRIT_CHANGE_ID}",
            'com.mirantis.gerritPatchsetRevision': "${env.GERRIT_PATCHSET_REVISION}",
            'com.mirantis.targetImg': "${image}",
            'com.mirantis.targetTag': "${version}"
        ]
    }

    setProperties(image_url, properties)

    if ( buildInfo != null ) {
        buildInfo.env.capture = true
        buildInfo.env.filter.addInclude("*")
        buildInfo.env.filter.addExclude("*PASSWORD*")
        buildInfo.env.filter.addExclude("*password*")
        buildInfo.env.collect()
        server.publishBuildInfo(buildInfo)
    }
}

/**
 * Upload binaries to Artifactory
 *
 * @param server ArtifactoryServer, the instance of Artifactory server
 * @param buildInfo BuildInfo, the instance of a build-info object which can be published
 * @param uploadSpec String, a spec which is a JSON file that specifies which files should be
 *        uploaded or downloaded and the target path
 * @param publishInfo Boolean, whether publish a build-info object to Artifactory
 */
def uploadBinariesToArtifactory (ArtifactoryServer server, BuildInfo buildInfo, String uploadSpec,
                                 Boolean publishInfo = false) {
    server.upload(uploadSpec, buildInfo)

    if ( publishInfo ) {
        buildInfo.env.capture = true
        buildInfo.env.filter.addInclude("*")
        buildInfo.env.filter.addExclude("*PASSWORD*")
        buildInfo.env.filter.addExclude("*password*")
        buildInfo.env.collect()
        server.publishBuildInfo(buildInfo)
    }
}

/**
 * Promote Docker image artifact to release repo
 *
 * @param artifactoryURL String, an URL to Artifactory
 * @param artifactoryDevRepo String, the source dev repository name
 * @param artifactoryProdRepo String, the target repository for the move or copy
 * @param dockerRepo String, the docker repository name to promote
 * @param artifactTag String, an image tag name to promote
 * @param targetTag String, target tag to assign the image after promotion
 * @param copy Boolean, an optional value to set whether to copy instead of move
 *        Default: false
 */
def promoteDockerArtifact(String artifactoryURL, String artifactoryDevRepo,
                          String artifactoryProdRepo, String dockerRepo,
                          String artifactTag, String targetTag, Boolean copy = false) {
    def url = "${artifactoryURL}/api/docker/${artifactoryDevRepo}/v2/promote"
    String queryFile = UUID.randomUUID().toString()
    writeFile file: queryFile,
            text: """{
                  \"targetRepo\": \"${artifactoryProdRepo}\",
                  \"dockerRepository\": \"${dockerRepo}\",
                  \"tag\": \"${artifactTag}\",
                  \"targetTag\" : \"${targetTag}\",
                  \"copy\": \"${copy}\"
              }""".stripIndent()
    sh "cat ${queryFile}"
    withCredentials([
            [$class          : 'UsernamePasswordMultiBinding',
             credentialsId   : 'artifactory',
             passwordVariable: 'ARTIFACTORY_PASSWORD',
             usernameVariable: 'ARTIFACTORY_LOGIN']
    ]) {
        sh "bash -c \"curl  -u ${ARTIFACTORY_LOGIN}:${ARTIFACTORY_PASSWORD} -H \"Content-Type:application/json\" -X POST -d @${queryFile} ${url}\""
    }
    sh "rm -v ${queryFile}"
}
