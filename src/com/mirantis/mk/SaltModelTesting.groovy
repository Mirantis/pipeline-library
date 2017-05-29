package com.mirantis.mk

/**
 * setup and test salt-master
 *
 * @param masterName          salt master's name
 * @param extraFormulas       extraFormulas to install
 * @param testDir             directory of model
 */

def setupAndTestNode(masterName, extraFormulas, testDir) {
  def saltOpts = "--retcode-passthrough --force-color"
  def common = new com.mirantis.mk.Common()
  def workspace = common.getWorkspace()
  def imageFound = true
  def img
  try {
    img = docker.image("tcpcloud/salt-models-testing")
    img.pull()
  } catch (Throwable e) {
    img = docker.image("ubuntu:latest")
    imageFound = false
  }

  if (!extraFormulas || extraFormulas == "") {
    extraFormulas = "linux"
  }

  img.inside("-u root:root --hostname=${masterName}") {

    def is_mk_ci
    try {
      is_mk_ci = DEFAULT_GIT_URL.contains("mk-ci")
    } catch (Throwable e) {
      is_mk_ci = false
    }

    wrap([$class: 'AnsiColorBuildWrapper']) {
      if (!imageFound) {
        sh("apt-get update && apt-get install -y curl subversion git python-pip sudo python-pip python-dev zlib1g-dev git")
        sh("pip install git+https://github.com/epcim/reclass.git@pr/fix/fix_raise_UndefinedVariableError")
      }
      sh("mkdir -p /srv/salt/ || true")
      sh("cp -r ${testDir} /srv/salt/reclass")
      sh("svn export --force https://github.com/salt-formulas/salt-formulas/trunk/deploy/scripts /srv/salt/scripts")
      sh("git config --global user.email || git config --global user.email 'ci@ci.local'")
      sh("git config --global user.name || git config --global user.name 'CI'")

      withEnv(["FORMULAS_SOURCE=pkg", "EXTRA_FORMULAS=${extraFormulas}", "DEBUG=1", "MASTER_HOSTNAME=${masterName}", "MINION_ID=${masterName}", "HOSTNAME=cfg01", "DOMAIN=mk-ci.local"]){
          sh("bash -c 'echo $MASTER_HOSTNAME'")
          sh("bash -c 'source /srv/salt/scripts/salt-master-init.sh; cd /srv/salt/scripts && system_config'")
          sh("bash -c 'source /srv/salt/scripts/salt-master-init.sh; cd /srv/salt/scripts && saltmaster_bootstrap'")
          sh("bash -c 'source /srv/salt/scripts/salt-master-init.sh; cd /srv/salt/scripts && saltmaster_init'")

          if (!is_mk_ci) {
             sh("bash -c 'source /srv/salt/scripts/salt-master-init.sh; cd /srv/salt/scripts && verify_salt_minions'")
          }
      }

      if (is_mk_ci) {
        def nodes = sh script: "find /srv/salt/reclass/nodes -name '*.yml' | grep -v 'cfg*.yml'", returnStdout: true
        for (minion in nodes.tokenize()) {
          def basename = sh script: "basename ${minion} .yml", returnStdout: true
          if (!basename.trim().contains(masterName)) {
            testMinion(basename.trim())
          }
        }
      }

    }
  }
}

/**
 * Test salt-minion
 *
 * @param minion          salt minion
 */

def testMinion(minionName)
{
  sh("service salt-master restart && service salt-minion restart && sleep 5 && bash -c 'source /srv/salt/scripts/salt-master-init.sh; cd /srv/salt/scripts && verify_salt_minion ${minionName}'")
}