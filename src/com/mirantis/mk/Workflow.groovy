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
 * Print 'global_variables' accumulated during workflow execution, including
 * collected artifacts.
 * Output is prepared in format that can be copy-pasted into groovy code
 * to replay the workflow using the already created artifacts.
 *
 * @param global_variables  Map that keeps the artifact URLs and used 'env' objects:
 *                          {'PARAM1_NAME': <param1 value>, 'PARAM2_NAME': 'http://.../artifacts/param2_value', ...}
 */
def printVariables(global_variables) {
    def message = "// Collected global_variables during the workflow:\n"
    for (variable in global_variables) {
        message += "env.${variable.key}=\"\"\"${variable.value}\"\"\"\n"
    }
    common.warningMsg(message)
}

/**
 * Get Jenkins parameter names, values and types from jobName
 * @param jobName job name
 * @return Map with parameter names as keys and the following map as values:
 *  [
 *    <str name1>: [type: <str cls1>, use_variable: <str name1>, defaultValue: <cls value1>],
 *    <str name2>: [type: <str cls2>, use_variable: <str name2>, defaultValue: <cls value2>],
 *  ]
 */
def getJobDefaultParameters(jobName) {
    def jenkinsUtils = new com.mirantis.mk.JenkinsUtils()
    def item = jenkinsUtils.getJobByName(env.JOB_NAME)
    def parameters = [:]
    def prop = item.getProperty(ParametersDefinitionProperty.class)
    if (prop != null) {
        for (param in prop.getParameterDefinitions()) {
            def defaultParam = param.getDefaultParameterValue()
            def cls = defaultParam.getClass().getName()
            def value = defaultParam.getValue()
            def name = defaultParam.getName()
            parameters[name] = [type: cls, use_variable: name, defaultValue: value]
        }
    }
    return parameters
}

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
 *                          or
 *                          {'PARAM_NAME': {'type': <job parameter $class name>, 'get_variable_from_yaml': {'yaml_url': <URL with YAML content>,
 *                                                                                                          'yaml_key': <a groovy-interpolating path to the key in the YAML, starting from dot '.'> } }, ...}
 * @param global_variables  Map that keeps the artifact URLs and used 'env' objects:
 *                          {'PARAM1_NAME': <param1 value>, 'PARAM2_NAME': 'http://.../artifacts/param2_value', ...}
 * @param propagate         Boolean. If false: allows to collect artifacts after job is finished, even with FAILURE status
 *                          If true: immediatelly fails the pipeline. DO NOT USE 'true' if you want to collect artifacts
 *                          for 'finally' steps
 */
