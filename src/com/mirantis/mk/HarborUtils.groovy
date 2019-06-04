package com.mirantis.mk

/**
 *
 *  Functions to work with Harbor Api
 *
 */

 /**
 * Returns a list of Harbor projects (hash maps) containing common pattern in name
 * @param harborApi HashMap         contains 'url' and 'auth' fields. Url field is a string
 *                                  containing Harbor api url - e.g. http://[harbor_api_host]/api,
 *                                  Auth field should provide string with base64 encoded presentation
 *                                  of string 'username:password' for connection to Harbor Api
 * @param projectPattern String     contains full name or part of project name
 * @return list
 */
def getHarborProjectsByPattern(harborApi, projectPattern){
    def http = new com.mirantis.mk.Http()
    def common = new com.mirantis.mk.Common()
    def auth = harborApi['auth']
    def headers = ['Authorization': "Basic ${auth}"]
    def res = http.restGet(harborApi, "/projects?name=${projectPattern}", null, headers)
    if (!(res instanceof List)){
        common.warningMsg("No projects found for the pattern ${projectPattern}, The result is ${res}")
        return []
    }
    return res
}

 /**
 * Returns an id of a project
 * @param harborApi HashMap         contains 'url' and 'auth' fields. Url field is a string
 *                                  containing Harbor api url - e.g. http://[harbor_api_host]/api,
 *                                  Auth field should provide string with base64 encoded presentation
 *                                  of string 'username:password' for connection to Harbor Api
 * @param projectName String        project name to search for
 * @return string
 */
def getHarborProjectByName(harborApi, projectName){
    def plist = getHarborProjectsByPattern(harborApi, projectName)
    for (i in plist){
        if (i.get('name') == projectName){
            return i['project_id']
        }
    }
}

 /**
 * Creates a project in Harbor, returns string
 * @param harborApi HashMap         contains 'url' and 'auth' fields. Url field is a string
 *                                  containing Harbor api url - e.g. http://[harbor_api_host]/api,
 *                                  Auth field should provide string with base64 encoded presentation
 *                                  of string 'username:password' for connection to Harbor Api
 * @param projectName String        project name to create
 * @param isPublic Bool             whether to create public project
 * @return string
 */
def createHarborProject(harborApi, projectName, isPublic = true){
    def http = new com.mirantis.mk.Http()
    def data =  ['project_name': projectName, 'metadata': ['public': isPublic.toString()]]
    def auth = harborApi['auth']
    def headers = ['Authorization': "Basic ${auth}"]
    return http.restPost(harborApi, '/projects/', data, headers)
}

 /**
 * Deletes a project in Harbor returns string
 * @param harborApi HashMap         contains 'url' and 'auth' fields. Url field is a string
 *                                  containing Harbor api url - e.g. http://[harbor_api_host]/api,
 *                                  Auth field should provide string with base64 encoded presentation
 *                                  of string 'username:password' for connection to Harbor Api
 * @param projectId String          project id to delete
 * @return string
 */
def deleteHarborProject(harborApi, projectId){
    def http = new com.mirantis.mk.Http()
    def auth = harborApi['auth']
    def headers = ['Authorization': "Basic ${auth}"]
    return http.restDelete(harborApi, "/projects/${projectId}", null, headers)
}

 /**
 * Returns a list of Helm charts (hash maps) from repo
 * @param harborApi HashMap         contains 'url' and 'auth' fields. Url field is a string
 *                                  containing Harbor api url - e.g. http://[harbor_api_host]/api,
 *                                  Auth field should provide string with base64 encoded presentation
 *                                  of string 'username:password' for connection to Harbor Api
 * @param repoName String           name of Helm charts repo
 * @return list
 */
def getHarborHelmCharts(harborApi, repoName){
    def http = new com.mirantis.mk.Http()
    def auth = harborApi['auth']
    def headers = ['Authorization': "Basic ${auth}"]
    return http.restGet(harborApi, "/chartrepo/${repoName}/charts", null, headers)
}

 /**
 * Deletes a Helm chart from repo, returns string
 * @param harborApi HashMap         contains 'url' and 'auth' fields. Url field is a string
 *                                  containing Harbor api url - e.g. http://[harbor_api_host]/api,
 *                                  Auth field should provide string with base64 encoded presentation
 *                                  of string 'username:password' for connection to Harbor Api
 * @param repoName String           name of Helm charts repo
 * @param chartName String          name of chart to remove
 * @return string
 */
 def deleteHarborHelmChart(harborApi, repoName, chartName){
    def http = new com.mirantis.mk.Http()
    def auth = harborApi['auth']
    def headers = ['Authorization': "Basic ${auth}"]
    return http.restDelete(harborApi, "/chartrepo/${repoName}/charts/${chartName}", null, headers)
}

 /**
 * Publishes a Helm chart to Harbor repo. TODO(mkarpin): currently http.restCall doesn't support
 * multipart/form-data content type for POST request, when this support is added, this method
 * should be switched to restCall.
 * @param harborApi HashMap         contains 'url' and 'auth' fields. Url field is a string
 *                                  containing Harbor api url - e.g. http://[harbor_api_host]/api,
 *                                  Auth field should provide string with base64 encoded presentation
 *                                  of string 'username:password' for connection to Harbor Api
 * @param repoName String           name of Helm charts repo
 * @param chartFile String          name of file with Helm chart
 * @param chartFile String          name of file with signature of Helm chart
 * @return string
 */
def publishHarborHelmChart(harborApi, repoName, chartFile, provFile = null){
    def http = new com.mirantis.mk.Http()
    def common = new com.mirantis.mk.Common()
    def data = "-F 'chart=@${chartFile}'"
    def res = [:]
    if (provFile){
        data += ' '
        data += "-F 'prov=@${provFile}'"
    }
    def auth = harborApi['auth']
    def headers = "-H 'Authorization: Basic ${auth}'"
    def repo_url = "${harborApi['url']}/chartrepo/${repoName}/charts"

    common.infoMsg("Publishing chart ${chartFile} to ${repo_url}")
    res = common.shCmdStatus("set +x; curl --fail ${headers} ${data} ${repo_url}")
    if (res['status'] == 0){
        return res['stdout']
    } else {
        error "Chart publishing failed with error ${res['stderr']}"
    }
}