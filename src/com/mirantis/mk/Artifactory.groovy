package com.mirantis.mk

/**
 *
 * Artifactory functions
 *
 */

/**
 * Make generic call using Artifactory REST API and return parsed JSON
 *
 * @param art   Artifactory connection object
 * @param uri   URI which will be appended to artifactory server base URL
 * @param method    HTTP method to use (default GET)
 * @param data      JSON data to POST or PUT
 * @param headers   Map of additional request headers
 */
def restCall(art, uri, method = 'GET', data = null, headers = [:]) {
    def connection = new URL("${art.url}/api${uri}").openConnection()
    if (method != 'GET') {
        connection.setRequestMethod(method)
    }

    connection.setRequestProperty('User-Agent', 'jenkins-groovy')
    connection.setRequestProperty('Accept', 'application/json')
    connection.setRequestProperty('Authorization', "Basic " +
        "${art.creds.username}:${art.creds.password}".bytes.encodeBase64().toString())

    for (header in headers) {
        connection.setRequestProperty(header.key, header.value)
    }

    if (data) {
        connection.setDoOutput(true)
        if (data instanceof String) {
            connection.setRequestProperty('Content-Type', 'application/json')
            dataStr = data
        } else if (data instanceof java.io.File) {
            connection.setRequestProperty('Content-Type', 'application/octet-stream')
            dataStr = data.bytes
        } else if (data instanceof byte[]) {
            connection.setRequestProperty('Content-Type', 'application/octet-stream')
            dataStr = data
        } else {
            connection.setRequestProperty('Content-Type', 'application/json')
            dataStr = new groovy.json.JsonBuilder(data).toString()
        }
        def out = new OutputStreamWriter(connection.outputStream)
        out.write(dataStr)
        out.close()
    }

    if ( connection.responseCode >= 200 && connection.responseCode < 300 ) {
        res = connection.inputStream.text
        try {
            return new groovy.json.JsonSlurperClassic().parseText(res)
        } catch (Exception e) {
            return res
        }
    } else {
        throw new Exception(connection.responseCode + ": " + connection.inputStream.text)
    }
}

/**
 * Make GET request using Artifactory REST API and return parsed JSON
 *
 * @param art   Artifactory connection object
 * @param uri   URI which will be appended to artifactory server base URL
 */
def restGet(art, uri) {
    return restCall(art, uri)
}

/**
 * Make PUT request using Artifactory REST API and return parsed JSON
 *
 * @param art   Artifactory connection object
 * @param uri   URI which will be appended to artifactory server base URL
 * @param data  JSON Data to PUT
 */
def restPut(art, uri, data = null) {
    return restCall(art, uri, 'PUT', data, ['Accept': '*/*'])
}

/**
 * Make DELETE request using Artifactory REST API
 *
 * @param art   Artifactory connection object
 * @param uri   URI which will be appended to artifactory server base URL
 */
def restDelete(art, uri) {
    return restCall(art, uri, 'DELETE', null, ['Accept': '*/*'])
}

/**
 * Make POST request using Artifactory REST API and return parsed JSON
 *
 * @param art   Artifactory connection object
 * @param uri   URI which will be appended to artifactory server base URL
 * @param data  JSON Data to PUT
 */
def restPost(art, uri, data = null) {
    return restCall(art, uri, 'POST', data, ['Accept': '*/*'])
}

/**
 * Query artifacts by properties
 *
 * @param art   Artifactory connection object
 * @param properties    String or list of properties in key=value format
 * @param repo  Optional repository to search in
 */
def findArtifactByProperties(art, properties, repo) {
    query = parseProperties(properties)
    if (repo) {
        query = query + "&repos=${repo}"
    }
    res = restGet(art, "/search/prop?${query}")
    return res.results
}

/**
 * Parse properties string or map and return URL-encoded string
 *
 * @param properties    string or key,value map
 */
def parseProperties(properties) {
    if (properties instanceof String) {
        return properties
    } else {
        props = []
        for (e in properties) {
            props.push("${e.key}=${e.value}")
        }
        props = props.join('|')
        return props
    }
}

/**
 * Set single property or list of properties to existing artifact
 *
 * @param art       Artifactory connection object
 * @param name      Name of artifact
 * @param version   Artifact's version, eg. Docker image tag
 * @param properties    String or list of properties in key=value format
 * @param recursive Set properties recursively (default false)
 */
