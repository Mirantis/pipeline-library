package com.mirantis.mk

/**
 *
 * Run a simple workflow
 *
 * Function runScenario() executes a sequence of jobs, like
 * - Parameters for the jobs are taken from the 'env' object
 * - URLs of artifacts from completed jobs may be passed
 *   as parameters to the next jobs.
 *
 * No constants, environment specific logic or other conditional dependencies.
 * All the logic should be placed in the workflow jobs, and perform necessary
 * actions depending on the job parameters.
 * The runScenario() function only provides the
 *
 */


/**
 * Run a Jenkins job using the collected parameters
 *
 * @param job_name          Name of the running job
 * @param job_parameters    Map that declares which values from global_variables should be used, in the following format:
 *                          {'PARAM_NAME': {'type': <job parameter $class name>, 'use_variable': <a key from global_variables>}, ...}
 *                          or
 *                          {'PARAM_NAME': {'type': <job parameter $class name>, 'get_variable_from_url': <a key from global_variables which contains URL with required content>}, ...}
 *                          or
 *                          {'PARAM_NAME': {'type': <job parameter $class name>, 'use_template': <a GString multiline template with variables from global_variables>}, ...}
 * @param global_variables  Map that keeps the artifact URLs and used 'env' objects:
 *                          {'PARAM1_NAME': <param1 value>, 'PARAM2_NAME': 'http://.../artifacts/param2_value', ...}
 * @param propagate         Boolean. If false: allows to collect artifacts after job is finished, even with FAILURE status
 *                          If true: immediatelly fails the pipeline. DO NOT USE 'true' if you want to collect artifacts
 *                          for 'finally' steps
 */
def runJob(job_name, job_parameters, global_variables, Boolean propagate = false) {
    def parameters = []
    def http = new com.mirantis.mk.Http()
    def engine = new groovy.text.GStringTemplateEngine()
    def template
    def base = [:]
    base["url"] = ''
    def variable_content

    // Collect required parameters from 'global_variables' or 'env'
    for (param in job_parameters) {
        if (param.value.containsKey('use_variable')) {
            if (!global_variables[param.value.use_variable]) {
                global_variables[param.value.use_variable] = env[param.value.use_variable] ?: ''
            }
            parameters.add([$class: "${param.value.type}", name: "${param.key}", value: global_variables[param.value.use_variable]])
            println "${param.key}: <${param.value.type}> ${global_variables[param.value.use_variable]}"
        } else if (param.value.containsKey('get_variable_from_url')) {
            if (!global_variables[param.value.get_variable_from_url]) {
                global_variables[param.value.get_variable_from_url] = env[param.value.get_variable_from_url] ?: ''
            }
            if (global_variables[param.value.get_variable_from_url]) {
                variable_content = http.restGet(base, global_variables[param.value.get_variable_from_url]).trim()
                parameters.add([$class: "${param.value.type}", name: "${param.key}", value: variable_content])
                println "${param.key}: <${param.value.type}> ${variable_content}"
            } else {
                println "${param.key} is empty, skipping get_variable_from_url"
            }
        } else if (param.value.containsKey('use_template')) {
            template = engine.createTemplate(param.value.use_template).make(global_variables)
            parameters.add([$class: "${param.value.type}", name: "${param.key}", value: template.toString()])
            println "${param.key}: <${param.value.type}>\n${template.toString()}"
        }
    }

    // Build the job
    def job_info = build job: "${job_name}", parameters: parameters, propagate: propagate
    return job_info
}

/**
 * Store URLs of the specified artifacts to the global_variables
 *
 * @param build_url         URL of the completed job
 * @param step_artifacts    Map that contains artifact names in the job, and variable names
 *                          where the URLs to that atrifacts should be stored, for example:
 *                          {'ARTIFACT1': 'logs.tar.gz', 'ARTIFACT2': 'test_report.xml', ...}
 * @param global_variables  Map that will keep the artifact URLs. Variable 'ARTIFACT1', for example,
 *                          be used in next job parameters: {'ARTIFACT1_URL':{ 'use_variable': 'ARTIFACT1', ...}}
 *
 *                          If the artifact with the specified name not found, the parameter ARTIFACT1_URL
 *                          will be empty.
 *
 */
def storeArtifacts(build_url, step_artifacts, global_variables, job_name, build_num) {
    def common = new com.mirantis.mk.Common()
    def http = new com.mirantis.mk.Http()
    def baseJenkins = [:]
    def baseArtifactory = [:]
    build_url = build_url.replaceAll(~/\/+$/, "")
    artifactory_url = "https://artifactory.mcp.mirantis.net/api/storage/si-local/jenkins-job-artifacts"
    baseArtifactory["url"] = artifactory_url + "/${job_name}/${build_num}"

    baseJenkins["url"] = build_url
    def job_config = http.restGet(baseJenkins, "/api/json/")
    def job_artifacts = job_config['artifacts']
    for (artifact in step_artifacts) {
        try {
            artifactoryResp = http.restGet(baseArtifactory, "/${artifact.value}")
            global_variables[artifact.key] = artifactoryResp.downloadUri
            println "Artifact URL ${artifactoryResp.downloadUri} stored to ${artifact.key}"
            continue
        } catch (Exception e) {
            common.warningMsg("Can't find an artifact in ${artifactory_url}/${job_name}/${build_num}/${artifact.value} error code ${e.message}")
        }

        job_artifact = job_artifacts.findAll { item -> artifact.value == item['fileName'] || artifact.value == item['relativePath'] }
        if (job_artifact.size() == 1) {
            // Store artifact URL
            def artifact_url = "${build_url}/artifact/${job_artifact[0]['relativePath']}"
            global_variables[artifact.key] = artifact_url
            println "Artifact URL ${artifact_url} stored to ${artifact.key}"
        } else if (job_artifact.size() > 1) {
            // Error: too many artifacts with the same name, fail the job
            error "Multiple artifacts ${artifact.value} for ${artifact.key} found in the build results ${build_url}, expected one:\n${job_artifact}"
        } else {
            // Warning: no artifact with expected name
            println "Artifact ${artifact.value} for ${artifact.key} not found in the build results ${build_url} and in the artifactory ${artifactory_url}/${job_name}/${build_num}/, found the following artifacts in Jenkins:\n${job_artifacts}"
            global_variables[artifact.key] = ''
        }
    }
}


