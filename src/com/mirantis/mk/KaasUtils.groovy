package com.mirantis.mk

/**
 *
 * KaaS Component Testing Utilities
 *
 */


/**
 * Determine scope of test suite against per-commit KaaS deployment based on keywords
 * Keyword list: https://gerrit.mcp.mirantis.com/plugins/gitiles/kaas/core/+/refs/heads/master/.git-message-template#50
 *
 * Used for components team to combine test-suites and forward desired parameters to kaas/core deployment jobs
 * Example scheme:
 * New CR pushed in kubernetes/lcm-ansible -> parsing it's commit body and combine test-suite -> trigger deployment jobs from kaas/core
 * manage test-suite through Jenkins Job Parameters
 *
 * @return      (map)[
 *                    deployChildEnabled: (bool) True if need to deploy child cluster during demo-run
 *                    runUie2eEnabled:    (bool) True if need to run ui-e2e cluster during demo-run
 *                   ]
 */
def checkDeploymentTestSuite() {
    def common = new com.mirantis.mk.Common()

    // Available triggers and its sane defaults
    def deployChild = env.DEPLOY_CHILD_CLUSTER ? env.DEPLOY_CHILD_CLUSTER.toBoolean() : false
    def upgradeChild = env.UPGRADE_CHILD_CLUSTER ? env.UPGRADE_CHILD_CLUSTER.toBoolean() : false
    def upgradeMgmt = env.UPGRADE_MGMT_CLUSTER ? env.UPGRADE_MGMT_CLUSTER.toBoolean() : false
    def runUie2e = env.RUN_UI_E2E ? env.RUN_UI_E2E.toBoolean() : false
    def runMgmtConformance = env.RUN_MGMT_CFM ? env.RUN_MGMT_CFM.toBoolean() : false
    def runChildConformance = env.RUN_CHILD_CFM ? env.RUN_CHILD_CFM.toBoolean() : false
    def fetchServiceBinaries = env.FETCH_BINARIES_FROM_UPSTREAM ? env.FETCH_BINARIES_FROM_UPSTREAM.toBoolean() : false
    def awsOnDemandDemo = env.ALLOW_AWS_ON_DEMAND ? env.ALLOW_AWS_ON_DEMAND.toBoolean() : false

    def commitMsg = env.GERRIT_CHANGE_COMMIT_MESSAGE ? new String(env.GERRIT_CHANGE_COMMIT_MESSAGE.decodeBase64()) : ''
    if (commitMsg ==~ /(?s).*\[child-deploy\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*child-deploy.*/ || upgradeChild || runChildConformance) {
        deployChild = true
    }
    if (commitMsg ==~ /(?s).*\[child-upgrade\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*child-upgrade.*/) {
        deployChild = true
        upgradeChild = true
    }
    if (commitMsg ==~ /(?s).*\[mgmt-upgrade\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*mgmt-upgrade.*/) {
        upgradeMgmt = true
    }
    if (commitMsg ==~ /(?s).*\[ui-e2e\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*ui-e2e.*/) {
        runUie2e = true
    }
    if (commitMsg ==~ /(?s).*\[mgmt-cfm\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*mgmt-cfm.*/) {
        runMgmtConformance = true
    }
    if (commitMsg ==~ /(?s).*\[child-cfm\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*child-cfm.*/) {
        runChildConformance = true
        deployChild = true
    }
    if (commitMsg ==~ /(?s).*\[fetch.*binaries\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*fetch.*binaries.*/) {
        fetchServiceBinaries = true
    }
    if (commitMsg ==~ /(?s).*\[aws-demo\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*aws-demo.*/) {
        awsOnDemandDemo = true
        common.warningMsg('Forced running additional kaas deployment with AWS provider, triggered on patchset using custom keyword: \'aws-demo\' ')
    }

    // TODO (vnaumov) remove below condition after moving all releases to UCP
    def ucpChildMatches = (commitMsg =~ /(\[child-ucp\s*ucp-.*?\])/)
    if (ucpChildMatches.size() > 0) {
        deployChild = true
        common.warningMsg('Forced UCP based child deployment triggered on patchset using custom keyword: \'[child-ucp ucp-5-1-0-3-3-0-example]\' ')

        // TODO(vnaumov) delete after ucp upgrades support
        common.errorMsg('Child upgrade test will be skipped, UCP upgrades temporally disabled')
        upgradeChild = false
    }

    common.infoMsg("""
        Child cluster deployment scheduled: ${deployChild}
        Child cluster release upgrade scheduled: ${upgradeChild}
        Child conformance testing scheduled: ${runChildConformance}
        Mgmt cluster release upgrade scheduled: ${upgradeMgmt}
        Mgmt conformance testing scheduled: ${runMgmtConformance}
        Mgmt UI e2e testing scheduled: ${runUie2e}
        AWS provider additional deployment scheduled: ${awsOnDemandDemo}
        Service binaries fetching scheduled: ${fetchServiceBinaries}
        Triggers: https://gerrit.mcp.mirantis.com/plugins/gitiles/kaas/core/+/refs/heads/master/.git-message-template#50""")
    return [
        deployChildEnabled         : deployChild,
        upgradeChildEnabled        : upgradeChild,
        runChildConformanceEnabled : runChildConformance,
        upgradeMgmtEnabled         : upgradeMgmt,
        runUie2eEnabled            : runUie2e,
        runMgmtConformanceEnabled  : runMgmtConformance,
        fetchServiceBinariesEnabled: fetchServiceBinaries,
        awsOnDemandDemoEnabled     : awsOnDemandDemo]
}

/**
 * Determine if custom si tests/pipelines refspec forwarded from gerrit change request

 * Keyword list: https://gerrit.mcp.mirantis.com/plugins/gitiles/kaas/core/+/refs/heads/master/.git-message-template#59
 * Used for components team to test component changes w/ custom SI refspecs using kaas/core deployment jobs
 * Example scheme:
 * New CR pushed in kubernetes/lcm-ansible -> parsing it's commit body and get custom test refspecs -> trigger deployment jobs from kaas/core
 * manage refspecs through Jenkins Job Parameters
 *
 * @return (map)[*                    siTests: (string) final refspec for si-tests
 *                    siPipelines: (string) final refspec for si-pipelines
 *                   ]
 */
def checkCustomSIRefspec() {
    def common = new com.mirantis.mk.Common()

    // Available triggers and its sane defaults
    def siTestsRefspec = env.SI_TESTS_REFSPEC ?: 'master'
    def siTestsFeatureFlags = env.SI_TESTS_FEATURE_FLAGS ?: ''
    def siPipelinesRefspec = env.SI_PIPELINES_REFSPEC ?: 'master'
    def siTestsDockerImage = env.SI_TESTS_DOCKER_IMAGE ?: 'docker-dev-kaas-local.docker.mirantis.net/mirantis/kaas/si-test:master'
    def commitMsg = env.GERRIT_CHANGE_COMMIT_MESSAGE ? new String(env.GERRIT_CHANGE_COMMIT_MESSAGE.decodeBase64()) : ''

    def siTestMatches = (commitMsg =~ /(\[si-tests-ref\s*refs\/changes\/.*?\])/)
    def siFeatureFlagsMatches = (commitMsg =~ /(\[si-feature-flags\s.*?\])/)
    def siPipelinesMatches = (commitMsg =~ /(\[si-pipelines-ref\s*refs\/changes\/.*?\])/)

    if (siTestMatches.size() > 0) {
        siTestsRefspec = siTestMatches[0][0].split('si-tests-ref')[1].replaceAll('[\\[\\]]', '').trim()
        siTestsDockerImage = "docker-dev-local.docker.mirantis.net/review/" +
            "kaas-si-test-${siTestsRefspec.split('/')[-2]}:${siTestsRefspec.split('/')[-1]}"
    }
    if (siFeatureFlagsMatches.size() > 0) {
        siTestsFeatureFlags = siFeatureFlagsMatches[0][0].split('si-feature-flags')[1].replaceAll('[\\[\\]]', '').trim()
    }
    if (siPipelinesMatches.size() > 0) {
        siPipelinesRefspec = siPipelinesMatches[0][0].split('si-pipelines-ref')[1].replaceAll('[\\[\\]]', '').trim()
    }

    common.infoMsg("""
        kaas/si-pipelines will be fetched from: ${siPipelinesRefspec}
        kaas/si-tests will be fetched from: ${siTestsRefspec}
        kaas/si-tests as dockerImage will be fetched from: ${siTestsDockerImage}
        kaas/si-tests additional feature flags applied: [${siTestsFeatureFlags}]
        Keywords: https://gerrit.mcp.mirantis.com/plugins/gitiles/kaas/core/+/refs/heads/master/.git-message-template#59""")
    return [siTests: siTestsRefspec, siFeatureFlags: siTestsFeatureFlags, siPipelines: siPipelinesRefspec, siTestsDockerImage: siTestsDockerImage]
}

/**
 * Determine if custom kaas core/pipelines refspec forwarded from gerrit change request

 * Keyword list: https://gerrit.mcp.mirantis.com/plugins/gitiles/kaas/core/+/refs/heads/master/.git-message-template#59
 * Used for components team to test component changes w/ custom Core refspecs using kaas/core deployment jobs
 * Example scheme:
 * New CR pushed in kubernetes/lcm-ansible -> parsing it's commit body and get custom test refspecs -> trigger deployment jobs from kaas/core
 * manage refspecs through Jenkins Job Parameters
 *
 * @return          (map)[     core: (string) final refspec for kaas/core
 *                             corePipelines: (string) final refspec for pipelines in kaas/core
 *                       ]
 */
def checkCustomCoreRefspec() {
    def common = new com.mirantis.mk.Common()

    // Available triggers and its sane defaults
    def coreRefspec = env.KAAS_CORE_REFSPEC ?: 'master'
    // by default using value of GERRIT_REFSPEC parameter in *kaas/core jobs*
    def corePipelinesRefspec = env.KAAS_PIPELINE_REFSPEC ?: '\$GERRIT_REFSPEC'
    def commitMsg = env.GERRIT_CHANGE_COMMIT_MESSAGE ? new String(env.GERRIT_CHANGE_COMMIT_MESSAGE.decodeBase64()) : ''

    def coreMatches = (commitMsg =~ /(\[core-ref\s*refs\/changes\/.*?\])/)
    def corePipelinesMatches = (commitMsg =~ /(\[core-pipelines-ref\s*refs\/changes\/.*?\])/)

    if (coreMatches.size() > 0) {
        coreRefspec = coreMatches[0][0].split('core-ref')[1].replaceAll('[\\[\\]]', '').trim()
    }
    if (corePipelinesMatches.size() > 0) {
        corePipelinesRefspec = corePipelinesMatches[0][0].split('core-pipelines-ref')[1].replaceAll('[\\[\\]]', '').trim()
    }

    common.infoMsg("""
        kaas/core will be fetched from: ${coreRefspec}
        kaas/core pipelines will be fetched from: ${corePipelinesRefspec}
        Keywords: https://gerrit.mcp.mirantis.com/plugins/gitiles/kaas/core/+/refs/heads/master/.git-message-template#59""")
    return [core: coreRefspec, corePipelines: corePipelinesRefspec]
}


/**
 * Trigger KaaS demo jobs based on AWS/OS providers with customized test suite, parsed from external sources (gerrit commit/jj vars)
 * Keyword list: https://gerrit.mcp.mirantis.com/plugins/gitiles/kaas/core/+/refs/heads/master/.git-message-template
 * Used for components team to test component changes w/ customized SI tests/refspecs using kaas/core deployment jobs
 *
 * @param:        (string) component name [iam, lcm, stacklight]
 * @param:        (string) Patch for kaas/cluster releases in json format
 */
def triggerPatchedComponentDemo(component, patchSpec) {
    def common = new com.mirantis.mk.Common()
    // Determine if custom trigger keywords forwarded from gerrit
    def triggers = checkDeploymentTestSuite()
    // Determine SI refspecs
    def siRefspec = checkCustomSIRefspec()
    // Determine Core refspecs
    def coreRefspec = checkCustomCoreRefspec()

    def jobs = [:]
    def parameters = [
        string(name: 'GERRIT_REFSPEC', value: coreRefspec.core),
        string(name: 'KAAS_PIPELINE_REFSPEC', value: coreRefspec.corePipelines),
        string(name: 'SI_TESTS_REFSPEC', value: siRefspec.siTests),
        string(name: 'SI_TESTS_FEATURE_FLAGS', value: siRefspec.siFeatureFlags),
        string(name: 'SI_PIPELINES_REFSPEC', value: siRefspec.siPipelines),
        string(name: 'CUSTOM_RELEASE_PATCH_SPEC', value: patchSpec),
        booleanParam(name: 'UPGRADE_MGMT', value: triggers.upgradeMgmtEnabled),
        booleanParam(name: 'RUN_UI_E2E', value: triggers.runUie2eEnabled),
        booleanParam(name: 'RUN_MGMT_CONFORMANCE', value: triggers.runMgmtConformanceEnabled),
        booleanParam(name: 'DEPLOY_CHILD', value: triggers.deployChildEnabled),
        booleanParam(name: 'UPGRADE_CHILD', value: triggers.upgradeChildEnabled),
        booleanParam(name: 'RUN_CHILD_CONFORMANCE', value: triggers.runChildConformanceEnabled),
        booleanParam(name: 'ALLOW_AWS_ON_DEMAND', value: triggers.awsOnDemandDemoEnabled),
    ]

    def jobResults = []
    jobs["kaas-core-openstack-patched-${component}"] = {
        try {
            common.infoMsg('Deploy: patched KaaS demo with Openstack provider')
            job_info = build job: "kaas-testing-core-openstack-workflow-${component}", parameters: parameters, wait: true
            def build_description = job_info.getDescription()
            if (build_description) {
                currentBuild.description += build_description
            }
        } finally {
            def build_result = job_info.getResult()
            common.infoMsg("Patched KaaS demo with Openstack provider finished with status: ${build_result}")
            jobResults.add(build_result)
        }
    }
    if (triggers.awsOnDemandDemoEnabled) {
        jobs["kaas-core-aws-patched-${component}"] = {
            try {
                common.infoMsg('Deploy: patched KaaS demo with AWS provider')
                job_info = build job: "kaas-testing-core-aws-workflow-${component}", parameters: parameters, wait: true
                def build_description = job_info.getDescription()
                if (build_description) {
                    currentBuild.description += build_description
                }
            } finally {
                def build_result = job_info.getResult()
                common.infoMsg("Patched KaaS demo with AWS provider finished with status: ${build_result}")
                jobResults.add(build_result)
            }
        }
    }

    common.infoMsg('Trigger KaaS demo deployments according to defined provider set')
    // Limit build concurency workaround examples: https://issues.jenkins-ci.org/browse/JENKINS-44085
    parallel jobs

    if (jobResults.contains('FAILURE')) {
        common.infoMsg('One of parallel downstream jobs is failed, mark executor job as failed')
        currentBuild.result = 'FAILURE'
    }
}
