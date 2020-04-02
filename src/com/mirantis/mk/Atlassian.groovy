package com.mirantis.mk

/**
 *
 * Send HTTP request to JIRA REST API.
 *
 * @param uri (string) JIRA url to post message to
 * @param auth (string) authentication data
 * @param method (string) HTTP method to call. Default: GET
 * @param message (string) payload to send to JIRA
 *
 * @return map with two elements:
 *   - responseCode
 *   - responseText
 *
**/
def callREST (String uri, String auth, String method = 'GET', String message = null) {
    String authEnc = auth.bytes.encodeBase64()
    def req = new URL(uri).openConnection()
    req.setRequestMethod(method)
    req.setRequestProperty('Content-Type', 'application/json')
    req.setRequestProperty('Authorization', "Basic ${authEnc}")
    if (message) {
        req.setDoOutput(true)
        req.getOutputStream().write(message.getBytes('UTF-8'))
    }
    Integer responseCode = req.getResponseCode()
    String responseText = ''
    if (responseCode == 200) {
        responseText = req.getInputStream().getText()
    }
    req = null // to reset the connection
    return [ 'responseCode': responseCode, 'responseText': responseText ]
}

/**
 *
 * Exract JIRA ticket numbers from the commit
 * message for Gerrit change request.
 *
 * @param commitMsg string to parse for JIRA ticket IDs
 * @param matcherRegex (string) regex to match JIRA issue IDs in commitMsg. Default: '([A-Z]+-[0-9]+)'
 *
 * @return list of JIRA ticket IDs
 *
**/

List extractJIRA(String commitMsg, String matcherRegex = '([A-Z]+-[0-9]+)') {
  String msg = new String(commitMsg.decodeBase64())
  def matcher = (msg =~ matcherRegex)
  List tickets = []

  matcher.each{ tickets.add(it[0]) }
  return tickets
}

/**
 *
 * Post a text message in comments to a JIRA issue.
 *
 * @param uri (string) JIRA url to post message to
 * @param auth (string) authentication data
 * @param message (string) message to post to a JIRA issue as comment
 *
**/

def postComment(String uri, String auth, String message) {
    String messageBody = message.replace('"', '\\"').replace('\n', '\\n')
    String payload = """{"body": "${messageBody}"}"""
    callREST("${uri}/comment", auth, 'POST', payload)
}

/**
 *
 * Post comment to list of JIRA issues.
 *
 * @param uri (string) base JIRA url, each ticket ID appends to it
 * @param auth (string) authentication data
 * @param message (string) payload to post to JIRA issues as comment
 * @param tickets list of ticket IDs to post message to
 *
**/

def postMessageToTickets(String uri, String auth, String message, List tickets) {
    tickets.each{
        if ( callREST("${uri}/${it}", auth)['responseCode'] == 200 ) {
            println "Updating ${uri}/${it} ...".replaceAll('rest/api/2/issue', 'browse')
            postComment("${uri}/${it}", auth, message)
        }
    }
}
