package com.mirantis.mcp

/**
 * Send POST request to url with some params
 * @param urlString url
 * @param paramString JSON string
 */
def sendPostRequest(String urlString, String paramString){
    def url = new URL(urlString)
    def conn = url.openConnection()
    conn.setDoOutput(true)
    def writer = new OutputStreamWriter(conn.getOutputStream())
    writer.write(paramString)
    writer.flush()
    String line
    def reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))
    while ((line = reader.readLine()) != null) {
        println line
    }
    writer.close()
    reader.close()
}

/**
 * Send slack notification
 * @param buildStatusParam message type (success, warning, error)
 * @param jobName job name param, if empty env.JOB_NAME will be used
 * @param buildNumber build number param, if empty env.BUILD_NUM will be used
 * @param buildUrl build url param, if empty env.BUILD_URL will be used
 * @param channel param, default is '#mk-ci'
 * @param credentialsId slack hook url credential, default is 'SLACK_WEBHOOK_URL'
 * @param description Additional text related to Job result
 */
def jobResultNotification(String buildStatusParam, String channel = "#mk-ci",
                          String jobName=null,
                          Number buildNumber=null, String buildUrl=null,
                          String credentialsId="SLACK_WEBHOOK_URL",
                          String description='') {
    def jobNameParam = jobName != null && jobName != "" ? jobName : env.JOB_NAME
    def buildUrlParam = buildUrl != null && buildUrl != "" ? buildUrl : env.BUILD_URL
    def buildNumberParam = buildNumber != null && buildNumber != "" ? buildNumber : env.BUILD_NUMBER


    def common = new com.mirantis.mk.Common()
    def cred = common.getCredentialsById(credentialsId)
    def hook_url_parsed = cred.getSecret().toString()
    String colorCode
    String colorName

    switch(buildStatusParam.toLowerCase()) {
        case "success":
            colorCode = "#00FF00"
            colorName = "green"
            break
        case "unstable":
            colorCode = "#FFFF00"
            colorName = "yellow"
            break
        case "failure":
            colorCode = "#FF0000"
            colorName = "red"
            break
        default:
            colorCode = "#808080"
            colorName = "grey"
    }

    String queryString = 'payload={' +
            "'text':'${buildStatusParam.toUpperCase()}: Job <${buildUrlParam}|${jobNameParam} [${buildNumberParam}]>\\n ${description}', " +
            "'color':'${colorCode}'," +
            "'pretext': '', " +
            '"icon_url": "https://cfr.slack-edge.com/ae7f/img/services/jenkins-ci_192.png",' +
            "'channel': '${channel}', " +
            '}'
    sendPostRequest(hook_url_parsed, queryString)
}

/*
node {
    jobResultNotification(
            "success",
            "#test_reclass_notify",
            "test-reclass-system",
            44,
            "https://ci.mcp.mirantis.net/",)
}
*/