def runJob(job_name, job_parameters, global_variables, Boolean propagate = false) {
    def parameters = []
    def common = new com.mirantis.mk.Common()
    def http = new com.mirantis.mk.Http()
    def engine = new groovy.text.GStringTemplateEngine()
    def template
    def yamls_from_urls = [:]
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
            common.infoMsg("${param.key}: <${param.value.type}> ${global_variables[param.value.use_variable]}")
        } else if (param.value.containsKey('get_variable_from_url')) {
            if (!global_variables[param.value.get_variable_from_url]) {
                global_variables[param.value.get_variable_from_url] = env[param.value.get_variable_from_url] ?: ''
            }
            if (global_variables[param.value.get_variable_from_url]) {
                variable_content = http.restGet(base, global_variables[param.value.get_variable_from_url]).trim()
                parameters.add([$class: "${param.value.type}", name: "${param.key}", value: variable_content])
                common.infoMsg("${param.key}: <${param.value.type}> ${variable_content}")
            } else {
                common.warningMsg("${param.key} is empty, skipping get_variable_from_url")
            }
        } else if (param.value.containsKey('get_variable_from_yaml')) {
            if (param.value.get_variable_from_yaml.containsKey('yaml_url') && param.value.get_variable_from_yaml.containsKey('yaml_key')) {
                // YAML url is stored in an environment or a global variable (like 'SI_CONFIG_ARTIFACT')
                def yaml_url_var = param.value.get_variable_from_yaml.yaml_url
                if (!global_variables[yaml_url_var]) {
                    global_variables[yaml_url_var] = env[yaml_url_var] ?: ''
                }
                yaml_url = global_variables[yaml_url_var]  // Real YAML URL
                yaml_key = param.value.get_variable_from_yaml.yaml_key
                // Key to get the data from YAML, to interpolate in the groovy, for example:
                //  <yaml_map_variable>.key.to.the[0].required.data , where yaml_key = '.key.to.the[0].required.data'
                if (yaml_url) {
                    if (!yamls_from_urls[yaml_url]) {
                        common.infoMsg("Reading YAML from ${yaml_url} for ${param.key}")
                        yaml_content = http.restGet(base, yaml_url)
                        yamls_from_urls[yaml_url] = readYaml text: yaml_content
                    }
                    common.infoMsg("Getting key ${yaml_key} from YAML ${yaml_url} for ${param.key}")
                    template_variables = [
                      'yaml_data': yamls_from_urls[yaml_url]
                    ]
                    request = "\${yaml_data${yaml_key}}"
                    def result
                    // Catch errors related to wrong key or index in the list or map objects
                    // For wrong key in map or wrong index in list, groovy returns <null> object,
                    // but it can be catched only after the string interpolation <template.toString()>,
                    // so we should catch the string 'null' instead of object <null>.
                    try {
                        template = engine.createTemplate(request).make(template_variables)
                        result = template.toString()
                        if (result == 'null') {
                            error "No such key or index, got 'null'"
                        }
                    } catch (e) {
                        error("Failed to get the key ${yaml_key} from YAML ${yaml_url}: " + e.toString())
                    }

                    parameters.add([$class: "${param.value.type}", name: "${param.key}", value: result])
                    common.infoMsg("${param.key}: <${param.value.type}>\n${result}")
                } else {
                    common.warningMsg("'yaml_url' in ${param.key} is empty, skipping get_variable_from_yaml")
                }
            } else {
                common.warningMsg("${param.key} missing 'yaml_url'/'yaml_key' parameters, skipping get_variable_from_yaml")
            }
        } else if (param.value.containsKey('use_template')) {
            template = engine.createTemplate(param.value.use_template).make(global_variables)
            parameters.add([$class: "${param.value.type}", name: "${param.key}", value: template.toString()])
            common.infoMsg("${param.key}: <${param.value.type}>\n${template.toString()}")
        }
    }

    // Build the job
    def job_info = build job: "${job_name}", parameters: parameters, propagate: propagate
    return job_info
}

def runOrGetJob(job_name, job_parameters, global_variables, propagate, String fullTaskName = '') {
    /**
     *  Run job directly or try to find already executed build
     *  Flow, in case CI_JOBS_OVERRIDES passed:
     *
     *
     *  CI_JOBS_OVERRIDES = text in yaml|json format
     *  CI_JOBS_OVERRIDES = 'kaas-testing-core-release-artifact'                : 3505
     *                     'reindex-testing-core-release-index-with-rc'        : 2822
     *                     'si-test-release-sanity-check-prepare-configuration': 1877
     */
    common = new com.mirantis.mk.Common()
    def jobsOverrides = readYaml(text: env.CI_JOBS_OVERRIDES ?: '---') ?: [:]
    // get id of overriding job
    def jobOverrideID = jobsOverrides.getOrDefault(fullTaskName, '')
    if (fullTaskName in jobsOverrides.keySet()) {
        common.warningMsg("Overriding: ${fullTaskName}/${job_name} <<< ${jobOverrideID}")
        common.infoMsg("For debug pin use:\n'${fullTaskName}' : ${jobOverrideID}")
        return Jenkins.instance.getItemByFullName(job_name,
          hudson.model.Job.class).getBuildByNumber(jobOverrideID.toInteger())
    } else {
        return runJob(job_name, job_parameters, global_variables, propagate)
    }
}

