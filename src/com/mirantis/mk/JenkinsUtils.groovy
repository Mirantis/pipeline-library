package com.mirantis.mk

/**
 *
 * Jenkins common functions
 *
 */

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