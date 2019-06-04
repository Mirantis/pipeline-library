package com.mirantis.mcp

/**
 * DEPRECATED
 * Tests providing functions
 *
 */

/**
 * Run docker container with basic (keystone) parameters
 * For backward compatibility. Deprecated.
 * Will be removed soon.
 *
 * @param target            Host to run container
 * @param dockerImageLink   Docker image link. May be custom or default rally image
 */
def runBasicContainer(master, target, dockerImageLink="xrally/xrally-openstack:0.10.1"){
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    common.errorMsg('You are using deprecated method! Please migrate to validate.runContainer. This method will be removed')
    error('You are using deprecated method! Please migrate to validate.runContainer. This method will be removed')
    def _pillar = salt.getPillar(master, 'I@keystone:server', 'keystone:server')
    def keystone = _pillar['return'][0].values()[0]
    if ( salt.cmdRun(master, target, "docker ps -f name=cvp -q", false, null, false)['return'][0].values()[0] ) {
        salt.cmdRun(master, target, "docker rm -f cvp")
    }
    salt.cmdRun(master, target, "docker run -tid --net=host --name=cvp " +
            "-u root -e OS_USERNAME=${keystone.admin_name} " +
            "-e OS_PASSWORD=${keystone.admin_password} -e OS_TENANT_NAME=${keystone.admin_tenant} " +
            "-e OS_AUTH_URL=http://${keystone.bind.private_address}:${keystone.bind.private_port}/v2.0 " +
            "-e OS_REGION_NAME=${keystone.region} -e OS_ENDPOINT_TYPE=admin --entrypoint /bin/bash ${dockerImageLink}")
}


/**
 * Run docker container with parameters
 *
 * @param target                Host to run container
 * @param dockerImageLink       Docker image link. May be custom or default rally image
 * @param name                  Name for container
 * @param env_var               Environment variables to set in container
 * @param entrypoint            Set entrypoint to /bin/bash or leave default
 * @param mounts                Map with mounts for container
**/

def runContainer(Map params){
    def common = new com.mirantis.mk.Common()
    defaults = ["name": "cvp", "env_var": [], "entrypoint": true]
    params = defaults + params
    def salt = new com.mirantis.mk.Salt()
    def variables = ''
    def entry_point = ''
    def tempest_conf_mount = ''
    def mounts = ''
    def cluster_name = salt.getPillar(params.master, 'I@salt:master', '_param:cluster_name')['return'][0].values()[0]
    default_mounts = ["/etc/ssl/certs/": "/etc/ssl/certs/",
                      "/srv/salt/pki/${cluster_name}/": "/etc/certs",
                      "/root/test/": "/root/tempest/",
                      "/tmp/": "/tmp/",
                      "/etc/hosts": "/etc/hosts"]
    params.mounts = default_mounts + params.mounts
    if ( salt.cmdRun(params.master, params.target, "docker ps -f name=${params.name} -q", false, null, false)['return'][0].values()[0] ) {
        salt.cmdRun(params.master, params.target, "docker rm -f ${params.name}")
    }
    if (params.env_var.size() > 0) {
        variables = ' -e ' + params.env_var.join(' -e ')
    }
    if (params.entrypoint) {
        entry_point = '--entrypoint /bin/bash'
    }
    params.mounts.each { local, container ->
        mounts = mounts + " -v ${local}:${container}"
    }
    salt.cmdRun(params.master, params.target, "docker run -tid --net=host --name=${params.name}" +
                                "${mounts} -u root ${entry_point} ${variables} ${params.dockerImageLink}")
}

def runContainer(master, target, dockerImageLink, name='cvp', env_var=[], entrypoint=true, mounts=[:]){
    def common = new com.mirantis.mk.Common()
    common.infoMsg("This method will be deprecated. Convert you method call to use Map as input parameter")
    // Convert to Map
    params = ['master': master, 'target': target, 'dockerImageLink': dockerImageLink, 'name': name, 'env_var': env_var,
              'entrypoint': entrypoint, 'mounts': mounts]
    // Call new method with Map as parameter
    return runContainer(params)
}

/**
 * Get v2 Keystone credentials from pillars
 *
 */
def _get_keystone_creds_v2(master){
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def keystone = []
    _pillar = false
    common.infoMsg("Fetching Keystone v2 credentials")
    _response = salt.runSaltProcessStep(master, 'I@keystone:server', 'pillar.get', 'keystone:server', null, false, 1)['return'][0]
    for (i = 0; i < _response.keySet().size(); i++) {
        if ( _response.values()[i] ) {
            _pillar = _response.values()[i]
        }
    }
    if (_pillar) {
        keystone.add("OS_USERNAME=${_pillar.admin_name}")
        keystone.add("OS_PASSWORD=${_pillar.admin_password}")
        keystone.add("OS_TENANT_NAME=${_pillar.admin_tenant}")
        keystone.add("OS_AUTH_URL=http://${_pillar.bind.private_address}:${_pillar.bind.private_port}/v2.0")
        keystone.add("OS_REGION_NAME=${_pillar.region}")
        keystone.add("OS_ENDPOINT_TYPE=admin")
        return keystone
    }
    else {
        throw new Exception("Cannot fetch Keystone v2 credentials. Response: ${_response}")
    }
}

/**
 * Get v3 Keystone credentials from pillars
 *
 */