/**
 * Store URLs of the specified artifacts to the global_variables
 *
 * @param build_url           URL of the completed job
 * @param step_artifacts      Map that contains artifact names in the job, and variable names
 *                            where the URLs to that atrifacts should be stored, for example:
 *                            {'ARTIFACT1': 'logs.tar.gz', 'ARTIFACT2': 'test_report.xml', ...}
 * @param global_variables    Map that will keep the artifact URLs. Variable 'ARTIFACT1', for example,
 *                            be used in next job parameters: {'ARTIFACT1_URL':{ 'use_variable': 'ARTIFACT1', ...}}
 *
 *                            If the artifact with the specified name not found, the parameter ARTIFACT1_URL
 *                            will be empty.
 * @param artifactory_server  Artifactory server ID defined in Jenkins config
 *
 */
def storeArtifacts(build_url, step_artifacts, global_variables, job_name, build_num, artifactory_url = '', artifactory_server = '') {
    def common = new com.mirantis.mk.Common()
    def http = new com.mirantis.mk.Http()
    def artifactory = new com.mirantis.mcp.MCPArtifactory()
    if(!artifactory_url && !artifactory_server) {
        artifactory_url = 'https://artifactory.mcp.mirantis.net/artifactory/api/storage/si-local/jenkins-job-artifacts'
    } else if (!artifactory_url && artifactory_server) {
        artifactory_url = artifactory.getArtifactoryServer(artifactory_server).getUrl() + '/artifactory/api/storage/si-local/jenkins-job-artifacts'
    }

    def baseJenkins = [:]
    def baseArtifactory = [:]
    build_url = build_url.replaceAll(~/\/+$/, "")
    baseArtifactory["url"] = artifactory_url + "/${job_name}/${build_num}"
    baseJenkins["url"] = build_url
    def job_config = http.restGet(baseJenkins, "/api/json/")
    def job_artifacts = job_config['artifacts']
    common.infoMsg("Attempt to storeArtifacts for: ${job_name}/${build_num}")
    for (artifact in step_artifacts) {
        try {
            def artifactoryResp = http.restGet(baseArtifactory, "/${artifact.value}")
            global_variables[artifact.key] = artifactoryResp.downloadUri
            common.infoMsg("Artifact URL ${artifactoryResp.downloadUri} stored to ${artifact.key}")
            continue
        } catch (Exception e) {
            common.warningMsg("Can't find an artifact in ${artifactory_url}/${job_name}/${build_num}/${artifact.value} to store in ${artifact.key}\n" +
              "error code ${e.message}")
        }

        def job_artifact = job_artifacts.findAll { item -> artifact.value == item['fileName'] || artifact.value == item['relativePath'] }
        if (job_artifact.size() == 1) {
            // Store artifact URL
            def artifact_url = "${build_url}/artifact/${job_artifact[0]['relativePath']}"
            global_variables[artifact.key] = artifact_url
            common.infoMsg("Artifact URL ${artifact_url} stored to ${artifact.key}")
        } else if (job_artifact.size() > 1) {
            // Error: too many artifacts with the same name, fail the job
            error "Multiple artifacts ${artifact.value} for ${artifact.key} found in the build results ${build_url}, expected one:\n${job_artifact}"
        } else {
            // Warning: no artifact with expected name
            common.warningMsg("Artifact ${artifact.value} for ${artifact.key} not found in the build results ${build_url} and in the artifactory ${artifactory_url}/${job_name}/${build_num}/, found the following artifacts in Jenkins:\n${job_artifacts}")
            global_variables[artifact.key] = ''
        }
    }
}

/**
 * Update workflow job build description
 *
 * @param jobs_data               Map with all job names and result statuses, to showing it in description
 */
