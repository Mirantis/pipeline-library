package com.mirantis.mk
/**
 *
 * HTTP functions
 *
 */

/**
 * Make generic HTTP call and return parsed JSON
 *
 * @param url       URL to make the request against
 * @param method    HTTP method to use (default GET)
 * @param data      JSON data to POST or PUT
 * @param headers   Map of additional request headers
 * @param read_timeout http session read timeout
 */
@NonCPS
def sendHttpRequest(url, method = 'GET', data = null, headers = [:], read_timeout=-1) {
    def connection = new URL(url).openConnection()

    if (read_timeout != -1){
        connection.setReadTimeout(read_timeout*1000)
    }
    if (method != 'GET') {
        connection.setRequestMethod(method)
    }

    if (data) {
        headers['Content-Type'] = 'application/json'
    }

    headers['User-Agent'] = 'jenkins-groovy'
    headers['Accept'] = 'application/json'

    for (header in headers) {
        connection.setRequestProperty(header.key, header.value)
    }

    if (data) {
        connection.setDoOutput(true)
        if (data instanceof String) {
            dataStr = data
        } else {
            dataStr = new groovy.json.JsonBuilder(data).toString()
        }
        if(env.getEnvironment().containsKey('DEBUG') && env['DEBUG'] == "true"){
            println("[HTTP] Request URL: ${url}, method: ${method}, headers: ${headers}, content: ${dataStr}")
        }
        def output = new OutputStreamWriter(connection.outputStream)
        output.write(dataStr)
        output.close()
    }

    if ( connection.responseCode == 200 ) {
        response = connection.inputStream.text
        try {
            response_content = new groovy.json.JsonSlurperClassic().parseText(response)
        } catch (groovy.json.JsonException e) {
            response_content = response
        }
        if(env.getEnvironment().containsKey('DEBUG') && env['DEBUG'] == "true"){
            println("[HTTP] Response: code ${connection.responseCode}")
        }
        return response_content
    } else {
        if(env.getEnvironment().containsKey('DEBUG') && env['DEBUG'] == "true"){
            println("[HTTP] Response: code ${connection.responseCode}")
        }
        throw new Exception(connection.responseCode + ": " + connection.inputStream.text)
    }
}

/**
 * Make HTTP GET request
 *
 * @param url     URL which will requested
 * @param data    JSON data to PUT
 */
def sendHttpGetRequest(url, data = null, headers = [:]) {
    return sendHttpRequest(url, 'GET', data, headers)
}

/**
 * Make HTTP POST request
 *
 * @param url     URL which will requested
 * @param data    JSON data to PUT
 * @param read_timeout http session read timeout
 */
def sendHttpPostRequest(url, data = null, headers = [:], read_timeout=-1) {
    return sendHttpRequest(url, 'POST', data, headers, read_timeout)
}

/**
 * Make HTTP PUT request
 *
 * @param url     URL which will requested
 * @param data    JSON data to PUT
 */
def sendHttpPutRequest(url, data = null, headers = [:]) {
    return sendHttpRequest(url, 'PUT', data, headers)
}

/**
 * Make HTTP DELETE request
 *
 * @param url     URL which will requested
 * @param data    JSON data to PUT
 */
def sendHttpDeleteRequest(url, data = null, headers = [:]) {
    return sendHttpRequest(url, 'DELETE', data, headers)
}

/**
 * Make generic call using Salt REST API and return parsed JSON
 *
 * @param master   Salt connection object
 * @param uri   URI which will be appended to Salt server base URL
 * @param method    HTTP method to use (default GET)
 * @param data      JSON data to POST or PUT
 * @param headers   Map of additional request headers
 */
def restCall(master, uri, method = 'GET', data = null, headers = [:]) {
    def connection = new URL("${master.url}${uri}").openConnection()
    if (method != 'GET') {
        connection.setRequestMethod(method)
    }

    connection.setRequestProperty('User-Agent', 'jenkins-groovy')
    connection.setRequestProperty('Accept', 'application/json')
    if (master.authToken) {
        // XXX: removeme
        connection.setRequestProperty('X-Auth-Token', master.authToken)
    }

    for (header in headers) {
        connection.setRequestProperty(header.key, header.value)
    }

    if (data) {
        connection.setDoOutput(true)
        if (data instanceof String) {
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
 * Make GET request using Salt REST API and return parsed JSON
 *
 * @param master   Salt connection object
 * @param uri   URI which will be appended to Salt server base URL
 */
def restGet(master, uri, data = null) {
    return restCall(master, uri, 'GET', data)
}

/**
 * Make POST request using Salt REST API and return parsed JSON
 *
 * @param master   Salt connection object
 * @param uri   URI which will be appended to Docker server base URL
 * @param data  JSON Data to PUT
 */
def restPost(master, uri, data = null) {
    return restCall(master, uri, 'POST', data, ['Accept': '*/*'])
}

/**
 * Set HTTP and HTTPS proxy for running JVM
 * @param host HTTP proxy host
 * @param port HTTP proxy port
 * @param nonProxyHosts proxy excluded hosts, optional, default *.local
 */
def enableHttpProxy(host, port, nonProxyHosts="*.local"){
    System.getProperties().put("proxySet", "true")
    System.getProperties().put("http.proxyHost", host)
    System.getProperties().put("http.proxyPort", port)
    System.getProperties().put("https.proxyHost", host)
    System.getProperties().put("https.proxyPort", port)
    System.getProperties().put("http.nonProxyHosts", nonProxyHosts)
    System.getProperties().put("https.nonProxyHosts", nonProxyHosts)
}
/**
 * Disable HTTP and HTTPS proxy for running JVM
 */
def disableHttpProxy(){
    System.getProperties().put("proxySet", "false")
    System.getProperties().remove("http.proxyHost")
    System.getProperties().remove("http.proxyPort")
    System.getProperties().remove("https.proxyHost")
    System.getProperties().remove("https.proxyPort")
    System.getProperties().remove("http.nonProxyHosts")
    System.getProperties().remove("https.nonProxyHosts")
}
