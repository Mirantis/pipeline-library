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
def printVariables(global_variables, Boolean yamlStyle = true) {
    def common = new com.mirantis.mk.Common()
    def mcpcommon = new com.mirantis.mcp.Common()
    def global_variables_msg = ''
    if (yamlStyle) {
        global_variables_msg = mcpcommon.dumpYAML(global_variables)
    } else {
        for (variable in global_variables) {
            global_variables_msg += "env.${variable.key}=\"\"\"${variable.value}\"\"\"\n"
        }
    }
    def message = "// Collected global_variables during the workflow:\n${global_variables_msg}"
    common.warningMsg(message)
}


/**
 * Print stack trace to the console
 */
def printStackTrace(e, String prefix = 'at com.mirantis') {
    def common = new com.mirantis.mk.Common()
    StringWriter writer = new StringWriter()
    e.printStackTrace(new PrintWriter(writer))
    String stackTrace = writer

    // Filter the stacktrace to show only the lines related to the specified library
    String[] lines = stackTrace.split("\n")
    String stackTraceFiltered = ''
    Boolean filteredLine = false
    for (String line in lines) {
        if (line.contains('at ') && line.contains(prefix)) {
            if (!filteredLine) {
                stackTraceFiltered += "...\n"
                filteredLine = true
            }
            stackTraceFiltered += "${line}\n"
        }
        else if (!line.contains('at ')) {
            if (filteredLine) {
                stackTraceFiltered += "...\n"
                filteredLine = false
            }
            stackTraceFiltered += "${line}\n"
        }
    }
    common.errorMsg("Stack trace:\n${stackTraceFiltered}")
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
    def item = jenkinsUtils.getJobByName(jobName)
    def parameters = [:]
    // def prop = item.getProperty(ParametersDefinitionProperty.class)
    def prop = item.getProperty(ParametersDefinitionProperty)
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
 * Generate parameters for a Jenkins job using different sources
 *
 * @param job_parameters    Map that declares which values from global_variables should be used, in the following format:
 *                          {'PARAM_NAME': {'type': <job parameter $class name>, 'use_variable': <a key from global_variables>}, ...}
 *                          or
 *                          {'PARAM_NAME': {'type': <job parameter $class name>, 'get_variable_from_url': <a key from global_variables which contains URL with required content>}, ...}
 *                          or
 *                          {'PARAM_NAME': {'type': <job parameter $class name>, 'use_template': <a GString multiline template with variables from global_variables>}, ...}
 *                          or
 *                          {'PARAM_NAME': {'type': <job parameter $class name>, 'get_variable_from_yaml': {'yaml_url': <URL with YAML content>,
 *                                                                                                          'yaml_key': <a groovy-interpolating path to the key in the YAML, starting from dot '.'> } }, ...}
 *                          or
 *                          {'PARAM_NAME': {'type': <job parameter $class name>, 'use_variables_map': <a nested map of job_parameters>}, ...}
 *                                         , where job_parameters may contain a special 'type': '_defaultText' for a Yaml with some additional parameters for this map
 * @param global_variables  Map that keeps the artifact URLs and used 'env' objects:
 *                          {'PARAM1_NAME': <param1 value>, 'PARAM2_NAME': 'http://.../artifacts/param2_value', ...}
 */
def generateParameters(job_parameters, global_variables) {
    def parameters = []
    def common = new com.mirantis.mk.Common()
    def mcpcommon = new com.mirantis.mcp.Common()
    def http = new com.mirantis.mk.Http()
    def engine = new groovy.text.GStringTemplateEngine()
    def template
    def yamls_from_urls = [:]
    def base = [:]
    base["url"] = ''
    def variable_content
    def env_variables = common.getEnvAsMap()

    // Collect required parameters from 'global_variables' or 'env'
    def _msg = ''
    for (param in job_parameters) {
        if (param.value.containsKey('use_variable')) {
            if (!global_variables[param.value.use_variable]) {
                global_variables[param.value.use_variable] = env[param.value.use_variable] ?: ''
            }
            parameters.add([$class: "${param.value.type}", name: "${param.key}", value: global_variables[param.value.use_variable]])
            _msg += "\n${param.key}: <${param.value.type}> From:${param.value.use_variable}, Value:${global_variables[param.value.use_variable]}"
        } else if (param.value.containsKey('get_variable_from_url')) {
            if (!global_variables[param.value.get_variable_from_url]) {
                global_variables[param.value.get_variable_from_url] = env[param.value.get_variable_from_url] ?: ''
            }
            if (global_variables[param.value.get_variable_from_url]) {
                variable_content = http.restGet(base, global_variables[param.value.get_variable_from_url])
                // http.restGet() attempts to read the response as a JSON, and may return an object instead of a string
                variable_content = "${variable_content}".trim()
                parameters.add([$class: "${param.value.type}", name: "${param.key}", value: variable_content])
                _msg += "\n${param.key}: <${param.value.type}> Content from url: ${variable_content}"
            } else {
                _msg += "\n${param.key} is empty, skipping get_variable_from_url"
            }
        } else if (param.value.containsKey('get_variable_from_yaml')) {
            if (param.value.get_variable_from_yaml.containsKey('yaml_url') && param.value.get_variable_from_yaml.containsKey('yaml_key')) {
                // YAML url is stored in an environment or a global variable (like 'SI_CONFIG_ARTIFACT')
                def yaml_url_var = param.value.get_variable_from_yaml.yaml_url
                if (!global_variables[yaml_url_var]) {
                    global_variables[yaml_url_var] = env[yaml_url_var] ?: ''
                }
                def yaml_url = global_variables[yaml_url_var]  // Real YAML URL
                def yaml_key = param.value.get_variable_from_yaml.yaml_key
                // Key to get the data from YAML, to interpolate in the groovy, for example:
                //  <yaml_map_variable>.key.to.the[0].required.data , where yaml_key = '.key.to.the[0].required.data'
                if (yaml_url) {
                    if (!yamls_from_urls[yaml_url]) {
                        _msg += "\nReading YAML from ${yaml_url} for ${param.key}"
                        def yaml_content = http.restGet(base, yaml_url)
                        yamls_from_urls[yaml_url] = readYaml text: yaml_content
                    }
                    _msg += "\nGetting key ${yaml_key} from YAML ${yaml_url} for ${param.key}"
                    def template_variables = [
                      'yaml_data': yamls_from_urls[yaml_url],
                    ]
                    def request = "\${yaml_data${yaml_key}}"
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
                    _msg += "\n${param.key}: <${param.value.type}>\n${result}"
                } else {
                    common.warningMsg("'yaml_url' in ${param.key} is empty, skipping get_variable_from_yaml")
                }
            } else {
                common.warningMsg("${param.key} missing 'yaml_url'/'yaml_key' parameters, skipping get_variable_from_yaml")
            }
        } else if (param.value.containsKey('use_template')) {
            template = engine.createTemplate(param.value.use_template.toString()).make(env_variables + global_variables)
            parameters.add([$class: "${param.value.type}", name: "${param.key}", value: template.toString()])
            _msg += "\n${param.key}: <${param.value.type}>\n${template.toString()}"
        } else if (param.value.containsKey('use_variables_map')) {
            // Generate multistring YAML with key/value pairs (like job_parameters) from a nested parameters map
            def nested_parameters = generateParameters(param.value.use_variables_map, global_variables)
            def nested_values = [:]
            for (_parameter in nested_parameters) {
                if (_parameter.$class == '_defaultText') {
                    // This is a special type for multiline with default values
                    def _values = readYaml(text: _parameter.value ?: '---') ?: [:]
                    _values << nested_values
                    nested_values = _values
                } else {
                    nested_values[_parameter.name] = _parameter.value
                }
            }
            def multistring_value = mcpcommon.dumpYAML(nested_values)
            parameters.add([$class: "${param.value.type}", name: "${param.key}", value: multistring_value])
            _msg += "\n${param.key}: <${param.value.type}>\n${multistring_value}"
        }
    }
    // Inject hidden random parameter (is not showed in jjb) to be sure we are triggering unique downstream job.
    // Most actual case - parallel run for same jobs( but with different params)
    parameters.addAll([string(name: 'RANDOM_SEED_STRING', value: "${env.JOB_NAME.toLowerCase()}-${env.BUILD_NUMBER}-${UUID.randomUUID().toString().split('-')[0]}")])
    common.infoMsg(_msg)
    return parameters
}


/**
 * Run a Jenkins job using the collected parameters
 *
 * @param job_name          Name of the running job
 * @param job_parameters    Map that declares which values from global_variables should be used
 * @param global_variables  Map that keeps the artifact URLs and used 'env' objects
 * @param propagate         Boolean. If false: allows to collect artifacts after job is finished, even with FAILURE status
 *                          If true: immediatelly fails the pipeline. DO NOT USE 'true' if you want to collect artifacts
 *                          for 'finally' steps
 */
def runJob(job_name, job_parameters, global_variables, Boolean propagate = false) {

    def parameters = generateParameters(job_parameters, global_variables)
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
    def common = new com.mirantis.mk.Common()
    def jobsOverrides = readYaml(text: env.CI_JOBS_OVERRIDES ?: '---') ?: [:]
    // get id of overriding job
    def jobOverrideID = jobsOverrides.getOrDefault(fullTaskName, '')
    if (fullTaskName in jobsOverrides.keySet()) {
        common.warningMsg("Overriding: ${fullTaskName}/${job_name} <<< ${jobOverrideID}")
        common.infoMsg("For debug pin use:\n'${fullTaskName}' : ${jobOverrideID}")
        return Jenkins.instance.getItemByFullName(job_name, hudson.model.Job).getBuildByNumber(jobOverrideID.toInteger())
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
def storeArtifacts(build_url, step_artifacts, global_variables, job_name, build_num, artifactory_url = '', artifactory_server = '', artifacts_msg='local artifacts') {
    def common = new com.mirantis.mk.Common()
    def http = new com.mirantis.mk.Http()
    def artifactory = new com.mirantis.mcp.MCPArtifactory()
    if (!artifactory_url && !artifactory_server) {
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
    common.infoMsg("Attempt to store ${artifacts_msg} for: ${job_name}/${build_num}")
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


def getStatusStyle(status) {
    // Styling the status of job result
    def status_style = ''
    switch (status) {
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
    return status_style
}


def getTrStyle(jobdata) {
    def trstyle = "<tr>"
    // Grey background for 'finally' jobs in list
    if (jobdata.getOrDefault('type', '') == 'finally') {
        trstyle = "<tr style='background: #DDDDDD;'>"
    }
    return trstyle
}


/**
 * Update a 'job' step description
 *
 * @param jobsdata               Map with a 'job' step details and status
 */
def getJobDescription(jobdata) {
    def trstyle = getTrStyle(jobdata)
    def display_name = jobdata['desc'] ? "${jobdata['desc']}: ${jobdata['build_id']}" : "${jobdata['name']}: ${jobdata['build_id']}"
    if ((env.WF_SHOW_FULL_WORKFLOW_DESCRIPTION ?: false).toBoolean()) {
        display_name = "[${jobdata['name']}/${jobdata['build_id']}]: ${jobdata['desc']}"
    }

    // Attach url for already built jobs
    def build_url = display_name
    if (jobdata['build_url'] != "0") {
        build_url = "<a href=${jobdata['build_url']}>$display_name</a>"
    }

    def status_style = getStatusStyle(jobdata['status'].toString())

    return [[trstyle, build_url, jobdata['duration'], status_style,],]
}


/**
 * Update a 'script' step description
 *
 * @param jobsdata               Map with a 'script' step details and status
 */
def getScriptDescription(jobdata) {
    def trstyle = getTrStyle(jobdata)

    def display_name = "${jobdata['desc']}" ?: "${jobdata['name']}"
    if ((env.WF_SHOW_FULL_WORKFLOW_DESCRIPTION ?: false).toBoolean()) {
        display_name = "[${jobdata['name']}]: ${jobdata['desc']}"
    }

    // Attach url for already built jobs
    def build_url = display_name
    if (jobdata['build_url'] != "0") {
        build_url = "<a href=${jobdata['build_url']}>$display_name</a>"
    }

    def status_style = getStatusStyle(jobdata['status'].toString())

    return [[trstyle, build_url, jobdata['duration'], status_style,],]
}


/**
 * Update a 'parallel' or a 'sequence' step description
 *
 * @param jobsdata               Map with a 'together' step details and statuses
 */
def getNestedDescription(jobdata) {
    def tableEntries = []
    def trstyle = getTrStyle(jobdata)

    def display_name = "${jobdata['desc']}" ?: "${jobdata['name']}"
    if ((env.WF_SHOW_FULL_WORKFLOW_DESCRIPTION ?: false).toBoolean()) {
        display_name = "[${jobdata['name']}]: ${jobdata['desc']}"
    }

    // Attach url for already built jobs
    def build_url = display_name
    if (jobdata['build_url'] != "0") {
        build_url = "<a href=${jobdata['build_url']}>$display_name</a>"
    }

    def status_style = getStatusStyle(jobdata['status'].toString())

    tableEntries += [[trstyle, build_url, jobdata['duration'], status_style,],]

    // Collect nested job descriptions
    for (nested_jobdata in jobdata['nested_steps_data']) {
        (nestedTableEntries, _) = getStepDescription(nested_jobdata.value)
        for (nestedTableEntry in nestedTableEntries) {
            (nested_trstyle, nested_display_name, nested_duration, nested_status_style) = nestedTableEntry
            tableEntries += [[nested_trstyle, "&emsp;| ${nested_jobdata.key}: ${nested_display_name}", nested_duration, nested_status_style,],]
        }
    }
    return tableEntries
}


def getStepDescription(jobs_data) {
    def tableEntries = []
    def child_jobs_description = ''
    for (jobdata in jobs_data) {

        if (jobdata['step_key'] == 'job') {
            tableEntries += getJobDescription(jobdata)
        }
        else if (jobdata['step_key'] == 'script') {
            tableEntries += getScriptDescription(jobdata)
        }
        else if (jobdata['step_key'] == 'parallel' || jobdata['step_key'] == 'sequence') {
            tableEntries += getNestedDescription(jobdata)
        }

        // Collecting descriptions of builded child jobs
        if (jobdata['child_desc'] != '') {
            child_jobs_description += "<b><small><a href=${jobdata['build_url']}>- ${jobdata['name']} (${jobdata['status']}):</a></small></b><br>"
            // remove "null" message-result from description, but leave XXX:JOBRESULT in description
            if (jobdata['child_desc'] != 'null') {
                child_jobs_description += "<small>${jobdata['child_desc']}</small><br>"
            }
        }
    }
    return [tableEntries, child_jobs_description]
}

/**
 * Update description for workflow steps
 *
 * @param jobs_data               Map with all step names and result statuses, to showing it in description
 */
def updateDescription(jobs_data) {
    def child_jobs_description = '<strong>Descriptions from jobs:</strong><br>'
    def table_template_start = "<div><table style='border: solid 1px;'><tr><th>Job:</th><th>Duration:</th><th>Status:</th></tr>"
    def table_template_end = "</table></div>"

    (tableEntries, _child_jobs_description) = getStepDescription(jobs_data)

    def table = ''
    for (tableEntry in tableEntries) {
        // Collect table
        (trstyle, display_name, duration, status_style) = tableEntry
        table += "${trstyle}<td>${display_name}</td><td>${duration}</td>${status_style}</td></tr>"
    }

    child_jobs_description += _child_jobs_description

    currentBuild.description = table_template_start + table + table_template_end + child_jobs_description
}


def runStep(global_variables, step, Boolean propagate = false, artifactoryBaseUrl = '', artifactoryServer = '', parent_global_variables=null) {
    return {
        def common = new com.mirantis.mk.Common()
        def engine = new groovy.text.GStringTemplateEngine()
        def env_variables = common.getEnvAsMap()

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
                def cause = err.getCauses().get(0)
                if (cause instanceof org.jenkinsci.plugins.workflow.steps.TimeoutStepExecution.ExceededTimeout) {
                    common.infoMsg("Timeout finished, continue..")
                } else {
                    def user = causes[0].getUser()
                    error("Aborted after workflow pause by: [${user}]")
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
        def desc = engine.createTemplate(jobDescription.toString()).make(env_variables + global_variables)
        if ((desc.toString() == '') || (desc.toString() == 'null')) {
            desc = ''
        }
        def jobSummary = [
          job_result       : jobResult.getResult().toString(),
          build_url        : jobResult.getAbsoluteUrl().toString(),
          build_id         : jobResult.getId().toString(),
          buildDuration    : buildDuration,
          desc             : desc,
        ]
        def _buildDescription = jobResult.getDescription().toString()
        if (_buildDescription) {
            jobSummary['build_description'] = _buildDescription
        }
        // Store links to the resulting artifacts into 'global_variables'
        storeArtifacts(jobSummary['build_url'], step['artifacts'],
          global_variables, jobName, jobSummary['build_id'], artifactoryBaseUrl, artifactoryServer, artifacts_msg='artifacts to local variables')
        // Store links to the resulting 'global_artifacts' into 'global_variables'
        storeArtifacts(jobSummary['build_url'], step['global_artifacts'],
          global_variables, jobName, jobSummary['build_id'], artifactoryBaseUrl, artifactoryServer, artifacts_msg='global_artifacts to local variables')
        // Store links to the resulting 'global_artifacts' into 'parent_global_variables'
        storeArtifacts(jobSummary['build_url'], step['global_artifacts'],
          parent_global_variables, jobName, jobSummary['build_id'], artifactoryBaseUrl, artifactoryServer, artifacts_msg='global_artifacts to global_variables')
        return jobSummary
    }
}


def runScript(global_variables, step, artifactoryBaseUrl = '', artifactoryServer = '', scriptsLibrary = null, parent_global_variables=null) {
    def common = new com.mirantis.mk.Common()
    def env_variables = common.getEnvAsMap()

    if (!scriptsLibrary) {
        error "'scriptsLibrary' argument is not provided to load a script object '${step['script']}' from that library"
    }
    // Evaluate the object from it's name, for example: scriptsLibrary.com.mirantis.si.runtime_steps.ParallelMkeMoskUpgradeSequences
    def scriptObj = scriptsLibrary
    for (sObj in step['script'].split("\\.")) {
        scriptObj = scriptObj."$sObj"
    }

    def script = scriptObj.new()

    def scriptSummary = [
      job_result       : '',
      desc             : step['description'] ?: '',
    ]

    // prepare 'script_env' from merged 'env' and script step parameters
    def script_env = env_variables.clone()
    def stepParameters = step['parameters'] ?: [:]
    def script_parameters = generateParameters(stepParameters, global_variables)
    println "${script_parameters}"
    for (script_parameter in script_parameters) {
        common.infoMsg("Updating script env['${script_parameter.name}'] with value: ${script_parameter.value}")
        script_env[script_parameter.name] = script_parameter.value
    }

    try {
        script.main(this, script_env)
        scriptSummary['script_result'] = 'SUCCESS'
    } catch (InterruptedException e) {
        scriptSummary['script_result'] = 'ABORTED'
        printStackTrace(e)
    } catch (e) {
        scriptSummary['script_result'] = 'FAILURE'
        printStackTrace(e)
    }

    // Store links to the resulting 'artifacts' into 'global_variables'
    storeArtifacts(env.BUILD_URL, step['artifacts'],
                   global_variables, env.JOB_NAME, env.BUILD_NUMBER, artifactoryBaseUrl, artifactoryServer, artifacts_msg='artifacts to local variables')
    // Store links to the resulting 'global_artifacts' into 'global_variables'
    storeArtifacts(env.BUILD_URL, step['global_artifacts'],
                   global_variables, env.JOB_NAME, env.BUILD_NUMBER, artifactoryBaseUrl, artifactoryServer, artifacts_msg='global_artifacts to local variables')
    // Store links to the resulting 'global_artifacts' into 'parent_global_variables'
    storeArtifacts(env.BUILD_URL, step['global_artifacts'],
                   parent_global_variables, env.JOB_NAME, env.BUILD_NUMBER, artifactoryBaseUrl, artifactoryServer, artifacts_msg='global_artifacts to global_variables')

    return scriptSummary
}


def runParallel(global_variables, step, failed_jobs, global_jobs_data, nested_steps_data, artifactoryBaseUrl = '', artifactoryServer = '', scriptsLibrary = null, prefixMsg = '', parent_global_variables=null) {
    // Run the specified steps in parallel
    // Repeat the steps for each parameters set from 'repeat_with_parameters_from_yaml'
    // If 'repeat_with_parameters_from_yaml' is not provided, then 'parallel' step will perform just one iteration for a default "- _FOO: _BAR" parameter
    // If 'repeat_with_parameters_from_yaml' is present, but the specified artifact contains empty list '[]', then 'parallel' step will be skipped
    // Example:
    // - parallel:
    //     - job:
    //     - job:
    //     - sequence:
    //   repeat_with_parameters_from_yaml:
    //     type: TextParameterValue
    //     get_variable_from_url: SI_PARALLEL_PARAMETERS
    //   max_concurrent: 2               # how many parallel jobs shold be run at the same time
    //   max_concurrent_interval: 300    # how many seconds should be passed between checking for an available concurrency
    //   check_failed_concurrent: false  # stop waiting for available concurrent executors if count of failed jobs >= max_concurrent,
    //                                   # which means that all available shared resources are occupied by the failed jobs
    //   abort_on_parallel_fail: false                 # pass parallel.fail_fast option. force your parallel stages to all be aborted when any one of them fails
    def common = new com.mirantis.mk.Common()

    def sourceText = ""
    def defaultSourceText = "- _FOO: _BAR"
    if (step['repeat_with_parameters_from_yaml']) {
        def sourceParameter = ["repeat_with_parameters_from_yaml": step['repeat_with_parameters_from_yaml']]
        for (parameter in generateParameters(sourceParameter, global_variables)) {
            if (parameter.name == "repeat_with_parameters_from_yaml") {
                sourceText = parameter.value
                common.infoMsg("'repeat_with_parameters_from_yaml' is defined, using it as a yaml text:\n${sourceText}")
            }
        }
    }
    if (!sourceText) {
        sourceText = defaultSourceText
        common.warningMsg("'repeat_with_parameters_from_yaml' is not defined. To get one iteration, use default single entry:\n${sourceText}")
    }
    def iterateParametersList = readYaml text: sourceText
    if (!(iterateParametersList instanceof List)) {
        // Stop the pipeline if there is wrong parameters data type, to not generate parallel jobs for wrong data
        error "Expected a List in 'repeat_with_parameters_from_yaml' for 'parallel' step, but got:\n${sourceText}"
    }

    // Limit the maximum steps in parallel at the same time
    def max_concurrent = (step['max_concurrent'] ?: 100).toInteger()
    // Sleep for the specified amount of time until a free thread will be available
    def max_concurrent_interval = (step['max_concurrent_interval'] ?: 600).toInteger()
    // Check that failed jobs is not >= free executors. if 'true', then don't wait for free executors, fail the parallel step
    def check_failed_concurrent = (step['check_failed_concurrent'] ?: false).toBoolean()

    def jobs = [:]
    jobs.failFast = (step['abort_on_parallel_fail'] ?: false).toBoolean()
    def nested_step_id = 0
    def free_concurrent = max_concurrent
    def failed_concurrent = []

    common.printMsg("${prefixMsg} Running parallel steps with the following parameters:\n${iterateParametersList}", "purple")

    for (parameters in iterateParametersList) {
        for (parallel_step in step['parallel']) {
            def step_name = "parallel#${nested_step_id}"
            def nested_step = parallel_step
            def nested_step_name = step_name
            def nested_prefix_name = "${prefixMsg}${nested_step_name} | "

            nested_steps_data[step_name] = []
            prepareJobsData([nested_step,], 'parallel', nested_steps_data[step_name])

            //Copy global variables and merge "parameters" dict into it for the current particular step
            def nested_global_variables = global_variables.clone()
            nested_global_variables << parameters

            jobs[step_name] = {
                // initialRecurrencePeriod in milliseconds
                waitUntil(initialRecurrencePeriod: 1500, quiet: true) {
                    if (check_failed_concurrent) {
                        if (failed_concurrent.size() >= max_concurrent){
                            common.errorMsg("Failed jobs count is equal max_concurrent value ${max_concurrent}. Will not continue because resources are consumed")
                            error("max_concurrent == failed_concurrent")
                        }
                    }
                    if (free_concurrent > 0) {
                        free_concurrent--
                        true
                    } else {
                        sleep(max_concurrent_interval)
                        false
                    }
                }

                try {
                    runWorkflowStep(nested_global_variables, nested_step, 0, nested_steps_data[nested_step_name], global_jobs_data, failed_jobs, false, artifactoryBaseUrl, artifactoryServer, scriptsLibrary, nested_prefix_name, parent_global_variables)
                }
                catch (e) {
                    failed_concurrent.add(step_name)
                    throw(e)
                }

                free_concurrent++
            } // 'jobs' closure

            nested_step_id++
        }
    }

    def parallelSummary = [
      nested_result       : '',
      desc                : step['description'] ?: '',
      nested_steps_data   : [:],
    ]

    if (iterateParametersList) {
        // Run parallel iterations
        try {
            common.infoMsg("${prefixMsg} Run steps in parallel")
            parallel jobs

            parallelSummary['nested_result'] = 'SUCCESS'
        } catch (InterruptedException e) {
            parallelSummary['nested_result'] = 'ABORTED'
            printStackTrace(e)
        } catch (e) {
            parallelSummary['nested_result'] = 'FAILURE'
            printStackTrace(e)
        }
        parallelSummary['nested_steps_data'] = nested_steps_data
    }
    else
    {
        // No parameters were provided to iterate
        common.errorMsg("${prefixMsg} No parameters were provided to iterate, skipping 'parallel' step")
        parallelSummary['nested_result'] = 'SUCCESS'
    }
    return parallelSummary
}


def runSequence(global_variables, step, failed_jobs, global_jobs_data, nested_steps_data, artifactoryBaseUrl = '', artifactoryServer = '', scriptsLibrary = null, prefixMsg = '', parent_global_variables=null) {
    // Run the steps in the specified order, like in main workflow, but repeat the sequence for each parameters set from 'repeat_with_parameters_from_yaml'
    // If 'repeat_with_parameters_from_yaml' is not provided, then 'sequence' step will perform just one iteration for a default "- _FOO: _BAR" parameter
    // If 'repeat_with_parameters_from_yaml' is present, but the specified artifact contains empty list '[]', then 'sequence' step will be skipped
    // - sequence:
    //     - job:
    //     - job:
    //     - script:
    //   repeat_with_parameters_from_yaml:
    //     type: TextParameterValue
    //     get_variable_from_url: SI_PARALLEL_PARAMETERS
    def common = new com.mirantis.mk.Common()

    def sourceText = ""
    def defaultSourceText = "- _FOO: _BAR"
    if (step['repeat_with_parameters_from_yaml']) {
        def sourceParameter = ["repeat_with_parameters_from_yaml": step['repeat_with_parameters_from_yaml']]
        for (parameter in generateParameters(sourceParameter, global_variables)) {
            if (parameter.name == "repeat_with_parameters_from_yaml") {
                sourceText = parameter.value
                common.infoMsg("'repeat_with_parameters_from_yaml' is defined, using it as a yaml text:\n${sourceText}")
            }
        }
    }
    if (!sourceText) {
        sourceText = defaultSourceText
        common.warningMsg("'repeat_with_parameters_from_yaml' is not defined. To get one iteration, use default single entry:\n${sourceText}")
    }
    def iterateParametersList = readYaml text: sourceText
    if (!(iterateParametersList instanceof List)) {
        // Stop the pipeline if there is wrong parameters data type, to not generate parallel jobs for wrong data
        error "Expected a List in 'repeat_with_parameters_from_yaml' for 'sequence' step, but got:\n${sourceText}"
    }

    def jobs = [:]
    def nested_step_id = 0

    common.printMsg("${prefixMsg} Running parallel steps with the following parameters:\n${iterateParametersList}", "purple")

    for (parameters in iterateParametersList) {
        def step_name = "sequence#${nested_step_id}"
        def nested_steps = step['sequence']
        def nested_step_name = step_name
        def nested_prefix_name = "${prefixMsg}${nested_step_name} | "

        nested_steps_data[step_name] = []
        prepareJobsData(nested_steps, 'sequence', nested_steps_data[step_name])

        //Copy global variables and merge "parameters" dict into it for the current particular step
        def nested_global_variables = global_variables.clone()
        nested_global_variables << parameters

        jobs[step_name] = {

            runSteps(nested_steps, nested_global_variables, failed_jobs, nested_steps_data[nested_step_name], global_jobs_data, 0, false, artifactoryBaseUrl, artifactoryServer, scriptsLibrary, nested_prefix_name, parent_global_variables)

        } // 'jobs' closure

        nested_step_id++
    }

    def sequenceSummary = [
      nested_result       : '',
      desc                : step['description'] ?: '',
      nested_steps_data   : [:],
    ]

    if (iterateParametersList) {
        // Run sequence iterations
        try {
            jobs.each { stepName, job ->
                common.infoMsg("${prefixMsg} Running sequence ${stepName}")
                job()
                // just in case sleep.
                sleep(5)
            }
            sequenceSummary['nested_result'] = 'SUCCESS'
        } catch (InterruptedException e) {
            sequenceSummary['nested_result'] = 'ABORTED'
            printStackTrace(e)
        } catch (e) {
            sequenceSummary['nested_result'] = 'FAILURE'
            printStackTrace(e)
        }
        sequenceSummary['nested_steps_data'] = nested_steps_data
    }
    else
    {
        // No parameters were provided to iterate
        common.errorMsg("${prefixMsg} No parameters were provided to iterate, skipping 'sequence' step")
        sequenceSummary['nested_result'] = 'SUCCESS'
    }

    return sequenceSummary
}


def checkResult(job_result, build_url, step, failed_jobs) {
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
                break
            case "UNSTABLE":
                ignoreStepResult = step['ignore_unstable'] ?: (step['ignore_failed'] ?: false)
                if (ignoreStepResult && !step['skip_results'] ?: false) {
                    failed_jobs[build_url] = job_result
                }
                break
            case "ABORTED":
                ignoreStepResult = step['ignore_aborted'] ?: (step['ignore_failed'] ?: false)
                if (ignoreStepResult && !step['skip_results'] ?: false) {
                    failed_jobs[build_url] = job_result
                }
                break
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
}

def runWorkflowStep(global_variables, step, step_id, jobs_data, global_jobs_data, failed_jobs, propagate, artifactoryBaseUrl, artifactoryServer, scriptsLibrary = null, prefixMsg = '', parent_global_variables=null) {
    def common = new com.mirantis.mk.Common()

    def _sep = "\n======================\n"
    if (step.containsKey('job')) {

        common.printMsg("${_sep}${prefixMsg}Run job ${step['job']} [at ${java.time.LocalDateTime.now()}]${_sep}", "blue")
        stage("Run job ${step['job']}") {

            def job_summary = runStep(global_variables, step, propagate, artifactoryBaseUrl, artifactoryServer, parent_global_variables).call()

            // Update jobs_data for updating description
            jobs_data[step_id]['build_url'] = job_summary['build_url']
            jobs_data[step_id]['build_id'] = job_summary['build_id']
            jobs_data[step_id]['status'] = job_summary['job_result']
            jobs_data[step_id]['duration'] = job_summary['buildDuration']
            jobs_data[step_id]['desc'] = job_summary['desc']
            if (job_summary['build_description']) {
                jobs_data[step_id]['child_desc'] = job_summary['build_description']
            }
            def job_result = job_summary['job_result']
            def build_url = job_summary['build_url']
            common.printMsg("${_sep}${prefixMsg}Job ${build_url} finished with result: ${job_result} [at ${java.time.LocalDateTime.now()}]${_sep}", "blue")
        }
    }
    else if (step.containsKey('script')) {
        common.printMsg("${_sep}${prefixMsg}Run script ${step['script']} [at ${java.time.LocalDateTime.now()}]${_sep}", "blue")
        stage("Run script ${step['script']}") {

            def scriptResult = runScript(global_variables, step, artifactoryBaseUrl, artifactoryServer, scriptsLibrary, parent_global_variables)

            // Use build_url just as an unique key for failed_jobs.
            // All characters after '#' are 'comment'
            def build_url = "${env.BUILD_URL}#${step_id}:${step['script']}"
            def job_result = scriptResult['script_result']
            common.printMsg("${_sep}${prefixMsg}Script ${build_url} finished with result: ${job_result} [at ${java.time.LocalDateTime.now()}]${_sep}", "blue")

            jobs_data[step_id]['build_url'] = build_url
            jobs_data[step_id]['status'] = scriptResult['script_result']
            jobs_data[step_id]['desc'] = scriptResult['desc']
            if (scriptResult['build_description']) {
                jobs_data[step_id]['child_desc'] = scriptResult['build_description']
            }
        }
    }
    else if (step.containsKey('parallel')) {
        common.printMsg("${_sep}${prefixMsg}Run steps in parallel [at ${java.time.LocalDateTime.now()}]:${_sep}", "blue")
        stage("Run steps in parallel:") {

            // Allocate a map to collect nested steps data for updateDescription()
            def nested_steps_data = [:]
            jobs_data[step_id]['nested_steps_data'] = nested_steps_data

            def parallelResult = runParallel(global_variables, step, failed_jobs, global_jobs_data, nested_steps_data, artifactoryBaseUrl, artifactoryServer, scriptsLibrary, prefixMsg, parent_global_variables)

            // Use build_url just as an unique key for failed_jobs.
            // All characters after '#' are 'comment'
            def build_url = "${env.BUILD_URL}#${step_id}"
            def job_result = parallelResult['nested_result']
            common.printMsg("${_sep}${prefixMsg}Parallel steps ${build_url} finished with result: ${job_result} [at ${java.time.LocalDateTime.now()}]${_sep}", "blue")

            jobs_data[step_id]['build_url'] = build_url
            jobs_data[step_id]['status'] = parallelResult['nested_result']
            jobs_data[step_id]['desc'] = parallelResult['desc']
            if (parallelResult['build_description']) {
                jobs_data[step_id]['child_desc'] = parallelResult['build_description']
            }
        }
    }
    else if (step.containsKey('sequence')) {
        common.printMsg("${_sep}${prefixMsg}Run steps in sequence [at ${java.time.LocalDateTime.now()}]:${_sep}", "blue")
        stage("Run steps in sequence:") {

            // Allocate a map to collect nested steps data for updateDescription()
            def nested_steps_data = [:]
            jobs_data[step_id]['nested_steps_data'] = nested_steps_data

            def sequenceResult = runSequence(global_variables, step, failed_jobs, global_jobs_data, nested_steps_data, artifactoryBaseUrl, artifactoryServer, scriptsLibrary, prefixMsg, parent_global_variables)

            // Use build_url just as an unique key for failed_jobs.
            // All characters after '#' are 'comment'
            def build_url = "${env.BUILD_URL}#${step_id}"
            def job_result = sequenceResult['nested_result']
            common.printMsg("${_sep}${prefixMsg}Sequence steps ${build_url} finished with result: ${job_result} [at ${java.time.LocalDateTime.now()}]${_sep}", "blue")

            jobs_data[step_id]['build_url'] = build_url
            jobs_data[step_id]['status'] = sequenceResult['nested_result']
            jobs_data[step_id]['desc'] = sequenceResult['desc']
            if (sequenceResult['build_description']) {
                jobs_data[step_id]['child_desc'] = sequenceResult['build_description']
            }
        }
    }

    updateDescription(global_jobs_data)

    job_result = jobs_data[step_id]['status']
    checkResult(job_result, build_url, step, failed_jobs)

//    return build_url

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
def runSteps(steps, global_variables, failed_jobs, jobs_data, global_jobs_data, step_id, Boolean propagate = false, artifactoryBaseUrl = '', artifactoryServer = '', scriptsLibrary = null, prefixMsg = '', parent_global_variables=null) {
    // Show expected jobs list in description
    updateDescription(global_jobs_data)

    for (step in steps) {

        runWorkflowStep(global_variables, step, step_id, jobs_data, global_jobs_data, failed_jobs, propagate, artifactoryBaseUrl, artifactoryServer, scriptsLibrary, prefixMsg, parent_global_variables)

        // Jump to next ID for updating next job data in description table
        step_id++
    }
}


/**
 * Prepare jobs_data for generating the scenario description
 */
def prepareJobsData(scenario_steps, step_type, jobs_data) {
    def list_id = jobs_data.size()

    for (step in scenario_steps) {
        def display_name = ''
        def step_key = ''
        def desc = ''

        if (step.containsKey('job')) {
            display_name = step['job']
            step_key = 'job'
        }
        else if (step.containsKey('script')) {
            display_name = step['script']
            step_key = 'script'
        }
        else if (step.containsKey('parallel')) {
            display_name = 'Parallel steps'
            step_key = 'parallel'
        }
        else if (step.containsKey('sequence')) {
            display_name = 'Sequence steps'
            step_key = 'sequence'
        }

        if (step['description'] != null && step['description'] != 'null' && step['description'].toString() != '') {
            desc = (step['description'] ?: '').toString()
        }

        jobs_data.add([list_id      : "$list_id",
                       type         : step_type,
                       name         : "$display_name",
                       build_url    : "0",
                       build_id     : "-",
                       status       : "-",
                       desc         : desc,
                       child_desc   : "",
                       duration     : '-',
                       step_key     : step_key,
                       together_steps: [],
                      ])
        list_id += 1
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
 *       global_artifacts:
 *         CHILD_CONFIG_1: artifacts/child_kubeconfig
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
 *   ignore_aborted: bool. if true, keep running the workflow jobs if the job is aborted, but mark the workflow is unstable at finish
 *   skip_results: bool. if true, keep running the workflow jobs if the job is failed, but do not fail the workflow at finish. Makes sense only when ignore_failed is set.
 *   ignore_not_built: bool. if true, keep running the workflow jobs if the job set own status to NOT_BUILT, do not fail the workflow at finish for such jobs
 *   inherit_parent_params: bool. if true, provide all parameters from the parent job to the child job as defaults
 *   parameters: dict. parameters name and type to inherit from parent to child job, or from artifact to child job
 *   wf_pause_step_before_run: bool. Interactive pause exact step before run.
 *   wf_pause_step_slack_report_channel: If step paused, send message about it in slack.
 *   wf_pause_step_timeout: timeout im minutes to wait for manual unpause.
 */
def runScenario(scenario, slackReportChannel = '', artifactoryBaseUrl = '', Boolean logGlobalVariables = false, artifactoryServer = '', scriptsLibrary = null,
                global_variables = null, failed_jobs = null, jobs_data = null) {
    def common = new com.mirantis.mk.Common()

    // Clear description before adding new messages
    currentBuild.description = ''
    // Collect the parameters for the jobs here
    if (global_variables == null) {
        global_variables = [:]
    }
    // List of failed jobs to show at the end
    if (failed_jobs == null) {
        failed_jobs = [:]
    }
    // Jobs data to use for wf job build description
    if (jobs_data == null) {
        jobs_data = []
    }
    def global_jobs_data = jobs_data

    // Counter for matching step ID with cell ID in description table
    def step_id = jobs_data.size()
    // Generate expected list jobs for description
    prepareJobsData(scenario['workflow'], 'workflow', jobs_data)

    def pause_step_id = jobs_data.size()
    // Generate expected list jobs for description
    prepareJobsData(scenario['pause'], 'pause', jobs_data)

    def finally_step_id = jobs_data.size()
    // Generate expected list jobs for description
    prepareJobsData(scenario['finally'], 'finally', jobs_data)


    def job_failed_flag = false
    try {
        // Run the 'workflow' jobs
        runSteps(scenario['workflow'], global_variables, failed_jobs, jobs_data, global_jobs_data, step_id, false, artifactoryBaseUrl, artifactoryServer, scriptsLibrary, '', global_variables)
    } catch (InterruptedException e) {
        job_failed_flag = true
        error "The job was aborted"
    } catch (e) {
        job_failed_flag = true
        printStackTrace(e)
        error("Build failed: " + e.toString())

    } finally {
        // Log global_variables
        if (logGlobalVariables) {
            printVariables(global_variables)
        }

        def flag_pause_variable = (env.PAUSE_FOR_DEBUG) != null
        // Run the 'finally' or 'pause' jobs
        common.infoMsg(failed_jobs)
        // Run only if there are failed jobs in the scenario
        if (flag_pause_variable && (PAUSE_FOR_DEBUG && job_failed_flag)) {
            // Switching to 'pause' step index
            common.infoMsg("FINALLY BLOCK - PAUSE")
            step_id = pause_step_id
            runSteps(scenario['pause'], global_variables, failed_jobs, jobs_data, global_jobs_data, step_id, false, artifactoryBaseUrl, artifactoryServer, scriptsLibrary, '', global_variables)

        }
         // Switching to 'finally' step index
        common.infoMsg("FINALLY BLOCK - CLEAR")
        step_id = finally_step_id
        runSteps(scenario['finally'], global_variables, failed_jobs, jobs_data, global_jobs_data, step_id, false, artifactoryBaseUrl, artifactoryServer, scriptsLibrary, '', global_variables)

        if (failed_jobs) {
            def statuses = []
            failed_jobs.each {
                statuses += it.value
            }
            if (statuses.contains('FAILURE')) {
                currentBuild.result = 'FAILURE'
            } else if (statuses.contains('ABORTED')) {
                currentBuild.result = 'ABORTED'
            } else if (statuses.contains('UNSTABLE')) {
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


def manageArtifacts(entrypointDirectory, storeArtsInJenkins = false, artifactoryServerName = 'mcp-ci') {
    def mcpArtifactory = new com.mirantis.mcp.MCPArtifactory()
    def artifactoryRepoPath = "si-local/jenkins-job-artifacts/${JOB_NAME}/${BUILD_NUMBER}"
    def tests_log = "${entrypointDirectory}/tests.log"

    if (fileExists(tests_log)) {
        try {
            def size = sh([returnStdout: true, script: "stat --printf='%s' ${tests_log}"]).trim().toInteger()
            // do not archive unless it is more than 50 MB
            def allowed_size = 1048576 * 50
            if (size >= allowed_size) {
                sh("gzip ${tests_log} || true")
            }
        } catch (e) {
            print("Cannot determine tests.log filesize: ${e}")
        }
    }

    if (storeArtsInJenkins) {
        archiveArtifacts(
            artifacts: "${entrypointDirectory}/**",
            allowEmptyArchive: true
        )
    }
    artConfig = [
        deleteArtifacts: false,
        artifactory    : artifactoryServerName,
        artifactPattern: "${entrypointDirectory}/**",
        artifactoryRepo: "artifactory/${artifactoryRepoPath}",
    ]
    def artDescription = mcpArtifactory.uploadArtifactsToArtifactory(artConfig)
    if (currentBuild.description) {
        currentBuild.description += "${artDescription}<br>"
    } else {
        currentBuild.description = "${artDescription}<br>"
    }

    junit(testResults: "${entrypointDirectory}/**/*.xml", allowEmptyResults: true)

    def artifactoryServer = Artifactory.server(artifactoryServerName)
    def artifactsUrl = "${artifactoryServer.getUrl()}/artifactory/${artifactoryRepoPath}"
    return artifactsUrl
}


return this