def setProperty(art, name, version, properties, recursive = 0) {
    props = parseProperties(properties)
    restPut(art, "/storage/${art.outRepo}/${name}/${version}?properties=${props}&recursive=${recursive}")
}

/**
 * Artifactory connection and context parameters
 *
 * @param url       Artifactory server URL
 * @param dockerRegistryBase  Base to docker registry
 * @param dockerRegistrySSL   Use https to access docker registry
 * @param outRepo             Output repository name used in context of this
 *                            connection
 * @param credentialsID       ID of credentials store entry
 * @param serverName          Artifactory server name (optional)
 */
def connection(url, dockerRegistryBase, dockerRegistrySsl, outRepo, credentialsId = "artifactory", serverName = null) {
    params = [
        "url": url,
        "credentialsId": credentialsId,
        "docker": [
            "base": dockerRegistryBase,
            "ssl": dockerRegistrySsl
        ],
        "outRepo": outRepo,
        "creds": getCredentials(credentialsId)
    ]

    if (dockerRegistrySsl ?: false) {
        params["docker"]["proto"] = "https"
    } else {
        params["docker"]["proto"] = "http"
    }

    if (serverName ?: null) {
        params['server'] = Artifactory.server(serverName)
    }

    params["docker"]["url"] = "${params.docker.proto}://${params.outRepo}.${params.docker.base}"

    return params
}

/**
 * Push docker image and set artifact properties
 *
 * @param art   Artifactory connection object
 * @param img   Docker image object
 * @param imgName       Name of docker image
 * @param properties    Map of additional artifact properties
 * @param timestamp     Build timestamp
 * @param latest        Push latest tag if set to true (default true)
 */
def dockerPush(art, img, imgName, properties, timestamp, latest = true) {
    docker.withRegistry(art.docker.url, art.credentialsId) {
        img.push()
        // Also mark latest image
        img.push("latest")
    }

    properties["build.number"] = currentBuild.build().environment.BUILD_NUMBER
    properties["build.name"] = currentBuild.build().environment.JOB_NAME
    properties["timestamp"] = timestamp

    /* Set artifact properties */
    setProperty(
        art,
        imgName,
        timestamp,
        properties
    )

    // ..and the same for latest
    if (latest == true) {
        setProperty(
            art,
            imgName,
            "latest",
            properties
        )
    }
}

/**
 * Promote docker image to another environment
 *
 * @param art   Artifactory connection object
 * @param imgName       Name of docker image
 * @param tag           Tag to promote
 * @param env           Environment (repository suffix) to promote to
 * @param keep          Keep artifact in source repository (copy, default true)
 * @param latest        Push latest tag if set to true (default true)
 */
def dockerPromote(art, imgName, tag, env, keep = true, latest = true) {
    /* XXX: promotion this way doesn't work
    restPost(art, "/docker/${art.outRepo}/v2/promote", [
        "targetRepo": "${art.outRepo}-${env}",
        "dockerRepository": imgName,
        "tag": tag,
        "copy": keep ? true : false
    ])
    */

    action = keep ? "copy" : "move"
    restPost(art, "/${action}/${art.outRepo}/${imgName}/${tag}?to=${art.outRepo}-${env}/${imgName}/${tag}")
    if (latest == true) {
        dockerUrl = "${art.docker.proto}://${art.outRepo}-${env}.${art.docker.base}"
        docker.withRegistry(dockerUrl, art.credentialsId) {
            img = docker.image("${imgName}:$tag")
            img.pull()
            img.push("latest")
        }
    }
}

/**
 * Set offline parameter to repositories
 *
 * @param art       Artifactory connection object
 * @param repos     List of base repositories
 * @param suffix    Suffix to append to new repository names
 */
def setOffline(art, repos, suffix) {
    for (repo in repos) {
        repoName = "${repo}-${suffix}"
        restPost(art, "/repositories/${repoName}", ['offline': true])
    }
    return
}

/**
 * Create repositories based on timestamp or other suffix from already
 * existing repository
 *
 * @param art       Artifactory connection object
 * @param repos     List of base repositories
 * @param suffix    Suffix to append to new repository names
 */
def createRepos(art, repos, suffix) {
    def created = []
    for (repo in repos) {
        repoNewName = "${repo}-${suffix}"
        repoOrig = restGet(art, "/repositories/${repo}")
        repoOrig.key = repoNewName
        repoNew  = restPut(art, "/repositories/${repoNewName}", repoOrig)
        created.push(repoNewName)
    }
    return created
}