def _get_keystone_creds_v3(master){
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    _pillar = false
    pillar_name = 'keystone:client:os_client_config:cfgs:root:content:clouds:admin_identity'
    common.infoMsg("Fetching Keystone v3 credentials")
    _response = salt.runSaltProcessStep(master, 'I@keystone:server', 'pillar.get', pillar_name, null, false, 1)['return'][0]
    for (i = 0; i < _response.keySet().size(); i++) {
        if ( _response.values()[i] ) {
            _pillar = _response.values()[i]
        }
    }
    def keystone = []
    if (_pillar) {
        keystone.add("OS_USERNAME=${_pillar.auth.username}")
        keystone.add("OS_PASSWORD=${_pillar.auth.password}")
        keystone.add("OS_TENANT_NAME=${_pillar.auth.project_name}")
        keystone.add("OS_PROJECT_NAME=${_pillar.auth.project_name}")
        keystone.add("OS_AUTH_URL=${_pillar.auth.auth_url}/v3")
        keystone.add("OS_REGION_NAME=${_pillar.region_name}")
        keystone.add("OS_IDENTITY_API_VERSION=${_pillar.identity_api_version}")
        keystone.add("OS_ENDPOINT_TYPE=internal")
        keystone.add("OS_PROJECT_DOMAIN_NAME=${_pillar.auth.project_domain_name}")
        keystone.add("OS_USER_DOMAIN_NAME=${_pillar.auth.user_domain_name}")
        // we mount /srv/salt/pki/${cluster_name}/:/etc/certs with certs for cvp container
        keystone.add("OS_CACERT='/etc/certs/proxy-with-chain.crt'")
        return keystone
    }
    else {
        common.warningMsg("Failed to fetch Keystone v3 credentials")
        return false
    }
}

/**
 * Get file content (encoded). The content encoded by Base64.
 *
 * @param target            Compound target (should target only one host)
 * @param file              File path to read
 * @return                  The encoded content of the file
 */
def getFileContentEncoded(master, target, file) {
    def salt = new com.mirantis.mk.Salt()
    def file_content = ''
    def cmd = "base64 -w0 ${file} > ${file}_encoded; " +
              "split -b 1MB -d ${file}_encoded ${file}__; " +
              "rm ${file}_encoded"
    salt.cmdRun(master, target, cmd, false, null, false)
    def filename = file.tokenize('/').last()
    def folder = file - filename
    def parts = salt.runSaltProcessStep(master, target, 'file.find', ["${folder}", "type=f", "name=${filename}__*"])
    for ( part in parts['return'][0].values()[0]) {
        def _result = salt.cmdRun(master, target, "cat ${part}", false, null, false)
        file_content = file_content + _result['return'][0].values()[0].replaceAll('Salt command execution success','')
    }
    salt.runSaltProcessStep(master, target, 'file.find', ["${folder}", "type=f", "name=${filename}__*", "delete"])
    return file_content
}

/**
 * Copy files from remote to local directory. The content of files will be
 * decoded by Base64.
 *
 * @param target            Compound target (should target only one host)
 * @param folder            The path to remote folder.
 * @param output_dir        The path to local folder.
 */
def addFiles(master, target, folder, output_dir) {
    def salt = new com.mirantis.mk.Salt()
    def _result = salt.runSaltProcessStep(master, target, 'file.find', ["${folder}", "type=f"])
    def files = _result['return'][0].values()[0]
    for (file in files) {
        def file_content = getFileContentEncoded(master, target, "${file}")
        def fileName = file.tokenize('/').last()
        writeFile file: "${output_dir}${fileName}_encoded", text: file_content
        def cmd = "base64 -d ${output_dir}${fileName}_encoded > ${output_dir}${fileName}; " +
                  "rm ${output_dir}${fileName}_encoded"
        sh(script: cmd)
    }
}

/**
 * DEPRECATED
 * Get reclass value
 *
 * @param target            The host for which the values will be provided
 * @param filter            Parameters divided by dots
 * @return                  The pillar data
 */
def getReclassValue(master, target, filter) {
    def common = new com.mirantis.mk.Common()
    common.errorMsg('You are using deprecated method! This method will be removed')
    error('You are using deprecated method! This method will be removed')
    def salt = new com.mirantis.mk.Salt()
    def items = filter.tokenize('.')
    def _result = salt.cmdRun(master, 'I@salt:master', "reclass-salt -o json -p ${target}", false, null, false)
    _result = common.parseJSON(_result['return'][0].values()[0])
    for (int k = 0; k < items.size(); k++) {
        if ( _result ) {
            _result = _result["${items[k]}"]
        }
    }
    return _result
}

/**
 * DEPRECATED
 * Create list of nodes in JSON format.
 *
 * @param filter            The Salt's matcher
 * @return                  JSON list of nodes
 */
