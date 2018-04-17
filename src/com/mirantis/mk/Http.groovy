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
 * Make DELETE request using Salt REST API and return parsed JSON
 *
 * @param master   Salt connection object
 * @param uri   URI which will be appended to Salt server base URL
 */
def restDelete(master, uri, data = null) {
    return restCall(master, uri, 'DELETE', data)
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

/**
 * Make HTTP request to the specified URL
 *
 * @param url       URL to do HTTP request to
 * @param method    HTTP method to execute (`GET` by default)
 * @param credsId   Jenkins credentials ID to use for authorization
 * @param pushData  Data to send
 * @param pushType  MIME-type of pushData
 * @param params    Custom HTTP-headers
 *
 * @return          Array containing return code and text
 *
 * @exception       org.jenkinsci.plugins.scriptsecurity.sandbox.RejectedAccessException
 */
def methodCall(String url, String method = 'GET', String credsId = '',
         String pushData = '', String pushType = '', Map params = [:]) {

    // Connection object
    def httpReq = new URI(url).normalize().toURL().openConnection()
    httpReq.setRequestMethod(method)

    // Timeouts
    httpReq.setConnectTimeout(10*1000) // milliseconds
    httpReq.setReadTimeout(600*1000)   // milliseconds

    // Add authentication data
    if (credsId) {
        String authHeader = ''
        def cred = com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials(
            com.cloudbees.plugins.credentials.impl.BaseStandardCredentials.class,
            jenkins.model.Jenkins.instance).findAll {it.id == credsId}[0]
        if (cred.class == com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl.class) {
            authHeader = 'Basic ' + "${cred.getUsername()}:${cred.getPassword()}".getBytes('UTF-8').encodeBase64().toString()
        } else if (cred.class == org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl.class) {
            authHeader = 'Bearer ' + cred.getSecret()
            //params << ['X-JFrog-Art-Api': cred.getSecret()]
        }
        params << ['Authorization': authHeader]
    }

    // Add custom headers if any
    for (param in params) {
        httpReq.setRequestProperty(param.key, param.value.toString())
    }

    // Do request
    try {
        if (pushData) {
            httpReq.setRequestProperty('Content-Type', pushType ?: 'application/x-www-form-urlencoded')
            if (method == 'GET') { // override incorrect method if pushData is passed
                httpReq.setRequestMethod('POST')
            }
            httpReq.setDoOutput(true)
            httpReq.getOutputStream().write(pushData.getBytes('UTF-8'))
        } else {
            httpReq.connect()
        }
    } catch (org.jenkinsci.plugins.scriptsecurity.sandbox.RejectedAccessException e) {
        throw e // show sandbox errors
    } catch (Exception e) {
        echo "Cauhgt '${e.class.name}' error: ${e.getMessage()}"
        return [ -1, e.getMessage() ]
    }

    // Handle return data
    int respCode = httpReq.getResponseCode()
    String respText = ''
    if (respCode >= 400) {
        respText = httpReq.getErrorStream().getText() ?: httpReq.getResponseMessage()
    } else {
        respText = httpReq.getInputStream().getText()
    }

    // Return result as a tuple of response code and text
    return [respCode, respText]
}

/**
 * Make HTTP GET request to the specified URL
 *
 * @param url       URL to do HTTP request to
 * @param credsId   Jenkins credentials ID to use for authorization
 * @param params    Custom HTTP-headers
 *
 * @return          Array containing return code and text
 */
def doGet(String url, String credsId = '', Map params = [:]) {
    return methodCall(url, 'GET', credsId, null, null, params)
}

/**
 * Make HTTP POST request to the specified URL
 *
 * @param url       URL to do HTTP request to
 * @param credsId   Jenkins credentials ID to use for authorization
 * @param pushData  Data to send
 * @param pushType  MIME-type of pushData
 * @param params    Custom HTTP-headers
 *
 * @return          Array containing return code and text
 */
def doPost(String url, String credsId = '',
         String pushData = '', String pushType = '', Map params = [:]) {
    return methodCall(url, 'POST', credsId, pushData, pushType, params)
}

/**
 * Make HTTP PUT request to the specified URL
 *
 * @param url       URL to do HTTP request to
 * @param credsId   Jenkins credentials ID to use for authorization
 * @param pushData  Data to send
 * @param pushType  MIME-type of pushData
 * @param params    Custom HTTP-headers
 *
 * @return          Array containing return code and text
 */
def doPut(String url, String credsId = '',
        String pushData = '', String pushType = '', Map params = [:]) {
    return methodCall(url, 'PUT', credsId, pushData, pushType, params)
}

/**
 * Make HTTP DELETE request to the specified URL
 *
 * @param url       URL to do HTTP request to
 * @param credsId   Jenkins credentials ID to use for authorization
 * @param params    Custom HTTP-headers
 *
 * @return          Array containing return code and text
 */
def doDelete(String url, String credsId = '', Map params = [:]) {
    return methodCall(url, 'DELETE', credsId, null, null, params)
}
