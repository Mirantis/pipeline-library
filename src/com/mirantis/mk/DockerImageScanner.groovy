#!groovy

package com.mirantis.mk

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

def callREST(String uri, String auth,
              String method = 'GET', String message = null) {
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
    if (responseCode == 200 || responseCode == 201) {
        responseText = req.getInputStream().getText()
    }
    req = null
    return [ 'responseCode': responseCode, 'responseText': responseText ]
}

def getTeam(String image = '') {
    def team_assignee = ''
    switch(image) {
        case ~/^openstack\/extra\/xrally-openstack\/.*$/:
            team_assignee = 'Scale'
            break
        case ~/^(tungsten|tungsten-operator)\/.*$/:
            team_assignee = 'OpenContrail'
            break
        case ~/^(openstack|general\/mariadb|general\/general\/rabbitmq|general\/general\/rabbitmq-management)\/.*$/:
            team_assignee = 'OpenStack hardening'
            break
        case ~/^stacklight\/.*$/:
            team_assignee = 'Stacklight LMA'
            break
        case ~/^ceph\/.*$/:
            team_assignee = 'Storage'
            break
        case ~/^(core|iam|lcm|general\/external\/docker.io\/library\/nginx)\/.*$/:
            team_assignee = 'KaaS'
            break
        case ~/^(bm|general)\/.*$/:
            team_assignee = 'BM/OS (KaaS BM)'
            break
        default:
            team_assignee = 'Release Engineering'
            break
    }

    return team_assignee
}

def updateDictionary(String jira_issue_key, Map dict, String uri, String auth, String jira_user_id) {
    def response = callREST("${uri}/${jira_issue_key}", auth)
    if ( response['responseCode'] == 200 ) {
        def issueJSON = new JsonSlurper().parseText(response["responseText"])
        if (issueJSON.containsKey('fields')) {
            if (!dict.containsKey(jira_issue_key)) {
                dict[jira_issue_key] = [
                        summary : '',
                        description: '',
                        comments: []
                ]
            }
            if (issueJSON['fields'].containsKey('summary')){
                dict[jira_issue_key].summary = issueJSON['fields']['summary']
            }
            if (issueJSON['fields'].containsKey('description')) {
                dict[jira_issue_key].description = issueJSON['fields']['description']
            }
            if (issueJSON['fields'].containsKey('comment') && issueJSON['fields']['comment']['comments']) {
                issueJSON['fields']['comment']['comments'].each {
                    if (it.containsKey('author') && it['author'].containsKey('accountId') && it['author']['accountId'] == jira_user_id) {
                        dict[jira_issue_key]['comments'].add(it['body'])
                    }
                }
            }
        }
    }
    return dict
}