def getNodeList(master, filter = null) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    common.errorMsg('You are using deprecated method! This method will be removed')
    error('You are using deprecated method! This method will be removed')
    def nodes = []
    def filtered_list = null
    def controllers = salt.getMinions(master, 'I@nova:controller')
    def hw_nodes = salt.getMinions(master, 'G@virtual:physical')
    if ( filter ) {
        filtered_list = salt.getMinions(master, filter)
    }
    def _result = salt.cmdRun(master, 'I@salt:master', "reclass-salt -o json -t", false, null, false)
    def reclass_top = common.parseJSON(_result['return'][0].values()[0])
    def nodesList = reclass_top['base'].keySet()
    for (int i = 0; i < nodesList.size(); i++) {
        if ( filtered_list ) {
            if ( ! filtered_list.contains(nodesList[i]) ) {
                continue
            }
        }
        def ip = getReclassValue(master, nodesList[i], '_param.linux_single_interface.address')
        def network_data = [ip: ip, name: 'management']
        def roles = [nodesList[i].tokenize('.')[0]]
        if ( controllers.contains(nodesList[i]) ) {
            roles.add('controller')
        }
        if ( hw_nodes.contains(nodesList[i]) ) {
            roles.add('hw_node')
        }
        nodes.add([id: i+1, ip: ip, roles: roles, network_data: [network_data]])
    }
    return common.prettify(nodes)
}

/**
 * DEPRECATED
 * Execute mcp sanity tests
 * Deprecated. Will be removed soon
 *
 * @param salt_url          Salt master url
 * @param salt_credentials  Salt credentials
 * @param test_set          Test set for mcp sanity framework
 * @param env_vars          Additional environment variables for cvp-sanity-checks
 * @param output_dir        Directory for results
 */
def runSanityTests(salt_url, salt_credentials, test_set="", output_dir="validation_artifacts/", env_vars="") {
    def common = new com.mirantis.mk.Common()
    common.errorMsg('You are using deprecated method! Please migrate to validate.runTests. This method will be removed')
    error('You are using deprecated method! Please migrate to validate.runTests. This method will be removed')
    def creds = common.getCredentials(salt_credentials)
    def username = creds.username
    def password = creds.password
    def settings = ""
    if ( env_vars != "" ) {
        for (var in env_vars.tokenize(";")) {
            settings += "export ${var}; "
        }
    }
    def script = ". ${env.WORKSPACE}/venv/bin/activate; ${settings}" +
                 "pytest --junitxml ${output_dir}cvp_sanity.xml --tb=short -sv ${env.WORKSPACE}/cvp-sanity-checks/cvp_checks/tests/${test_set}"
    withEnv(["SALT_USERNAME=${username}", "SALT_PASSWORD=${password}", "SALT_URL=${salt_url}"]) {
        def statusCode = sh script:script, returnStatus:true
    }
}

/**
 * DEPRECATED
 * Execute pytest framework tests
 *
 * @param salt_url          Salt master url
 * @param salt_credentials  Salt credentials
 * @param test_set          Test set to run
 * @param env_vars          Additional environment variables for cvp-sanity-checks
 * @param output_dir        Directory for results
 */
def runPyTests(salt_url, salt_credentials, test_set="", env_vars="", name='cvp', container_node="", remote_dir='/root/qa_results/', artifacts_dir='validation_artifacts/') {
    def xml_file = "${name}_report.xml"
    def common = new com.mirantis.mk.Common()
    common.errorMsg('You are using deprecated method! Please migrate to validate.runTests. This method will be removed')
    error('You are using deprecated method! Please migrate to validate.runTests. This method will be removed')
    def salt = new com.mirantis.mk.Salt()
    def creds = common.getCredentials(salt_credentials)
    def username = creds.username
    def password = creds.password
    if (container_node != "") {
        def saltMaster
        saltMaster = salt.connection(salt_url, salt_credentials)
        def script = "pytest --junitxml ${xml_file} --tb=short -sv ${test_set}"
        env_vars.addAll("SALT_USERNAME=${username}", "SALT_PASSWORD=${password}",
                        "SALT_URL=${salt_url}")
        variables = ' -e ' + env_vars.join(' -e ')
        salt.cmdRun(saltMaster, container_node, "docker exec ${variables} ${name} bash -c '${script}'", false)
        salt.cmdRun(saltMaster, container_node, "docker cp ${name}:/var/lib/${xml_file} ${remote_dir}${xml_file}")
        addFiles(saltMaster, container_node, remote_dir+xml_file, artifacts_dir)
    }
    else {
        if (env_vars.size() > 0) {
        variables = 'export ' + env_vars.join(';export ')
        }
        def script = ". ${env.WORKSPACE}/venv/bin/activate; ${variables}; " +
                     "pytest --junitxml ${artifacts_dir}${xml_file} --tb=short -sv ${env.WORKSPACE}/${test_set}"
        withEnv(["SALT_USERNAME=${username}", "SALT_PASSWORD=${password}", "SALT_URL=${salt_url}"]) {
            def statusCode = sh script:script, returnStatus:true
        }
    }
}

/**
 * Execute pytest framework tests
 * For backward compatibility
 * Will be removed soon
 *
 * @param salt_url          Salt master url
 * @param salt_credentials  Salt credentials
 * @param test_set          Test set to run
 * @param env_vars          Additional environment variables for cvp-sanity-checks
 * @param output_dir        Directory for results
 */
def runTests(salt_url, salt_credentials, test_set="", output_dir="validation_artifacts/", env_vars="") {
    def common = new com.mirantis.mk.Common()
    def creds = common.getCredentials(salt_credentials)
    def username = creds.username
    def password = creds.password
    def settings = ""
    if ( env_vars != "" ) {
        for (var in env_vars.tokenize(";")) {
            settings += "export ${var}; "
        }
    }
    def script = ". ${env.WORKSPACE}/venv/bin/activate; ${settings}" +
                 "pytest --junitxml ${output_dir}report.xml --tb=short -sv ${env.WORKSPACE}/${test_set}"
    withEnv(["SALT_USERNAME=${username}", "SALT_PASSWORD=${password}", "SALT_URL=${salt_url}"]) {
        def statusCode = sh script:script, returnStatus:true
    }
}

