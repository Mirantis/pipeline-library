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
def getJobRunningBuilds(jobName){
  return Jenkins.instance.items.find{it -> it.name.equals(jobName)}.builds.findAll{build -> build.isBuilding()}
}