def cacheLookUp(Map dict, String image_short_name, String image_full_name = '', String cve_id = '' ) {
    def found_key = ['','']
    if (!found_key[0] && dict && image_short_name) {
        dict.each { issue_key_name ->
            if (!found_key[0]) {
                def s
                if (image_short_name =~ /^mirantis(eng)?\//) {
                    def tmp_image_short_name = image_short_name.replaceAll(/^mirantis(eng)?\//, '')
                    s = dict[issue_key_name.key]['summary'] =~ /^\[mirantis(eng)?\/${tmp_image_short_name}(?=\])/
                } else {
                    s = dict[issue_key_name.key]['summary'] =~ /(?<=[\/\[])${image_short_name}(?=\])/
                }
                if (s) {
                    if (image_full_name) {
                        def d = dict[issue_key_name.key]['description'] =~ /(?m)\b${image_full_name}\b/
                        if (d) {
                            found_key = [issue_key_name.key,'']
                        } else {
                            if (dict[issue_key_name.key]['comments']) {
                                def comment_match = false
                                dict[issue_key_name.key]['comments'].each{ comment ->
                                    if (!comment_match) {
                                        def c = comment =~ /(?m)\b${image_full_name}\b/
                                        if (c) {
                                            comment_match = true
                                        }
                                    }
                                }
                                if (!comment_match) {
                                    found_key = [issue_key_name.key,'na']
                                } else {
                                    found_key = [issue_key_name.key,'']
                                }
                            } else {
                                found_key = [issue_key_name.key,'na']
                            }
                        }
                    }
                }
            }
        }
    }
    return found_key
}

def getLatestAffectedVersion(cred, productName, defaultJiraAffectedVersion = 'Backlog') {
    def filterName = ''
    if (productName == 'mosk') {
        filterName = 'MOSK'
    } else if (productName == 'kaas') {
        filterName = 'KaaS'
    } else {
        return defaultJiraAffectedVersion
    }

    def search_api_url = "${cred.description}/rest/api/2/issue/createmeta?projectKeys=PRODX&issuetypeNames=Bug&expand=projects.issuetypes.fields"
    def response = callREST("${search_api_url}", "${cred.username}:${cred.password}", 'GET')
    def InputJSON = new JsonSlurper().parseText(response["responseText"])
    def AffectedVersions = InputJSON['projects'][0]['issuetypes'][0]['fields']['versions']['allowedValues']

    def versions = []
    AffectedVersions.each{
        // 'MOSK' doesn not contain 'released' field
        if (productName != 'mosk' && it.containsKey('released') && it['released']) {
            return
        }
        if (it.containsKey('name') && it['name'].startsWith(filterName)) {
            def justVersion = it['name'].replaceAll(/.*_/, '')
            justVersion = justVersion.replaceAll(/([0-9]+\.)([0-9])$/, '$10$2')
            versions.add("${justVersion}`${it['name']}")
        }
    }
    if (versions) {
        return versions.sort()[0].split('`')[-1]
    }
    return defaultJiraAffectedVersion
}

def getNvdInfo(nvdApiUrl, cve, requestDelay = 1, requestRetryNum = 5, sleepTimeOnBan = 60) {
    def cveArr = []
    sleep requestDelay
    def response = callREST("${nvdApiUrl}/${cve}", '')
    for (i = 0; i < requestRetryNum; i++) {
        if (response['responseCode'] == 429) {
            sleep sleepTimeOnBan
            response = callREST("${nvdApiUrl}/${cve}", '')
        } else {
            break
        }
    }
    if (response['responseCode'] == 200) {
        def InputJSON = new JsonSlurper().parseText(response["responseText"])
        if (InputJSON && InputJSON.containsKey('impact')) {
            def cveImpact = InputJSON['impact']
            ['V3','V2'].each {
                if (cveImpact.containsKey('baseMetric' + it)) {
                    if (cveImpact['baseMetric' + it].containsKey('cvss' + it)) {
                        if (cveImpact['baseMetric' + it]['cvss' + it].containsKey('baseScore')) {
                            def cveBaseSeverity = ''
                            if (cveImpact['baseMetric' + it]['cvss' + it].containsKey('baseSeverity')) {
                                cveBaseSeverity = cveImpact['baseMetric'+it]['cvss'+it]['baseSeverity']
                            }
                            cveArr.add([it, cveImpact['baseMetric'+it]['cvss'+it]['baseScore'],cveBaseSeverity])
                        }

                    }
                }
            }
        }
    }
    return cveArr
}

def nvdCacheLookUp(Map dict, String cveId) {
    if (dict.containsKey(cveId)) {
        return dict[cveId]
    }
    return false
}

def nvdUpdateDictionary(Map dict, String cveId, List cveArr) {
    if (!dict.containsKey(cveId)) {
        dict[cveId] = cveArr
    }
    return dict
}

def logInfo(String infoText, String infoLogFile) {
    if (infoLogFile) {
        sh """#!/bin/bash -e
            mkdir -p `dirname $infoLogFile`
            echo "[`date +'%Y-%m-%d %H:%M:%S'`] ${errorText}" >> $infoLogFile
        """
    }
}

def reportJiraTickets(String reportFileContents, String jiraCredentialsID, String jiraUserID, String productName = '', String ignoreImageListFileContents = '[]', Integer retryTry = 0, String nvdApiUrl = '', String reportsDirLoc = '', jiraNamespace = 'PRODX', nvdNistGovCveUrl = 'https://nvd.nist.gov/vuln/detail/') {

    def dict = [:]
    def nvdDict = [:]

    def common = new com.mirantis.mk.Common()
    def cred = common.getCredentialsById(jiraCredentialsID)
    def auth = "${cred.username}:${cred.password}"
    def uri = "${cred.description}/rest/api/2/issue"

    def search_api_url = "${cred.description}/rest/api/2/search"

    def jiraLog = ''
    if (reportsDirLoc) {
        jiraLog = "${reportsDirLoc}/jira.log"
    }

    def jqlStartAt = 0
    def jqlStep = 100
    def jqlProcessedItems = 0
    def jqlUnfinishedProcess = true
    def jqlTotalItems = 0
    while (jqlUnfinishedProcess) {
        def search_json = """
{
        "jql": "reporter = ${jiraUserID} and (labels = cve and labels = security) and (status = 'To Do' or status = 'For Triage' or status = Open or status = 'In Progress' or status = New or status = 'Input Required')", "maxResults":-1, "startAt": ${jqlStartAt}
}
"""

        def response = callREST("${search_api_url}", auth, 'POST', search_json)
        def InputJSON = new JsonSlurper().parseText(response["responseText"])
        if (InputJSON.containsKey('maxResults')){
            if (jqlStep > InputJSON['maxResults']) {
                jqlStep = InputJSON['maxResults']
            }
        }

        jqlStartAt = jqlStartAt + jqlStep

        if (InputJSON.containsKey('total')){
            jqlTotalItems = InputJSON['total']
        }

        if (InputJSON.containsKey('issues')){
            if (!InputJSON['issues'] && retryTry != 0) {
                throw new Exception('"issues" list is empty')
            }
        } else {
            throw new Exception('Returned JSON from jql does not contain "issues" section')
        }
//        print 'Temporal debug information:'
//        InputJSON['issues'].each {
//            print it['key'] + ' -> ' + it['fields']['summary']
//        }

        InputJSON['issues'].each {
            dict[it['key']] = [
                    summary : '',
                    description: '',
                    comments: []
            ]
        }

        InputJSON['issues'].each { jira_issue ->
            dict = updateDictionary(jira_issue['key'], dict, uri, auth, jiraUserID)
            jqlProcessedItems = jqlProcessedItems + 1
        }
        if (jqlProcessedItems >= jqlTotalItems) {
            jqlUnfinishedProcess = false
        }
    }

    def reportJSON = new JsonSlurper().parseText(reportFileContents)
    def imageDict = [:]
    reportJSON.each{
        image ->
            if ("${image.value}".contains('issues')) { return }
            image.value.each{
                pkg ->
                    pkg.value.each{
                        cve ->
                            if (cve[2] && (cve[1].contains('High') || cve[1].contains('Critical'))) {
                                if (!imageDict.containsKey(image.key)) {
                                    imageDict.put(image.key, [:])
                                }
                                if (!imageDict[image.key].containsKey(pkg.key)) {
                                    imageDict[image.key].put(pkg.key, [])
                                }
                                imageDict[image.key][pkg.key].add("[${cve[0]}|${cve[4]}] (${cve[2]}) (${cve[3]}) | ${cve[5]}")
                            }
                    }
            }
    }

    def affectedVersion = ''
    if (jiraNamespace == 'PRODX') {
        affectedVersion = getLatestAffectedVersion(cred, productName)
    }

    def ignoreImageList = new JsonSlurper().parseText(ignoreImageListFileContents)

    def jira_summary = ''
    def jira_description = ''
    def jira_description_nvd_scoring = []
    imageDict.each{
        image ->
            def image_key = image.key.replaceAll(/(^[a-z0-9-.]+.mirantis.(net|com)\/|:.*$)/, '')

            // Ignore images listed
            if ((image.key in ignoreImageList) || (image.key.replaceAll(/:.*$/, '') in ignoreImageList)) {
                print "\n\nIgnoring ${image.key} as it has been found in Docker image ignore list\n"
                logInfo("Ignoring ${image.key} as it has been found in Docker image ignore list", jiraLog)
                return
            }

            // Below change was produced due to other workflow for UCP Docker images (RE-274)
            if (image_key.startsWith('lcm/docker/ucp')) {
                return
            } else if (image_key.startsWith('mirantis/ucp') || image_key.startsWith('mirantiseng/ucp')) {
                jiraNamespace = 'MKE'
            } else if (image_key.startsWith('mirantis/dtr') || image_key.startsWith('mirantiseng/dtr')) {
                jiraNamespace = 'ENGDTR'
            } else {
                jiraNamespace = 'PRODX'
            }
            jira_summary = "[${image_key}] Found CVEs in Docker image"
            jira_description = "${image.key}\n"
            def filter_mke_severity = false
            image.value.each{
                pkg ->
                    jira_description += "__* ${pkg.key}\n"
                    pkg.value.each{
                        cve ->
                            jira_description += "________${cve}\n"
                            if (nvdApiUrl) {
                                def cveId = cve.replaceAll(/(^\[|\|.*$)/, '')
                                if (cveId.startsWith('CVE-')) {
                                    jira_description_nvd_scoring = nvdCacheLookUp(nvdDict, cveId)
                                    if (!jira_description_nvd_scoring) {
                                        jira_description_nvd_scoring = getNvdInfo(nvdApiUrl, cveId)
                                        if (jira_description_nvd_scoring) {
                                            nvdDict = nvdUpdateDictionary(nvdDict, cveId, jira_description_nvd_scoring)
                                        }
                                    }
                                    jira_description_nvd_scoring.each {
                                        jira_description += 'CVSS ' + it.join(' ') + '\n'
                                        // According to Vikram there will be no fixes for
                                        // CVEs with CVSS base score below 7
                                        if (jiraNamespace == 'MKE' && it[0] == 'V3' && it[1].toInteger() >= 7) {
                                            filter_mke_severity = true
                                        }
                                    }
                                    if (filter_mke_severity) {
                                        jira_description += nvdNistGovCveUrl + cveId + '\n'
                                    }
                                } else {
                                    print "No info about ${cveId} item from NVD API server"
                                }
                            } else {
                                print 'nvdApiUrl var is not specified.'
                            }
                    }
            }

            if (filter_mke_severity) {
                print "\n\nIgnoring ${image.key} as it does not have CVEs with CVSS base score >7\n"
                logInfo("Ignoring ${image.key} as it does not have CVEs with CVSS base score >7", jiraLog)
                return
            }

            def team_assignee = getTeam(image_key)

            def basicIssueJSON = new JsonSlurper().parseText('{"fields": {}}')

            basicIssueJSON['fields'] = [
                project:[
                    key:"${jiraNamespace}"
                ],
                summary:"${jira_summary}",
                description:"${jira_description}",
                issuetype:[
                    name:'Bug'
                ],
                labels:[
                    'security',
                    'cve'
                ]
            ]
            if (jiraNamespace == 'PRODX') {
                basicIssueJSON['fields']['customfield_19000'] = [value:"${team_assignee}"]
                basicIssueJSON['fields']['versions'] = [["name": affectedVersion]]
                if (image_key.startsWith('lcm/')) {
                    basicIssueJSON['fields']['components'] = [["name": 'KaaS: LCM']]
                }
            }

            if (jiraNamespace == 'MKE') {
                // Assign issues by default to Vikram bir Singh, as it was asked by him
                basicIssueJSON['fields']['assignee'] = ['accountId': '5ddd4d67b95b180d17cecc67']
            }

            def post_issue_json = JsonOutput.toJson(basicIssueJSON)
            def jira_comment = jira_description.replaceAll(/\n/, '\\\\n')
            def post_comment_json = """
{
    "body": "${jira_comment}"
}
"""
            def jira_key = cacheLookUp(dict, image_key, image.key)
            if (jira_key[0] && jira_key[1] == 'na') {
                def post_comment_response = callREST("${uri}/${jira_key[0]}/comment", auth, 'POST', post_comment_json)
                if ( post_comment_response['responseCode'] == 201 ) {
                    def issueCommentJSON = new JsonSlurper().parseText(post_comment_response["responseText"])
                    print "\n\nComment was posted to ${jira_key[0]} ${affectedVersion} for ${image_key} and ${image.key}"
                    logInfo("Comment was posted to ${jira_key[0]} ${affectedVersion} for ${image_key} and ${image.key}", jiraLog)
                } else {
                    print "\nComment to ${jira_key[0]} Jira issue was not posted"
                    logInfo("Comment to ${jira_key[0]} Jira issue was not posted", jiraLog)
                }
            } else if (!jira_key[0]) {
                def post_issue_response = callREST("${uri}/", auth, 'POST', post_issue_json)
                if (post_issue_response['responseCode'] == 201) {
                    def issueJSON = new JsonSlurper().parseText(post_issue_response["responseText"])
                    dict = updateDictionary(issueJSON['key'], dict, uri, auth, jiraUserID)
                    print "\n\nJira issue was created ${issueJSON['key']} ${affectedVersion} for ${image_key} and ${image.key}"
                    logInfo("Ignoring ${image.key} as it has been found in Docker image ignore list", jiraLog)
                } else {
                    print "\n${image.key} CVE issues were not published\n"
                    logInfo("Ignoring ${image.key} as it has been found in Docker image ignore list", jiraLog)
                }
            } else {
                print "\n\nNothing to process for ${image_key} and ${image.key}"
                logInfo("Nothing to process for ${image_key} and ${image.key}", jiraLog)
            }
    }
}

def find_cves_by_severity(String reportJsonContent, String Severity) {
    def cves = []
    def reportJSON = new JsonSlurper().parseText(reportJsonContent)
    reportJSON.each{
        image ->
            image.value.each{
                pkg ->
                    pkg.value.each{
                        cve ->
                            if (cve[2]) {
                                if (cve[1].contains(Severity)) {
                                    cves.add("${pkg.key} ${cve[0]} (${cve[2]})")
                                }
                            }
                    }
            }
    }
    return cves
}