/**
 * DEPRECATED
 * Execute tempest tests
 *
 * @param target            Host to run tests
 * @param dockerImageLink   Docker image link
 * @param pattern           If not false, will run only tests matched the pattern
 * @param output_dir        Directory for results
 * @param confRepository    Git repository with configuration files for Tempest
 * @param confBranch        Git branch which will be used during the checkout
 * @param repository        Git repository with Tempest
 * @param version           Version of Tempest (tag, branch or commit)
 * @param results           The reports directory
 */
def runTempestTests(master, target, dockerImageLink, output_dir, confRepository, confBranch, repository, version, pattern = "false", results = '/root/qa_results') {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    common.errorMsg('You are using deprecated method! This method will be removed')
    error('You are using deprecated method! This method will be removed')
    def output_file = 'docker-tempest.log'
    def dest_folder = '/home/rally/qa_results'
    def skip_list = '--skip-list /opt/devops-qa-tools/deployment/skip_contrail.list'
    salt.runSaltProcessStep(master, target, 'file.remove', ["${results}"])
    salt.runSaltProcessStep(master, target, 'file.mkdir', ["${results}", "mode=777"])
    def _pillar = salt.getPillar(master, 'I@keystone:server', 'keystone:server')
    def keystone = _pillar['return'][0].values()[0]
    def env_vars = ['tempest_version=15.0.0',
                    "OS_USERNAME=${keystone.admin_name}",
                    "OS_PASSWORD=${keystone.admin_password}",
                    "OS_TENANT_NAME=${keystone.admin_tenant}",
                    "OS_AUTH_URL=http://${keystone.bind.private_address}:${keystone.bind.private_port}/v2.0",
                    "OS_REGION_NAME=${keystone.region}",
                    'OS_ENDPOINT_TYPE=admin'].join(' -e ')
    def cmd = '/opt/devops-qa-tools/deployment/configure.sh; '
    if (confRepository != '' ) {
        cmd = "git clone -b ${confBranch ?: 'master'} ${confRepository} test_config; " +
            'rally deployment create --fromenv --name=tempest; rally deployment config; ' +
            'rally verify create-verifier --name tempest_verifier --type tempest ' +
            "--source ${repository ?: '/tmp/tempest/'} --version ${version: '15.0.0'}; " +
            'rally verify configure-verifier --extend test_config/tempest/tempest.conf --show; '
        skip_list = '--skip-list test_config/tempest/skip-list.yaml'
    }
    if (pattern == 'false') {
        cmd += "rally verify start --pattern set=full ${skip_list} --detailed; "
    }
    else {
        cmd += "rally verify start --pattern set=${pattern} ${skip_list} --detailed; "
    }
    cmd += "rally verify report --type json --to ${dest_folder}/report-tempest.json; " +
        "rally verify report --type html --to ${dest_folder}/report-tempest.html"
    salt.cmdRun(master, target, "docker run -w /home/rally -i --rm --net=host -e ${env_vars} " +
        "-v ${results}:${dest_folder} --entrypoint /bin/bash ${dockerImageLink} " +
        "-c \"${cmd}\" > ${results}/${output_file}")
    addFiles(master, target, results, output_dir)
}

/**
 * Make all-in-one scenario cmd for rally tests
 *
 * @param scenarios_path    Path to scenarios folder/file
 * @param skip_scenarios    Comma-delimited list of scenarios names to skip
 * @param bundle_file       Bundle name to create
*/
def bundle_up_scenarios(scenarios_path, skip_scenarios, bundle_file = '' ) {
      def skip_names = ''
      def skip_dirs = ''
      def result = ''
      if (skip_scenarios != ''){
        for ( scen in skip_scenarios.split(',') ) {
          if ( scen.contains('yaml')) {
            skip_names += "! -name ${scen} "
          }
          else {
            skip_dirs += "-path '${scenarios_path}/${scen}' -prune -o "
          }
        }
      }
      if (bundle_file != '') {
        result = "if [ -f ${scenarios_path} ]; then cp ${scenarios_path} ${bundle_file}; " +
          "else " +
          "find -L ${scenarios_path} " + skip_dirs +
          " -name '*.yaml' " + skip_names +
          "-exec cat {} >> ${bundle_file} \\; ; " +
          "sed -i '/---/d' ${bundle_file}; fi; "
      } else {
        result = "find -L ${scenarios_path} " + skip_dirs +
            " -name '*.yaml' -print " + skip_names
      }

      return result
}

/**
 * Prepare setupDockerAndTest() commands to start Rally tests (optionally with K8S/Stacklight plugins)
 *
 * @param platform          Map with underlay platform data
 * @param scenarios         Directory inside repo with specific scenarios
 * @param sl_scenarios      Directory inside repo with specific scenarios for stacklight
 * @param tasks_args_file   Argument file that is used for throttling settings
 * @param db_connection_str Rally-compliant external DB connection string
 * @param tags              Additional tags used for tagging tasks or building trends
 * @param trends            Build rally trends if enabled
 *
 * Returns: map
 *
 */
