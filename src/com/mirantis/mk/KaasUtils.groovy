package com.mirantis.mk

import static groovy.json.JsonOutput.toJson

/**
 *
 * KaaS Component Testing Utilities
 *
 */

/**
 * Check KaaS Core CICD feature flags
 * such triggers can be used in case of switching between pipelines,
 * conditions inside pipelines to reduce dependency on jenkins job builder and jenkins job templates itself
 *
 * @return      (map)[
 *                    ffNameEnabled: (bool) True/False
 *                   ]
 */
def checkCoreCIFeatureFlags() {
    def common = new com.mirantis.mk.Common()
    def ff = [
        build_artifacts_upgrade: false,
    ]

    def commitMsg = env.GERRIT_CHANGE_COMMIT_MESSAGE ? new String(env.GERRIT_CHANGE_COMMIT_MESSAGE.decodeBase64()) : ''
    if (commitMsg ==~ /(?s).*\[ci-build-artifacts-upgrade\].*/) {
        ff['build_artifacts_upgrade'] = true
    }

    common.infoMsg("Core ci feature flags status: ${ff}")
    return ff
}

/**
 * Determine scope of test suite against per-commit KaaS deployment based on keywords
 * Keyword list: https://gerrit.mcp.mirantis.com/plugins/gitiles/kaas/core/+/refs/heads/master/hack/ci-gerrit-keywords.md
 *
 * Used for components team to combine test-suites and forward desired parameters to kaas/core deployment jobs
 * Example scheme:
 * New CR pushed in kubernetes/lcm-ansible -> parsing it'cs commit body and combine test-suite -> trigger deployment jobs from kaas/core
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
    def seedMacOs = env.SEED_MACOS ? env.SEED_MACOS.toBoolean() : false
    def deployChild = env.DEPLOY_CHILD_CLUSTER ? env.DEPLOY_CHILD_CLUSTER.toBoolean() : false
    def upgradeChild = env.UPGRADE_CHILD_CLUSTER ? env.UPGRADE_CHILD_CLUSTER.toBoolean() : false
    def mosDeployChild = env.DEPLOY_MOS_CHILD_CLUSTER ? env.DEPLOY_MOS_CHILD_CLUSTER.toBoolean() : false
    def mosUpgradeChild = env.UPGRADE_MOS_CHILD_CLUSTER ? env.UPGRADE_MOS_CHILD_CLUSTER.toBoolean() : false
    def customChildRelease = env.KAAS_CHILD_CLUSTER_RELEASE_NAME ? env.KAAS_CHILD_CLUSTER_RELEASE_NAME : ''
    def mosTfDeploy = env.MOS_TF_DEPLOY ? env.MOS_TF_DEPLOY.toBoolean() : false
    def attachBYO = env.ATTACH_BYO ? env.ATTACH_BYO.toBoolean() : false
    def upgradeBYO = env.UPGRADE_BYO ? env.UPGRADE_BYO.toBoolean() : false
    def runBYOMatrix = env.RUN_BYO_MATRIX ? env.RUN_BYO_MATRIX.toBoolean() : false
    def defaultBYOOs = env.DEFAULT_BYO_OS ? env.DEFAULT_BYO_OS.toString() : 'ubuntu'
    def upgradeMgmt = env.UPGRADE_MGMT_CLUSTER ? env.UPGRADE_MGMT_CLUSTER.toBoolean() : false
    def autoUpgradeMgmt = env.AUTO_UPGRADE_MCC ? env.AUTO_UPGRADE_MCC.toBoolean() : false
    def enableLMALogging = env.ENABLE_LMA_LOGGING ? env.ENABLE_LMA_LOGGING.toBoolean(): false
    def runUie2e = env.RUN_UI_E2E ? env.RUN_UI_E2E.toBoolean() : false
    def runUie2eNew = env.RUN_UI_E2E_NEW ? env.RUN_UI_E2E_NEW.toBoolean() : false
    def runMgmtConformance = env.RUN_MGMT_CFM ? env.RUN_MGMT_CFM.toBoolean() : false
    def runLMATest = env.RUN_LMA_TEST ? env.RUN_LMA_TEST.toBoolean() : false
    def runMgmtUserControllerTest = env.RUN_MGMT_USER_CONTROLLER_TEST ? env.RUN_MGMT_USER_CONTROLLER_TEST.toBoolean() : false
    def runProxyChildTest = env.RUN_PROXY_CHILD_TEST ? env.RUN_PROXY_CHILD_TEST.toBoolean() : false
    def runChildConformance = env.RUN_CHILD_CFM ? env.RUN_CHILD_CFM.toBoolean() : false
    def fetchServiceBinaries = env.FETCH_BINARIES_FROM_UPSTREAM ? env.FETCH_BINARIES_FROM_UPSTREAM.toBoolean() : false
    def equinixMetalV2ChildDiffMetro = env.EQUINIXMETALV2_CHILD_DIFF_METRO ? env.EQUINIXMETALV2_CHILD_DIFF_METRO.toBoolean() : false
    def runMaintenanceTest = env.RUN_MAINTENANCE_TEST ? env.RUN_MAINTENANCE_TEST.toBoolean() : false
    def runContainerregistryTest = env.RUN_CONTAINER_REGISTRY_TEST ? env.RUN_CONTAINER_REGISTRY_TEST.toBoolean() : false
    def runMgmtDeleteMasterTest = env.RUN_MGMT_DELETE_MASTER_TEST ? env.RUN_MGMT_DELETE_MASTER_TEST.toBoolean() : false
    def runRgnlDeleteMasterTest = env.RUN_RGNL_DELETE_MASTER_TEST ? env.RUN_RGNL_DELETE_MASTER_TEST.toBoolean() : false
    def runChildDeleteMasterTest = env.RUN_CHILD_DELETE_MASTER_TEST ? env.RUN_CHILD_DELETE_MASTER_TEST.toBoolean() : false
    // multiregion configuration from env variable: comma-separated string in form $mgmt_provider,$regional_provider
    def multiregionalMappings = env.MULTIREGION_SETUP ? multiregionWorkflowParser(env.MULTIREGION_SETUP) : [
        enabled: false,
        managementLocation: '',
        regionLocation: '',
    ]

    // proxy customization
    def proxyConfig = [
        mgmtOffline: env.OFFLINE_MGMT_CLUSTER ? env.OFFLINE_MGMT_CLUSTER.toBoolean() : false,
        childOffline: env.OFFLINE_CHILD_CLUSTER ? env.OFFLINE_CHILD_CLUSTER.toBoolean() : false,
        childProxy: env.PROXY_CHILD_CLUSTER ? env.PROXY_CHILD_CLUSTER.toBoolean() : false,
    ]

    // optional demo deployment customization
    def awsOnDemandDemo = env.ALLOW_AWS_ON_DEMAND ? env.ALLOW_AWS_ON_DEMAND.toBoolean() : false
    def equinixOnDemandDemo = env.ALLOW_EQUINIX_ON_DEMAND ? env.ALLOW_EQUINIX_ON_DEMAND.toBoolean() : false
    def equinixMetalV2OnDemandDemo = env.ALLOW_EQUINIXMETALV2_ON_DEMAND ? env.ALLOW_EQUINIXMETALV2_ON_DEMAND.toBoolean() : false
    def equinixOnAwsDemo = env.EQUINIX_ON_AWS_DEMO ? env.EQUINIX_ON_AWS_DEMO.toBoolean() : false
    def azureOnAwsDemo = env.AZURE_ON_AWS_DEMO ? env.AZURE_ON_AWS_DEMO.toBoolean() : false
    def azureOnDemandDemo = env.ALLOW_AZURE_ON_DEMAND ? env.ALLOW_AZURE_ON_DEMAND.toBoolean() : false
    def enableVsphereDemo = true
    def enableOSDemo = true
    def enableBMDemo = true
    def enableArtifactsBuild = true
    def openstackIMC = env.OPENSTACK_CLOUD_LOCATION ? env.OPENSTACK_CLOUD_LOCATION : 'us'
    def enableVsphereUbuntu = env.VSPHERE_DEPLOY_UBUNTU ? env.VSPHERE_DEPLOY_UBUNTU.toBoolean() : false
    def childOsBootFromVolume = env.OPENSTACK_BOOT_FROM_VOLUME ? env.OPENSTACK_BOOT_FROM_VOLUME.toBoolean() : false
    def bootstrapV2Scenario = env.BOOTSTRAP_V2_ENABLED ? env.BOOTSTRAP_V2_ENABLED.toBoolean() : false
    def equinixMetalV2Metro = env.EQUINIX_MGMT_METRO ? env.EQUINIX_MGMT_METRO : ''

    def commitMsg = env.GERRIT_CHANGE_COMMIT_MESSAGE ? new String(env.GERRIT_CHANGE_COMMIT_MESSAGE.decodeBase64()) : ''
    if (commitMsg ==~ /(?s).*\[mgmt-proxy\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*mgmt-proxy.*/) {
        proxyConfig['mgmtOffline'] = true
        common.warningMsg('Forced running offline mgmt deployment, some provider CDN regions for mgmt deployment may be set to *public-ci* to verify proxy configuration')
    }
    if (commitMsg ==~ /(?s).*\[seed-macos\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*seed-macos.*/) {
        seedMacOs = true
    }
    if (commitMsg ==~ /(?s).*\[child-deploy\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*child-deploy.*/ || upgradeChild || runChildConformance || runProxyChildTest) {
        deployChild = true
    }
    if (commitMsg ==~ /(?s).*\[child-upgrade\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*child-upgrade.*/) {
        deployChild = true
        upgradeChild = true
    }
    def childDeployMatches = (commitMsg =~ /(\[child-deploy\s*(\w|\-)+?\])/)
    if (childDeployMatches.size() > 0) {
        // override child version when it set explicitly
        deployChild = true
        customChildRelease = childDeployMatches[0][0].split('child-deploy')[1].replaceAll('[\\[\\]]', '').trim()
        common.warningMsg("Forced child deployment using custom release version ${customChildRelease}")
    }
    if (commitMsg ==~ /(?s).*\[mos-child-deploy\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*mos-child-deploy.*/) {
        mosDeployChild = true
    }
    if (commitMsg ==~ /(?s).*\[mos-child-upgrade\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*mos-child-upgrade.*/) {
        mosDeployChild = true
        mosUpgradeChild = true
    }
    if (commitMsg ==~ /(?s).*\[byo-attach\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*byo-attach.*/) {
        attachBYO = true
    }
    if (commitMsg ==~ /(?s).*\[byo-upgrade\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*byo-upgrade.*/) {
        attachBYO = true
        upgradeBYO = true
    }
    if (commitMsg ==~ /(?s).*\[ui-test-on-all-providers\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*ui-test-on-all-providers.*/) {
        enableVsphereDemo = true
        enableOSDemo = true
        awsOnDemandDemo = true
        azureOnDemandDemo = true
        equinixOnDemandDemo = true
        equinixMetalV2OnDemandDemo = true
        runUie2e = true
        // Edit after fix PRODX-3961
        enableBMDemo = false
    }
    def byoDeployMatches = (commitMsg =~ /(\[run-byo-matrix\s*(ubuntu|centos)\])/)
    if (commitMsg ==~ /(?s).*\[run-byo-matrix\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*run-byo-matrix\.*/ || byoDeployMatches.size() > 0) {
        runBYOMatrix = true

        if (byoDeployMatches.size() > 0) {
            defaultBYOOs = byoDeployMatches[0][2]
            common.warningMsg("Custom BYO OS detected, using ${defaultBYOOs}")
        }

        common.warningMsg('Forced byo matrix test via run-byo-matrix, all other byo triggers will be skipped')
        attachBYO = false
        upgradeBYO = false
    }
    if (commitMsg ==~ /(?s).*\[mgmt-upgrade\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*mgmt-upgrade.*/) {
        upgradeMgmt = true
    }
    if (commitMsg ==~ /(?s).*\[auto-upgrade\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*auto-upgrade.*/) {
        autoUpgradeMgmt = true
    }
    if (commitMsg ==~ /(?s).*\[lma-logging\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*lma-logging.*/) {
        enableLMALogging = true
    }
    if (commitMsg ==~ /(?s).*\[ui-e2e\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*ui-e2e.*/) {
        runUie2e = true
    }
    if (commitMsg ==~ /(?s).*\[ui-e2e-new\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*ui-e2e-new.*/) {
        runUie2eNew = true
    }
    if (commitMsg ==~ /(?s).*\[mgmt-cfm\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*mgmt-cfm.*/) {
        runMgmtConformance = true
    }
    if (commitMsg ==~ /(?s).*\[test-user-controller\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*test-user-controller.*/) {
        runMgmtUserControllerTest = true
    }
    if (commitMsg ==~ /(?s).*\[test-proxy-child\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*test-proxy-child.*/) {
        runProxyChildTest = true
        deployChild = true
        common.infoMsg('Child cluster deployment will be enabled since proxy child test suite will be executed')
    }
    if (commitMsg ==~ /(?s).*\[child-cfm\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*child-cfm.*/) {
        runChildConformance = true
        deployChild = true
    }
    if (commitMsg ==~ /(?s).*\[lma-test\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*lma-test.*/) {
        runLMATest = true
        enableLMALogging = true
        common.infoMsg('LMA logging will be enabled since LMA test suite will be executed')
    }
    if (commitMsg ==~ /(?s).*\[maintenance-test\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*maintenance-test.*/) {
        runMaintenanceTest = true
    }
    if (commitMsg ==~ /(?s).*\[container-registry-test\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*container-registry-test.*/) {
        runContainerregistryTest = true
    }
    if (commitMsg ==~ /(?s).*\[mgmt-delete-master-test\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*mgmt-delete-master-test.*/) {
        runMgmtDeleteMasterTest = true
    }
    if (commitMsg ==~ /(?s).*\[rgnl-delete-master-test\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*rgnl-delete-master-test.*/) {
        runRgnlDeleteMasterTest = true
    }
    if (commitMsg ==~ /(?s).*\[child-delete-master-test\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*child-delete-master-test.*/) {
        runChildDeleteMasterTest = true
    }
    if (commitMsg ==~ /(?s).*\[child-offline\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*child-offline.*/) {
        proxyConfig['childOffline'] = true
        deployChild = true
    }
    if (commitMsg ==~ /(?s).*\[child-proxy\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*child-proxy.*/) {
        proxyConfig['childOffline'] = true
        proxyConfig['childProxy'] = true
        deployChild = true
    }
    if (commitMsg ==~ /(?s).*\[fetch.*binaries\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*fetch.*binaries.*/) {
        fetchServiceBinaries = true
    }
    if (commitMsg ==~ /(?s).*\[equinix-on-aws\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*equinix-on-aws.*/) {
        equinixOnAwsDemo = true
        common.warningMsg('Forced running child cluster deployment on EQUINIX METAL provider based on AWS management cluster, triggered on patchset using custom keyword: \'[equinix-on-aws]\' ')
    }
    if (commitMsg ==~ /(?s).*\[azure-on-aws\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*azure-on-aws.*/) {
        azureOnAwsDemo = true
        common.warningMsg('Forced running child cluster deployment on Azure provider based on AWS management cluster, triggered on patchset using custom keyword: \'[azure-on-aws]\' ')
    }
    if (commitMsg ==~ /(?s).*\[aws-demo\].*/                 ||
        env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*aws-demo.*/ ||
        attachBYO                                            ||
        upgradeBYO                                           ||
        runBYOMatrix                                         ||
        seedMacOs                                            ||
        equinixOnAwsDemo                                     ||
        azureOnAwsDemo) {

        awsOnDemandDemo = true
        common.warningMsg('Running additional kaas deployment with AWS provider, may be forced due applied trigger cross dependencies, follow docs to clarify info')
    }
    if (commitMsg ==~ /(?s).*\[equinix-demo\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*equinix-demo\.*/) {
        equinixOnDemandDemo = true
    }
    if (commitMsg ==~ /(?s).*\[equinixmetalv2-demo\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*equinixmetalv2-demo\.*/) {
        equinixMetalV2OnDemandDemo = true
    }
    if (commitMsg ==~ /(?s).*\[equinixmetalv2-child-diff-metro\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*equinixmetalv2-child-diff-metro\.*/) {
        equinixMetalV2OnDemandDemo = true
        equinixMetalV2ChildDiffMetro = true
    }
    if (commitMsg ==~ /(?s).*\[azure-demo\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*azure-demo\.*/) {
        azureOnDemandDemo = true
    }
    if (commitMsg ==~ /(?s).*\[disable-os-demo\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*disable-os-demo\.*/) {
        enableOSDemo = false
        common.errorMsg('Openstack demo deployment will be aborted, VF -1 will be set')
    }

    if (commitMsg ==~ /(?s).*\[disable-bm-demo\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*disable-bm-demo\.*/) {
        enableBMDemo = false
        common.errorMsg('BM demo deployment will be aborted, VF -1 will be set')
    }

    if (commitMsg ==~ /(?s).*\[disable-vsphere-demo\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*disable-vsphere-demo\.*/) {
        enableVsphereDemo = false
        common.errorMsg('vSphere demo deployment will be aborted, VF -1 will be set')
    }
    if (commitMsg ==~ /(?s).*\[vsphere-ubuntu\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*vsphere-ubuntu\.*/) {
        enableVsphereUbuntu = true
        common.warningMsg('Ubuntu will be used to deploy vsphere machines')
    }

    if (commitMsg ==~ /(?s).*\[disable-artifacts-build\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*disable-artifacts-build\.*/) {
        enableArtifactsBuild = false
        common.errorMsg('artifacts build will be aborted, VF -1 will be set')
    }

    if (commitMsg ==~ /(?s).*\[child-os-boot-from-volume\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*child-os-boot-from-volume\.*/) {
        childOsBootFromVolume = true
        common.warningMsg('OS will be booted from Ceph volumes')
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

    // multiregional tests
    def multiRegionalMatches = (commitMsg =~ /(\[multiregion\s*.*?\])/)
    if (multiRegionalMatches.size() > 0) {
        multiregionalMappings = multiregionWorkflowParser(multiRegionalMatches)
    }
    switch (multiregionalMappings['managementLocation']) {
        case 'aws':
            common.warningMsg('Forced running additional kaas deployment with AWS provider according multiregional demo request')
            awsOnDemandDemo = true

            if (multiregionalMappings['regionLocation'] != 'aws' && seedMacOs) { // macstadium seed node has access only to *public* providers
                error('incompatible triggers: [seed-macos] and multiregional deployment based on *private* regional provider cannot be applied simultaneously')
            }
            break
        case 'os':
            if (enableOSDemo == false) {
                error('incompatible triggers: [disable-os-demo] and multiregional deployment based on OSt management region cannot be applied simultaneously')
            }
            break
        case 'vsphere':
            if (enableVsphereDemo == false) {
                error('incompatible triggers: [disable-vsphere-demo] and multiregional deployment based on Vsphere management region cannot be applied simultaneously')
            }
            break
        case 'equinix':
            common.warningMsg('Forced running additional kaas deployment with Equinix provider according multiregional demo request')
            equinixOnDemandDemo = true
            break
        case 'equinixmetalv2':
            common.warningMsg('Forced running additional kaas deployment with Equinix Metal V2 provider according multiregional demo request')
            equinixMetalV2OnDemandDemo = true
            break
        case 'azure':
            common.warningMsg('Forced running additional kaas deployment with Azure provider according multiregional demo request')
            azureOnDemandDemo = true
            break
    }

    // CDN configuration
    def cdnConfig = [
        mgmt: [
            openstack:  (proxyConfig['mgmtOffline'] == true) ? 'public-ci' : 'internal-ci',
            vsphere:  'internal-ci',
            aws: 'public-ci',
            equinix: 'public-ci',
            azure: 'public-ci',
        ],
    ]

    if (commitMsg ==~ /(?s).*\[eu-demo\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*eu-demo.*/) {
        openstackIMC = 'eu'
    }
    if (commitMsg ==~ /(?s).*\[mos-tf-demo\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*mos-tf-demo.*/) {
        openstackIMC = 'eu2'
    }
    if (openstackIMC == 'eu' || openstackIMC == 'eu2') {
        // use internal-eu because on internal-ci with eu cloud image pull takes much time
        def cdnRegion = (proxyConfig['mgmtOffline'] == true) ? 'public-ci' : 'internal-eu'
        common.infoMsg("eu2-demo was triggered, force switching CDN region to ${cdnRegion}")
        cdnConfig['mgmt']['openstack'] = cdnRegion
    }

    // calculate weight of current demo run to manage lockable resources
    def demoWeight = (deployChild || runUie2e) ? 2 : 1 // management = 1, child = 1

    if (commitMsg ==~ /(?s).*\[bootstrapv2-scenario\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*bootstrapv2-scenario\.*/) {
        bootstrapV2Scenario = true
    }

    // parse equinixmetalv2-metro trigger
    def equinixMetalV2MetroMatcher = (commitMsg =~ /\[equinixmetalv2-metro(\s+.*)?\]/)
    if (equinixMetalV2OnDemandDemo && equinixMetalV2MetroMatcher.size() > 0) {
        equinixMetalV2Metro = equinixMetalV2MetroMatcher[0][1].trim().toLowerCase()
        common.infoMsg("Forced Equnix mgmt deployment using custom metro ${equinixMetalV2Metro}")
    }

    common.infoMsg("""
        OpenStack Cloud location: ${openstackIMC}
        CDN deployment configuration: ${cdnConfig}
        MCC offline deployment configuration: ${proxyConfig}
        Use MacOS node as seed: ${seedMacOs}
        Child cluster deployment scheduled: ${deployChild}
        Custom child cluster release: ${customChildRelease}
        Child cluster release upgrade scheduled: ${upgradeChild}
        MOS child deploy scheduled: ${mosDeployChild}
        MOS child upgrade scheduled: ${mosUpgradeChild}
        Child conformance testing scheduled: ${runChildConformance}
        Single BYO cluster attachment scheduled: ${attachBYO}
        Single Attached BYO cluster upgrade test scheduled: ${upgradeBYO}
        BYO test matrix whole suite scheduled: ${runBYOMatrix}
        Default BYO OS: ${defaultBYOOs}
        Mgmt cluster release upgrade scheduled: ${upgradeMgmt}
        Mgmt cluster release auto upgrade scheduled: ${autoUpgradeMgmt}
        Mgmt LMA logging enabled: ${enableLMALogging}
        Mgmt conformance testing scheduled: ${runMgmtConformance}
        LMA testing scheduled: ${runLMATest}
        Mgmt user controller testing scheduled: ${runMgmtUserControllerTest}
        Mgmt UI e2e testing scheduled: ${runUie2e}
        Mgmt UI e2e playwrite testing scheduled: ${runUie2eNew}
        Maintenance test: ${runMaintenanceTest}
        Container Registry test: ${runContainerregistryTest}
        Child proxy test: ${runProxyChildTest}
        Delete mgmt master node test: ${runMgmtDeleteMasterTest}
        Delete rgnl master node test: ${runRgnlDeleteMasterTest}
        Delete child master node test: ${runChildDeleteMasterTest}
        AWS provider deployment scheduled: ${awsOnDemandDemo}
        Equinix provider deployment scheduled: ${equinixOnDemandDemo}
        EquinixmetalV2 provider deployment scheduled: ${equinixMetalV2OnDemandDemo}
        EquinixmetalV2 child deploy in a separate metro scheduled: ${equinixMetalV2ChildDiffMetro}
        EquinixmetalV2 mgmt will be deployed on the ${equinixMetalV2Metro} metro
        Equinix@AWS child cluster deployment scheduled: ${equinixOnAwsDemo}
        Azure provider deployment scheduled: ${azureOnDemandDemo}
        Azure@AWS child cluster deployment scheduled: ${azureOnAwsDemo}
        VSPHERE provider deployment scheduled: ${enableVsphereDemo}
        OS provider deployment scheduled: ${enableOSDemo}
        BM provider deployment scheduled: ${enableBMDemo}
        Ubuntu on vSphere scheduled: ${enableVsphereUbuntu}
        Artifacts build scheduled: ${enableArtifactsBuild}
        Boot OS child from Ceph volumes: ${childOsBootFromVolume}
        Multiregional configuration: ${multiregionalMappings}
        Service binaries fetching scheduled: ${fetchServiceBinaries}
        Current weight of the demo run: ${demoWeight} (Used to manage lockable resources)
        Bootstrap v2 scenario enabled: ${bootstrapV2Scenario}
        Triggers: https://gerrit.mcp.mirantis.com/plugins/gitiles/kaas/core/+/refs/heads/master/hack/ci-gerrit-keywords.md""")
    return [
        osCloudLocation                      : openstackIMC,
        cdnConfig                            : cdnConfig,
        proxyConfig                          : proxyConfig,
        useMacOsSeedNode                     : seedMacOs,
        deployChildEnabled                   : deployChild,
        childDeployCustomRelease             : customChildRelease,
        upgradeChildEnabled                  : upgradeChild,
        mosDeployChildEnabled                : mosDeployChild,
        mosUpgradeChildEnabled               : mosUpgradeChild,
        runChildConformanceEnabled           : runChildConformance,
        attachBYOEnabled                     : attachBYO,
        upgradeBYOEnabled                    : upgradeBYO,
        runBYOMatrixEnabled                  : runBYOMatrix,
        defaultBYOOs                         : defaultBYOOs,
        upgradeMgmtEnabled                   : upgradeMgmt,
        autoUpgradeMgmtEnabled               : autoUpgradeMgmt,
        enableLMALoggingEnabled              : enableLMALogging,
        runUie2eEnabled                      : runUie2e,
        runUie2eNewEnabled                   : runUie2eNew,
        runMgmtConformanceEnabled            : runMgmtConformance,
        runMaintenanceTestEnabled            : runMaintenanceTest,
        runContainerregistryTestEnabled      : runContainerregistryTest,
        runMgmtDeleteMasterTestEnabled       : runMgmtDeleteMasterTest,
        runRgnlDeleteMasterTestEnabled       : runRgnlDeleteMasterTest,
        runChildDeleteMasterTestEnabled      : runChildDeleteMasterTest,
        runLMATestEnabled                    : runLMATest,
        runMgmtUserControllerTestEnabled     : runMgmtUserControllerTest,
        runProxyChildTestEnabled             : runProxyChildTest,
        fetchServiceBinariesEnabled          : fetchServiceBinaries,
        awsOnDemandDemoEnabled               : awsOnDemandDemo,
        equinixOnDemandDemoEnabled           : equinixOnDemandDemo,
        equinixMetalV2OnDemandDemoEnabled    : equinixMetalV2OnDemandDemo,
        equinixMetalV2ChildDiffMetroEnabled  : equinixMetalV2ChildDiffMetro,
        equinixOnAwsDemoEnabled              : equinixOnAwsDemo,
        azureOnDemandDemoEnabled             : azureOnDemandDemo,
        azureOnAwsDemoEnabled                : azureOnAwsDemo,
        vsphereDemoEnabled                   : enableVsphereDemo,
        vsphereOnDemandDemoEnabled           : enableVsphereDemo, // TODO: remove after MCC 2.7 is out
        bmDemoEnabled                        : enableBMDemo,
        osDemoEnabled                        : enableOSDemo,
        vsphereUbuntuEnabled                 : enableVsphereUbuntu,
        artifactsBuildEnabled                : enableArtifactsBuild,
        childOsBootFromVolume                : childOsBootFromVolume,
        multiregionalConfiguration           : multiregionalMappings,
        demoWeight                           : demoWeight,
        bootstrapV2Scenario                  : bootstrapV2Scenario,
        equinixMetalV2Metro                  : equinixMetalV2Metro]
}

/**
 * Determine management and regional setup for demo workflow scenario
 *
 *
 * @param:        keyword (string) string , represents keyword trigger, specified in gerrit commit body, like `[multiregion aws,os]`
                                   or Jenkins environment string variable in form like 'aws,os'
 * @return        (map)[
                          enabled: (bool),
 *                        managementLocation: (string), //aws,os
 *                        regionLocation: (string), //aws,os
 *                     ]
 */
def multiregionWorkflowParser(keyword) {
    def common = new com.mirantis.mk.Common()
    def supportedManagementProviders = ['os', 'aws', 'vsphere', 'equinix', 'equinixmetalv2', 'azure']
    def supportedRegionalProviders = ['os', 'vsphere', 'equinix', 'equinixmetalv2', 'bm', 'azure', 'aws']

    def clusterTypes = ''
    if (keyword.toString().contains('multiregion')) {
        common.infoMsg('Multiregion definition configured via gerrit keyword trigger')
        clusterTypes = keyword[0][0].split('multiregion')[1].replaceAll('[\\[\\]]', '').trim().split(',')
    } else {
        common.infoMsg('Multiregion definition configured via environment variable')
        clusterTypes = keyword.trim().split(',')
    }

    if (clusterTypes.size() != 2) {
        error("Incorrect regions definiton, valid scheme: [multiregion ${management}, ${region}], got: ${clusterTypes}")
    }

    def desiredManagementProvider = clusterTypes[0].trim()
    def desiredRegionalProvider = clusterTypes[1].trim()
    if (! supportedManagementProviders.contains(desiredManagementProvider) || ! supportedRegionalProviders.contains(desiredRegionalProvider)) {
        error("""unsupported management <-> regional bundle, available options:
              management providers list - ${supportedManagementProviders}
              regional providers list - ${supportedRegionalProviders}""")
    }

    return [
        enabled: true,
        managementLocation: desiredManagementProvider,
        regionLocation: desiredRegionalProvider,
    ]
}

/**
 * Determine if custom si tests/pipelines refspec forwarded from gerrit change request

 * Keyword list: https://gerrit.mcp.mirantis.com/plugins/gitiles/kaas/core/+/refs/heads/master/hack/ci-gerrit-keywords.md
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
    def siPipelinesRefspec = env.SI_PIPELINES_REFSPEC ?: 'master'
    def siTestsDockerImage = env.SI_TESTS_DOCKER_IMAGE ?: 'docker-dev-kaas-local.docker.mirantis.net/mirantis/kaas/si-test'
    def siTestsDockerImageTag = env.SI_TESTS_DOCKER_IMAGE_TAG ?: 'master'
    def commitMsg = env.GERRIT_CHANGE_COMMIT_MESSAGE ? new String(env.GERRIT_CHANGE_COMMIT_MESSAGE.decodeBase64()) : ''

    def siTestMatches = (commitMsg =~ /(\[si-tests-ref\s*refs\/changes\/.*?\])/)
    def siPipelinesMatches = (commitMsg =~ /(\[si-pipelines-ref\s*refs\/changes\/.*?\])/)

    if (siTestMatches.size() > 0) {
        siTestsRefspec = siTestMatches[0][0].split('si-tests-ref')[1].replaceAll('[\\[\\]]', '').trim()
        siTestsDockerImage = "docker-review-local.docker.mirantis.net/review/kaas-si-test-${siTestsRefspec.split('/')[-2]}"
        siTestsDockerImageTag = siTestsRefspec.split('/')[-1]
    }
    if (siPipelinesMatches.size() > 0) {
        siPipelinesRefspec = siPipelinesMatches[0][0].split('si-pipelines-ref')[1].replaceAll('[\\[\\]]', '').trim()
    }

    common.infoMsg("""
        kaas/si-pipelines will be fetched from: ${siPipelinesRefspec}
        kaas/si-tests will be fetched from: ${siTestsRefspec}
        kaas/si-tests as dockerImage will be fetched from: ${siTestsDockerImage}:${siTestsDockerImageTag}
        Keywords: https://gerrit.mcp.mirantis.com/plugins/gitiles/kaas/core/+/refs/heads/master/hack/ci-gerrit-keywords.md""")
    return [siTests: siTestsRefspec, siPipelines: siPipelinesRefspec, siTestsDockerImage: siTestsDockerImage, siTestsDockerImageTag: siTestsDockerImageTag]
}

/**
 * Parse additional configuration for kaas component CICD repo
 * @param configurationFile    (str) path to configuration file in yaml format
 *
 * @return                     (map)[     siTestsFeatureFlags (string) dedicated feature flags that will be used in SI tests,
 *                                  ]
 */
def parseKaaSComponentCIParameters(configurationFile){
   def common = new com.mirantis.mk.Common()
   def ciConfig = readYaml file: configurationFile
   def ciSpec = [
   siTestsFeatureFlags: env.SI_TESTS_FEATURE_FLAGS ?: '',
   ]

   if (ciConfig.containsKey('si-tests-feature-flags')) {
       common.infoMsg("""SI tests feature flags customization detected,
           results will be merged with existing flags: [${ciSpec['siTestsFeatureFlags']}] identification...""")

       def ffMeta = ciSpec['siTestsFeatureFlags'].tokenize(',').collect { it.trim() }
       ffMeta.addAll(ciConfig['si-tests-feature-flags'])

       ciSpec['siTestsFeatureFlags'] = ffMeta.unique().join(',')
       common.infoMsg("SI tests custom feature flags: ${ciSpec['siTestsFeatureFlags']}")
   }

   common.infoMsg("""Additional ci configuration parsed successfully:
       siTestsFeatureFlags: ${ciSpec['siTestsFeatureFlags']}""")
   return ciSpec
}

/**
 * Determine if custom kaas core/pipelines refspec forwarded from gerrit change request

 * Keyword list: https://gerrit.mcp.mirantis.com/plugins/gitiles/kaas/core/+/refs/heads/master/hack/ci-gerrit-keywords.md
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
        Keywords: https://gerrit.mcp.mirantis.com/plugins/gitiles/kaas/core/+/refs/heads/master/hack/ci-gerrit-keywords.md""")
    return [core: coreRefspec, corePipelines: corePipelinesRefspec]
}


/**
 * generate Jenkins Parameter objects from from text parameter with additonal kaas core context
 * needed to forward inside kaas core set of jobs
 *
 * @param           context (string) Representation of the string enviroment variables needed for kaas core jobs in yaml format
 * @return          (list)[    string(name: '', value: ''),
 *                       ]
 */
def generateKaaSVarsFromContext(context) {
    def common = new com.mirantis.mk.Common()
    def parameters = []
    def config = readYaml text: context

    config.each { k,v ->
        common.infoMsg("Custom KaaS Core context parameter: ${k}=${v}")
        parameters.add(string(name: k, value: v))
    }

    return parameters
}

/**
 * Trigger KaaS demo jobs based on AWS/OS providers with customized test suite, parsed from external sources (gerrit commit/jj vars)
 * Keyword list: https://gerrit.mcp.mirantis.com/plugins/gitiles/kaas/core/+/refs/heads/master/hack/ci-gerrit-keywords.md
 * Used for components team to test component changes w/ customized SI tests/refspecs using kaas/core deployment jobs
 *
 * @param:        component (string) component name [iam, lcm, stacklight]
 * @param:        patchSpec (string) Patch for kaas/cluster releases in json format
 * @param:        configurationFile (string) Additional file for component repo CI config in yaml format
 */
def triggerPatchedComponentDemo(component, patchSpec = '', configurationFile = '.ci-parameters.yaml', coreContext = '') {
    def common = new com.mirantis.mk.Common()
    // Determine if custom trigger keywords forwarded from gerrit
    def triggers = checkDeploymentTestSuite()
    // Determine SI refspecs
    def siRefspec = checkCustomSIRefspec()
    // Determine Core refspecs
    def coreRefspec = checkCustomCoreRefspec()

    // Determine component repo ci configuration
    def ciSpec = [:]
    def componentFeatureFlags = env.SI_TESTS_FEATURE_FLAGS ?: ''
    if (fileExists(configurationFile)) {
        common.infoMsg('Component CI configuration file detected, parsing...')
        ciSpec = parseKaaSComponentCIParameters(configurationFile)
        componentFeatureFlags = ciSpec['siTestsFeatureFlags']
    } else {
        common.warningMsg('''Component CI configuration file is not exists,
            several code-management features may be unavailable,
            follow https://mirantis.jira.com/wiki/spaces/QA/pages/2310832276/SI-tests+feature+flags#%5BUpdated%5D-Using-a-feature-flag
            to create the configuration file''')
    }

    def platforms = []
    if (component == 'ipam' && triggers.vsphereDemoEnabled) {
        // Currently only vsphere demo is required for IPAM component
        platforms.add('vsphere')
    } else {
        if (triggers.osDemoEnabled) {
            platforms.add('openstack')
        }
        if (triggers.awsOnDemandDemoEnabled) {
            platforms.add('aws')
        }
        if (triggers.equinixOnDemandDemoEnabled) {
            platforms.add('equinix')
        }
        if (triggers.equinixMetalV2OnDemandDemoEnabled) {
            platforms.add('equinixmetalv2')
        }
        if (triggers.azureOnDemandDemoEnabled) {
            platforms.add('azure')
        }
        if (triggers.vsphereDemoEnabled) {
            platforms.add('vsphere')
        }
    }

    def jobs = [:]
    def parameters = [
        string(name: 'GERRIT_REFSPEC', value: coreRefspec.core),
        string(name: 'KAAS_PIPELINE_REFSPEC', value: coreRefspec.corePipelines),
        string(name: 'SI_TESTS_REFSPEC', value: siRefspec.siTests),
        string(name: 'SI_TESTS_FEATURE_FLAGS', value: componentFeatureFlags),
        string(name: 'SI_TESTS_DOCKER_IMAGE', value: siRefspec.siTestsDockerImage),
        string(name: 'SI_TESTS_DOCKER_IMAGE_TAG', value: siRefspec.siTestsDockerImageTag),
        string(name: 'SI_PIPELINES_REFSPEC', value: siRefspec.siPipelines),
        string(name: 'CUSTOM_RELEASE_PATCH_SPEC', value: patchSpec),
        string(name: 'KAAS_CHILD_CLUSTER_RELEASE_NAME', value: triggers.childDeployCustomRelease),
        string(name: 'OPENSTACK_CLOUD_LOCATION', value: triggers.osCloudLocation),
        booleanParam(name: 'OFFLINE_MGMT_CLUSTER', value: triggers.proxyConfig['mgmtOffline']),
        booleanParam(name: 'OFFLINE_CHILD_CLUSTER', value: triggers.proxyConfig['childOffline']),
        booleanParam(name: 'PROXY_CHILD_CLUSTER', value: triggers.proxyConfig['childProxy']),
        booleanParam(name: 'SEED_MACOS', value: triggers.useMacOsSeedNode),
        booleanParam(name: 'UPGRADE_MGMT_CLUSTER', value: triggers.upgradeMgmtEnabled),
        booleanParam(name: 'AUTO_UPGRADE_MCC', value: triggers.autoUpgradeMgmtEnabled),
        booleanParam(name: 'ENABLE_LMA_LOGGING', value: triggers.enableLMALoggingEnabled),
        booleanParam(name: 'RUN_UI_E2E', value: triggers.runUie2eEnabled),
        booleanParam(name: 'RUN_MGMT_CFM', value: triggers.runMgmtConformanceEnabled),
        booleanParam(name: 'RUN_MAINTENANCE_TEST', value: triggers.runMaintenanceTestEnabled),
        booleanParam(name: 'RUN_CONTAINER_REGISTRY_TEST', value: triggers.runContainerregistryTestEnabled),
        booleanParam(name: 'RUN_MGMT_DELETE_MASTER_TEST', value: triggers.runMgmtDeleteMasterTestEnabled),
        booleanParam(name: 'RUN_RGNL_DELETE_MASTER_TEST', value: triggers.runRgnlDeleteMasterTestEnabled),
        booleanParam(name: 'RUN_CHILD_DELETE_MASTER_TEST', value: triggers.runChildDeleteMasterTestEnabled),
        booleanParam(name: 'RUN_LMA_TEST', value: triggers.runLMATestEnabled),
        booleanParam(name: 'RUN_MGMT_USER_CONTROLLER_TEST', value: triggers.runMgmtUserControllerTestEnabled),
        booleanParam(name: 'DEPLOY_CHILD_CLUSTER', value: triggers.deployChildEnabled),
        booleanParam(name: 'UPGRADE_CHILD_CLUSTER', value: triggers.upgradeChildEnabled),
        booleanParam(name: 'RUN_PROXY_CHILD_TEST', value: triggers.runProxyChildTestEnabled),
        booleanParam(name: 'ATTACH_BYO', value: triggers.attachBYOEnabled),
        booleanParam(name: 'UPGRADE_BYO', value: triggers.upgradeBYOEnabled),
        booleanParam(name: 'RUN_BYO_MATRIX', value: triggers.runBYOMatrixEnabled),
        booleanParam(name: 'RUN_CHILD_CFM', value: triggers.runChildConformanceEnabled),
        booleanParam(name: 'ALLOW_AWS_ON_DEMAND', value: triggers.awsOnDemandDemoEnabled),
        booleanParam(name: 'ALLOW_EQUINIX_ON_DEMAND', value: triggers.equinixOnDemandDemoEnabled),
        booleanParam(name: 'ALLOW_EQUINIXMETALV2_ON_DEMAND', value: triggers.equinixMetalV2OnDemandDemoEnabled),
        booleanParam(name: 'EQUINIXMETALV2_CHILD_DIFF_METRO', value: triggers.equinixMetalV2ChildDiffMetroEnabled),
        booleanParam(name: 'EQUINIX_ON_AWS_DEMO', value: triggers.equinixOnAwsDemoEnabled),
        booleanParam(name: 'ALLOW_AZURE_ON_DEMAND', value: triggers.azureOnDemandDemoEnabled),
        booleanParam(name: 'AZURE_ON_AWS_DEMO', value: triggers.azureOnAwsDemoEnabled),
        booleanParam(name: 'VSPHERE_DEPLOY_UBUNTU', value: triggers.vsphereUbuntuEnabled),
    ]

    // customize multiregional demo
    if (triggers.multiregionalConfiguration.enabled) {
        parameters.add(string(name: 'MULTIREGION_SETUP',
                              value: "${triggers.multiregionalConfiguration.managementLocation},${triggers.multiregionalConfiguration.regionLocation}"
                              ))
    }

    // Determine component team custom context
    if (coreContext != '') {
        common.infoMsg('Additional KaaS Core context detected, will be forwarded into kaas core cicd...')
        def additionalParameters = generateKaaSVarsFromContext(coreContext)
        parameters.addAll(additionalParameters)
    }

    def jobResults = []

    platforms.each { platform ->
        jobs["kaas-core-${platform}-patched-${component}"] = {
            try {
                common.infoMsg("Deploy: patched KaaS demo with ${platform} provider")
                def job_info = build job: "kaas-testing-core-${platform}-workflow-${component}", parameters: parameters, wait: true
                def build_description = job_info.getDescription()
                def build_result = job_info.getResult()
                jobResults.add(build_result)

                if (build_description) {
                    currentBuild.description += build_description
                }
            } finally {
                common.infoMsg("Patched KaaS demo with ${platform} provider finished")
            }
        }
    }

    common.infoMsg('Trigger KaaS demo deployments according to defined provider set')
    if (jobs.size() == 0) {
        error('No demo jobs matched with keywords, execution will be aborted, at least 1 provider should be enabled')
    }
    // Limit build concurency workaround examples: https://issues.jenkins-ci.org/browse/JENKINS-44085
    parallel jobs

    if (jobResults.contains('FAILURE')) {
        common.infoMsg('One of parallel downstream jobs is failed, mark executor job as failed')
        currentBuild.result = 'FAILURE'
    }
}


/**
 * Function currently supported to be called from aws or vsphere demos. It gets particular demo context
 * and generate proper lockResources data and netMap data for vsphere,equinix related clusters.
 *
 * @param:        callBackDemo (string) Demo which requested to generate lockResources [aws or vsphere]
 * @param:        triggers (map) Custom trigger keywords forwarded from gerrit
 * @param:        multiregionalConfiguration (map) Multiregional configuration
 * @return        (map) Return aggregated map with lockResources and netMap
 */


def generateLockResources(callBackDemo, triggers) {
    def common = new com.mirantis.mk.Common()
    def netMap = [
        vsphere: [:],
        equinix: [:],
    ]
    // Define vsphere locklabels with initial quantity
    def lockLabels = [
        vsphere_networking_core_ci: 0,
        vsphere_offline_networking_core_ci: 0,
    ]
    def deployChild = triggers.deployChildEnabled
    def testUiVsphere = triggers.runUie2eEnabled
    def multiregionConfig = triggers.multiregionalConfiguration
    def runMultiregion = multiregionConfig.enabled

    // Generate vsphere netMap and lockLabels based on demo context
    switch (callBackDemo) {
        case 'aws':
            // Add aws specific lock label with quantity calculated based on single mgmt deploy or mgmt + child
            lockLabels['aws_core_ci_queue'] = triggers.demoWeight
            if (triggers.runBYOMatrixEnabled) { lockLabels['aws_core_ci_queue'] += 6 }

            // Define netMap for Vsphere region
            if (runMultiregion && multiregionConfig.managementLocation == 'aws') {
                if (multiregionConfig.regionLocation == 'vsphere') {
                    if (deployChild) {
                        addToProviderNetMap(netMap, 'vsphere', 'regional-child')
                    }
                    addToProviderNetMap(netMap, 'vsphere', 'region')
                }

                if (multiregionConfig.regionLocation == 'azure') {
                    lockLabels['azure_core_ci_queue'] = 1
                    if (deployChild) {
                        lockLabels['azure_core_ci_queue'] += 1
                    }
                }
            }
            if (triggers.azureOnAwsDemoEnabled) {
                lockLabels['azure_core_ci_queue'] = 1
            }

            if (triggers.equinixOnAwsDemoEnabled) {
                lockLabels['equinix_core_ci_queue'] = 1
            }
            break
        case 'vsphere':
            addToProviderNetMap(netMap, 'vsphere', 'mgmt')
            if (deployChild || testUiVsphere) {
                addToProviderNetMap(netMap, 'vsphere', 'child')
            }
            if (runMultiregion && multiregionConfig.managementLocation == 'vsphere' &&
                multiregionConfig.regionLocation == 'vsphere') {
                if (deployChild) {
                    addToProviderNetMap(netMap, 'vsphere', 'regional-child')
                }
                addToProviderNetMap(netMap, 'vsphere', 'region')
            }
            break
        case 'azure':
            lockLabels['azure_core_ci_queue'] = triggers.demoWeight
            if (runMultiregion && multiregionConfig.managementLocation == 'azure') {
                if (multiregionConfig.regionLocation == 'aws') {
                    lockLabels['aws_core_ci_queue'] = 1
                    if (deployChild) {
                        lockLabels['aws_core_ci_queue'] += 1
                    }
                }

                if (multiregionConfig.regionLocation == 'equinix') {
                    lockLabels['equinix_core_ci_queue'] = 1
                    if (deployChild) {
                        lockLabels['equinix_core_ci_queue'] +=1
                    }
                }
            }
        default:
            error('Supposed to be called from aws, azure or vsphere demos only')
    }

    // Checking gerrit triggers and manage lock label quantity and network types in case of Offline deployment
    // Vsphere labels only
    netMap['vsphere'].each { clusterType, netConfig ->
        if (triggers.proxyConfig["${clusterType}Offline"] == true                             ||
            (clusterType == 'regional-child' && triggers.proxyConfig['childOffline'] == true) ||
            (clusterType == 'region' && triggers.proxyConfig['mgmtOffline'])) {

            netMap['vsphere'][clusterType]['netName'] = 'offline'
            lockLabels['vsphere_offline_networking_core_ci']++
        } else {
            lockLabels['vsphere_networking_core_ci']++
        }
    }

    // generate lock metadata
    def lockResources = []
    lockLabels.each { label, quantity ->
        if (quantity > 0) {
            def res = [
                label: label,
                quantity: quantity,
            ]
            lockResources.add(res)
        }
    }

    common.infoMsg("""Generated vsphere netMap: ${netMap}
                    Generated lockResources: ${lockResources}""")

    return [
        netMap: netMap,
        lockResources: lockResources,
    ]
}

/**
 * Function gets vsphere netMap or empty map and adds new vsphere clusterType with default netName
 * and empty rangeConfig to the this map.
 *
 * @param:        netMap      (string) vsphere, equinix netMap or empty map
 * @param:        provider    (string) provider type
 * @param:        clusterType (string) Vsphere cluster type
 */

def addToProviderNetMap (netMap, provider, clusterType) {
    switch (provider) {
        case 'equinix':
            netMap[provider][clusterType] = [
                vlanConfig: '',
              ]
            break
        case 'vsphere':
            netMap[provider][clusterType] = [
                netName: 'default',
                rangeConfig: '',
              ]
            break
        default:
            error('Net map locks supported for Equinix/Vsphere providers only')
    }
}

/**
* getCIKeywordsFromCommitMsg parses commit message and returns all gerrit keywords with their values as a list of maps.
* Each element (map) contains keys 'key' for keyword name and 'value' for its value.
* If keyword contains only 'key' part then 'value' is boolean True.
* This function does not perform keywords validation.
* First line of a commit message is ignored.
* To use '[' or ']' characters inside keyword prepend it with backslash '\'.
* TODO: Remove backslash chars from values if they prepend '[' or ']'.
**/

List getCIKeywordsFromCommitMsg() {
    String commitMsg = env.GERRIT_CHANGE_COMMIT_MESSAGE ? new String(env.GERRIT_CHANGE_COMMIT_MESSAGE.decodeBase64()) : ''
    List commitMsgLines = commitMsg.split('\n')
    List keywords = []
    if (commitMsgLines.size() < 2) {
        return keywords
    }

    String commitMsgBody = commitMsgLines[1..-1].join('\n')

    // Split commit message body to chunks using '[' or ']' as delimiter,
    // ignoring them if prepended by backslash (regex negative lookbehind).
    // Resulting list will have chunks between '[' and ']' at odd indexes.
    List parts = commitMsgBody.split(/(?<!\\)[\[\]]/)

    // Iterate chunks by odd indexes only, trim values and split to
    // <key> / <value> pair where <key> is the part of a sting before the first
    // whitespace delimiter, and <value> is the rest (may include whitespaces).
    // If there is no whitespace in the string then this is a 'switch'
    // and <value> will be boolean True.
    for (i = 1; i < parts.size(); i += 2) {
        def (key, value) = (parts[i].trim().split(/\s+/, 2) + [true, ])[0..1]
        keywords.add(['key': key, 'value': value])
    }

    return keywords
}

/**
* getJobsParamsFromCommitMsg parses list of CI keywords and returns values of 'job-params' keyword
* that were specified for given job name. `job-params` keyword has the following structure
*
*   [job-params <job name> <parameter name> <parameter value>]
*
* Return value is a Map that contains those parameters using the following structure:
*
*    <job name>:
*       <parameter name>: <parameter value>
*
**/
Map getJobsParamsFromCommitMsg() {
    List keywords = getCIKeywordsFromCommitMsg()

    List jobsParamsList = []
    keywords.findAll{ it.key == 'job-params' }.collect(jobsParamsList) {
        def (name, params) = (it['value'].split(/\s+/, 2) + [null, ])[0..1]
        def (key, value) = params.split(/\s+/, 2)
        ['name': name, 'key': key, 'value': value]
    }

    Map jobsParams = jobsParamsList.inject([:]) { result, it ->
        if (!result.containsKey(it.name)) {
            result[it.name] = [:]
        }
        result[it.name][it.key] = it.value
        result
    }

    return jobsParams
}


/**
* getJobParamsFromCommitMsg returns key:value Map of parameters set for a job in commit message.
* It uses getJobsParamsFromCommitMsg to get all parameters from commit message and then
* uses only those parametes that were set to all jobs (with <job name> == '*') or to
* a particular job. Parameters set to a particular job have higher precedence.
*
* Return value is a Map that contains those parameters:
*
*    <parameter name>: <parameter value>
*
**/
Map getJobParamsFromCommitMsg(String jobName) {
    jobsParams = getJobsParamsFromCommitMsg()
    jobParams = jobsParams.getOrDefault('*', [:])
    if (jobName) {
        jobParams.putAll(jobsParams.getOrDefault(jobName, [:]))
    }
    return jobParams
}

/** Getting test scheme from text, which should be
Imput example:
text="""
 DATA

 kaas_bm_test_schemas:
  KAAS_RELEASES_REFSPEC: ''
  KEY: VAL

 DATA
 """

 Call: parseTextForTestSchemas(['text' : text,'keyLine' : 'kaas_bm_test_schemas'])

 Return:
 ['KAAS_RELEASES_REFSPEC': '', 'KEY' : 'VAL']
 **/
def parseTextForTestSchemas(Map opts) {
    String text = opts.getOrDefault('text', '')
    String keyLine = opts.getOrDefault('keyLine', '')
    Map testScheme = [:]
    if (!text || !keyLine) {
        return testScheme
    }
    if (text =~ /\n$keyLine\n.*/) {
        def common = new com.mirantis.mk.Common()
        try {
            String regExp = '\\n' + keyLine + '\\n'
            // regexep  block must be followed by empty line
            testScheme = readYaml text: "${text.split(regExp)[1].split('\n\n')[0]}"
            common.infoMsg("parseTextForTestSchemas result:\n" + testScheme)
            common.mergeEnv(env, toJson(testScheme))
        }
        catch (Exception e) {
            common.errorMsg("There is an error occured during parseTextForTestSchemas execution:\n${e}")
            throw e
        }
    }
    return testScheme
}


/**
* getEquinixMetrosWithCapacity returns list of Equinix metros using specified
* instance type (nodeType) and desired count of instances (nodeCount) in a metro.
* Function downloads metal CLI from the
* https://github.com/equinix/metal-cli/releases/download/v0.9.0/metal-linux-amd64
* Empty list is returned in case of errors.
*
* @param:        nodeCount (int)      Desired count of instances
* @param:        nodeType  (string)   Instance type
* @return                  ([]string) List of selected metros
*
**/
def getEquinixMetrosWithCapacity(nodeCount = 10, nodeType = 'c3.small.x86', version = '0.9.0') {
    def common = new com.mirantis.mk.Common()
    def metalUrl = "https://artifactory.mcp.mirantis.net:443/artifactory/binary-dev-kaas-local/core/bin/mirror/metal-${version}-linux"
    def metros = []
    def out = ''
    try {
        sh "curl -o metal -# ${metalUrl} && chmod +x metal"
        withCredentials([string(credentialsId: env.KAAS_EQUINIX_API_TOKEN, variable: 'KAAS_EQUINIX_API_TOKEN')]) {
            sh 'echo "project-id: ${KAAS_EQUINIX_PROJECT_ID}\ntoken: ${KAAS_EQUINIX_API_TOKEN}" >metal.yaml'
            out = sh(script: "./metal --config metal.yaml capacity get -m -P ${nodeType}|awk '/${nodeType}/ {print \$2}'|paste -s -d,|xargs ./metal --config metal.yaml capacity check -P ${nodeType} -q ${nodeCount} -m|grep true|awk '{print \$2}'|paste -s -d,", returnStdout: true).trim()
            sh 'rm metal.yaml'
        }
        metros = out.tokenize(',')
    } catch (Exception e) {
        common.errorMsg "Exception: '${e}'"
        return []
    }
    return metros
}