/**
 * Run the workflow or final steps one by one
 *
 * @param steps                   List of steps (Jenkins jobs) to execute
 * @param global_variables        Map where the collected artifact URLs and 'env' objects are stored
 * @param failed_jobs             Map with failed job names and result statuses, to report it later
 * @param propagate               Boolean. If false: allows to collect artifacts after job is finished, even with FAILURE status
 *                                If true: immediatelly fails the pipeline. DO NOT USE 'true' with runScenario().
 */
def runSteps(steps, global_variables, failed_jobs, Boolean propagate = false) {
    for (step in steps) {
        stage("Running job ${step['job']}") {

            def job_name = step['job']
            def job_parameters = step['parameters']
            // Collect job parameters and run the job
            def job_info = runJob(job_name, job_parameters, global_variables, propagate)
            def job_result = job_info.getResult()
            def build_url = job_info.getAbsoluteUrl()
            def build_description = job_info.getDescription()
            def build_id = job_info.getId()

            currentBuild.description += "<a href=${build_url}>${job_name}</a>: ${job_result}<br>"
            // Import the remote build description into the current build
            if (build_description) { // TODO -  add also the job status
                currentBuild.description += build_description
            }

            // Store links to the resulting artifacts into 'global_variables'
            storeArtifacts(build_url, step['artifacts'], global_variables, job_name, build_id)

            // Job failed, fail the build or keep going depending on 'ignore_failed' flag
            if (job_result != "SUCCESS") {
                def job_ignore_failed = step['ignore_failed'] ?: false
                failed_jobs[build_url] = job_result
                if (job_ignore_failed) {
                    println "Job ${build_url} finished with result: ${job_result}"
                } else {
                    currentBuild.result = job_result
                    error "Job ${build_url} finished with result: ${job_result}"
                }
            } // if (job_result == "SUCCESS")
        } // stage ("Running job ${step['job']}")
    } // for (step in scenario['workflow'])
}

/**
 * Run the workflow scenario
 *
 * @param scenario: Map with scenario steps.

 * There are two keys in the scenario:
 *   workflow: contains steps to run deploy and test jobs
 *   finally: contains steps to run report and cleanup jobs
 *
 * Scenario execution example:
 *
 *     scenario_yaml = """\
 *     workflow:
 *     - job: deploy-kaas
 *       ignore_failed: false
 *       parameters:
 *         KAAS_VERSION:
 *           type: StringParameterValue
 *           use_variable: KAAS_VERSION
 *       artifacts:
 *         KUBECONFIG_ARTIFACT: artifacts/management_kubeconfig
 *         DEPLOYED_KAAS_VERSION: artifacts/management_version
 *
 *     - job: test-kaas-ui
 *       ignore_failed: false
 *       parameters:
 *         KUBECONFIG_ARTIFACT_URL:
 *           type: StringParameterValue
 *           use_variable: KUBECONFIG_ARTIFACT
 *         KAAS_VERSION:
 *           type: StringParameterValue
 *           get_variable_from_url: DEPLOYED_KAAS_VERSION
 *       artifacts:
 *         REPORT_SI_KAAS_UI: artifacts/test_kaas_ui_result.xml
 *
 *     finally:
 *     - job: testrail-report
 *       ignore_failed: true
 *       parameters:
 *         KAAS_VERSION:
 *           type: StringParameterValue
 *           get_variable_from_url: DEPLOYED_KAAS_VERSION
 *         REPORTS_LIST:
 *           type: TextParameterValue
 *           use_template: |
 *             REPORT_SI_KAAS_UI: \$REPORT_SI_KAAS_UI
 *     """
 *
 *     runScenario(scenario)
 *
 */

def runScenario(scenario, slackReportChannel = '') {

    // Clear description before adding new messages
    currentBuild.description = ''
    // Collect the parameters for the jobs here
    global_variables = [:]
    // List of failed jobs to show at the end
    failed_jobs = [:]

    try {
        // Run the 'workflow' jobs
        runSteps(scenario['workflow'], global_variables, failed_jobs)

    } catch (InterruptedException x) {
        error "The job was aborted"

    } catch (e) {
        error("Build failed: " + e.toString())

    } finally {
        // Run the 'finally' jobs
        runSteps(scenario['finally'], global_variables, failed_jobs)

        if (failed_jobs) {
            statuses = []
            failed_jobs.each {
                statuses += it.value
                }
            if (statuses.contains('FAILURE')) {
                currentBuild.result = 'FAILURE'
            }
            else if (statuses.contains('UNSTABLE')) {
                currentBuild.result = 'UNSTABLE'
            }
            else {
                currentBuild.result = 'FAILURE'
            }
            println "Failed jobs: ${failed_jobs}"
        }

        if (slackReportChannel) {
            def slack = new com.mirantis.mcp.SlackNotification()
            slack.jobResultNotification(currentBuild.result, slackReportChannel, '', null, '', 'slack_webhook_url')
        }
    } // finally
}
