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
 */
@NonCPS
def sendHttpRequest(url, method = 'GET', data = null, headers = [:]) {

    def connection = new URL(url).openConnection()
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
        def output = new OutputStreamWriter(connection.outputStream)
        //infoMsg("[HTTP] Request URL: ${url}, method: ${method}, headers: ${headers}, content: ${dataStr}")
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
        //successMsg("[HTTP] Response: code ${connection.responseCode}")
        return response_content
    } else {
        //errorMsg("[HTTP] Response: code ${connection.responseCode}")
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
 */
def sendHttpPostRequest(url, data = null, headers = [:]) {
    return sendHttpRequest(url, 'POST', data, headers)
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