/**
 * Delete repositories based on timestamp or other suffix
 *
 * @param art       Artifactory connection object
 * @param repos     List of base repositories
 * @param suffix    Suffix to append to new repository names
 */
def deleteRepos(art, repos, suffix) {
    def deleted = []
    for (repo in repos) {
        repoName = "${repo}-${suffix}"
        restDelete(art, "/repositories/${repoName}")
        deleted.push(repoName)
    }
    return deleted
}

@NonCPS
def convertProperties(properties) {
    return properties.collect { k,v -> "$k=$v" }.join(';')
}

/**
 * Upload debian package
 *
 * @param art           Artifactory connection object
 * @param file          File path
 * @param properties    Map with additional artifact properties
 * @param timestamp     Image tag
 */

def uploadDebian(art, file, properties, distribution, component, timestamp) {
    def arch = file.split('_')[-1].split('\\.')[0]

    /* Set artifact properties */
    properties["build.number"] = currentBuild.build().environment.BUILD_NUMBER
    properties["build.name"] = currentBuild.build().environment.JOB_NAME
    properties["timestamp"] = timestamp

    properties["deb.distribution"] = distribution
    properties["deb.component"] = component
    properties["deb.architecture"] = arch
    props = convertProperties(properties)

    def uploadSpec = """{
      "files": [
        {
          "pattern": "${file}",
          "target": "${art.outRepo}",
          "props": "${props}"
        }
      ]
    }"""
    art.server.upload(uploadSpec)
}

/**
 * Build step to upload docker image. For use with eg. parallel
 *
 * @param art           Artifactory connection object
 * @param img           Image name to push
 * @param properties    Map with additional artifact properties
 * @param timestamp     Image tag
 */
def uploadDockerImageStep(art, img, properties, timestamp) {
    return {
        println "Uploading artifact ${img} into ${art.outRepo}"
        dockerPush(
            art,
            docker.image("${img}:${timestamp}"),
            img,
            properties,
            timestamp
        )
    }
}

/**
 * Build step to upload package. For use with eg. parallel
 *
 * @param art           Artifactory connection object
 * @param file          File path
 * @param properties    Map with additional artifact properties
 * @param timestamp     Image tag
 */
def uploadPackageStep(art, file, properties, distribution, component, timestamp) {
    return {
        uploadDebian(
            art,
            file,
            properties,
            distribution,
            component,
            timestamp
        )
    }
}

/**
 * Get Helm repo for Artifactory
 *
 * @param art           Artifactory connection object
 * @param repoName      Chart repository name
 */
def getArtifactoryProjectByName(art, repoName){
    return restGet(art, "/repositories/${repoName}")
}

/**
 * Get repo by packageType for Artifactory
 *
 * @param art           Artifactory connection object
 * @param packageType   Repository package type
 */
def getArtifactoryProjectByPackageType(art, repoName){
    return restGet(art, "/repositories?${packageType}")
}

/**
 * Create Helm repo for Artifactory
 *
 * @param art           Artifactory connection object
 * @param repoName      Chart repository name
 * @param data          Transmitted data
 */
def createArtifactoryChartRepo(art, repoName){
    return restPut(art, "/repositories/${repoName}", '{"rclass": "local","handleSnapshots": false,"packageType": "helm"}')
}

/**
 * Delete Helm repo for Artifactory
 *
 * @param art           Artifactory connection object
 * @param repoName      Chart repository name
 */
def deleteArtifactoryChartRepo(art, repoName){
    return restDelete(art, "/repositories/${repoName}")
}

/**
 * Create Helm repo for Artifactory
 *
 * @param art           Artifactory connection object
 * @param repoName      Repository Chart name
 * @param chartName     Chart name
 */
def publishArtifactoryHelmChart(art, repoName, chartName){
    return restPut(art, "/repositories/${repoName}", "${chartName}")
}

/**
 * Create Helm repo for Artifactory
 *
 * @param art           Artifactory connection object
 * @param repoName      Repository Chart name
 * @param chartName     Chart name
 */
def deleteArtifactoryHelmChart(art, repoName, chartName){
    return restDelete(art, "/repositories/${repoName}", "${chartName}")
}
