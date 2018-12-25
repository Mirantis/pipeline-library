package com.mirantis.mk
import com.cloudbees.groovy.cps.NonCPS

/**
 *
 * Jenkins common functions
 *
 */

/**
 * Returns a list of groups which user belongs
 * @param username String
 * @return list of groups [String]
 */
def userGroups(username) {
    res = []
    def authorities = Jenkins.instance.securityRealm.loadUserByUsername(username).getAuthorities()
    authorities.each {
        res.add(it.toString())
    }
    return res
}

/**
 * Check if user belongs to group
 * @param username String
 * @param group String
 * @return boolean result
 */
def userInGroup(username, group) {
    def authorities = userGroups(username)
    return authorities.any{it==group}
}

/**
 * Check if user belongs to at least one of given groups
 * @param username String
 * @param groups [String]
 * @return boolean result
 */
def userInGroups(username, groups) {
    return groups.any{userInGroup(username, it)}
}

/**
 * Returns current username from build
 * @return username String
 */
def currentUsername() {
    username = ''
    wrap([$class: 'BuildUser']) {
        username = BUILD_USER
    }
    if (username) {
        return username
    } else {
        throw new Exception('cant get  current username')
    }
}

/**
 * Check if current user belongs to at least one of given groups
 * @param groups [String]
 * @return boolean result
 */
def currentUserInGroups(groups) {
    username = currentUsername()
    return userInGroups(username, groups)
}

/**
 * Check if current user belongs to group
 * @param group String
 * @return boolean result
 */
def currentUserInGroup(group) {
    username = currentUsername()
    return userInGroup(username, group)
}

/**
 * Get Jenkins job running builds
 * @param jobName job name
 * @return list of running builds
 */
@NonCPS
def getJobRunningBuilds(jobName){
    def job = Jenkins.instance.items.find{it -> it.name.equals(jobName)}
    if(job){
        return job.builds.findAll{build -> build.isBuilding()}
    }
    return []
}

@NonCPS
def getRunningBuilds(job){
    return job.builds.findAll{build -> build.isBuilding()}
}

@NonCPS
def killStuckBuilds(maxSeconds, job){
    def common = new com.mirantis.mk.Common()
    def result = true
    def runningBuilds = getRunningBuilds(job)
    def jobName = job.name
    for(int j=0; j < runningBuilds.size(); j++){
        int durationInSeconds = (System.currentTimeMillis() - runningBuilds[j].getTimeInMillis())/1000.0
        if(durationInSeconds > maxSeconds){
            result = false
            def buildId = runningBuilds[j].id
            common.infoMsg("Aborting ${jobName}-${buildId} which is running for ${durationInSeconds}s")
            try{
                runningBuilds[j].finish(hudson.model.Result.ABORTED, new java.io.IOException("Aborting build by long running jobs killer"));
                result = true
            }catch(e){
                common.errorMsg("Error occured during aborting build: Exception: ${e}")
            }
        }
    }
    return result
}

/**
 * Get Jenkins job object
 * @param jobName job name
 * @return job object that matches jobName
 */
def getJobByName(jobName){
    for(item in Hudson.instance.items) {
        if(item.name == jobName){
            return item
        }
    }
}

/**
 * Get Jenkins job parameters
 * @param jobName job name
 * @return HashMap with parameter names as keys and their values as values
 */
def getJobParameters(jobName){
    def job = getJobByName(jobName)
    def prop = job.getProperty(ParametersDefinitionProperty.class)
    def params = new java.util.HashMap<String,String>()
    if(prop != null) {
        for(param in prop.getParameterDefinitions()) {
            params.put(param.name, param.defaultValue)
        }
    }
    return params
}
