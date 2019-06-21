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
        username = env.BUILD_USER_ID ?: 'jenkins'
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
def getJobByName(jobName, regexp=false){
    for(item in Hudson.instance.items) {
        if (regexp && item.name ==~ jobName || item.name == jobName) {
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

/**
 * Get list of causes actions for given build
 *
 * @param build Job build object (like, currentBuild.rawBuild)
 * @return list of causes actions for given build
 */
@NonCPS
def getBuildCauseActions(build) {
    for(action in build.actions) {
        if (action instanceof hudson.model.CauseAction) {
            return action.causes
        }
    }
    return []
}

/**
 * Get list of builds, triggered by Gerrit with given build
 * @param build Job build object (like, currentBuild.rawBuild)
 * @return list of builds with names and numbers
 */
@NonCPS
def getGerritBuildContext(build) {
    def causes = getBuildCauseActions(build)
    for(cause in causes) {
        if (cause instanceof com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.GerritCause) {
            return cause.context.getOtherBuilds()
        }
    }
    return []
}

/**
 * Wait for other jobs
 * @param config config parameter:
 *   builds - List of job build objects, which should be checked
 *   checkBuilds - List of job names or regexps, which should be used to check provided builds list
 *   regexp - Wheither to use regexp or simple string matching
 */
@NonCPS
def waitForOtherBuilds(LinkedHashMap config){
  def context = config.get('context', 'gerrit')
  def builds = []
  if (context == 'gerrit') {
    builds = getGerritBuildContext(currentBuild.rawBuild)
  } else if (context == 'custom') {
    builds = config.get('builds')
  }
  def checkBuilds = config.get('checkBuilds')
  def regexp = config.get('regexp', false)

  def waitForBuilds = builds.findAll { build ->
    def jobName = build.fullDisplayName.tokenize(' ')[0]
    if (regexp) {
      checkBuilds.find { jobName ==~ it }
    } else {
      jobName in checkBuilds
    }
  }

  def buildsMap = []
  if (waitForBuilds) {
    def waiting = true
    print "\u001B[36mWaiting for next jobs: ${waitForBuilds}\u001B[0m"
    while(waiting) {
      waiting = false
      waitForBuilds.each { job ->
        if (job.inProgress) {
          waiting = true
        } else {
          buildInfo = [
              'jobName': job.fullDisplayName.tokenize(' ')[0],
              'jobNumber': job.number,
          ]
          buildsMap.add(buildInfo)
        }
      }
    }
  }
  return buildsMap
}
