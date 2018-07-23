package com.mirantis.mk

/**
 * setup and test salt-master
 *
 * @param masterName          salt master's name
 * @param clusterName         model cluster name
 * @param extraFormulas       extraFormulas to install
 * @param formulasSource      formulas source (git or pkg)
 * @param reclassVersion      Version of used reclass (branch, tag, ...) (optional, default master)
 * @param testDir             directory of model
 * @param formulasSource      Salt formulas source type (optional, default pkg)
 * @param formulasRevision    APT revision for formulas (optional default stable)
 * @param ignoreClassNotfound Ignore missing classes for reclass model
 * @param dockerMaxCpus       max cpus passed to docker (default 0, disabled)
 * @param legacyTestingMode   do you want to enable legacy testing mode (iterating through the nodes directory definitions instead of reading cluster models)
 * @param aptRepoUrl          package repository with salt formulas
 * @param aptRepoGPG          GPG key for apt repository with formulas
 * Return                     true | false
 */

def setupAndTestNode(masterName, clusterName, extraFormulas, testDir, formulasSource = 'pkg',
                     formulasRevision = 'stable', reclassVersion = "1.5.4", dockerMaxCpus = 0,
                     ignoreClassNotfound = false, legacyTestingMode = false, aptRepoUrl = '', aptRepoGPG = '', dockerContainerName = false) {
  // timeout for test execution (40min)
  def testTimeout = 40 * 60
  def TestMarkerResult = false
  def saltOpts = "--retcode-passthrough --force-color"
  def common = new com.mirantis.mk.Common()
  def workspace = common.getWorkspace()
  def img = docker.image("mirantis/salt:saltstack-ubuntu-xenial-salt-2017.7")
  img.pull()

  if (!extraFormulas || extraFormulas == "") {
    extraFormulas = "linux"
  }
  if (!dockerContainerName) {
    dockerContainerName = 'setupAndTestNode' + UUID.randomUUID().toString()
  }
  def dockerMaxCpusOpt = "--cpus=4"
  if (dockerMaxCpus > 0) {
    dockerMaxCpusOpt = "--cpus=${dockerMaxCpus}"
  }
  try {
    img.inside("-u root:root --hostname=${masterName} --ulimit nofile=4096:8192 ${dockerMaxCpusOpt} --name=${dockerContainerName}") {
      withEnv(["FORMULAS_SOURCE=${formulasSource}", "EXTRA_FORMULAS=${extraFormulas}", "DISTRIB_REVISION=${formulasRevision}",
               "DEBUG=1", "MASTER_HOSTNAME=${masterName}", "CLUSTER_NAME=${clusterName}", "MINION_ID=${masterName}",
               "RECLASS_VERSION=${reclassVersion}", "RECLASS_IGNORE_CLASS_NOTFOUND=${ignoreClassNotfound}", "APT_REPOSITORY=${aptRepoUrl}",
               "APT_REPOSITORY_GPG=${aptRepoGPG}", "SALT_STOPSTART_WAIT=10"]) {
        sh(script: "git clone https://github.com/salt-formulas/salt-formulas-scripts /srv/salt/scripts", returnStdout: true)
        sh("""rsync -ah ${testDir}/* /srv/salt/reclass && echo '127.0.1.2  salt' >> /etc/hosts
              cd /srv/salt && find . -type f \\( -name '*.yml' -or -name '*.sh' \\) -exec sed -i 's/apt-mk.mirantis.com/apt.mirantis.net:8085/g' {} \\;
              cd /srv/salt && find . -type f \\( -name '*.yml' -or -name '*.sh' \\) -exec sed -i 's/apt.mirantis.com/apt.mirantis.net:8085/g' {} \\;""")
        sh("""for s in \$(python -c \"import site; print(' '.join(site.getsitepackages()))\"); do
                sudo -H pip install --install-option=\"--prefix=\" --upgrade --force-reinstall -I \
                    -t \"\$s\" git+https://github.com/salt-formulas/reclass.git@${reclassVersion};
                done""")
        timeout(time: testTimeout, unit: 'SECONDS') {
          sh('''#!/bin/bash
                source /srv/salt/scripts/bootstrap.sh
                cd /srv/salt/scripts
                source_local_envs
                configure_salt_master
                configure_salt_minion
                install_salt_formula_pkg
                source /srv/salt/scripts/bootstrap.sh
                cd /srv/salt/scripts
                saltservice_restart''')
          sh('''#!/bin/bash
                source /srv/salt/scripts/bootstrap.sh
                cd /srv/salt/scripts
                source_local_envs
                saltmaster_init''')

          if (!legacyTestingMode.toBoolean()) {
            sh('''#!/bin/bash
                  source /srv/salt/scripts/bootstrap.sh
                  cd /srv/salt/scripts
                  verify_salt_minions''')
          }
        }
        // If we didn't dropped for now - test has been passed.
        TestMarkerResult = true
      }
    }
  }
  catch (Exception er) {
    common.warningMsg("IgnoreMe:Something wrong with img.Message:\n" + er.toString())
  }

  if (legacyTestingMode.toBoolean()) {
    common.infoMsg("Running legacy mode test for master hostname ${masterName}")
    def nodes = sh(script: "find /srv/salt/reclass/nodes -name '*.yml' | grep -v 'cfg*.yml'", returnStdout: true)
    for (minion in nodes.tokenize()) {
      def basename = sh(script: "set +x;basename ${minion} .yml", returnStdout: true)
      if (!basename.trim().contains(masterName)) {
        testMinion(basename.trim())
      }
    }
  }

  try {
    common.warningMsg("IgnoreMe:Force cleanup slave.Ignore docker-daemon errors")
    timeout(time: 10, unit: 'SECONDS') {
      sh(script: "set -x; docker kill ${dockerContainerName} || true", returnStdout: true)
    }
    timeout(time: 10, unit: 'SECONDS') {
      sh(script: "set -x; docker rm --force ${dockerContainerName} || true", returnStdout: true)
    }
  }
  catch (Exception er) {
    common.warningMsg("IgnoreMe:Timeout to delete test docker container with force!Message:\n" + er.toString())
  }

  if (TestMarkerResult) {
    common.infoMsg("Test finished: SUCCESS")
  } else {
    common.warningMsg("Test finished: FAILURE")
  }
  return TestMarkerResult

}

/**
 * Test salt-minion
 *
 * @param minion salt minion
 */

def testMinion(minionName) {
  sh(script: "bash -c 'source /srv/salt/scripts/bootstrap.sh; cd /srv/salt/scripts && verify_salt_minion ${minionName}'", returnStdout: true)
}
