package com.mirantis.mk
import com.cloudbees.groovy.cps.NonCPS

/**
 *
 * Jenkins common functions
 *
 */

/**
 * Tests if current user belongs to given group
 * @param groupName name of the group you want to verify user presence
 * @return boolean result
 */
def currentUserInGroup(groupName){
    return currentUserInGroups([groupName])
}
/**
 * Tests if current user belongs to at least one of given groups
 * @param groups list of group names you want to verify user presence
 * @return boolean result
 */
def currentUserInGroups(groups){
    def hasAccess = false
    wrap([$class: 'BuildUser']) {
        def authorities = Jenkins.instance.securityRealm.loadUserByUsername(BUILD_USER).getAuthorities()
        for(int i=0;i < authorities.size();i++){
            if(groups.contains(authorities[i])){
                hasAccess=true
                break
            }
        }
    }
    return hasAccess
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
