package com.mirantis.mk

/**
 * Setup Docker to run some tests. Returns true/false based on
 were tests successful or not.
 * @param config - LinkedHashMap with configuration params:
 *   dockerHostname - (required) Hostname to use for Docker container.
 *   distribRevision - (optional) Revision of packages to use (default proposed).
 *   runCommands - (optional) Dict with closure structure of body required tests. For example:
 *     [ '001_Test': { sh("./run-some-test") }, '002_Test': { sh("./run-another-test") } ]
 *     Before execution runCommands will be sorted by key names. Alpabetical order is preferred.
 *   runFinally - (optional) Dict with closure structure of body required commands, which should be
 *     executed in any case of test results. Same format as for runCommands
 *   updateRepo - (optional) Whether to run common repo update step.
 *   dockerContainerName - (optional) Docker container name.
 *   dockerImageName - (optional) Docker image name
 *   dockerMaxCpus - (optional) Number of CPUS to use in Docker.
 *   dockerExtraOpts - (optional) Array of Docker extra opts for container
 *   envOpts - (optional) Array of variables that should be passed as ENV vars to Docker container.
 * Return true | false
 */

def setupDockerAndTest(LinkedHashMap config) {
    def common = new com.mirantis.mk.Common()
    def TestMarkerResult = false
    // setup options
    def defaultContainerName = 'test-' + UUID.randomUUID().toString()
    def dockerHostname = config.get('dockerHostname', defaultContainerName)
    def distribRevision = config.get('distribRevision', 'proposed')
    def runCommands = config.get('runCommands', [:])
    def runFinally = config.get('runFinally', [:])
    def baseRepoPreConfig = config.get('baseRepoPreConfig', true)
    def dockerContainerName = config.get('dockerContainerName', defaultContainerName)
    //  def dockerImageName = config.get('image', "mirantis/salt:saltstack-ubuntu-xenial-salt-2017.7")
    // FIXME /PROD-25244
    def dockerImageName = config.get('image', "docker-prod-local.artifactory.mirantis.com/mirantis/salt:saltstack-ubuntu-xenial-salt-2017.7")
    def dockerMaxCpus = config.get('dockerMaxCpus', 4)
    def dockerExtraOpts = config.get('dockerExtraOpts', [])
    def envOpts = config.get('envOpts', [])
    envOpts.add("DISTRIB_REVISION=${distribRevision}")
    def dockerBaseOpts = [
        '-u root:root',
        "--hostname=${dockerHostname}",
        '--ulimit nofile=4096:8192',
        "--name=${dockerContainerName}",
        "--cpus=${dockerMaxCpus}"
    ]
    def dockerOptsFinal = (dockerBaseOpts + dockerExtraOpts).join(' ')
    def extraReposConfig = null
    if (baseRepoPreConfig) {
        // extra repo on mirror.mirantis.net, which is not supported before 2018.11.0 release
        def extraRepoSource = "deb [arch=amd64] http://mirror.mirantis.com/${distribRevision}/extra/xenial xenial main"
        def releaseVersionQ4 = '2018.11.0'
        def oldRelease = false
        try {
            def releaseNaming = 'yyyy.MM.dd'
            def repoDateUsed = new Date().parse(releaseNaming, distribRevision)
            def extraAvailableFrom = new Date().parse(releaseNaming, releaseVersionQ4)
            if (repoDateUsed < extraAvailableFrom) {
                extraRepoSource = "deb http://apt.mcp.mirantis.net/xenial ${distribRevision} extra"
                oldRelease = true
            }
        } catch (Exception e) {
            common.warningMsg(e)
            if (!(distribRevision in ['nightly', 'proposed', 'testing'])) {
                extraRepoSource = "deb [arch=amd64] http://apt.mcp.mirantis.net/xenial ${distribRevision} extra"
                oldRelease = true
            }
        }

        def defaultExtraReposYaml = """
---
aprConfD: |-
  APT::Get::AllowUnauthenticated 'true';
  APT::Get::Install-Suggests 'false';
  APT::Get::Install-Recommends 'false';
repo:
  mcp_saltstack:
    source: "deb [arch=amd64] http://mirror.mirantis.com/${distribRevision}/saltstack-2017.7/xenial xenial main"
    pin:
      - package: "libsodium18"
        pin: "release o=SaltStack"
        priority: 50
      - package: "*"
        pin: "release o=SaltStack"
        priority: "1100"
  mcp_extra:
    source: "${extraRepoSource}"
  mcp_saltformulas:
    source:   "deb [arch=amd64]  http://mirror.mirantis.com/${distribRevision}/salt-formulas/xenial xenial main"
    repo_key: "http://mirror.mirantis.com/${distribRevision}/salt-formulas/xenial/archive-salt-formulas.key"
  ubuntu:
    source: "deb [arch=amd64] http://mirror.mirantis.com/${distribRevision}/ubuntu xenial main restricted universe"
  ubuntu-upd:
    source: "deb [arch=amd64] http://mirror.mirantis.com/${distribRevision}/ubuntu xenial-updates main restricted universe"
  ubuntu-sec:
    source: "deb [arch=amd64] http://mirror.mirantis.com/${distribRevision}/ubuntu xenial-security main restricted universe"
"""
        // override for now
        def extraRepoMergeStrategy = config.get('extraRepoMergeStrategy', 'override')
        def extraRepos = config.get('extraRepos', [:])
        def updateSaltFormulas = config.get('updateSaltFormulas', true).toBoolean()
        def defaultRepos = readYaml text: defaultExtraReposYaml
        // Don't check for magic, if set explicitly
        if (updateSaltFormulas) {
            if (!oldRelease && distribRevision != releaseVersionQ4) {
                defaultRepos['repo']['mcp_saltformulas_update'] = [
                    'source'  : "deb [arch=amd64]  http://mirror.mirantis.com/update/${distribRevision}/salt-formulas/xenial xenial main",
                    'repo_key': "http://mirror.mirantis.com/update/${distribRevision}/salt-formulas/xenial/archive-salt-formulas.key"
                ]
            }
        }
        if (extraRepoMergeStrategy == 'merge') {
            extraReposConfig = common.mergeMaps(defaultRepos, extraRepos)
        } else {
            extraReposConfig = extraRepos ? extraRepos : defaultRepos
        }
    }
    def img = docker.image(dockerImageName)

    img.pull()

    try {
        img.inside(dockerOptsFinal) {
            withEnv(envOpts) {
                try {
                    sh('printenv |sort -u')
                    // Currently, we don't have any other point to install
                    // runtime dependencies for tests.
                    if (baseRepoPreConfig) {
                        // Warning! POssible point of 'allow-downgrades' issue
                        // Probably, need to add such flag into apt.prefs
                        sh("""#!/bin/bash -xe
                            echo "Installing extra-deb dependencies inside docker:"
                            echo > /etc/apt/sources.list
                            rm -vf /etc/apt/sources.list.d/* || true
                            rm -vf /etc/apt/preferences.d/* || true
                        """)
                        common.debianExtraRepos(extraReposConfig)
                        sh('''#!/bin/bash -xe
                            apt-get update
                            apt-get install -y python-netaddr
                        ''')

                    }
                    runCommands.sort().each { command, body ->
                        common.warningMsg("Running command: ${command}")
                        // doCall is the closure implementation in groovy, allow to pass arguments to closure
                        body.call()
                    }
                    // If we didn't dropped for now - test has been passed.
                    TestMarkerResult = true
                }
                finally {
                    runFinally.sort().each { command, body ->
                        common.warningMsg("Running ${command} command.")
                        // doCall is the closure implementation in groovy, allow to pass arguments to closure
                        body.call()
                    }
                }
            }
        }
    }
    catch (Exception er) {
        common.warningMsg("IgnoreMe:Something wrong with img.Message:\n" + er.toString())
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
 * Wrapper around setupDockerAndTest, to run checks against new Reclass version
 * that current model is compatible with new Reclass.
 *
 * @param config - LinkedHashMap with configuration params:
 *   dockerHostname - (required) Hostname to use for Docker container.
 *   distribRevision - (optional) Revision of packages to use (default proposed).
 *   extraRepo - (optional) Extra repo to use to install new Reclass version. Has
 *     high priority on distribRevision
 *   targetNodes - (required) List nodes to check pillar data.
 */
def compareReclassVersions(config) {
    def common = new com.mirantis.mk.Common()
    def salt = new com.mirantis.mk.Salt()
    common.infoMsg("Going to test new reclass for CFG node")
    def distribRevision = config.get('distribRevision', 'proposed')
    def venv = config.get('venv')
    def extraRepo = config.get('extraRepo', '')
    def extraRepoKey = config.get('extraRepoKey', '')
    def targetNodes = config.get('targetNodes')
    sh "rm -rf ${env.WORKSPACE}/old ${env.WORKSPACE}/new"
    sh "mkdir -p ${env.WORKSPACE}/old ${env.WORKSPACE}/new"
    def configRun = [
        'distribRevision': distribRevision,
        'dockerExtraOpts': [
            "-v /srv/salt/reclass:/srv/salt/reclass:ro",
            "-v /etc/salt:/etc/salt:ro",
            "-v /usr/share/salt-formulas/:/usr/share/salt-formulas/:ro"
        ],
        'envOpts'        : [
            "WORKSPACE=${env.WORKSPACE}",
            "NODES_LIST=${targetNodes.join(' ')}"
        ],
        'runCommands'    : [
            '001_Update_Reclass_package'    : {
                sh('apt-get update && apt-get install -y reclass')
            },
            '002_Test_Reclass_Compatibility': {
                sh('''
                reclass-salt -b /srv/salt/reclass -t > ${WORKSPACE}/new/inventory || exit 1
                for node in $NODES_LIST; do
                    reclass-salt -b /srv/salt/reclass -p $node > ${WORKSPACE}/new/$node || exit 1
                done
              ''')
            }
        ]
    ]
    if (extraRepo) {
        // FIXME
        configRun['runCommands']['0001_Additional_Extra_Repo_Passed'] = {
            sh("""
                echo "${extraRepo}" > /etc/apt/sources.list.d/mcp_extra.list
                [ "${extraRepoKey}" ] && wget -O - ${extraRepoKey} | apt-key add -
            """)
        }
    }
    if (setupDockerAndTest(configRun)) {
        common.infoMsg("New reclass version is compatible with current model: SUCCESS")
        def inventoryOld = salt.cmdRun(venv, "I@salt:master", "reclass-salt -b /srv/salt/reclass -t", true, null, true).get("return")[0].values()[0]
        // [0..-31] to exclude 'echo Salt command execution success' from output
        writeFile(file: "${env.WORKSPACE}/old/inventory", text: inventoryOld[0..-31])
        for (String node in targetNodes) {
            def nodeOut = salt.cmdRun(venv, "I@salt:master", "reclass-salt -b /srv/salt/reclass -p ${node}", true, null, true).get("return")[0].values()[0]
            writeFile(file: "${env.WORKSPACE}/old/${node}", text: nodeOut[0..-31])
        }
        def reclassDiff = common.comparePillars(env.WORKSPACE, env.BUILD_URL, '')
        currentBuild.description = reclassDiff
        if (reclassDiff != '<b>No job changes</b>') {
            throw new RuntimeException("Pillars with new reclass version has been changed: FAILED")
        } else {
            common.infoMsg("Pillars not changed with new reclass version: SUCCESS")
        }
    } else {
        throw new RuntimeException("New reclass version is not compatible with current model: FAILED")
    }
}

/**
 * Wrapper over setupDockerAndTest, to test CC model.
 *
 * @param config - dict with params:
 *   dockerHostname - (required) salt master's name
 *   clusterName - (optional) model cluster name
 *   extraFormulas - (optional) extraFormulas to install. DEPRECATED
 *   formulasSource - (optional) formulas source (git or pkg, default pkg)
 *   reclassVersion - (optional) Version of used reclass (branch, tag, ...) (optional, default master)
 *   reclassEnv - (require) directory of model
 *   ignoreClassNotfound - (optional) Ignore missing classes for reclass model (default false)
 *   aptRepoUrl - (optional) package repository with salt formulas
 *   aptRepoGPG - (optional) GPG key for apt repository with formulas
 *   testContext - (optional) Description of test
 Return: true\exception
 */

def testNode(LinkedHashMap config) {
    def common = new com.mirantis.mk.Common()
    def dockerHostname = config.get('dockerHostname')
    def domain = config.get('domain')
    def reclassEnv = config.get('reclassEnv')
    def clusterName = config.get('clusterName', "")
    def formulasSource = config.get('formulasSource', 'pkg')
    def extraFormulas = config.get('extraFormulas', 'linux')
    def ignoreClassNotfound = config.get('ignoreClassNotfound', false)
    def aptRepoUrl = config.get('aptRepoUrl', "")
    def aptRepoGPG = config.get('aptRepoGPG', "")
    def testContext = config.get('testContext', 'test')
    def nodegenerator = config.get('nodegenerator', false)
    config['envOpts'] = [
        "RECLASS_ENV=${reclassEnv}", "SALT_STOPSTART_WAIT=5",
        "HOSTNAME=${dockerHostname}", "CLUSTER_NAME=${clusterName}",
        "DOMAIN=${domain}", "FORMULAS_SOURCE=${formulasSource}",
        "EXTRA_FORMULAS=${extraFormulas}", "EXTRA_FORMULAS_PKG_ALL=true",
        "RECLASS_IGNORE_CLASS_NOTFOUND=${ignoreClassNotfound}", "DEBUG=1",
        "APT_REPOSITORY=${aptRepoUrl}", "APT_REPOSITORY_GPG=${aptRepoGPG}"
    ]

    config['runCommands'] = [
        '001_Clone_salt_formulas_scripts': {
            sh(script: 'git clone http://gerrit.mcp.mirantis.com/salt-formulas/salt-formulas-scripts /srv/salt/scripts', returnStdout: true)
        },

        '002_Prepare_something'          : {
            sh('''#!/bin/bash -x
              rsync -ah ${RECLASS_ENV}/* /srv/salt/reclass && echo '127.0.1.2  salt' >> /etc/hosts
              echo "127.0.0.1 ${HOSTNAME}.${DOMAIN}" >> /etc/hosts
              if [ -f '/srv/salt/reclass/salt_master_pillar.asc' ] ; then
                mkdir -p /etc/salt/gpgkeys
                chmod 700 /etc/salt/gpgkeys
                GNUPGHOME=/etc/salt/gpgkeys gpg --import /srv/salt/reclass/salt_master_pillar.asc
              fi
              cd /srv/salt && find . -type f \\( -name '*.yml' -or -name '*.sh' \\) -exec sed -i 's/apt-mk.mirantis.com/apt.mcp.mirantis.net/g' {} \\;
              cd /srv/salt && find . -type f \\( -name '*.yml' -or -name '*.sh' \\) -exec sed -i 's/apt.mirantis.com/apt.mcp.mirantis.net/g' {} \\;
            ''')
        },

        '003_Install_Reclass_package'    : {
            sh('apt-get install -y reclass')
        },

        '004_Run_tests'                  : {
            def testTimeout = 40 * 60
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

                sh('''#!/bin/bash
                source /srv/salt/scripts/bootstrap.sh
                cd /srv/salt/scripts
                verify_salt_minions''')
            }
        }
    ]
    config['runFinally'] = [
        '001_Archive_artefacts': {
            sh(script: "cd /tmp; tar -czf ${env.WORKSPACE}/nodesinfo.tar.gz *reclass*", returnStatus: true)
            archiveArtifacts artifacts: "nodesinfo.tar.gz"
        }
    ]
    // this tool should be tested in master branch only
    // and not for all jobs, as pilot will be used cc-reclass-chunk
    if (nodegenerator) {
        config['runCommands']['005_Test_new_nodegenerator'] = {
            try {
                sh('''#!/bin/bash
                new_generated_dir=/srv/salt/_new_generated
                mkdir -p ${new_generated_dir}
                nodegenerator -b /srv/salt/reclass/classes/ -o ${new_generated_dir} ${CLUSTER_NAME}
                diff -r /srv/salt/reclass/nodes/_generated ${new_generated_dir} > /tmp/nodegenerator.diff
                tar -czf /tmp/_generated.tar.gz /srv/salt/reclass/nodes/_generated/
                tar -czf /tmp/_new_generated.tar.gz ${new_generated_dir}/
                tar -czf /tmp/_model.tar.gz /srv/salt/reclass/classes/cluster/*
                ''')
            } catch (Exception e) {
                print "Test new nodegenerator tool is failed: ${e}"
            }
        }
        config['runFinally']['002_Archive_nodegenerator_artefact'] = {
            sh(script: "cd /tmp; [ -f nodegenerator.diff ] && tar -czf ${env.WORKSPACE}/nodegenerator.tar.gz nodegenerator.diff _generated.tar.gz _new_generated.tar.gz _model.tar.gz", returnStatus: true)
            if (fileExists('nodegenerator.tar.gz')) {
                archiveArtifacts artifacts: "nodegenerator.tar.gz"
            }
        }
    }
    testResult = setupDockerAndTest(config)
    if (testResult) {
        common.infoMsg("Node test for context: ${testContext} model: ${reclassEnv} finished: SUCCESS")
    } else {
        throw new RuntimeException("Node test for context: ${testContext} model: ${reclassEnv} finished: FAILURE")
    }
    return testResult
}

/**
 * setup and test salt-master
 *
 * @param masterName salt master's name
 * @param clusterName model cluster name
 * @param extraFormulas extraFormulas to install. DEPRECATED
 * @param formulasSource formulas source (git or pkg)
 * @param reclassVersion Version of used reclass (branch, tag, ...) (optional, default master)
 * @param testDir directory of model
 * @param formulasSource Salt formulas source type (optional, default pkg)
 * @param formulasRevision APT revision for formulas (optional default stable)
 * @param ignoreClassNotfound Ignore missing classes for reclass model
 * @param dockerMaxCpus max cpus passed to docker (default 0, disabled)
 * @param legacyTestingMode do you want to enable legacy testing mode (iterating through the nodes directory definitions instead of reading cluster models)
 * @param aptRepoUrl package repository with salt formulas
 * @param aptRepoGPG GPG key for apt repository with formulas
 * Return                     true | false
 */

def setupAndTestNode(masterName, clusterName, extraFormulas = '*', testDir, formulasSource = 'pkg',
                     formulasRevision = 'stable', reclassVersion = "master", dockerMaxCpus = 0,
                     ignoreClassNotfound = false, legacyTestingMode = false, aptRepoUrl = '', aptRepoGPG = '', dockerContainerName = false) {
    def common = new com.mirantis.mk.Common()
    // TODO
    common.errorMsg('You are using deprecated function!Please migrate to "setupDockerAndTest".' +
        'It would be removed after 2018.q4 release!Pushing forced 60s sleep..')
    sh('sleep 60')
    // timeout for test execution (40min)
    def testTimeout = 40 * 60
    def TestMarkerResult = false
    def saltOpts = "--retcode-passthrough --force-color"
    def workspace = common.getWorkspace()
    def img = docker.image("mirantis/salt:saltstack-ubuntu-xenial-salt-2017.7")
    img.pull()

    if (formulasSource == 'pkg') {
        if (extraFormulas) {
            common.warningMsg("You have passed deprecated variable:extraFormulas=${extraFormulas}. " +
                "\n It would be ignored, and all formulas would be installed anyway")
        }
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
            withEnv(["FORMULAS_SOURCE=${formulasSource}", "EXTRA_FORMULAS=${extraFormulas}", "EXTRA_FORMULAS_PKG_ALL=true",
                     "DISTRIB_REVISION=${formulasRevision}",
                     "DEBUG=1", "MASTER_HOSTNAME=${masterName}",
                     "CLUSTER_NAME=${clusterName}", "MINION_ID=${masterName}",
                     "RECLASS_VERSION=${reclassVersion}", "RECLASS_IGNORE_CLASS_NOTFOUND=${ignoreClassNotfound}",
                     "APT_REPOSITORY=${aptRepoUrl}", "SALT_STOPSTART_WAIT=5",
                     "APT_REPOSITORY_GPG=${aptRepoGPG}"]) {
                try {
                    // Currently, we don't have any other point to install
                    // runtime dependencies for tests.
                    sh("""#!/bin/bash -xe
            echo "Installing extra-deb dependencies inside docker:"
            echo "APT::Get::AllowUnauthenticated 'true';"  > /etc/apt/apt.conf.d/99setupAndTestNode
            echo "APT::Get::Install-Suggests 'false';"  >> /etc/apt/apt.conf.d/99setupAndTestNode
            echo "APT::Get::Install-Recommends 'false';"  >> /etc/apt/apt.conf.d/99setupAndTestNode
            rm -vf /etc/apt/sources.list.d/* || true
            echo 'deb [arch=amd64] http://mirror.mirantis.com/$DISTRIB_REVISION/ubuntu xenial main restricted universe' > /etc/apt/sources.list
            echo 'deb [arch=amd64] http://mirror.mirantis.com/$DISTRIB_REVISION/ubuntu xenial-updates main restricted universe' >> /etc/apt/sources.list
            apt-get update
            apt-get install -y python-netaddr
            """)
                    sh(script: "git clone https://github.com/salt-formulas/salt-formulas-scripts /srv/salt/scripts", returnStdout: true)
                    sh("""rsync -ah ${testDir}/* /srv/salt/reclass && echo '127.0.1.2  salt' >> /etc/hosts
            cd /srv/salt && find . -type f \\( -name '*.yml' -or -name '*.sh' \\) -exec sed -i 's/apt-mk.mirantis.com/apt.mcp.mirantis.net/g' {} \\;
            cd /srv/salt && find . -type f \\( -name '*.yml' -or -name '*.sh' \\) -exec sed -i 's/apt.mirantis.com/apt.mcp.mirantis.net/g' {} \\;
            """)
                    // FIXME: should be changed to use reclass from mcp_extra_nigtly?
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
                verify_salt_minions
                ''')
                        }
                    }
                    // If we didn't dropped for now - test has been passed.
                    TestMarkerResult = true
                }

                finally {
                    // Collect rendered per-node data.Those info could be simply used
                    // for diff processing. Data was generated via reclass.cli --nodeinfo,
                    /// during verify_salt_minions.
                    sh(script: "cd /tmp; tar -czf ${env.WORKSPACE}/nodesinfo.tar.gz *reclass*", returnStatus: true)
                    archiveArtifacts artifacts: "nodesinfo.tar.gz"
                }
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

/**
 * Wrapper over setupAndTestNode, to test exactly one CC model.
 Whole workspace and model - should be pre-rendered and passed via MODELS_TARGZ
 Flow: grab all data, and pass to setupAndTestNode function
 under-modell will be directly mirrored to `model/{cfg.testReclassEnv}/* /srv/salt/reclass/*`
 *
 * @param cfg - dict with params:
 MODELS_TARGZ       http link to arch with (models|contexts|global_reclass)
 modelFile
 DockerCName        directly passed to setupAndTestNode
 EXTRA_FORMULAS     directly passed to setupAndTestNode
 DISTRIB_REVISION   directly passed to setupAndTestNode
 reclassVersion     directly passed to setupAndTestNode

 Return: true\exception
 */

def testCCModel(cfg) {
    def common = new com.mirantis.mk.Common()
    common.errorMsg('You are using deprecated function!Please migrate to "testNode".' +
        'It would be removed after 2018.q4 release!Pushing forced 60s sleep..')
    sh('sleep 60')
    sh(script: 'find . -mindepth 1 -delete || true', returnStatus: true)
    sh(script: "wget --progress=dot:mega --auth-no-challenge -O models.tar.gz ${cfg.MODELS_TARGZ}")
    // unpack data
    sh(script: "tar -xzf models.tar.gz ")
    common.infoMsg("Going to test exactly one context: ${cfg.modelFile}\n, with params: ${cfg}")
    content = readFile(file: cfg.modelFile)
    templateContext = readYaml text: content
    clusterName = templateContext.default_context.cluster_name
    clusterDomain = templateContext.default_context.cluster_domain

    def testResult = false
    testResult = setupAndTestNode(
        "cfg01.${clusterDomain}",
        clusterName,
        '',
        cfg.testReclassEnv, // Sync into image exactly one env
        'pkg',
        cfg.DISTRIB_REVISION,
        cfg.reclassVersion,
        0,
        false,
        false,
        '',
        '',
        cfg.DockerCName)
    if (testResult) {
        common.infoMsg("testCCModel for context: ${cfg.modelFile} model: ${cfg.testReclassEnv} finished: SUCCESS")
    } else {
        throw new RuntimeException("testCCModel for context: ${cfg.modelFile} model: ${cfg.testReclassEnv} finished: FAILURE")
    }
    return testResult
}
