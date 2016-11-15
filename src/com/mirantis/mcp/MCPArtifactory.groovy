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
* Get URL to artifact by properties
* Returns String with URL to found artifact or null if nothing
*
* @param artifactoryURL String, an URL to Artifactory
* @param properties String, URI in format prop1=val1&prop2=val2&prop3val3
*        which should determine artifact in Artifactory
*/
def uriByProperties(String artifactoryURL, String properties) {
  def search_url = "${artifactoryURL}/api/search/prop?${properties}"

  def result = sh(script: "bash -c \"curl -X GET \'${search_url}\'\"",
          returnStdout: true).trim()
  def content = new groovy.json.JsonSlurperClassic().parseText(result)
  def uri = content.get("results")
  if ( uri ) {
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
    for (int i = 0; i < properties.size(); i++) {
        // avoid serialization errors
        key = properties.entrySet().toArray()[i].key
        value = properties.entrySet().toArray()[i].value
        properties_str += "${key}=${value}|"
    }
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
 * Upload docker image to Artifactory
 *
 * @param artifactoryURL String, an URL to Artifactory
 * @param registry String, the name of Docker registry
 * @param image String, Docker image name
 * @param version String, Docker image version
 * @param repository String, The name of Artifactory Docker repository
 */
def uploadImageToArtifactory(registry, image, version, repository) {
    def server = Artifactory.server('mcp-ci')
    def artDocker
    withCredentials([
            [$class: 'UsernamePasswordMultiBinding',
             credentialsId: 'artifactory',
             passwordVariable: 'ARTIFACTORY_PASSWORD',
             usernameVariable: 'ARTIFACTORY_LOGIN']
    ]) {
        sh ("docker login -u ${ARTIFACTORY_LOGIN} -p ${ARTIFACTORY_PASSWORD} ${registry}")
        sh ("docker push ${registry}/${image}:${version}")
        //artDocker = Artifactory.docker("${env.ARTIFACTORY_LOGIN}", "${env.ARTIFACTORY_PASSWORD}")
    }

    //artDocker.push("${registry}/${image}:${version}", "${repository}")
    def image_url = "${env.ARTIFACTORY_URL}/api/storage/${repository}/${image}/${version}"
    def properties = ['com.mirantis.build_name':"${env.JOB_NAME}",
                      'com.mirantis.build_id': "${env.BUILD_NUMBER}",
                      'com.mirantis.changeid': "${env.GERRIT_CHANGE_ID}",
                      'com.mirantis.patchset_number': "${env.GERRIT_PATCHSET_NUMBER}",
                      'com.mirantis.target_tag': "${version}"]

    setProperties(image_url, properties)
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
    buildInfo.append(server.upload(uploadSpec))

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
    writeFile file: "query.json",
            text: """{
                  \"targetRepo\": \"${artifactoryProdRepo}\",
                  \"dockerRepository\": \"${dockerRepo}\",
                  \"tag\": \"${artifactTag}\",
                  \"targetTag\" : \"${targetTag}\",
                  \"copy\": \"${copy}\"
              }""".stripIndent()
    sh "cat query.json"
    withCredentials([
            [$class          : 'UsernamePasswordMultiBinding',
             credentialsId   : 'artifactory',
             passwordVariable: 'ARTIFACTORY_PASSWORD',
             usernameVariable: 'ARTIFACTORY_LOGIN']
    ]) {
        sh "bash -c \"curl  -u ${ARTIFACTORY_LOGIN}:${ARTIFACTORY_PASSWORD} -H \"Content-Type:application/json\" -X POST -d @query.json ${url}\""
    }
}
