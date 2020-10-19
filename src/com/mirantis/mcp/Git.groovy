package com.mirantis.mcp

/**
 * Parse HEAD of current directory and return commit hash
 */
def getGitCommit() {
    git_commit = sh(
            script: 'git rev-parse HEAD',
            returnStdout: true
    ).trim()
    return git_commit
}

/**
 * Describe a commit using the most recent tag reachable from it
 *
 * @param useShort Boolean, which String format returns as result.
 *              false (Default): {gitTag}-{numCommits}-g{gitsha}
 *              true:            {gitTag}-{numCommits}
 */
def getGitDescribe(Boolean useShort = false) {
    if (useShort) {
        // original sed "s/-g[0-9a-f]\+$//g" should be escaped in groovy
        git_commit = sh (
                script: 'git describe --tags | sed "s/-g[0-9a-f]\\+$//g"',
                returnStdout: true
        ).trim()
    } else {
        git_commit = sh (
                script: 'git describe --tags',
                returnStdout: true
        ).trim()
    }
    return git_commit
}

/**
 * Execute git clone+checkout stage for some project,
 * through SSH
 *
 * @param config LinkedHashMap
 *        config includes next parameters:
 *          - credentialsId, id of user which should make checkout(Jenkins Credential)
 *          - branch, branch of project
 *          - host, gerrit-ci hostname(Also, could be in format username@host)
 *          - project, name of project
 *          - targetDir, target directory of cloned repo
 *          - withMerge, prevent detached mode in repo
 *          - withWipeOut, wipe repository and force clone
 *          - protocol, protocol for git connection(http\https\ssh\file\etc)
 *          - refspec, A refspec controls the remote refs to be retrieved and how they map to local refs.
 *          If left blank, it will default to the normal behaviour of git fetch, which retrieves all the branch heads
 *          as remotes/REPOSITORYNAME/BRANCHNAME. This default behaviour is OK for most cases.
 *
 * Usage example:
 *
 * def gitFunc = new com.mirantis.mcp.Git()
 * gitFunc.gitSSHCheckout ([
 *   credentialsId : 'mcp-ci-gerrit',
 *   branch : 'mcp-0.1',
 *   host : 'user@ci.mcp-ci.local',
 *   project : 'project',
 * ])
 *
 * Example for Anon http:
 * def gitFunc = new com.mirantis.mcp.Git()
 * gitFunc.gitHTTPCheckout ([
 *   branch : 'master',
 *   host : 'ci.mcp-ci.local',
 *   project : 'project',
 * ])
 *
 */
def gitCheckout(LinkedHashMap config) {
  def merge = config.get('withMerge', false)
  def wipe = config.get('withWipeOut', false)
  def targetDir = config.get('targetDir', "./")
  def port = config.get('port', "29418")
  def credentialsId = config.get('credentialsId', '')
  def protocol = config.get('protocol', 'ssh')
  def refspec = config.get('refspec', null)
  String branch = config.get('branch', 'FETCH_HEAD')
  Integer depth = config.get('depth', 0)
  Integer timeout = config.get('timeout', 0)

  // default parameters
  def scmExtensions = [
    [$class: 'CleanCheckout'],
    [$class: 'RelativeTargetDirectory', relativeTargetDir: "${targetDir}"]
  ]

  // https://issues.jenkins-ci.org/browse/JENKINS-6856
  if (merge) {
    scmExtensions.add([$class: 'LocalBranch', localBranch: "${branch}"])
  }

  // we need wipe workspace before checkout
  if (wipe) {
    scmExtensions.add([$class: 'WipeWorkspace'])
  }

  // optionally limit depth of checkout
  if (depth) {
    scmExtensions.add([$class: 'CloneOption', depth: "${depth}", shallow: 'true'])
  }

  // optionally set timeout
  if (timeout) {
    scmExtensions.add([$class: 'CloneOption', timeout: "${timeout}"])
  }

  checkout(
    scm: [
      $class: 'GitSCM',
      branches: [[name: "${branch}"]],
      extensions: scmExtensions,
      userRemoteConfigs: [[
        credentialsId: credentialsId,
        refspec: refspec,
        name: 'origin',
        url: "${protocol}://${config.host}:${port}/${config.project}.git"
      ]]
    ]
  )
}

def gitSSHCheckout(LinkedHashMap config) {
  config['protocol'] = config.get('protocol', 'ssh')
  config['port'] = config.get('port', 29418)
  gitCheckout(config)
}

def gitHTTPCheckout(LinkedHashMap config) {
  config['protocol'] = config.get('protocol', 'http')
  config['port'] = config.get('port', 80)
  gitCheckout(config)
}

/**
 * Execute git clone and checkout stage from gerrit review
 *
 * @param config LinkedHashMap
 *        config includes next parameters:
 *          - credentialsId, id of user which should make checkout
 *          - withMerge, prevent detached mode in repo
 *          - withWipeOut, wipe repository and force clone
 *
 * Usage example:
 * //anonymous gerrit checkout
 * def gitFunc = new com.mirantis.mcp.Git()
 * gitFunc.gerritPatchsetCheckout([
 *   withMerge : true
 * ])
 *
 * def gitFunc = new com.mirantis.mcp.Git()
 * gitFunc.gerritPatchsetCheckout([
 *   credentialsId : 'mcp-ci-gerrit',
 *   withMerge : true
 * ])
 */
def gerritPatchsetCheckout(LinkedHashMap config) {
  def merge = config.get('withMerge', false)
  def wipe = config.get('withWipeOut', false)
  def credentials = config.get('credentialsId','')

  // default parameters
  def scmExtensions = [
    [$class: 'CleanCheckout'],
    [$class: 'BuildChooserSetting', buildChooser: [$class: 'GerritTriggerBuildChooser']]
  ]
  def scmUserRemoteConfigs = [
        name: 'gerrit',
        refspec: "${GERRIT_REFSPEC}"
  ]

  if (credentials == '') {
    // then try to checkout in anonymous mode
    scmUserRemoteConfigs.put('url',"https://${GERRIT_HOST}/${GERRIT_PROJECT}")
  } else {
    // else use ssh checkout
    scmUserRemoteConfigs.put('url',"ssh://${GERRIT_HOST}:${GERRIT_PORT}/${GERRIT_PROJECT}.git")
    scmUserRemoteConfigs.put('credentialsId',credentials)
  }

  // if we need to "merge" code from patchset to GERRIT_BRANCH branch
  if (merge) {
    scmExtensions.add([$class: 'LocalBranch', localBranch: "${GERRIT_BRANCH}"])
  }
  // we need wipe workspace before checkout
  if (wipe) {
    scmExtensions.add([$class: 'WipeWorkspace'])
  }

  checkout(
    scm: [
      $class: 'GitSCM',
      branches: [[name: "${GERRIT_BRANCH}"]],
      extensions: scmExtensions,
      userRemoteConfigs: [scmUserRemoteConfigs]
    ]
  )
}
