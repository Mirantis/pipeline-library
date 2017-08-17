package com.mirantis.mk

/**
 * setup and test salt-master
 *
 * @param masterName          salt master's name
 * @param extraFormulas       extraFormulas to install
 * @param formulasSource      formulas source (git or pkg)
 * @param testDir             directory of model
 * @param formulasSource      Salt formulas source type (optional, default pkg)
 * @param formulasRevision    APT revision for formulas (optional default stable)
 */

def setupAndTestNode(masterName, extraFormulas, testDir, formulasSource = 'pkg', formulasRevision = 'stable', dockerMaxCpus = 0) {
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

  def dockerMaxCpusOption = ""
  if (dockerMaxCpus > 0) {
    dockerMaxCpusOption = "--cpus=${dockerMaxCpus}"
  }

  img.inside("-u root:root --hostname=${masterName} --ulimit nofile=4096:8192 ${dockerMaxCpusOption}") {

    def is_mk_ci
    try {
      is_mk_ci = DEFAULT_GIT_URL.contains("mk-ci")
    } catch (Throwable e) {
      is_mk_ci = false
    }

    if (!imageFound) {
      sh("apt-get update && apt-get install -y curl subversion git python-pip sudo python-pip python-dev zlib1g-dev git")
      sh("pip install git+https://github.com/salt-formulas/reclass.git")
    }
    sh("mkdir -p /srv/salt/scripts/ || true")
    sh("cp -r ${testDir} /srv/salt/reclass")
    sh("git config --global user.email || git config --global user.email 'ci@ci.local'")
    sh("git config --global user.name || git config --global user.name 'CI'")
    sh("git clone https://github.com/salt-formulas/salt-formulas-scripts /srv/salt/scripts")

    withEnv(["FORMULAS_SOURCE=${formulasSource}", "EXTRA_FORMULAS=${extraFormulas}", "DISTRIB_REVISION=${formulasRevision}", "DEBUG=1", "MASTER_HOSTNAME=${masterName}", "MINION_ID=${masterName}", "HOSTNAME=cfg01", "DOMAIN=mk-ci.local"]){
        sh("bash -c 'echo $MASTER_HOSTNAME'")
        sh("bash -c 'source /srv/salt/scripts/bootstrap.sh; cd /srv/salt/scripts && source_local_envs && system_config_master'")
        sh("bash -c 'source /srv/salt/scripts/bootstrap.sh; cd /srv/salt/scripts && source_local_envs && saltmaster_bootstrap'")
        sh("bash -c 'source /srv/salt/scripts/bootstrap.sh; cd /srv/salt/scripts && source_local_envs && saltmaster_init'")

        if (!is_mk_ci) {
           sh("bash -c 'source /srv/salt/scripts/bootstrap.sh; cd /srv/salt/scripts && verify_salt_minions'")
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

/**
 * Test salt-minion
 *
 * @param minion          salt minion
 */

def testMinion(minionName)
{
  sh("service salt-master restart && service salt-minion restart && sleep 5 && bash -c 'source /srv/salt/scripts/bootstrap.sh; cd /srv/salt/scripts && verify_salt_minion ${minionName}'")
}