def runRallyTests(
        platform, scenarios = '', sl_scenarios = '',
        tasks_args_file = '', db_connection_str = '', tags = [],
        trends = false, skip_list = '', generateReport = true
    ) {

    def dest_folder = '/home/rally'
    def pluginsDir = "${dest_folder}/rally-plugins"
    def scenariosDir = "${dest_folder}/rally-scenarios"
    def resultsDir = "${dest_folder}/test_results"
    def date = new Date()
    date = date.format("yyyyMMddHHmm")
    // compile rally deployment name
    deployment_name = "env=${platform.cluster_name}:platform=${platform.type}:" +
        "date=${date}:cmp=${platform.cmp_count}"

    // set up Rally DB
    def cmd_rally_init = ''
    if (db_connection_str) {
        cmd_rally_init = "sudo sed -i -e " +
            "'s#connection=.*#connection=${db_connection_str}#' " +
            "/etc/rally/rally.conf; "
    }
    cmd_rally_init += 'rally db ensure; '
    // if several jobs are running in parallel (same deployment name),
    // then try to find and use existing in db env
    if (db_connection_str) {
        cmd_rally_init += 'rally env use --env $(rally env list|awk \'/' +
            deployment_name + '/ {print $2}\') ||'
    }

    def cmd_rally_start
    def cmd_rally_stacklight
    def cmd_rally_task_args = tasks_args_file ?: 'job-params-light.yaml'
    def cmd_rally_report = ''
    def cmd_filter_tags = ''
    def trends_limit = 20

    // generate html report if required
    if (generateReport) {
        cmd_rally_report = 'rally task export ' +
            '--uuid $(rally task list --uuids-only --status finished) ' +
            "--type junit-xml --to ${resultsDir}/report-rally.xml; " +
            'rally task report --uuid $(rally task list --uuids-only --status finished) ' +
            "--out ${resultsDir}/report-rally.html; "
    }

    // build rally trends if required
    if (trends && db_connection_str) {
        if (tags) {
            cmd_filter_tags = "--tag " + tags.join(' ')
        }
        cmd_rally_report += 'rally task trends --tasks ' +
            '$(rally task list ' + cmd_filter_tags +
            ' --all-deployments --uuids-only --status finished ' +
            "| head -${trends_limit} ) " +
            "--out ${resultsDir}/trends-rally.html"
    }

    // add default env tags for inserting into rally tasks
    tags = tags + [
        "env=${platform.cluster_name}",
        "platform=${platform.type}",
        "cmp=${platform.cmp_count}"
    ]

    // set up rally deployment cmd
    if (platform['type'] == 'openstack') {
      cmd_rally_init += "rally deployment create --name='${deployment_name}' --fromenv; " +
          "rally deployment check; "
    } else if (platform['type'] == 'k8s') {
      cmd_rally_init += "rally env create --name='${deployment_name}' --from-sysenv; " +
          "rally env check; "
    } else {
      throw new Exception("Platform ${platform} is not supported yet")
    }

    // set up rally task args file
    switch(tasks_args_file) {
      case 'none':
        cmd_rally_task_args = ''
        break
      case '':
        cmd_rally_task_args = "--task-args-file ${scenariosDir}/job-params-light.yaml"
        break
      default:
        cmd_rally_task_args = "--task-args-file ${scenariosDir}/${tasks_args_file}"
      break
    }

    // configure Rally for Stacklight (only with Openstack for now)
    if (platform['stacklight']['enabled'] && (platform['type'] == 'openstack')) {
      if (! sl_scenarios) {
        throw new Exception("There's no Stacklight scenarios to execute")
      }
      def scenBundle = "${resultsDir}/scenarios_${platform.type}_stacklight.yaml"
      cmd_rally_stacklight = bundle_up_scenarios(
          scenariosDir + '/' + sl_scenarios,
          skip_list,
          scenBundle,
      )
      tags.add('stacklight')
      cmd_rally_stacklight += "sed -i 's/grafana_password: .*/grafana_password: ${platform.stacklight.grafanaPass}/' " +
          "${scenariosDir}/${tasks_args_file}; rally --log-file ${resultsDir}/tasks_stacklight.log task start --tag " + tags.join(' ') +
          " --task ${scenBundle} ${cmd_rally_task_args} || true "
    }

    // prepare scenarios and rally task cmd
    if (scenarios) {
      switch (platform['type']) {
        case 'openstack':
          def scenBundle = "${resultsDir}/scenarios_${platform.type}.yaml"
          cmd_rally_start = bundle_up_scenarios(
              scenariosDir + '/' + scenarios,
              skip_list,
              scenBundle,
          )
          cmd_rally_start += "rally --log-file ${resultsDir}/tasks_openstack.log task start --tag " + tags.join(' ') +
              " --task ${scenBundle} ${cmd_rally_task_args} || true; "
        break
        // due to the bug in Rally threads, K8S plugin gets stuck on big all-in-one scenarios
        // so we have to feed them separately for K8S case
        case 'k8s':
          cmd_rally_start = 'for task in $(' +
              bundle_up_scenarios(scenariosDir + '/' + scenarios, skip_list) + '); do ' +
              "rally --log-file ${resultsDir}/tasks_k8s.log task start --tag " + tags.join(' ') +
              ' --task $task ' + cmd_rally_task_args + ' || true; done; '
        break
      }
    } else {
      if (! cmd_rally_stacklight) {
        throw new Exception("No scenarios found to run Rally on")
      }
    }

    // compile full rally cmd map
    def full_cmd = [
        '001_install_plugins': "sudo pip install --upgrade ${pluginsDir}",
        '002_init_rally': cmd_rally_init,
        '003_start_rally': cmd_rally_start ?: "echo no tasks to run",
        '004_start_rally_stacklight': cmd_rally_stacklight ?: "echo no tasks to run",
        '005_rally_report': cmd_rally_report ?: "echo no tasks to run",
    ]

    return full_cmd

}