def updateDescription(jobs_data) {
    def common = new com.mirantis.mk.Common()
    def table = ''
    def child_jobs_description = '<strong>Descriptions from jobs:</strong><br>'
    def table_template_start = "<div><table style='border: solid 1px;'><tr><th>Job:</th><th>Duration:</th><th>Status:</th></tr>"
    def table_template_end = "</table></div>"

    for (jobdata in jobs_data) {
        def trstyle = "<tr>"
        // Grey background for 'finally' jobs in list
        if (jobdata['type'] == 'finally') {
            trstyle = "<tr style='background: #DDDDDD;'>"
        }
        // 'description' instead of job name if it exists
        def display_name = "'${jobdata['name']}': ${jobdata['build_id']}"
        if (jobdata['desc'].toString() != "") {
            display_name = "'${jobdata['desc']}': ${jobdata['build_id']}"
        }

        // Attach url for already built jobs
        def build_url = display_name
        if (jobdata['build_url'] != "0") {
            build_url = "<a href=${jobdata['build_url']}>$display_name</a>"
        }

        // Styling the status of job result
        switch (jobdata['status'].toString()) {
            case "SUCCESS":
                status_style = "<td style='color: green;'><img src='/images/16x16/blue.png' alt='SUCCESS'>"
                break
            case "UNSTABLE":
                status_style = "<td style='color: #FF5733;'><img src='/images/16x16/yellow.png' alt='UNSTABLE'>"
                break
            case "ABORTED":
                status_style = "<td style='color: red;'><img src='/images/16x16/aborted.png' alt='ABORTED'>"
                break
            case "NOT_BUILT":
                status_style = "<td style='color: red;'><img src='/images/16x16/aborted.png' alt='NOT_BUILT'>"
                break
            case "FAILURE":
                status_style = "<td style='color: red;'><img src='/images/16x16/red.png' alt='FAILURE'>"
                break
            default:
                status_style = "<td>-"
        }

        // Collect table
        table += "$trstyle<td>$build_url</td><td>${jobdata['duration']}</td>$status_style</td></tr>"

        // Collecting descriptions of builded child jobs
        if (jobdata['child_desc'] != "") {
            child_jobs_description += "<b><small><a href=${jobdata['build_url']}>- ${jobdata['name']} (${jobdata['status']}):</a></small></b><br>"
            // remove "null" message-result from description, but leave XXX:JOBRESULT in description
            if (jobdata['child_desc'] != "null") {
                child_jobs_description += "<small>${jobdata['child_desc']}</small><br>"
            }
        }
    }
    currentBuild.description = table_template_start + table + table_template_end + child_jobs_description
}

