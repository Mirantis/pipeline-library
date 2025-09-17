package com.mirantis.mk
import com.cloudbees.groovy.cps.NonCPS
import org.jenkins.plugins.lockableresources.LockableResourcesManager as LRM

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
    def res = []
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
    def username = ''
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
    def username = currentUsername()
    return userInGroups(username, groups)
}

/**
 * Check if current user belongs to group
 * @param group String
 * @return boolean result
 */
def currentUserInGroup(group) {
    def username = currentUsername()
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

/**
 * Check dependency jobs passed successfully

 * @param block           (bool) Block child jobs in case of parent dependencies failed
 * @param allowNotBuilt   (bool) Approve not_built status of the dependency job
 * @return                (map)[
 *                            status: (bool) True if there are no failed dependencies
 *                            log: (string) Verbose description
 *                           ]
 */
def checkDependencyJobs(block = true, allowNotBuilt = false) {
    def common = new com.mirantis.mk.Common()
    def acceptedStatuses = ['SUCCESS']
    if (allowNotBuilt) {
        acceptedStatuses.add('NOT_BUILT')
    }

    ArrayList depList = []
    if (env.TRIGGER_DEPENDENCY_KEYS){
        common.infoMsg('Job may depends on parent jobs, check if dependency jobs exist...')
        String depKeys = env.TRIGGER_DEPENDENCY_KEYS.toString()
        depList = depKeys.split()
        if (depList){
            common.infoMsg("Here is dependency jobs-list: ${depList} , accepted job statuses are: ${acceptedStatuses}")
            for (String item : depList) {
                String prjName = item.replaceAll('[^a-zA-Z0-9]+', '_')
                String triggerResult = 'TRIGGER_' + prjName.toUpperCase() + '_BUILD_RESULT'
                String triggerJobName = 'TRIGGER_' + prjName.toUpperCase() + '_BUILD_NAME'
                String triggerJobBuild = 'TRIGGER_' + prjName.toUpperCase() + '_BUILD_NUMBER'
                if (!acceptedStatuses.contains(env.getProperty(triggerResult))) {
                    msg = "Dependency job ${env.getProperty(triggerJobName)} #${env.getProperty(triggerJobBuild)} is ${env.getProperty(triggerResult)}"
                    common.warningMsg(msg)
                    if (block){
                        currentBuild.result = 'NOT_BUILT'
                        currentBuild.description = msg
                    }
                    return [status: false, log: msg, jobs: depList]
                }
            }
        }
    } else {
        common.infoMsg('There is no job-dependencies')
    }
    return [status: true, log: '', jobs: depList]
}

/**
 *  Return jenkins infra metadata according to specified jenkins intstance

 * @param jenkinsServerURL  (string) URL to jenkins server in form: env.JENKINS_URL
 * @return                  (map)[
 *                              jenkins_service_user: (string) name of jenkins user needed for gerrit ops
 *                             ]
 */
def getJenkinsInfraMetadata(jenkinsServerURL) {
    def meta = [
        jenkins_service_user: '',
    ]

    switch (jenkinsServerURL) {
        case 'https://ci.mcp.mirantis.net/':
            meta['jenkins_service_user'] = 'mcp-jenkins'
            break
        case 'https://mcc-ci.infra.mirantis.net/':
            meta['jenkins_service_user'] = 'mcc-ci-jenkins'
            break
        default:
            error("Failed to detect jenkins service user, supported jenkins platforms: 'https://ci.mcp.mirantis.net/' 'https://mcc-ci.infra.mirantis.net/'")
    }

    return meta
}

/**
 * Get list of all jenkins workers matched desired label
 *
 * @param labelString     (string) desired worker label
 * @return                (list) all workers, currently matched label
 */
@NonCPS
def getWorkers(String labelString = null) {
    def workerLabel = hudson.model.labels.LabelAtom.get(labelString)
    def workers = []
    hudson.model.Hudson.instance.slaves.each {
        if (it.getComputer().isOnline()) {
            if (workerLabel) {
                if (workerLabel in it.getAssignedLabels()) {
                    workers << it.name
                }
            } else {
                // if labelString is null, getting all workers
                workers << it.name
            }
        }
    }
    return workers
}

/**
 * Get deployment environment and related jenkins lock label and lock resource
 *
 * @param initialEnv        (string) Name of initially requested environment e.g. imc-eu or auto
 * @param namespace         (string) Name of environment namespace e.g imc-oscore-team
 * @param resources         (int)    Quantity of required lockable resources
 * @param candidateEnvs     (list)   List of names of env candidates to choose between
 * @return                  (list)   List whith environment name, lock label and lock resource
 */
def getEnvWithLockableResources(initialEnv, namespace, resources = 1, candidateEnvs = ["imc-eu", "imc-us"]){
    def common = new com.mirantis.mk.Common()
    def lockResource = null
    def env = initialEnv
    def lockLabel = "${namespace}-${env}"
    def lrm = LRM.get()
    if (initialEnv == "auto"){
        def freeResources = [:]
        for (cEnv in candidateEnvs){
            def label = "${namespace}-${cEnv}"
            freeResources[label] = lrm.getFreeResourceAmount(label)
        }
        common.infoMsg("Detecting target environment from candidates ${freeResources}")
        def max = 0
        def keys = freeResources.keySet().toList()
        Collections.shuffle(keys)
        for (key in keys){
            if (freeResources[key] >= max){
                max = freeResources[key]
                lockLabel = key
            }
        }
        if (max < resources){
            lockLabel = keys[0]
        }
        env = lockLabel.replaceAll("${namespace}-", "")
        common.infoMsg("Detected target environment ${env} lock ${lockLabel}")
    }
    // If no label configured on existing resources, create random lockresource
    if (! lrm.isValidLabel(lockLabel)){
        common.infoMsg("Running without locking, lock label ${lockLabel} does not exist")
        lockLabel = null
        lockResource = UUID.randomUUID().toString()
    }
    return [env, lockLabel, lockResource]
}