/**
 * DEPRECATED
 * Generate test report
 *
 * @param target            Host to run script from
 * @param dockerImageLink   Docker image link
 * @param output_dir        Directory for results
 * @param results           The reports directory
 */
def generateTestReport(master, target, dockerImageLink, output_dir, results = '/root/qa_results') {
    def report_file = 'jenkins_test_report.html'
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    common.errorMsg('You are using deprecated method! This method will be removed')
    error('You are using deprecated method! This method will be removed')
    def dest_folder = '/opt/devops-qa-tools/generate_test_report/test_results'
    salt.runSaltProcessStep(master, target, 'file.remove', ["${results}"])
    salt.runSaltProcessStep(master, target, 'file.mkdir', ["${results}", "mode=777"])
    def reports = ['report-tempest.json',
                   'report-rally.xml',
                   'report-k8s-e2e-tests.txt',
                   'report-ha.json',
                   'report-spt.txt']
    for ( report in reports ) {
        if ( fileExists("${output_dir}${report}") ) {
            common.infoMsg("Copying ${report} to docker container")
            def items = sh(script: "base64 -w0 ${output_dir}${report} > ${output_dir}${report}_encoded; " +
                "split -b 100KB -d -a 4 ${output_dir}${report}_encoded ${output_dir}${report}__; " +
                "rm ${output_dir}${report}_encoded; " +
                "find ${output_dir} -type f -name ${report}__* -printf \'%f\\n\' | sort", returnStdout: true)
            for ( item in items.tokenize() ) {
                def content = sh(script: "cat ${output_dir}${item}", returnStdout: true)
                salt.cmdRun(master, target, "echo \"${content}\" >> ${results}/${report}_encoded", false, null, false)
                sh(script: "rm ${output_dir}${item}")
            }
            salt.cmdRun(master, target, "base64 -d ${results}/${report}_encoded > ${results}/${report}; " +
                "rm ${results}/${report}_encoded", false, null, false)
        }
    }

    def cmd = "jenkins_report.py --path /opt/devops-qa-tools/generate_test_report/; " +
        "cp ${report_file} ${dest_folder}/${report_file}"
    salt.cmdRun(master, target, "docker run -i --rm --net=host " +
        "-v ${results}:${dest_folder} ${dockerImageLink} " +
        "/bin/bash -c \"${cmd}\"")
    def report_content = salt.getFileContent(master, target, "${results}/${report_file}")
    writeFile file: "${output_dir}${report_file}", text: report_content
}

/**
 * DEPRECATED
 * Execute SPT tests
 *
 * @param target            Host to run tests
 * @param dockerImageLink   Docker image link
 * @param output_dir        Directory for results
 * @param ext_variables     The list of external variables
 * @param results           The reports directory
 */
def runSptTests(master, target, dockerImageLink, output_dir, ext_variables = [], results = '/root/qa_results') {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    common.errorMsg('You are using deprecated method! This method will be removed')
    error('You are using deprecated method! This method will be removed')
    def dest_folder = '/home/rally/qa_results'
    salt.runSaltProcessStep(master, target, 'file.remove', ["${results}"])
    salt.runSaltProcessStep(master, target, 'file.mkdir', ["${results}", "mode=777"])
    def nodes = getNodeList(master)
    def nodes_hw = getNodeList(master, 'G@virtual:physical')
    def _pillar = salt.getPillar(master, 'I@keystone:server', 'keystone:server')
    def keystone = _pillar['return'][0].values()[0]
    def ssh_key = salt.getFileContent(master, 'I@salt:master', '/root/.ssh/id_rsa')
    def env_vars = ( ['tempest_version=15.0.0',
                      "OS_USERNAME=${keystone.admin_name}",
                      "OS_PASSWORD=${keystone.admin_password}",
                      "OS_TENANT_NAME=${keystone.admin_tenant}",
                      "OS_AUTH_URL=http://${keystone.bind.private_address}:${keystone.bind.private_port}/v2.0",
                      "OS_REGION_NAME=${keystone.region}",
                      'OS_ENDPOINT_TYPE=admin'] + ext_variables ).join(' -e ')
    salt.runSaltProcessStep(master, target, 'file.write', ["${results}/nodes.json", nodes])
    salt.runSaltProcessStep(master, target, 'file.write', ["${results}/nodes_hw.json", nodes_hw])
    def cmd = '/opt/devops-qa-tools/deployment/configure.sh; ' +
        'sudo mkdir -p /root/.ssh; sudo chmod 700 /root/.ssh; ' +
        "echo \\\"${ssh_key}\\\" | sudo tee /root/.ssh/id_rsa > /dev/null; " +
        'sudo chmod 600 /root/.ssh/id_rsa; ' +
        "sudo timmy -c simplified-performance-testing/config.yaml " +
        "--nodes-json ${dest_folder}/nodes.json --log-file ${dest_folder}/docker-spt2.log; " +
        "./simplified-performance-testing/SPT_parser.sh > ${dest_folder}/report-spt.txt; " +
        "custom_spt_parser.sh ${dest_folder}/nodes_hw.json > ${dest_folder}/report-spt-hw.txt; " +
        "cp /tmp/timmy/archives/general.tar.gz ${dest_folder}/results-spt.tar.gz"
    salt.cmdRun(master, target, "docker run -i --rm --net=host -e ${env_vars} " +
        "-v ${results}:${dest_folder} ${dockerImageLink} /bin/bash -c " +
        "\"${cmd}\" > ${results}/docker-spt.log")
    addFiles(master, target, results, output_dir)
}