def runStep(global_variables, step, Boolean propagate = false, artifactoryBaseUrl = '', artifactoryServer = '') {
    return {
        def common = new com.mirantis.mk.Common()
        def engine = new groovy.text.GStringTemplateEngine()

        String jobDescription = step['description'] ?: ''
        def jobName = step['job']
        def jobParameters = [:]
        def stepParameters = step['parameters'] ?: [:]
        if (step['inherit_parent_params'] ?: false) {
            // add parameters from the current job for the child job
            jobParameters << getJobDefaultParameters(env.JOB_NAME)
        }
        // add parameters from the workflow for the child job
        jobParameters << stepParameters
        def wfPauseStepBeforeRun = (step['wf_pause_step_before_run'] ?: false).toBoolean()
        def wfPauseStepTimeout = (step['wf_pause_step_timeout'] ?: 10).toInteger()
        def wfPauseStepSlackReportChannel = step['wf_pause_step_slack_report_channel'] ?: ''

        if (wfPauseStepBeforeRun) {
            // Try-catch construction will allow to continue Steps, if timeout reached
            try {
                if (wfPauseStepSlackReportChannel) {
                    def slack = new com.mirantis.mcp.SlackNotification()
                    wfPauseStepSlackReportChannel.split(',').each {
                        slack.jobResultNotification('wf_pause_step_before_run',
                                                    it.toString(),
                                                    env.JOB_NAME, null,
                                                    env.BUILD_URL, 'slack_webhook_url')
                    }
                }
                timeout(time: wfPauseStepTimeout, unit: 'MINUTES') {
                    input("Workflow pause requested before run: ${jobName}/${jobDescription}\n" +
                      "Timeout set to ${wfPauseStepTimeout}.\n" +
                      "Do you want to proceed workflow?")
                }
            } catch (err) { // timeout reached or input false
                def user = err.getCauses()[0].getUser()
                if (user.toString() != 'SYSTEM') { // SYSTEM means timeout.
                    error("Aborted after workFlow pause by: [${user}]")
                } else {
                    common.infoMsg("Timeout finished, continue..")
                }
            }
        }
        common.infoMsg("Attempt to run: ${jobName}/${jobDescription}")
        // Collect job parameters and run the job
        // WARN(alexz): desc must not contain invalid chars for yaml
        def jobResult = runOrGetJob(jobName, jobParameters,
                                   global_variables, propagate, jobDescription)
        def buildDuration = jobResult.durationString ?: '-'
        if (buildDuration.toString() == null) {
            buildDuration = '-'
        }
        def jobSummary = [
          job_result       : jobResult.getResult().toString(),
          build_url        : jobResult.getAbsoluteUrl().toString(),
          build_id         : jobResult.getId().toString(),
          buildDuration    : buildDuration,
          desc             : engine.createTemplate(jobDescription).make(global_variables),
        ]
        def _buildDescription = jobResult.getDescription().toString()
        if(_buildDescription){
            jobSummary['build_description'] = _buildDescription
        }
        // Store links to the resulting artifacts into 'global_variables'
        storeArtifacts(jobSummary['build_url'], step['artifacts'],
          global_variables, jobName, jobSummary['build_id'], artifactoryBaseUrl, artifactoryServer)
        return jobSummary
    }
}
/**
 * Run the workflow or final steps one by one
 *
 * @param steps                   List of steps (Jenkins jobs) to execute
 * @param global_variables        Map where the collected artifact URLs and 'env' objects are stored
 * @param failed_jobs             Map with failed job names and result statuses, to report it later
 * @param jobs_data               Map with all job names and result statuses, to showing it in description
 * @param step_id                 Counter for matching step ID with cell ID in description table
 * @param propagate               Boolean. If false: allows to collect artifacts after job is finished, even with FAILURE status
 *                                If true: immediatelly fails the pipeline. DO NOT USE 'true' with runScenario().
 */
