package com.mirantis.mk

import static groovy.json.JsonOutput.toJson
import java.util.regex.Pattern

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
    def fullUpgradeChild = env.FULL_UPGRADE_CHILD_CLUSTER ? env.FULL_UPGRADE_CHILD_CLUSTER.toBoolean() : false
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
    def deployOsOnMos = env.DEPLOY_OS_ON_MOS? env.DEPLOY_OS_ON_MOS.toBoolean() : false
    def runUie2e = env.RUN_UI_E2E ? env.RUN_UI_E2E.toBoolean() : false
    def runUie2eNew = env.RUN_UI_E2E_NEW ? env.RUN_UI_E2E_NEW.toBoolean() : false
    def runMgmtConformance = env.RUN_MGMT_CFM ? env.RUN_MGMT_CFM.toBoolean() : false
    def runMgmtConformanceNetworkPolicy = env.RUN_MGMT_CFM_NETWORK_POLICY ? env.RUN_MGMT_CFM_NETWORK_POLICY.toBoolean() : false
    def runLMATest = env.RUN_LMA_TEST ? env.RUN_LMA_TEST.toBoolean() : false
    def runMgmtUserControllerTest = env.RUN_MGMT_USER_CONTROLLER_TEST ? env.RUN_MGMT_USER_CONTROLLER_TEST.toBoolean() : false
    def runProxyChildTest = env.RUN_PROXY_CHILD_TEST ? env.RUN_PROXY_CHILD_TEST.toBoolean() : false
    def runChildConformance = env.RUN_CHILD_CFM ? env.RUN_CHILD_CFM.toBoolean() : false
    def runChildStacklightHa = env.RUN_STACKLIGHT_CHILD_HA ? env.RUN_STACKLIGHT_CHILD_HA.toBoolean() : false
    def runChildConformanceNetworkPolicy = env.RUN_CHILD_CFM_NETWORK_POLICY ? env.RUN_CHILD_CFM_NETWORK_POLICY.toBoolean() : false
    def runChildHPA = env.RUN_CHILD_HPA ? env.RUN_CHILD_HPA.toBoolean() : false
    def fetchServiceBinaries = env.FETCH_BINARIES_FROM_UPSTREAM ? env.FETCH_BINARIES_FROM_UPSTREAM.toBoolean() : false
    def equinixMetalV2ChildDiffMetro = env.EQUINIXMETALV2_CHILD_DIFF_METRO ? env.EQUINIXMETALV2_CHILD_DIFF_METRO.toBoolean() : false
    def runMaintenanceTest = env.RUN_MAINTENANCE_TEST ? env.RUN_MAINTENANCE_TEST.toBoolean() : false
    def runContainerregistryTest = env.RUN_CONTAINER_REGISTRY_TEST ? env.RUN_CONTAINER_REGISTRY_TEST.toBoolean() : false
    def runMgmtDeleteMasterTest = env.RUN_MGMT_DELETE_MASTER_TEST ? env.RUN_MGMT_DELETE_MASTER_TEST.toBoolean() : false
    def runRgnlDeleteMasterTest = env.RUN_RGNL_DELETE_MASTER_TEST ? env.RUN_RGNL_DELETE_MASTER_TEST.toBoolean() : false
    def runChildDeleteMasterTest = env.RUN_CHILD_DELETE_MASTER_TEST ? env.RUN_CHILD_DELETE_MASTER_TEST.toBoolean() : false
    def runGracefulRebootTest = env.RUN_GRACEFUL_REBOOT_TEST ? env.RUN_GRACEFUL_REBOOT_TEST.toBoolean() : false
    def pauseForDebug = env.PAUSE_FOR_DEBUG ? env.PAUSE_FOR_DEBUG.toBoolean() : false
    def runChildMachineDeletionPolicyTest = env.RUN_CHILD_MACHINE_DELETION_POLICY_TEST ? env.RUN_CHILD_MACHINE_DELETION_POLICY_TEST.toBoolean() : false
    def runChildCustomCertTest = env.RUN_CHILD_CUSTOM_CERT_TEST ? env.RUN_CHILD_CUSTOM_CERT_TEST.toBoolean() : false
    def runByoChildCustomCertTest = env.RUN_BYO_CHILD_CUSTOM_CERT_TEST ? env.RUN_BYO_CHILD_CUSTOM_CERT_TEST.toBoolean() : false
    def runMgmtCustomCacheCertTest = env.RUN_MGMT_CUSTOM_CACHE_CERT_TEST ? env.RUN_MGMT_CUSTOM_CACHE_CERT_TEST.toBoolean() : false
    def runMkeCustomCertTest = env.RUN_MKE_CUSTOM_CERT_TEST ? env.RUN_MKE_CUSTOM_CERT_TEST.toBoolean() : false
    def runCustomHostnames = env.RUN_CUSTOM_HOSTNAMES ? env.RUN_CUSTOM_HOSTNAMES.toBoolean() : false
    def slLatest = env.SL_LATEST ? env.SL_LATEST.toBoolean() : false
    def coreKeycloakLdap = env.CORE_KEYCLOAK_LDAP_ENABLED ? env.CORE_KEYCLOAK_LDAP_ENABLED.toBoolean() : false
    def configureInternalNTP = env.CORE_KAAS_NTP_ENABLED ? env.CORE_KAAS_NTP_ENABLED.toBoolean() : false
    def disableKubeApiAudit = env.DISABLE_KUBE_API_AUDIT ? env.DISABLE_KUBE_API_AUDIT.toBoolean() : false
    def auditd = env.AUDITD_ENABLE ? env.AUDITD_ENABLE.toBoolean() : false
    def customSlackChannel = env.SLACK_CHANNEL_NOTIFY ? env.SLACK_CHANNEL_NOTIFY : ''
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
    def enablebmCoreDemo = env.ALLOW_BM_CORE_ON_DEMAND ? env.ALLOW_BM_CORE_ON_DEMAND.toBoolean() : false
    def bmCoreCleanup = env.BM_CORE_CLEANUP ? env.BM_CORE_CLEANUP.toBoolean() : true
    def enableArtifactsBuild = true
    def bmDeployType = env.BM_DEPLOY_TYPE ? env.BM_DEPLOY_TYPE.toString() : 'virtual'
    def openstackIMC = env.OPENSTACK_CLOUD_LOCATION ? env.OPENSTACK_CLOUD_LOCATION : 'us'
    def enableVsphereUbuntu = env.VSPHERE_DEPLOY_UBUNTU ? env.VSPHERE_DEPLOY_UBUNTU.toBoolean() : false
    def enableVsphereRHEL = env.VSPHERE_DEPLOY_RHEL ? env.VSPHERE_DEPLOY_RHEL.toBoolean() : false
    def childOsBootFromVolume = env.OPENSTACK_BOOT_FROM_VOLUME ? env.OPENSTACK_BOOT_FROM_VOLUME.toBoolean() : false
    def bootstrapV2Scenario = env.BOOTSTRAP_V2_ENABLED ? env.BOOTSTRAP_V2_ENABLED.toBoolean() : false
    def equinixMetalV2Metro = env.EQUINIX_MGMT_METRO ? env.EQUINIX_MGMT_METRO : ''
    def enableFips = env.ENABLE_FIPS ? env.ENABLE_FIPS.toBoolean() : false
    def enableMkeDebug = env.ENABLE_MKE_DEBUG ? env.ENABLE_MKE_DEBUG.toBoolean() : false
    def aioCluster = env.AIO_CLUSTER ? env.AIO_CLUSTER.toBoolean() : false
    def useVsphereVvmtObjects = env.VSPHERE_USE_VVMT_OBJECTS ? env.VSPHERE_USE_VVMT_OBJECTS.toBoolean() : false
    def enableBv2Smoke = true
    def runCacheWarmup = env.CACHE_WARMUP_ENABLED ? env.CACHE_WARMUP_ENABLED.toBoolean() : false
    def cveScan = false

    def commitMsg = env.GERRIT_CHANGE_COMMIT_MESSAGE ? new String(env.GERRIT_CHANGE_COMMIT_MESSAGE.decodeBase64()) : ''
    if (commitMsg ==~ /(?s).*\[mgmt-proxy\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*mgmt-proxy.*/) {
        proxyConfig['mgmtOffline'] = true
        common.warningMsg('Forced running offline mgmt deployment, some provider CDN regions for mgmt deployment may be set to *public-ci* to verify proxy configuration')
    }
    if (commitMsg ==~ /(?s).*\[mgmt-upgrade\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*mgmt-upgrade.*/) {
        upgradeMgmt = true
    }
    if (commitMsg ==~ /(?s).*\[auto-upgrade\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*auto-upgrade.*/) {
        autoUpgradeMgmt = true
    }
    if (commitMsg ==~ /(?s).*\[seed-macos\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*seed-macos.*/) {
        seedMacOs = true
    }
    if (commitMsg ==~ /(?s).*\[child-deploy\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*child-deploy.*/ ||
            upgradeChild || runChildConformance || runProxyChildTest || runChildHPA || runChildConformanceNetworkPolicy) {
        deployChild = true
    }
    if (commitMsg ==~ /(?s).*\[child-upgrade\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*child-upgrade.*/) {
        deployChild = true
        upgradeChild = true
    }
    if (commitMsg ==~ /(?s).*\[child-upgrade-full\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*child-upgrade-full.*/) {
        deployChild = true
        upgradeChild = true
        common.warningMsg("2-step child updates are not testing (PRODX-33510)")
        //TODO: revert after start testing the two-step upgrade again (PRODX-33510)
        //fullUpgradeChild = true
    }
    if ((upgradeMgmt || autoUpgradeMgmt) && deployChild) {
        upgradeChild = true
        common.warningMsg('child upgrade is automatically enabled as mgmt upgrade and child deploy are enabled')
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
    if ((upgradeMgmt || autoUpgradeMgmt) && mosDeployChild) {
        mosUpgradeChild = true
        common.warningMsg('MOSK child upgrade is automatically enabled as mgmt upgrade and MOSK child deploy are enabled')
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
    if (commitMsg ==~ /(?s).*\[lma-logging\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*lma-logging.*/) {
        enableLMALogging = true
    }
    if (commitMsg ==~ /(?s).*\[deploy-os-on-mos\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*deploy-os-on-mos.*/) {
        deployOsOnMos = true
        mosDeployChild = true
    }

    if (commitMsg ==~ /(?s).*\[half-virtual\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*half-virtual.*/ || upgradeMgmt) {
        bmDeployType = 'half-virtual'
        common.infoMsg('Half-virtual will be deployed by default on upgrade case')
    }

    if (commitMsg ==~ /(?s).*\[ui-e2e-nw\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*ui-e2e-nw.*/) {
        runUie2e = true
    }
    if (commitMsg ==~ /(?s).*\[ui-e2e-pw\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*ui-e2e-pw.*/) {
        runUie2eNew = true
    }
    if (commitMsg ==~ /(?s).*\[mgmt-cfm\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*mgmt-cfm.*/) {
        runMgmtConformance = true
    }
    if (commitMsg ==~ /(?s).*\[mgmt-cfm-netpolicy\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*mgmt-cfm-netpolicy.*/) {
        runMgmtConformanceNetworkPolicy = true
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
    if (commitMsg ==~ /(?s).*\[child-cfm-netpolicy\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*child-cfm-netpolicy.*/) {
        runChildConformanceNetworkPolicy = true
        deployChild = true
    }
    if (commitMsg ==~ /(?s).*\[child-hpa\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*child-hpa.*/) {
        runChildHPA = true
        deployChild = true
    }
    if (commitMsg ==~ /(?s).*\[child-sl-ha\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*child-sl-ha.*/) {
        runChildStacklightHa = true
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
        if (!mosDeployChild) {
            deployChild = true
        }
        runChildDeleteMasterTest = true
        common.infoMsg('Child cluster deployment will be enabled since delete child master node test suite will be executed')
    }
    if (commitMsg ==~ /(?s).*\[child-machine-deletion-policy-test\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*child-machine-deletion-policy-test.*/) {
        runChildMachineDeletionPolicyTest = true
        deployChild = true
        common.infoMsg('Child cluster deployment will be enabled since machine deletion child policy test suite will be executed')
    }
    if (commitMsg ==~ /(?s).*\[graceful-reboot-test\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*graceful-reboot-test.*/) {
        runGracefulRebootTest = true
    }
    if (commitMsg ==~ /(?s).*\[pause-for-debug\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*pause-for-debug.*/) {
        pauseForDebug = true
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
    if (commitMsg ==~ /(?s).*\[disable-all-demo\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*disable-all-demo\.*/) {
        enableVsphereDemo = false
        enableOSDemo = false
        enableBMDemo = false
        enableBv2Smoke = false
        common.errorMsg('vSphere, BM, Openstack, demo deployments and Bootstrap v2 smoke checks will be aborted, VF -1 will be set')
    }

    if (commitMsg ==~ /(?s).*\[disable-os-demo\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*disable-os-demo\.*/) {
        enableOSDemo = false
        common.errorMsg('Openstack demo deployment will be aborted, VF -1 will be set')
    }

    if (commitMsg ==~ /(?s).*\[disable-bm-demo\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*disable-bm-demo\.*/) {
        enableBMDemo = false
        common.errorMsg('BM demo deployment will be aborted, VF -1 will be set')
    }

    if (commitMsg ==~ /(?s).*\[bm-core-demo\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*bm-core-demo\\.*/) {
        enablebmCoreDemo = true
        enableBMDemo = false
    }

    if (commitMsg ==~ /(?s).*\[disable-bm-core-cleanup\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*disable-bm-core-cleanup\\.*/) {
        bmCoreCleanup = false
    }

    if (commitMsg ==~ /(?s).*\[disable-vsphere-demo\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*disable-vsphere-demo\.*/) {
        enableVsphereDemo = false
        common.errorMsg('vSphere demo deployment will be aborted, VF -1 will be set')
    }
    if (commitMsg ==~ /(?s).*\[vsphere-ubuntu\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*vsphere-ubuntu\.*/) {
        enableVsphereUbuntu = true
        common.warningMsg('Ubuntu will be used to deploy vsphere machines')
    }
    if (commitMsg ==~ /(?s).*\[vsphere-rhel\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*vsphere-rhel\.*/) {
        enableVsphereRHEL = true
        common.warningMsg('RHEL will be used to deploy vsphere machines')
    }
    if (commitMsg ==~ /(?s).*\[disable-bv2-smoke\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*disable-bv2-smoke\.*/) {
        enableBv2Smoke = false
        common.errorMsg('Bootstrap v2 smoke checks will be aborted, WF -1 will be set')
    }

    if (commitMsg ==~ /(?s).*\[cve-scan\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*cve-scan\.*/) {
        cveScan = true
        common.errorMsg('CVE Scan job enabled')
    }

    def slackChannelMatches = (commitMsg =~ /(\[slack-channel\s*[#@](\S+)])/)
    if (slackChannelMatches.size() > 0) {
        // override chanenel notify when it set explicitly
        customSlackChannel = slackChannelMatches[0][0].split("slack-channel")[1].replaceAll('[\\[\\]]', '').trim()
        common.warningMsg("Forced send notify to ${customSlackChannel} channel")
    }

    if (commitMsg ==~ /(?s).*\[disable-artifacts-build\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*disable-artifacts-build\.*/) {
        enableArtifactsBuild = false
        common.errorMsg('artifacts build will be aborted, VF -1 will be set')
    }

    if (commitMsg ==~ /(?s).*\[child-os-boot-from-volume\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*child-os-boot-from-volume\.*/) {
        childOsBootFromVolume = true
        common.warningMsg('OS will be booted from Ceph volumes')
    }

    if (commitMsg ==~ /(?s).*\[child-custom-cert-test\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*child-custom-cert-test\.*/) {
        runChildCustomCertTest = true
        deployChild = true
        common.warningMsg('Child cluster deployment will be enabled since custom cert child test suite will be executed')
    }

    if (commitMsg ==~ /(?s).*\[mgmt-custom-cache-cert-test\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*mgmt-custom-cache-cert-test\.*/) {
        runMgmtCustomCacheCertTest = true
        deployChild = true
        common.warningMsg('Child cluster deployment will be enabled as the test replaces the mgmt and cluster childcertificates')
    }

    if (commitMsg ==~ /(?s).*\[mke-custom-cert-test\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*mke-custom-cert-test\.*/) {
        runMkeCustomCertTest = true
    }

    if (commitMsg ==~ /(?s).*\[custom-hostnames\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*custom-hostnames\.*/) {
        runCustomHostnames = true
        common.warningMsg('All clusters will be deployed with Custom Hostnames')
    }

    if (commitMsg ==~ /(?s).*\[sl-latest\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*sl-latest\.*/) {
        slLatest = true
        common.warningMsg('All clusters will be deployed with Stacklight version from artifact-metadata')
    }

    if (commitMsg ==~ /(?s).*\[keycloak-ldap\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*keycloak-ldap\.*/) {
        coreKeycloakLdap = true
        common.warningMsg('Management cluster will be deployed with LDAP integration enabled and after-deployment checks will be executed')
    }

    if (commitMsg ==~ /(?s).*\[internal-ntp\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*internal-ntp\.*/ || proxyConfig['mgmtOffline'] || proxyConfig['childOffline']) {
        configureInternalNTP = true
        common.warningMsg('Internal NTP servers will be used')
    }

    if (commitMsg ==~ /(?s).*\[disable-kube-api-audit\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*disable-kube-api-audit\.*/) {
        disableKubeApiAudit = true
        common.warningMsg('Disable KUBE API audit for mgmt cluster')
    }

    if (commitMsg ==~ /(?s).*\[auditd\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*disable-kube-api-audit\.*/) {
        auditd = true
    }

    if (commitMsg ==~ /(?s).*\[byo-child-custom-cert-test\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*byo-child-custom-cert-test\.*/) {
        runByoChildCustomCertTest = true
        attachBYO = true
        common.warningMsg('Byo child cluster deployment will be enabled since custom cert child test suite will be executed')
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

    if (commitMsg ==~ /(?s).*\[aio-cluster\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*aio-cluster.*/) {
        aioCluster = true
    }

    if (commitMsg ==~ /(?s).*\[cache-warmup\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*cache-warmup.*/) {
        runCacheWarmup = true
    }

    if (runCacheWarmup && (!deployChild && !mosDeployChild)) {
        runCacheWarmup = false
        common.errorMsg('Child cluster deployment is not enabled, skipping Cache Warmup')
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
            openstack: 'internal-ci',
            vsphere:  'internal-ci',
            aws: 'public-ci',
            equinix: 'public-ci',
            azure: 'public-ci',
        ],
    ]

    if (commitMsg ==~ /(?s).*\[eu-demo\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*eu-demo.*/) {
        openstackIMC = 'eu'
    }

    if (openstackIMC == 'eu') {
        // use internal-eu because on internal-ci with eu cloud image pull takes much time
        def cdnRegion = 'internal-eu'
        cdnConfig['mgmt']['openstack'] = cdnRegion
    }

    // calculate weight of current demo run to manage lockable resources
    def demoWeight = deployChild ? 2 : 1 // management = 1, child += 1
    if (runUie2e || runUie2eNew) {
        demoWeight += 1
    }

    if (commitMsg ==~ /(?s).*\[bootstrapv1-scenario\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*bootstrapv1-scenario\.*/) {
        bootstrapV2Scenario = false
    }

    if (commitMsg ==~ /(?s).*\[bootstrapv2-scenario\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*bootstrapv2-scenario\.*/) {
        bootstrapV2Scenario = true
    }

    if (commitMsg ==~ /(?s).*\[enable-fips\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*enable-fips\.*/) {
        enableFips = true
    }

    if (commitMsg ==~ /(?s).*\[enable-mke-debug\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*enable-mke-debug\.*/) {
        enableMkeDebug = true
    }

    if (commitMsg ==~ /(?s).*\[vsphere-vvmt-obj\].*/ || env.GERRIT_EVENT_COMMENT_TEXT ==~ /(?s).*vsphere-vvmt-obj\.*/) {
        useVsphereVvmtObjects = true
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
        Full Child cluster release upgrade scheduled: ${fullUpgradeChild}
        MOS child deploy scheduled: ${mosDeployChild}
        MOS child upgrade scheduled: ${mosUpgradeChild}
        Child conformance testing scheduled: ${runChildConformance}
        Child conformance network policy testing scheduled: ${runChildConformanceNetworkPolicy}
        Child HPA testing scheduled: ${runChildHPA}
        Child Stacklight HA: ${runChildStacklightHa}
        Single BYO cluster attachment scheduled: ${attachBYO}
        Single Attached BYO cluster upgrade test scheduled: ${upgradeBYO}
        BYO test matrix whole suite scheduled: ${runBYOMatrix}
        Default BYO OS: ${defaultBYOOs}
        Mgmt cluster release upgrade scheduled: ${upgradeMgmt}
        Mgmt cluster release auto upgrade scheduled: ${autoUpgradeMgmt}
        Mgmt LMA logging enabled: ${enableLMALogging}
        Deploy Os on child with mos release ${deployOsOnMos}
        Mgmt conformance testing scheduled: ${runMgmtConformance}
        Mgmt conformance network policy testing scheduled: ${runMgmtConformanceNetworkPolicy}
        LMA testing scheduled: ${runLMATest}
        Mgmt user controller testing scheduled: ${runMgmtUserControllerTest}
        Mgmt UI e2e testing scheduled: ${runUie2e}
        Mgmt UI e2e playwrite testing scheduled: ${runUie2eNew}
        Maintenance test: ${runMaintenanceTest}
        Container Registry test: ${runContainerregistryTest}
        Child proxy test: ${runProxyChildTest}
        Graceful reboot test: ${runGracefulRebootTest}
        Delete mgmt master node test: ${runMgmtDeleteMasterTest}
        Delete rgnl master node test: ${runRgnlDeleteMasterTest}
        Delete child master node test: ${runChildDeleteMasterTest}
        Child machine deletion policy test: ${runChildMachineDeletionPolicyTest}
        Custom cert test for child clusters: ${runChildCustomCertTest}
        Custom cert test for Byo child clusters: ${runByoChildCustomCertTest}
        Custom cache cert test for mgmt and child clusters: ${runMgmtCustomCacheCertTest}
        MKE custom cert test for mgmt/region: ${runMkeCustomCertTest}
        Custom hostnames for all clisuers: ${runCustomHostnames}
        Stacklight templates enchanced with latest version from artifact-metadata: ${slLatest}
        Disable Kubernetes API audit: ${disableKubeApiAudit}
        Enable Auditd : ${auditd}
        AWS provider deployment scheduled: ${awsOnDemandDemo}
        Equinix provider deployment scheduled: ${equinixOnDemandDemo}
        EquinixmetalV2 provider deployment scheduled: ${equinixMetalV2OnDemandDemo}
        EquinixmetalV2 child deploy in a separate metro scheduled: ${equinixMetalV2ChildDiffMetro}
        EquinixmetalV2 mgmt will be deployed on the metro: ${equinixMetalV2Metro?:'auto'}
        Equinix@AWS child cluster deployment scheduled: ${equinixOnAwsDemo}
        Azure provider deployment scheduled: ${azureOnDemandDemo}
        Azure@AWS child cluster deployment scheduled: ${azureOnAwsDemo}
        VSPHERE provider deployment scheduled: ${enableVsphereDemo}
        OS provider deployment scheduled: ${enableOSDemo}
        BM Core provider deployment scheduled: ${enablebmCoreDemo}
        BM Core type deplyment: ${bmDeployType}
        BM Core cleanup: ${bmCoreCleanup}
        BM provider deployment scheduled: ${enableBMDemo}
        Ubuntu on vSphere scheduled: ${enableVsphereUbuntu}
        RHEL on vSphere scheduled: ${enableVsphereRHEL}
        Artifacts build scheduled: ${enableArtifactsBuild}
        Boot OS child from Ceph volumes: ${childOsBootFromVolume}
        Multiregional configuration: ${multiregionalMappings}
        Service binaries fetching scheduled: ${fetchServiceBinaries}
        Current weight of the demo run: ${demoWeight} (Used to manage lockable resources)
        Bootstrap v2 scenario enabled: ${bootstrapV2Scenario}
        FIPS enabled: ${enableFips}
        MKE DEBUG enabled: ${enableMkeDebug}
        Pause for debug enabled: ${pauseForDebug}
        AIO cluster: ${aioCluster}
        Use Vsphere VVMT Objects: ${useVsphereVvmtObjects}
        Bootsrap v2 smoke checks enabled: ${enableBv2Smoke}
        Run Cache warmup for child clusters: ${runCacheWarmup}
        CVE Scan enabled: ${cveScan}
        Keycloak+LDAP integration enabled: ${coreKeycloakLdap}
        Triggers: https://gerrit.mcp.mirantis.com/plugins/gitiles/kaas/core/+/refs/heads/master/hack/ci-gerrit-keywords.md""")
    return [
        osCloudLocation                          : openstackIMC,
        cdnConfig                                : cdnConfig,
        proxyConfig                              : proxyConfig,
        useMacOsSeedNode                         : seedMacOs,
        deployChildEnabled                       : deployChild,
        childDeployCustomRelease                 : customChildRelease,
        upgradeChildEnabled                      : upgradeChild,
        fullUpgradeChildEnabled                  : fullUpgradeChild,
        mosDeployChildEnabled                    : mosDeployChild,
        mosUpgradeChildEnabled                   : mosUpgradeChild,
        runChildConformanceEnabled               : runChildConformance,
        runChildConformanceNetworkPolicyEnabled  : runChildConformanceNetworkPolicy,
        runChildHPAEnabled                       : runChildHPA,
        runChildStacklightHaEnabled              : runChildStacklightHa,
        attachBYOEnabled                         : attachBYO,
        upgradeBYOEnabled                        : upgradeBYO,
        runBYOMatrixEnabled                      : runBYOMatrix,
        defaultBYOOs                             : defaultBYOOs,
        upgradeMgmtEnabled                       : upgradeMgmt,
        autoUpgradeMgmtEnabled                   : autoUpgradeMgmt,
        enableLMALoggingEnabled                  : enableLMALogging,
        deployOsOnMosEnabled                     : deployOsOnMos,
        runUie2eEnabled                          : runUie2e,
        runUie2eNewEnabled                       : runUie2eNew,
        runMgmtConformanceEnabled                : runMgmtConformance,
        runMgmtConformanceNetworkPolicyEnabled   : runMgmtConformanceNetworkPolicy,
        runMaintenanceTestEnabled                : runMaintenanceTest,
        runContainerregistryTestEnabled          : runContainerregistryTest,
        runGracefulRebootTestEnabled             : runGracefulRebootTest,
        pauseForDebugEnabled                     : pauseForDebug,
        runMgmtDeleteMasterTestEnabled           : runMgmtDeleteMasterTest,
        runRgnlDeleteMasterTestEnabled           : runRgnlDeleteMasterTest,
        runChildDeleteMasterTestEnabled          : runChildDeleteMasterTest,
        runChildCustomCertTestEnabled            : runChildCustomCertTest,
        customSlackChannelEnabled                : customSlackChannel,
        runMgmtCustomCacheCertTestEnabled        : runMgmtCustomCacheCertTest,
        runMkeCustomCertTestEnabled              : runMkeCustomCertTest,
        runCustomHostnamesEnabled                : runCustomHostnames,
        slLatestEnabled                          : slLatest,
        runByoChildCustomCertTestEnabled         : runByoChildCustomCertTest,
        runChildMachineDeletionPolicyTestEnabled : runChildMachineDeletionPolicyTest,
        runLMATestEnabled                        : runLMATest,
        runMgmtUserControllerTestEnabled         : runMgmtUserControllerTest,
        runProxyChildTestEnabled                 : runProxyChildTest,
        fetchServiceBinariesEnabled              : fetchServiceBinaries,
        awsOnDemandDemoEnabled                   : awsOnDemandDemo,
        equinixOnDemandDemoEnabled               : equinixOnDemandDemo,
        equinixMetalV2OnDemandDemoEnabled        : equinixMetalV2OnDemandDemo,
        equinixMetalV2ChildDiffMetroEnabled      : equinixMetalV2ChildDiffMetro,
        equinixOnAwsDemoEnabled                  : equinixOnAwsDemo,
        azureOnDemandDemoEnabled                 : azureOnDemandDemo,
        azureOnAwsDemoEnabled                    : azureOnAwsDemo,
        vsphereDemoEnabled                       : enableVsphereDemo,
        bmDemoEnabled                            : enableBMDemo,
        bmCoreDemoEnabled                        : enablebmCoreDemo,
        bmCoreCleanup                            : bmCoreCleanup,
        bmDeployTypeEnabled                      : bmDeployType,
        osDemoEnabled                            : enableOSDemo,
        vsphereUbuntuEnabled                     : enableVsphereUbuntu,
        vsphereRHELEnabled                       : enableVsphereRHEL,
        artifactsBuildEnabled                    : enableArtifactsBuild,
        childOsBootFromVolume                    : childOsBootFromVolume,
        multiregionalConfiguration               : multiregionalMappings,
        demoWeight                               : demoWeight,
        bootstrapV2Scenario                      : bootstrapV2Scenario,
        equinixMetalV2Metro                      : equinixMetalV2Metro,
        enableFips                               : enableFips,
        enableMkeDebugEnabled                    : enableMkeDebug,
        aioCluster                               : aioCluster,
        useVsphereVvmtObjects                    : useVsphereVvmtObjects,
        bv2SmokeEnabled                          : enableBv2Smoke,
        runCacheWarmup                           : runCacheWarmup,
        cveScanEnabled                           : cveScan,
        disableKubeApiAudit                      : disableKubeApiAudit,
        auditdEnabled                            : auditd,
        coreKeycloakLdapEnabled                  : coreKeycloakLdap,
        internalNTPServersEnabled                : configureInternalNTP,
    ]
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
 *                                        siTestsFeatureFlagsStable (string) dedicated feature flags that will be used in SI tests for deploying stable release
 *                                  ]
 */
def parseKaaSComponentCIParameters(configurationFile){
   def common = new com.mirantis.mk.Common()
   def ciConfig = readYaml file: configurationFile
   def ciSpec = [
   siTestsFeatureFlags: env.SI_TESTS_FEATURE_FLAGS ?: '',
   siTestsFeatureFlagsStable: env.SI_TESTS_FEATURE_FLAGS_STABLE ?: '',
   ]

   // If exists and not empty
   if (ciConfig.getOrDefault('si-tests-feature-flags', [])) {
       common.infoMsg("""SI tests feature flags customization detected,
           results will be merged with existing flags: [${ciSpec['siTestsFeatureFlags']}] identification...""")

       def ffMeta = ciSpec['siTestsFeatureFlags'].tokenize(',').collect { it.trim() }
       ffMeta.addAll(ciConfig['si-tests-feature-flags'])

       ciSpec['siTestsFeatureFlags'] = ffMeta.unique().join(',')
       common.infoMsg("SI tests custom feature flags: ${ciSpec['siTestsFeatureFlags']}")
   }
   if (ciConfig.getOrDefault('si-tests-feature-flags-stable', [])) {
       common.infoMsg("""SI tests feature flags for stable release customization detected,
           results will be merged with existing flags: [${ciSpec['siTestsFeatureFlagsStable']}] identification...""")

       def ffMeta = ciSpec['siTestsFeatureFlagsStable'].tokenize(',').collect { it.trim() }
       ffMeta.addAll(ciConfig['si-tests-feature-flags-stable'])

       ciSpec['siTestsFeatureFlagsStable'] = ffMeta.unique().join(',')
       common.infoMsg("SI tests custom feature flags for stable release: ${ciSpec['siTestsFeatureFlagsStable']}")
   }

   common.infoMsg("""Additional ci configuration parsed successfully:
       siTestsFeatureFlags: ${ciSpec['siTestsFeatureFlags']}
       siTestsFeatureFlagsStable: ${ciSpec['siTestsFeatureFlagsStable']}""")
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
        if (triggers.bmCoreDemoEnabled) {
            platforms.add('bm')
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
        string(name: 'SLACK_CHANNEL_NOTIFY', value: triggers.customSlackChannelEnabled),
        booleanParam(name: 'OFFLINE_MGMT_CLUSTER', value: triggers.proxyConfig['mgmtOffline']),
        booleanParam(name: 'OFFLINE_CHILD_CLUSTER', value: triggers.proxyConfig['childOffline']),
        booleanParam(name: 'PROXY_CHILD_CLUSTER', value: triggers.proxyConfig['childProxy']),
        booleanParam(name: 'SEED_MACOS', value: triggers.useMacOsSeedNode),
        booleanParam(name: 'UPGRADE_MGMT_CLUSTER', value: triggers.upgradeMgmtEnabled),
        booleanParam(name: 'AUTO_UPGRADE_MCC', value: triggers.autoUpgradeMgmtEnabled),
        booleanParam(name: 'ENABLE_LMA_LOGGING', value: triggers.enableLMALoggingEnabled),
        booleanParam(name: 'DEPLOY_OS_ON_MOS', value: triggers.deployOsOnMosEnabled),
        booleanParam(name: 'RUN_UI_E2E', value: triggers.runUie2eEnabled),
        booleanParam(name: 'RUN_MGMT_CFM', value: triggers.runMgmtConformanceEnabled),
        booleanParam(name: 'RUN_MGMT_CFM_NETWORK_POLICY', value: triggers.runMgmtConformanceNetworkPolicyEnabled),
        booleanParam(name: 'RUN_MAINTENANCE_TEST', value: triggers.runMaintenanceTestEnabled),
        booleanParam(name: 'RUN_CONTAINER_REGISTRY_TEST', value: triggers.runContainerregistryTestEnabled),
        booleanParam(name: 'RUN_GRACEFUL_REBOOT_TEST', value: triggers.runGracefulRebootTestEnabled),
        booleanParam(name: 'RUN_MGMT_DELETE_MASTER_TEST', value: triggers.runMgmtDeleteMasterTestEnabled),
        booleanParam(name: 'RUN_RGNL_DELETE_MASTER_TEST', value: triggers.runRgnlDeleteMasterTestEnabled),
        booleanParam(name: 'RUN_CHILD_DELETE_MASTER_TEST', value: triggers.runChildDeleteMasterTestEnabled),
        booleanParam(name: 'RUN_CHILD_CUSTOM_CERT_TEST', value: triggers.runChildCustomCertTestEnabled),
        booleanParam(name: 'RUN_MGMT_CUSTOM_CACHE_CERT_TEST', value: triggers.runMgmtCustomCacheCertTestEnabled),
        booleanParam(name: 'RUN_MKE_CUSTOM_CERT_TEST', value: triggers.runMkeCustomCertTestEnabled),
        booleanParam(name: 'RUN_CUSTOM_HOSTNAMES', value: triggers.runCustomHostnamesEnabled),
        booleanParam(name: 'SL_LATEST', value: triggers.slLatestEnabled),
        booleanParam(name: 'RUN_BYO_CHILD_CUSTOM_CERT_TEST', value: triggers.runByoChildCustomCertTestEnabled),
        booleanParam(name: 'RUN_CHILD_MACHINE_DELETION_POLICY_TEST', value: triggers.runChildMachineDeletionPolicyTestEnabled),
        booleanParam(name: 'RUN_LMA_TEST', value: triggers.runLMATestEnabled),
        booleanParam(name: 'RUN_MGMT_USER_CONTROLLER_TEST', value: triggers.runMgmtUserControllerTestEnabled),
        booleanParam(name: 'DEPLOY_CHILD_CLUSTER', value: triggers.deployChildEnabled),
        booleanParam(name: 'UPGRADE_CHILD_CLUSTER', value: triggers.upgradeChildEnabled),
        booleanParam(name: 'FULL_UPGRADE_CHILD_CLUSTER', value: triggers.fullUpgradeChildEnabled),
        booleanParam(name: 'RUN_PROXY_CHILD_TEST', value: triggers.runProxyChildTestEnabled),
        booleanParam(name: 'ATTACH_BYO', value: triggers.attachBYOEnabled),
        booleanParam(name: 'UPGRADE_BYO', value: triggers.upgradeBYOEnabled),
        booleanParam(name: 'RUN_BYO_MATRIX', value: triggers.runBYOMatrixEnabled),
        booleanParam(name: 'RUN_CHILD_CFM', value: triggers.runChildConformanceEnabled),
        booleanParam(name: 'RUN_CHILD_CFM_NETPOLICY', value: triggers.runChildConformanceNetworkPolicyEnabled),
        booleanParam(name: 'RUN_CHILD_HPA', value: triggers.runChildHPAEnabled),
        booleanParam(name: 'RUN_STACKLIGHT_CHILD_HA', value: triggers.runChildStacklightHaEnabled),
        booleanParam(name: 'ALLOW_AWS_ON_DEMAND', value: triggers.awsOnDemandDemoEnabled),
        booleanParam(name: 'ALLOW_EQUINIX_ON_DEMAND', value: triggers.equinixOnDemandDemoEnabled),
        booleanParam(name: 'ALLOW_EQUINIXMETALV2_ON_DEMAND', value: triggers.equinixMetalV2OnDemandDemoEnabled),
        booleanParam(name: 'EQUINIXMETALV2_CHILD_DIFF_METRO', value: triggers.equinixMetalV2ChildDiffMetroEnabled),
        booleanParam(name: 'EQUINIX_ON_AWS_DEMO', value: triggers.equinixOnAwsDemoEnabled),
        booleanParam(name: 'ALLOW_AZURE_ON_DEMAND', value: triggers.azureOnDemandDemoEnabled),
        booleanParam(name: 'AZURE_ON_AWS_DEMO', value: triggers.azureOnAwsDemoEnabled),
        booleanParam(name: 'ALLOW_BM_CORE_ON_DEMAND', value: triggers.bmCoreDemoEnabled),
        booleanParam(name: 'VSPHERE_DEPLOY_UBUNTU', value: triggers.vsphereUbuntuEnabled),
        booleanParam(name: 'PAUSE_FOR_DEBUG', value: triggers.pauseForDebugEnabled),
        booleanParam(name: 'ENABLE_FIPS', value: triggers.enableFips),
        booleanParam(name: 'ENABLE_MKE_DUBUG', value: triggers.enableMkeDebugEnabled),
        booleanParam(name: 'AIO_CLUSTER', value: triggers.aioCluster),
        booleanParam(name: 'BM_CORE_CLEANUP', value: triggers.bmCoreCleanup),
        booleanParam(name: 'BM_DEPLOY_TYPE', value: triggers.bmDeployTypeEnabled),
        booleanParam(name: 'DISABLE_KUBE_API_AUDIT', value: triggers.disableKubeApiAudit),
        booleanParam(name: "AUDITD_ENABLE", value: triggers.auditdEnabled),
        booleanParam(name: 'CORE_KEYCLOAK_LDAP_ENABLED', value: triggers.coreKeycloakLdapEnabled),
        booleanParam(name: 'CORE_KAAS_NTP_ENABLED', value: triggers.internalNTPServersEnabled)
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
    def testUiVsphere = triggers.runUie2eEnabled || triggers.runUie2eNewEnabled
    def vsphereByo = triggers.attachBYOEnabled
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
            if (deployChild || testUiVsphere || vsphereByo) {
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
* getEquinixMetroWithCapacity returns list of Equinix metros using specified
* instance type (nodeType), desired count of metros (metroCount) and
* instances (nodeCount) in a metro using specified matal version.
* Function downloads metal CLI from the
* https://artifactory.mcp.mirantis.net:443/artifactory/binary-dev-kaas-local/core/bin/mirror/metal-${version}-linux
* Empty list is returned in case of no metros with specified capacity was found or any other errors.
* Non-empty list is shuffled.
*
* @param:        metroCount     (int) Desired count of metros
* @param:        nodeCount      (int) Desired count of instances
* @param:        nodeType    (string) Instance type
* @param:        version     (string) Metal version to use
* @return                  ([]string) List of selected metros
*
**/
def getEquinixMetroWithCapacity(metroCount = 1, nodeCount = 50, nodeType = 'c3.small.x86', version = '0.9.0') {
    def common = new com.mirantis.mk.Common()
    def metalUrl = "https://artifactory.mcp.mirantis.net:443/artifactory/binary-dev-kaas-local/core/bin/mirror/metal-${version}-linux"
    def metal = './metal --config metal.yaml'
    def metro = []
    def out = ''
    def retries = 3 // number of retries
    def i = 0
    def delay = 60 // 1 minute sleep
    def excludeMetro = [] // list of metros to exclude from selection
    try {
        if (excludeMetro.size() > 0) {
            common.infoMsg("Excluded metros: ${excludeMetros}")
        }
        sh "curl -o metal -# ${metalUrl} && chmod +x metal"
        withCredentials([string(credentialsId: env.KAAS_EQUINIX_API_TOKEN, variable: 'KAAS_EQUINIX_API_TOKEN')]) {
            sh 'echo "project-id: ${KAAS_EQUINIX_PROJECT_ID}\ntoken: ${KAAS_EQUINIX_API_TOKEN}" >metal.yaml'
        }
        while (metro.size() < metroCount && i < retries) {
            common.infoMsg("Selecting ${metroCount} available Equinix metros with free ${nodeCount} ${nodeType} hosts, try ${i+1}/${retries} ...")
            if (i > 0) { // skip sleep on first step
                sleep(delay)
            }
            out = sh(script: "${metal} capacity get -m -P ${nodeType}|awk '/${nodeType}/ {print \$2}'|paste -s -d,|xargs ${metal} capacity check -P ${nodeType} -q ${nodeCount} -m|grep true|awk '{print \$2}'|paste -s -d,", returnStdout: true).trim()
            metro = out.tokenize(',')
            metro -= excludeMetro
            if (metro.size() < metroCount) {
                nodeCount -= 10
            // We need different metros for the [equinixmetalv2-child-diff-metro] case
            } else if (metro.size() == 2 && metro[0][0, 1] == metro[1][0, 1]) {
                nodeCount -= 10
            }
            i++
        }
        if (metro.size() > 0) {
            m = metro.size() > 1 ? "${metro[0]},${metro[1]}" : "${metro[0]}"
            sh "${metal} capacity check -P ${nodeType} -m ${m} -q ${nodeCount}"
        }
    } catch (Exception e) {
        common.errorMsg "Exception: '${e}'"
        return []
    } finally {
        sh 'rm metal.yaml'
    }
    if (metro.size() > 0) {
        Collections.shuffle(metro)
        common.infoMsg("Selected metros: ${metro}")
    } else {
        common.warningMsg('No any metros have been selected !!! :(')
    }
    return metro
}


/**
* getEquinixFacilityWithCapacity returns list of Equinix facilities using specified
* instance type (nodeType), desired count of facilities (facilityCount) and
* instances (nodeCount) in a facility using specified matal version.
* Function downloads metal CLI from the
* https://artifactory.mcp.mirantis.net:443/artifactory/binary-dev-kaas-local/core/bin/mirror/metal-${version}-linux
* Empty list is returned in case of no facilities with specified capacity was found or any other errors.
* Non-empty list is shuffled.
*
* @param:        facilityCount  (int) Desired count of facilities
* @param:        nodeCount      (int) Desired count of instances
* @param:        nodeType    (string) Instance type
* @param:        version     (string) Metal version to use
* @return                  ([]string) List of selected facilities
*
**/
@Deprecated
def getEquinixFacilityWithCapacity(facilityCount = 1, nodeCount = 50, nodeType = 'c3.small.x86', version = '0.9.0') {
    def common = new com.mirantis.mk.Common()
    common.warningMsg('You are using deprecated method getEquinixFacilityWithCapacity. Use getEquinixMetroWithCapacity instead')
    def metalUrl = "https://artifactory.mcp.mirantis.net:443/artifactory/binary-dev-kaas-local/core/bin/mirror/metal-${version}-linux"
    def metal = './metal --config metal.yaml'
    def facility = []
    def out = ''
    def retries = 3 // number of retries
    def i = 0
    def delay = 60 // 1 minute sleep
    def excludeFacility = [] // list of facilities to exclude from selection
    try {
        if (excludeFacility.size() > 0) {
            common.infoMsg("Excluded facilities: ${excludeFacility}")
        }
        sh "curl -o metal -# ${metalUrl} && chmod +x metal"
        withCredentials([string(credentialsId: env.KAAS_EQUINIX_API_TOKEN, variable: 'KAAS_EQUINIX_API_TOKEN')]) {
            sh 'echo "project-id: ${KAAS_EQUINIX_PROJECT_ID}\ntoken: ${KAAS_EQUINIX_API_TOKEN}" >metal.yaml'
        }
        while (facility.size() < facilityCount && i < retries) {
            common.infoMsg("Selecting ${facilityCount} available Equinix facilities with free ${nodeCount} ${nodeType} hosts, try ${i+1}/${retries} ...")
            if (i > 0 ) { // skip sleep on first step
                sleep(delay)
            }
            out = sh(script: "${metal} capacity get -f -P ${nodeType}|awk '/${nodeType}/ {print \$2}'|paste -s -d,|xargs ${metal} capacity check -P ${nodeType} -q ${nodeCount} -f|grep true|awk '{print \$2}'|paste -s -d,", returnStdout: true).trim()
            facility = out.tokenize(',')
            facility -= excludeFacility
            if (facility.size() < facilityCount) {
                nodeCount -= 10
            // We need different metros for the [equinixmetalv2-child-diff-metro] case, facility[][0, 1] contains a metro name
            } else if (facility.size() == 2 && facility[0][0, 1] == facility[1][0, 1]) {
                nodeCount -= 10
            }
            i++
        }
        if (facility.size() > 0) {
            f = facility.size() > 1 ? "${facility[0]},${facility[1]}" : "${facility[0]}"
            sh "${metal} capacity check -P ${nodeType} -f ${f} -q ${nodeCount}"
        }
    } catch (Exception e) {
        common.errorMsg "Exception: '${e}'"
        return []
    } finally {
        sh 'rm metal.yaml'
    }
    if (facility.size() > 0) {
        Collections.shuffle(facility)
        common.infoMsg("Selected facilities: ${facility}")
    } else {
        common.warningMsg('No any facilities have been selected !!! :(')
    }
    return facility
}


/**
 * genCommandLine prepares command line for artifactory-replication
 * command using legacy environment variables
 *
 * @return: (string) Prepared command line
 */
def genCommandLine() {
    def envToParam = [
        'DESTINATION_USER': '-dst-user',
        'ARTIFACT_FILTER': '-artifact-filter',
        'ARTIFACT_FILTER_PROD':  '-artifact-filter-prod',
        'ARTIFACT_TYPE': '-artifact-type',
        'BINARY_CLEAN': '-bin-cleanup',
        'BINARY_CLEAN_KEEP_DAYS': '-bin-clean-keep-days',
        'BINARY_CLEAN_PREFIX': '-bin-clean-prefix',
        'BUILD_URL': '-slack-build-url',
        'CHECK_REPOS': '-check-repos',
        'DESTINATION_REGISTRY': '-dst-repo',
        'DESTINATION_REGISTRY_TYPE': '-dst-repo-type',
        'SIGNED_IMAGES_PATH': '-signed-images-path',
        'DOCKER_CLEAN': '-cleanup',
        'DOCKER_OLDER_THAN_DAYS': '-older-than-days',
        'DOCKER_REPO_PREFIX': '-docker-repo-prefix',
        'DOCKER_TAG': '-docker-tag',
        'FORCE': '-force',
        'HELM_CDN_DOMAIN': '-helm-cdn-domain',
        'SLACK_CHANNEL': '-slack-channel',
        'SLACK_CHANNELS': '-slack-channels',
        'SLACK_USER': '-slack-user',
        'SOURCE_REGISTRY': '-src-repo',
        'SOURCE_REGISTRY_TYPE': '-src-repo-type',
        'SYNC_PATTERN': '-sync-pattern'
    ]
    def cmdParams = ''
    def isCheckClean = false
    for (e in envToParam) {
        if (env[e.key] == null) {
            continue
        }
        if (e.key == 'CHECK_REPOS' || e.key == 'DOCKER_CLEAN') {
            // Avoid CHECK_REPOS=true and DOCKER_CLEAN=true
            if (env[e.key].toBoolean() && !isCheckClean) {
                cmdParams += e.value + ' '
                isCheckClean = true
            }
        } else if (e.key == 'FORCE') {
            if (env[e.key].toBoolean()) {
                cmdParams += e.value + ' '
            }
        } else {
            cmdParams += "${e.value} '${env[e.key]}' "
        }
    }
    // No any check or clean was specified - take a default action
    if (!isCheckClean) {
        cmdParams += '-replicate'
    }
    return cmdParams
}

/**
 * custom scheduling algorithm
 * it ensures that builds of the same job are distributed as much as possible between different nodes
 * @param label (string) desired node label
 * @return: (string) node name
 */
def schedule (label='docker') {
    def common = new com.mirantis.mk.Common()
    def freeNodes = []
    def nodesMap = [:]

    // filter nodes with the specified label and at least one free executor
    timeout(time: 30, unit: 'MINUTES') {
        while (!freeNodes) {
            freeNodes = jenkins.model.Jenkins.instance.computers.findAll { node ->
                label in node.getAssignedLabels().collect { it.name } &&
                        node.isPartiallyIdle() &&
                        node.isOnline()
            }
            if (!freeNodes) {
                echo 'No nodes available for scheduling, retrying...'
                sleep 30
            }
        }
    }

    // generate a map of nodes matching other criteria
    for (node in freeNodes) {
        // sameJobExecutors is the number of executors running the same job as the calling one
        sameJobExecutors = node.getExecutors() // get all executors
                .collect { executor -> executor.getCurrentExecutable() } // get running "threads"
                .collect { thread -> thread?.displayName } // filter job names from threads
                .minus(null) // null = empty executors, remove them from the list
                .findAll { it.contains(env.JOB_NAME) } // filter the same jobs as the calling one
                .size()

        // calculate busy executors, we don't want to count "sameJobExecutors" twice
        totalBusyExecutors = node.countBusy() - sameJobExecutors
        // generate the final map which contains nodes matching criteria with their load score
        // builds of the same jobs have x10 score, all others x1
        nodesMap += ["${node.getName()}" : sameJobExecutors * 10 + totalBusyExecutors]
    }

    // return the least loaded node
    return common.SortMapByValueAsc(nodesMap).collect { it.key }[0]
}


/**
 * Get latest tag for test/frontend & equinix-private-infra images
 * @param version   (str)    default tag value from main workflow
 * @param isChanged (bool)   is dependent directory files were changed
 * @param imageName (string) image name for information message
 * @return:         (string) tag name
 */
def getImageTag(version, isChanged, imageName) {
    def common = new com.mirantis.mk.Common()
    def latestTag = ''
    if (env.GERRIT_EVENT_TYPE && !(env.GERRIT_EVENT_TYPE in ['change-merged', 'ref-updated']) && isChanged) {
        latestTag = version
    } else {
        if (env.GERRIT_EVENT_TYPE == 'ref-updated') {
            latestTag = env.GERRIT_REFNAME.replace('refs/tags/v', '').trim()
        } else {
            latestTag = env.GERRIT_BRANCH ? env.GERRIT_BRANCH : env.GERRIT_REFSPEC ? env.GERRIT_REFSPEC : 'master'
            if (latestTag != 'master') {
                latestTag = latestTag.replaceAll('/', '_')
            }
        }
    }
    common.infoMsg("${imageName} image will use tag '${latestTag}'")
    return latestTag
}

/**
 * Get actual branch version for os deployment job
 * @param mosChildPreviouseComplexRelease   (string) kaas_previous_complex_mosk_cluster_release_version.txt
 * @param mosChildLatestComplexRelease      (string) kaas_latest_complex_mosk_cluster_release_version.txt
 * @param upgradeFlag                       (boolean)
 * all parametrs get from si-test-release-sanity-check-prepare-configuration job
 * @return:         (string) branch verison
 */
def getOpenstackbranchVersion(mosChildPreviouseComplexRelease, mosChildLatestComplexRelease, upgradeFlag) {
    def common = new com.mirantis.mk.Common()
    def regex = Pattern.compile('([a-z]+)-([0-9]+-[0-9]+-[0-9]+)-([a-z]*)-?([0-9]+-[0-9]+-?[0-9]*)')

    def mosVersionBranch = upgradeFlag ? mosChildPreviouseComplexRelease : mosChildLatestComplexRelease
    def matcherComplexVersion = regex.matcher((mosVersionBranch).toString())
    def releaseOpenstackK8sBranch = 'master'

    if (matcherComplexVersion.find()) {
        def matcherComplexVersionParts = matcherComplexVersion.group(2).split('-')
        releaseOpenstackK8sBranch = String.format('%s.%s.%s', matcherComplexVersionParts[0], matcherComplexVersionParts[1], '0')
    }
    common.infoMsg("Use: OPENSTACK_DEPLOY_RELEASE_DIR ${releaseOpenstackK8sBranch}")
    return releaseOpenstackK8sBranch
}



/**
 * Translates set of environment vars into actual replicator command line
 * @return: (string cmdParams, string jobDescription)
 *      cmdParams      - generated command line
 *      jobDescription - job description
 */
def genReplicatorCommandLine() {
    def mainModes = ['REPLICATE', 'CLEANUP', 'CHECK_REPOS', 'BIN_CLEANUP']
    def parameterWithoutArgument = mainModes
    def parametersList = parameterWithoutArgument + [
        'ARTIFACT_FILTER',
        'ARTIFACT_TYPE',
        'BIN_CLEAN_KEEP_DAYS',
        'BIN_CLEAN_PREFIX',
        'DOCKER_TAG',
        // DST_ will be changed to TARGET_
        'DST_REPO',
        'DST_REPO_TYPE',
        'DST_USER',
        'OLDER_THAN_DAYS',
        'SLACK_BUILD_URL',
        'SLACK_CHANNEL',
        'SLACK_USER',
        'SRC_REPO',
        'SRC_REPO_TYPE',
        'SRC_USER',
        'SYNC_PATTERN',
        'THREAD_COUNT'
    ]
    def mainModesDescriptions = [
        'REPLICATE': 'Replicating binaries/Docker images',
        'CLEANUP': 'Cleaning Docker images',
        'BIN_CLEANUP': 'Cleaning binaries',
        'CHECK_REPOS': 'Checking binaries'
    ]

    def cmdParams = ''
    def jobDescription = ''
    for (e in parametersList) {
        if (env[e] == null || env[e] == '') {
            continue
        }
        if (e in mainModes) {
            jobDescription = mainModesDescriptions[e]
        }
        cmdParams += "-${e.replaceAll('_', '-').toLowerCase()} "
        if (!(e in parameterWithoutArgument)) {
            cmdParams += "'${env[e]}' "
        }
    }
    return [cmdParams, jobDescription]
}