/**
 * Configure docker container
 *
 * @param target            		Host to run container
 * @param proxy            		Proxy for accessing github and pip
 * @param testing_tools_repo    	Repo with testing tools: configuration script, skip-list, etc.
 * @param tempest_repo         		Tempest repo to clone. Can be upstream tempest (default, recommended), your customized tempest in local/remote repo or path inside container. If not specified, tempest will not be configured.
 * @param tempest_endpoint_type         internalURL or adminURL or publicURL to use in tests
 * @param tempest_version	        Version of tempest to use. This value will be just passed to configure.sh script (cvp-configuration repo).
 * @param conf_script_path              Path to configuration script.
 * @param ext_variables                 Some custom extra variables to add into container
 * @param container_name                Name of container to use
 */
def configureContainer(master, target, proxy, testing_tools_repo, tempest_repo,
                       tempest_endpoint_type="internalURL", tempest_version="",
                       conf_script_path="", ext_variables = [], container_name="cvp") {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    if (testing_tools_repo != "" ) {
        workdir = ''
        if (testing_tools_repo.contains('http://') || testing_tools_repo.contains('https://')) {
            salt.cmdRun(master, target, "docker exec ${container_name} git clone ${testing_tools_repo} cvp-configuration")
            configure_script = conf_script_path != "" ? conf_script_path : "cvp-configuration/configure.sh"
        }
        else {
            configure_script = testing_tools_repo
            workdir = ' -w /var/lib/'
        }
        ext_variables.addAll("PROXY=${proxy}", "TEMPEST_REPO=${tempest_repo}",
                             "TEMPEST_ENDPOINT_TYPE=${tempest_endpoint_type}",
                             "tempest_version=${tempest_version}")
        salt.cmdRun(master, target, "docker exec -e " + ext_variables.join(' -e ') + " ${workdir} ${container_name} bash -c ${configure_script}")
    }
    else {
        common.infoMsg("TOOLS_REPO is empty, no configuration is needed for this container")
    }
}

/**
 * Run Tempest
 *
 * @param target                        Host to run container
 * @param test_pattern                  Test pattern to run
 * @param skip_list                     Path to skip-list
 * @param output_dir                    Directory on target host for storing results (containers is not a good place)
 */
def runCVPtempest(master, target, test_pattern="set=smoke", skip_list="", output_dir, output_filename="docker-tempest") {
    def salt = new com.mirantis.mk.Salt()
    def xml_file = "${output_filename}.xml"
    def html_file = "${output_filename}.html"
    skip_list_cmd = ''
    if (skip_list != '') {
        skip_list_cmd = "--skip-list ${skip_list}"
    }
    salt.cmdRun(master, target, "docker exec cvp rally verify start --pattern ${test_pattern} ${skip_list_cmd} --detailed")
    salt.cmdRun(master, target, "docker exec cvp rally verify report --type junit-xml --to /home/rally/${xml_file}")
    salt.cmdRun(master, target, "docker exec cvp rally verify report --type html --to /home/rally/${html_file}")
    salt.cmdRun(master, target, "docker cp cvp:/home/rally/${xml_file} ${output_dir}")
    salt.cmdRun(master, target, "docker cp cvp:/home/rally/${html_file} ${output_dir}")
    return salt.cmdRun(master, target, "docker exec cvp rally verify show | head -5 | tail -1 | " +
                                       "awk '{print \$4}'")['return'][0].values()[0].split()[0]
}

/**
 * Run Rally
 *
 * @param target                        Host to run container
 * @param test_pattern                  Test pattern to run
 * @param scenarios_path                Path to Rally scenarios
 * @param output_dir                    Directory on target host for storing results (containers is not a good place)
 * @param container_name                Name of container to use
 */
def runCVPrally(master, target, scenarios_path, output_dir, output_filename="docker-rally", container_name="cvp") {
    def salt = new com.mirantis.mk.Salt()
    def xml_file = "${output_filename}.xml"
    def html_file = "${output_filename}.html"
    salt.cmdRun(master, target, "docker exec ${container_name} rally task start ${scenarios_path}")
    salt.cmdRun(master, target, "docker exec ${container_name} rally task report --out ${html_file}")
    salt.cmdRun(master, target, "docker exec ${container_name} rally task report --junit --out ${xml_file}")
    salt.cmdRun(master, target, "docker cp ${container_name}:/home/rally/${xml_file} ${output_dir}")
    salt.cmdRun(master, target, "docker cp ${container_name}:/home/rally/${html_file} ${output_dir}")
}


/**
 * Shutdown node
 *
 * @param target          Host to run command
 * @param mode            How to shutdown node
 * @param retries         # of retries to make to check node status
 */