def runSteps(steps, global_variables, failed_jobs, jobs_data, step_id, Boolean propagate = false, artifactoryBaseUrl = '', artifactoryServer = '') {
    common = new com.mirantis.mk.Common()
    // Show expected jobs list in description
    updateDescription(jobs_data)

    for (step in steps) {
        stage("Preparing for run job ${step['job']}") {
            def job_summary = runStep(global_variables, step, propagate, artifactoryBaseUrl, artifactoryServer).call()

            // Update jobs_data for updating description
            jobs_data[step_id]['build_url'] = job_summary['build_url']
            jobs_data[step_id]['build_id'] = job_summary['build_id']
            jobs_data[step_id]['status'] = job_summary['job_result']
            jobs_data[step_id]['duration'] = job_summary['buildDuration']
            jobs_data[step_id]['desc'] = job_summary['desc']
            if (job_summary['build_description']) {
                jobs_data[step_id]['child_desc'] = job_summary['build_description']
            }
            updateDescription(jobs_data)
            def job_result = job_summary['job_result']
            def build_url = job_summary['build_url']

            // Check job result, in case of SUCCESS, move to next step.
            // In case job has status NOT_BUILT, fail the build or keep going depending on 'ignore_not_built' flag
            // In other cases check flag ignore_failed, if true ignore any statuses and keep going additionally
            // if skip_results is not set or set to false fail entrie workflow, otherwise succed.
            if (job_result != 'SUCCESS') {
                def ignoreStepResult = false
                switch (job_result) {
                // In cases when job was waiting too long in queue or internal job logic allows to skip building,
                // job may have NOT_BUILT status. In that case ignore_not_built flag can be used not to fail scenario.
                    case "NOT_BUILT":
                        ignoreStepResult = step['ignore_not_built'] ?: false
                        break;
                    case "UNSTABLE":
                        ignoreStepResult = step['ignore_unstable'] ?: (step['ignore_failed'] ?: false)
                        if (ignoreStepResult && !step['skip_results'] ?: false) {
                            failed_jobs[build_url] = job_result
                        }
                        break;
                    default:
                        ignoreStepResult = step['ignore_failed'] ?: false
                        if (ignoreStepResult && !step['skip_results'] ?: false) {
                            failed_jobs[build_url] = job_result
                        }
                }
                if (!ignoreStepResult) {
                    currentBuild.result = job_result
                    error "Job ${build_url} finished with result: ${job_result}"
                }
            }
            common.infoMsg("Job ${build_url} finished with result: ${job_result}")
        }
        // Jump to next ID for updating next job data in description table
        step_id++
    }
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
 *       description: "Management cluster ${KAAS_VERSION}"
 *       parameters:
 *         KAAS_VERSION:
 *           type: StringParameterValue
 *           use_variable: KAAS_VERSION
 *       artifacts:
 *         KUBECONFIG_ARTIFACT: artifacts/management_kubeconfig
 *         DEPLOYED_KAAS_VERSION: artifacts/management_version
 *
 *     - job: create-child
 *       inherit_parent_params: true
 *       ignore_failed: false
 *       parameters:
 *         KUBECONFIG_ARTIFACT_URL:
 *           type: StringParameterValue
 *           use_variable: KUBECONFIG_ARTIFACT
 *         KAAS_VERSION:
 *           type: StringParameterValue
 *           get_variable_from_url: DEPLOYED_KAAS_VERSION
 *         RELEASE_NAME:
 *           type: StringParameterValue
 *           get_variable_from_yaml:
 *               yaml_url: SI_CONFIG_ARTIFACT
 *               yaml_key: .clusters[0].release_name
 *
 *     - job: test-kaas-ui
 *       ignore_not_built: false
 *       parameters:
 *         KUBECONFIG_ARTIFACT_URL:
 *           type: StringParameterValue
 *           use_variable: KUBECONFIG_ARTIFACT
 *         KAAS_VERSION:
 *           type: StringParameterValue
 *           get_variable_from_url: DEPLOYED_KAAS_VERSION
 *       artifacts:
 *         REPORT_SI_KAAS_UI: artifacts/test_kaas_ui_result.xml
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
 * Scenario workflow keys:
 *
 *   job: string. Jenkins job name
 *   ignore_failed: bool. if true, keep running the workflow jobs if the job is failed, but fail the workflow at finish
 *   ignore_unstable: bool. if true, keep running the workflow jobs if the job is unstable, but mark the workflow is unstable at finish
 *   skip_results: bool. if true, keep running the workflow jobs if the job is failed, but do not fail the workflow at finish. Makes sense only when ignore_failed is set.
 *   ignore_not_built: bool. if true, keep running the workflow jobs if the job set own status to NOT_BUILT, do not fail the workflow at finish for such jobs
 *   inherit_parent_params: bool. if true, provide all parameters from the parent job to the child job as defaults
 *   parameters: dict. parameters name and type to inherit from parent to child job, or from artifact to child job
 *   wf_pause_step_before_run: bool. Interactive pause exact step before run.
 *   wf_pause_step_slack_report_channel: If step paused, send message about it in slack.
 *   wf_pause_step_timeout: timeout im minutes to wait for manual unpause.
 */

def runScenario(scenario, slackReportChannel = '', artifactoryBaseUrl = '', Boolean logGlobalVariables = false, artifactoryServer = '') {
    // Clear description before adding new messages
    currentBuild.description = ''
    // Collect the parameters for the jobs here
    def global_variables = [:]
    // List of failed jobs to show at the end
    def failed_jobs = [:]
    // Jobs data to use for wf job build description
    def jobs_data = []
    // Counter for matching step ID with cell ID in description table
    def step_id = 0

    // Generate expected list jobs for description
    def list_id = 0
    for (step in scenario['workflow']) {
        def display_name = step['job']
        if (step['description'] != null && step['description'].toString() != "") {
            display_name = step['description']
        }
        jobs_data.add([list_id   : "$list_id",
                       type      : "workflow",
                       name      : "$display_name",
                       build_url : "0",
                       build_id  : "-",
                       status    : "-",
                       desc      : "",
                       child_desc: "",
                       duration  : '-'])
        list_id += 1
    }

    def pause_step_id = list_id
    for (step in scenario['pause']) {
        def display_name = step['job']
        if (step['description'] != null && step['description'].toString() != "") {
            display_name = step['description']
        }
        jobs_data.add([list_id   : "$list_id",
                       type      : "pause",
                       name      : "$display_name",
                       build_url : "0",
                       build_id  : "-",
                       status    : "-",
                       desc      : "",
                       child_desc: "",
                       duration  : '-'])
        list_id += 1
    }

    def finally_step_id = list_id
    for (step in scenario['finally']) {
        def display_name = step['job']
        if (step['description'] != null && step['description'].toString() != "") {
            display_name = step['description']
        }
        jobs_data.add([list_id   : "$list_id",
                       type      : "finally",
                       name      : "$display_name",
                       build_url : "0",
                       build_id  : "-",
                       status    : "-",
                       desc      : "",
                       child_desc: "",
                       duration  : '-'])
        list_id += 1
    }
    def job_failed_flag = false
    try {
        // Run the 'workflow' jobs
        runSteps(scenario['workflow'], global_variables, failed_jobs, jobs_data, step_id, false, artifactoryBaseUrl, artifactoryServer)
    } catch (InterruptedException x) {
        job_failed_flag = true
        error "The job was aborted"
    } catch (e) {
        job_failed_flag = true
        error("Build failed: " + e.toString())

    } finally {
        // Log global_variables
        if (logGlobalVariables) {
            printVariables(global_variables)
        }

        flag_pause_variable = (env.PAUSE_FOR_DEBUG) != null
        // Run the 'finally' or 'pause' jobs
        common.infoMsg(failed_jobs)
        // Run only if there are failed jobs in the scenario
        if (flag_pause_variable && (PAUSE_FOR_DEBUG && job_failed_flag)) {
            // Switching to 'pause' step index
            common.infoMsg("FINALLY BLOCK - PAUSE")
            step_id = pause_step_id
            runSteps(scenario['pause'], global_variables, failed_jobs, jobs_data, step_id, false, artifactoryBaseUrl, artifactoryServer)

        }
         // Switching to 'finally' step index
        common.infoMsg("FINALLY BLOCK - CLEAR")
        step_id = finally_step_id
        runSteps(scenario['finally'], global_variables, failed_jobs, jobs_data, step_id, false, artifactoryBaseUrl, artifactoryServer)

        if (failed_jobs) {
            def statuses = []
            failed_jobs.each {
                statuses += it.value
            }
            if (statuses.contains('FAILURE')) {
                currentBuild.result = 'FAILURE'
            } else if (statuses.contains('ABORTED')) {
                currentBuild.result = 'ABORTED'
            }
            else if (statuses.contains('UNSTABLE')) {
                currentBuild.result = 'UNSTABLE'
            } else {
                currentBuild.result = 'FAILURE'
            }
            println "Failed jobs: ${failed_jobs}"
        } else {
            currentBuild.result = 'SUCCESS'
        }

        common.infoMsg("Workflow finished with result: ${currentBuild.result}")

        if (slackReportChannel) {
            def slack = new com.mirantis.mcp.SlackNotification()
            slack.jobResultNotification(currentBuild.result, slackReportChannel, '', null, '', 'slack_webhook_url')
        }
    } // finally
}
