#!groovy

package com.mirantis.mk

import groovy.json.JsonSlurper

def callREST (String uri, String auth,
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

def getTeam (String image = '') {
    def team_assignee = ''
    switch(image) {
        case ~/^(tungsten|tungsten-operator)\/.*$/:
            team_assignee = 'OpenContrail'
            break
        case ~/^bm\/.*$/:
            team_assignee = 'BM/OS (KaaS BM)'
            break
        case ~/^openstack\/.*$/:
            team_assignee = 'OpenStack hardening'
            break
        case ~/^stacklight\/.*$/:
            team_assignee = 'Stacklight LMA'
            break
        case ~/^ceph\/.*$/:
            team_assignee = 'Storage'
            break
        case ~/^iam\/.*$/:
            team_assignee = 'KaaS'
            break
        case ~/^lcm\/.*$/:
            team_assignee = 'Kubernetes'
            break
        default:
            team_assignee = 'Release Engineering'
            break
    }

    return team_assignee
}

def updateDictionary (String jira_issue_key, Map dict, String uri, String auth, String jira_user_id) {
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
                def s = dict[issue_key_name.key]['summary'] =~ /\b${image_short_name}\b/
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

def reportJiraTickets(String pathToResultsJSON, String jiraCredentialsID, String jiraUserID) {

    def dict = [:]

    def cred = common.getCredentialsById(jiraCredentialsID)
    def auth = "${cred.username}:${cred.password}"
    def uri = "${cred.description}/rest/api/2/issue"

    def search_api_url = "${cred.description}/rest/api/2/search"

    def search_json = """
{
    "jql": "reporter = ${jiraUserID} and (labels = cve and labels = security) and (status = 'To Do' or status = 'For Triage' or status = Open or status = 'In Progress')"
}
"""

    def response = callREST("${search_api_url}", auth, 'POST', search_json)

    def InputJSON = new JsonSlurper().parseText(response["responseText"])

    InputJSON['issues'].each {
        dict[it['key']] = [
                summary : '',
                description: '',
                comments: []
        ]
    }

    InputJSON['issues'].each { jira_issue ->
        dict = updateDictionary(jira_issue['key'], dict, uri, auth, jiraUserID)
        sleep(1000)
    }

    def reportFile = new File(pathToResultsJSON)
    def reportJSON = new JsonSlurper().parseText(reportFile.text)
    def imageDict = [:]
    def cves = []
    reportJSON.each{
        image ->
            if ("${image.value}".contains('issues')) { return }
            image.value.each{
                pkg ->
                    cves = []
                    pkg.value.each{
                        cve ->
                            if (cve[2] && (cve[1].contains('High') || cve[1].contains('Critical'))) {
                                if (!imageDict.containsKey("${image.key}")) {
                                    imageDict.put(image.key, [:])
                                }
                                if (!imageDict[image.key].containsKey(pkg.key)) {
                                    imageDict[image.key].put(pkg.key, [])
                                }
                                cves.add("${cve[0]} (${cve[2]})")
                            }
                    }
                    if (cves) {
                        imageDict[image.key] = [
                                "${pkg.key}": cves
                        ]
                    }
            }
    }

    def jira_summary = ''
    def jira_description = ''
    imageDict.each{
        image ->
            def image_key = image.key.replaceAll(/(^[a-z0-9-.]+.mirantis.(net|com)\/|:.*$)/, '')
            // Temporary exclude tungsten images
            if (image_key.startsWith('tungsten/') || image_key.startsWith('tungsten-operator/')) { return }
            jira_summary = "[${image_key}] Found CVEs in Docker image"
            jira_description = "{noformat}${image.key}\\n"
            image.value.each{
                pkg ->
                    jira_description += "  * ${pkg.key}\\n"
                    pkg.value.each{
                        cve ->
                            jira_description += "      - ${cve}\\n"
                    }
            }
            jira_description += "{noformat}"

            def team_assignee = getTeam(image_key)

            def post_issue_json = """
{
    "fields": {
        "project": {
            "key": "PRODX"
        },
        "summary": "${jira_summary}",
        "description": "${jira_description}",
        "issuetype": {
            "name": "Bug"
        },
        "labels": [
            "security",
            "cve"
        ],
        "customfield_19000": {
            "value": "${team_assignee}"
        },
        "versions": [
            {
                "name": "Backlog"
            }
        ]
    }
}
"""
            def post_comment_json = """
{
    "body": "${jira_description}"
}
"""
            def jira_key = cacheLookUp(dict, image_key, image.key)
            if (jira_key[0] && jira_key[1] == 'na') {
                def post_comment_response = callREST("${uri}/${jira_key[0]}/comment", auth, 'POST', post_comment_json)
                if ( post_comment_response['responseCode'] == 201 ) {
                    def issueCommentJSON = new JsonSlurper().parseText(post_comment_response["responseText"])
                } else {
                    print "\nComment to ${jira_key[0]} Jira issue was not posted"
                }
            } else if (!jira_key[0]) {
                def post_issue_response = callREST("${uri}/", auth, 'POST', post_issue_json)
                if (post_issue_response['responseCode'] == 201) {
                    def issueJSON = new JsonSlurper().parseText(post_issue_response["responseText"])
                    dict = updateDictionary(issueJSON['key'], dict, uri, auth, jiraUserID)
                } else {
                    print "\n${image.key} CVE issues were not published\n"
                }
            } else {
                print "\n\nNothing to process for for ${image_key} and ${image.key}"
            }
            sleep(10000)
    }
}