def shutdown_vm_node(master, target, mode, retries=200) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    if (mode == 'reboot') {
        try {
            def out = salt.runSaltCommand(master, 'local', ['expression': target, 'type': 'compound'], 'cmd.run', null, ['reboot'], null, 3, 3)
        } catch (Exception e) {
            common.warningMsg('Timeout from minion: node must be rebooting now')
        }
        common.warningMsg("Checking that minion is down")
        status = "True"
        for (i = 0; i < retries; i++) {
            status = salt.minionsReachable(master, 'I@salt:master', target, null, 5, 1)
            if (status != "True") {
                break
            }
        }
        if (status == "True") {
            throw new Exception("Tired to wait for minion ${target} to stop responding")
        }
    }
    if (mode == 'hard_shutdown' || mode == 'soft_shutdown') {
        kvm = locate_node_on_kvm(master, target)
        if (mode == 'soft_shutdown') {
            salt.cmdRun(master, target, "shutdown -h 0")
        }
        if (mode == 'hard_shutdown') {
            salt.cmdRun(master, kvm, "virsh destroy ${target}")
        }
        common.warningMsg("Checking that vm on kvm is in power off state")
        status = 'running'
        for (i = 0; i < retries; i++) {
            status = check_vm_status(master, target, kvm)
            echo "Current status - ${status}"
            if (status != 'running') {
                break
            }
            sleep (1)
        }
        if (status == 'running') {
            throw new Exception("Tired to wait for node ${target} to shutdown")
        }
    }
}


/**
 * Locate kvm where target host is located
 *
 * @param target          Host to check
 */
def locate_node_on_kvm(master, target) {
    def salt = new com.mirantis.mk.Salt()
    def list = salt.runSaltProcessStep(master, "I@salt:control", 'cmd.run', ["virsh list --all | grep ' ${target}'"])['return'][0]
    for (item in list.keySet()) {
        if (list[item]) {
            return item
        }
    }
}

/**
 * Check target host status
 *
 * @param target          Host to check
 * @param kvm             KVM node where target host is located
 */
def check_vm_status(master, target, kvm) {
    def salt = new com.mirantis.mk.Salt()
    def list = salt.runSaltProcessStep(master, "${kvm}", 'cmd.run', ["virsh list --all | grep ' ${target}'"])['return'][0]
    for (item in list.keySet()) {
        if (list[item]) {
            return list[item].split()[2]
        }
    }
}

/**
 * Find vip on nodes
 *
 * @param target          Pattern, e.g. ctl*
 */
def get_vip_node(master, target) {
    def salt = new com.mirantis.mk.Salt()
    def list = salt.runSaltProcessStep(master, "${target}", 'cmd.run', ["ip a | grep '/32'"])['return'][0]
    for (item in list.keySet()) {
        if (list[item]) {
            return item
        }
    }
}

/**
 * Find vip on nodes
 *
 * @param target          Host with cvp container
 * @param container_name  Name of container
 * @param script_path     Path to cleanup script (inside container)
 */
def openstack_cleanup(master, target, container_name="cvp", script_path="/home/rally/cleanup.sh") {
    def salt = new com.mirantis.mk.Salt()
    salt.runSaltProcessStep(master, "${target}", 'cmd.run', ["docker exec ${container_name} bash -c ${script_path}"])
}


/**
 * Cleanup
 *
 * @param target            Host to run commands
 * @param name              Name of container to remove
 */
def runCleanup(master, target, name='cvp') {
    def salt = new com.mirantis.mk.Salt()
    if ( salt.cmdRun(master, target, "docker ps -f name=${name} -q", false, null, false)['return'][0].values()[0] ) {
        salt.cmdRun(master, target, "docker rm -f ${name}")
    }
}
/**
 * Prepare venv for any python project
 * Note: <repo_name>\/requirements.txt content will be used
 * for this venv
 *
 * @param repo_url          Repository url to clone
 * @param proxy             Proxy address to use
 */
def prepareVenv(repo_url, proxy, useSystemPackages=false) {
    def python = new com.mirantis.mk.Python()
    repo_name = "${repo_url}".tokenize("/").last()
    if (repo_url.tokenize().size() > 1){
        if (repo_url.tokenize()[1] == '-b'){
            repo_name = repo_url.tokenize()[0].tokenize("/").last()
        }
    }
    path_venv = "${env.WORKSPACE}/venv"
    path_req = "${env.WORKSPACE}/${repo_name}/requirements.txt"
    sh "rm -rf ${repo_name}"
    // this is temporary W/A for offline deployments
    // Jenkins slave image has /opt/pip-mirror/ folder
    // where pip wheels for cvp projects are located
    if (proxy != 'offline') {
        withEnv(["HTTPS_PROXY=${proxy}", "HTTP_PROXY=${proxy}", "https_proxy=${proxy}", "http_proxy=${proxy}"]) {
            sh "git clone ${repo_url}"
            python.setupVirtualenv(path_venv, "python2", [], path_req, true, useSystemPackages)
        }
    }
    else {
        sh "git clone ${repo_url}"
        sh "virtualenv ${path_venv} --python python2"
        python.runVirtualenvCommand(path_venv, "pip install --no-index --find-links=/opt/pip-mirror/ -r ${path_req}", true)
    }
}

/** Install docker if needed
 *
 * @param target              Target node to install docker pkg
 */
def installDocker(master, target) {
    def salt = new com.mirantis.mk.Salt()
    if ( ! salt.runSaltProcessStep(master, target, 'pkg.version', ["docker-engine"]) ) {
        salt.runSaltProcessStep(master, target, 'pkg.install', ["docker.io"])
    }
}
