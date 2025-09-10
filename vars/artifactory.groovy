/**
 * This method creates config Map object and calls upload(Map) method
 *
 * @param  server  a server ID - file name at resources/../servers
 * @param  spec    a JSON representation of upload spec
 * @return         a List object that contains upload results
 */
List upload(String server, String spec) {
    return upload(server: server, spec: spec)
}


/**
 * This method converts JSON representation of spec into Map object and calls
 * upload(String, Map)
 *
 * @param  config  a Map object with params `[ server: String, spec: String or Map ]`
 * @return         a List object that contains upload results
 */
List upload(Map config) {
    if (config.spec?.getClass() in [java.util.LinkedHashMap, net.sf.json.JSONObject]) {
        return upload(config.server, config.spec)
    }
    return upload(config.server, parseJSON(config.spec))
}


/**
 * This method uploads files into artifactory instance
 *
 * Input spec example:
 *     [ files: [[
 *          pattern: "**",
 *          target: "my-repository/path/to/upload",
 *          props: "myPropKey1=myPropValue1;myPropKey2=myPropValue2", // optional
 *     ],]
 *
 * Result example:
 *     [[
 *        localPath: "local/path/to/file.name",
 *        remotePath: "/path/to/upload/local/path/to/file.name",
 *        repo: "my-repository",
 *        size: 12345,
 *        uri: ... ,
 *        checksums: [
 *            md5: ... ,
 *            sha1: ... ,
 *            sha256: ... "
 *        ],
 *     ],]
 *
 * @param  server  a server ID - server name at resources/../servers
 * @param  spec    a Map object with upload spec
 * @return         a List object that contains upload results
 */
List upload(String server, Map spec) {
    List result = []

    Map artConfig = parseJSON(loadResource("artifactory/servers/${server}.json"))
    String uploadScript = loadResource("artifactory/scripts/upload.sh")

    List artCredentials = [usernamePassword(
        credentialsId: artConfig.credentialsId,
        usernameVariable: 'ARTIFACTORY_USERNAME',
        passwordVariable: 'ARTIFACTORY_PASSWORD')]

    List files = createFileListBySpec(spec)

    retry(artConfig.get('connectionRetry', 1)) {
        withCredentials(artCredentials) {
            files.each{ file ->
                String scriptRawResult
                List envList = [
                    "ARTIFACTORY_URL=${artConfig.artifactoryUrl}",
                    "ARTIFACTORY_TARGET=${file.target}",
                    "ARTIFACTORY_PROPS=${file.props}",
                    "FILE_TO_UPLOAD=${file.name}"
                ]
                withEnv(envList) {
                    scriptRawResult = sh \
                        script: uploadScript,
                        returnStdout: true
                }

                Map scriptResult = parseScriptResult(scriptRawResult)
                Map uploadResult = parseJSON(scriptResult.stdout)
                ['created', 'createdBy', 'downloadUri', 'mimeType', 'originalChecksums'].each {
                    uploadResult.remove(it)
                }
                uploadResult.localPath = file.name
                uploadResult.put('remotePath', uploadResult.remove('path'));
                result << uploadResult
            }
        }
    }
    return result
}


/**
 * This method looks up for local files to upload according to the spec
 *
 * @param  spec   a Map object with upload spec
 * @return        a List object that contains found local files to upload
 */
List createFileListBySpec(Map spec) {
    List result = []

    Map jenkinsProps = [
        "build.name": env.JOB_NAME,
        "build.number": env.BUILD_NUMBER,
        "build.timestamp": currentBuild.startTimeInMillis
    ]

    spec.files?.each{ specItem ->
        Map targetProps = specItem.props?.split(';')?.findAll{ it.contains('=') }?.collectEntries{
                List parts = it.split('=', 2)
                return [(parts[0]): parts[1]]
            } ?: [:]

        String props = (jenkinsProps + targetProps)
            .collect{ "${it.key}=${it.value}" }
            .join(';')

        if (!(specItem.pattern && specItem.target)) {
            error "ArtifactoryUploader: Malformed upload spec:\n${spec}"
        }

        List targetFiles = []
        try {
            targetFiles = findFiles(glob: specItem.pattern.replaceFirst("^${env.WORKSPACE}/?", ""))
                .findAll{ !it.directory }
                .collect{ it.path }
        } catch (Exception e) {
            error "ArtifactoryUploader: Unable to find files by pattern: ${specItem.pattern}"
        }

        targetFiles.each{ file ->
            result << [ name: file, target: specItem.target, props: props ]
        }
    }
    return result
}


/**
 * This method parses uploading results
 *
 * @param  scriptRawResult   a JSON representation of uploading results
 * @return                   a Map object that contains uploading results
 */
Map parseScriptResult(String scriptRawResult) {
    Map result = parseJSON scriptRawResult

    if (result.exit_code == 0 &&
        result.response_code &&
        result.response_code in 200..299 &&
        result.stdout) { return result }

    String errorMessage = "ArtifactoryUploader: Upload failed"
    if (result.stdout) {
        errorMessage += "\nStdout: ${result.stdout}"
    }
    if (result.stderr) {
        errorMessage += "\nStderr: ${result.stderr}"
    }
    if (result.response_code) {
        errorMessage += "\nResponse code: ${result.response_code}"
    }
    error errorMessage
}


/**
 * This method loads resourcse file from the library
 *
 * @param  path   path to the resource file
 * @return        content of the resource file
 */
String loadResource(String path) {
    try {
        return libraryResource(path)
    } catch (Exception e) {
        error "ArtifactoryUploader: Unable to load resource: ${path}"
    }
}


/**
 * This method converts a JSON representation into an object
 *
 * @param  text   a JSON content
 * @return        a Map or List object
 */
Map parseJSON(String text) {
    def json = new groovy.json.JsonSlurper()
    Map result
    try {
        // result = readJSON text: text
        result = json.parseText(text)
    } catch (Exception e) {
        json = null
        error "ArtifactoryUploader: Unable to parse JSON:\n${text}"
    }
    json = null
    return result
}


/**
 * This method returns Artifactory URL for the given server ID
 *
 * @param  serverId   a server ID - server name at resources/../servers
 * @return            Artifactory URL (String)
 */
String getArtifactoryUrlByID(String serverId) {
    Map artConfig = parseJSON(loadResource("artifactory/servers/${serverId}.json"))
    return artConfig.artifactoryUrl
}


/**
 * This method generates an upload spec JSON string
 *
 * @param  artifactoryPath   target path in Artifactory repository
 * @param  artifactsPattern  file pattern for artifacts to upload
 * @return                   JSON string representation of upload spec
 */
String getUploadSpec(String artifactoryPath, String artifactsPattern) {
    return """{
        "files": [
            {
                "pattern": "${artifactsPattern}",
                "target": "${artifactoryPath}"
            }
        ]
    }"""
}
