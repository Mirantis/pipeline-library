package com.mirantis.mcp

/**
 *
 * Tests providing functions
 *
 */

/**
 * Configure docker image with tests
 *
 * @param dockerImageLink   Docker image link with rally and tempest
 * @param target            Host to run tests
 * @param output_dir        Directory for results
 * @param ext_variables     The set of external variables
 */
def runContainerConfiguration(master, dockerImageLink, target, output_dir, ext_variables){
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def output_file = 'docker.log'
    def nodes = getNodeList(master)
    def nodes_hw = getNodeList(master, 'G@virtual:physical')
    def _pillar = salt.getPillar(master, 'I@keystone:server', 'keystone:server')
    def keystone = _pillar['return'][0].values()[0]
    def ssh_key = getFileContent(master, 'I@salt:master', '/root/.ssh/id_rsa')
    salt.cmdRun(master, target, "docker run -tid --net=host --name=qa_tools " +
            " ${ext_variables} " +
            "-e tempest_version=15.0.0 -e OS_USERNAME=${keystone.admin_name} " +
            "-e OS_PASSWORD=${keystone.admin_password} -e OS_TENANT_NAME=${keystone.admin_tenant} " +
            "-e OS_AUTH_URL=http://${keystone.bind.private_address}:${keystone.bind.private_port}/v2.0 " +
            "-e OS_REGION_NAME=${keystone.region} -e OS_ENDPOINT_TYPE=admin ${dockerImageLink} /bin/bash")
    salt.cmdRun(master, target, "docker exec qa_tools bash -c \"sudo mkdir -p /root/.ssh; " +
            "echo \'${ssh_key}\' | sudo tee /root/.ssh/id_rsa > /dev/null; " +
            "sudo chmod 700 /root/.ssh; sudo chmod 600 /root/.ssh/id_rsa; " +
            "echo -e '${nodes}' > nodes.json; echo -e '${nodes_hw}' > nodes_hw.json\"")
    salt.cmdRun(master, target, "docker exec qa_tools bash -c /opt/devops-qa-tools/deployment/configure.sh > ${output_file}")
    def file_content = getFileContent(master, target, output_file)
    writeFile file: "${output_dir}${output_file}", text: file_content
}

/**
 * Get file content. Extended version
 *
 * @param target            Compound target (should target only one host)
 * @param file              File path to read
 * @return                  The content of the file
 */
def getFileContent(master, target, file) {
    def salt = new com.mirantis.mk.Salt()
    def _result = null
    def file_content = null
    def result = salt.cmdRun(master, target, "if [ \$(wc -c <${file}) -gt 1048575 ]; then echo 1; fi", false, null, false)
    def large_file = result['return'][0].values()[0]
    if ( large_file ) {
        salt.cmdRun(master, target, "split -b 1MB -d ${file} ${file}__", false, null, false)
        def list_files = salt.cmdRun(master, target, "ls ${file}__*", false, null, false)
        for ( item in list_files['return'][0].values()[0].tokenize() ) {
            _result = salt.cmdRun(master, target, "cat ${item}", false, null, false)
            file_content = file_content + _result['return'][0].values()[0].replaceAll('Salt command execution success','')
        }
        salt.cmdRun(master, target, "rm ${file}__*", false, null, false)
        return file_content
    } else {
        _result = salt.cmdRun(master, target, "cat ${file}", false, null, false)
        return _result['return'][0].values()[0].replaceAll('Salt command execution success','')
    }
}

/**
 * Get reclass value
 *
 * @param target            The host for which the values will be provided
 * @param filter            Parameters divided by dots
 * @return                  The pillar data
 */
def getReclassValue(master, target, filter) {
    def common = new com.mirantis.mk.Common()
    def salt = new com.mirantis.mk.Salt()
    def items = filter.tokenize('.')
    def _result = salt.cmdRun(master, 'I@salt:master', "reclass-salt -o json -p ${target} | " +
        "python -c 'import json,sys; print(json.dumps(json.loads(sys.stdin.read()).get(\"${items[0]}\")))'", false, null, false)
    _result = common.parseJSON(_result['return'][0].values()[0])
    for ( item in items.tail()) {
        if ( _result ) {
            _result = _result["${item}"]
        }
    }
    return _result
}

/**
 * Create list of nodes in JSON format.
 *
 * @param filter            The Salt's matcher
 * @return                  JSON list of nodes
 */
def getNodeList(master, filter = null) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def builder = new groovy.json.JsonBuilder()
    def nodes = []
    def n_counter = 0
    def filtered_list = null
    def controllers = salt.getMinions(master, 'I@nova:controller')
    def hw_nodes = salt.getMinions(master, 'G@virtual:physical')
    def json = builder (ip: '', roles: '', id: '', network_data: [ builder (name: 'management', ip:  '')])
    if ( filter ) {
        filtered_list = salt.getMinions(master, filter)
        filtered_list.addAll(controllers)
    }
    def _result = salt.cmdRun(master, 'I@salt:master', "reclass-salt -o json -t", false, null, false)
    def reclass_top = common.parseJSON(_result['return'][0].values()[0])
    for (item in reclass_top.base) {
        if ( filtered_list ) {
            if ( ! filtered_list.contains(item.getKey()) ) {
                continue
            }
        }
        n_counter += 1
        json.id = n_counter.toString()
        json.ip = getReclassValue(master, item.getKey(), '_param.linux_single_interface.address')
        json.network_data[0].ip = json.ip
        json.roles = item.getKey().tokenize('.')[0]
        if ( controllers.contains(item.getKey()) ) {
            json.roles = "${json.roles}, controller"
        }
        if ( hw_nodes.contains(item.getKey()) ) {
            json.roles = "${json.roles}, hw_node"
        }
        def node = builder.toPrettyString().replace('"', '\\"')
        nodes.add(node)
    }
    return nodes
}

/**
 * Execute mcp sanity tests
 *
 * @param salt_url          Salt master url
 * @param salt_credentials  Salt credentials
 * @param test_set          Test set for mcp sanity framework
 * @param output_dir        Directory for results
 */
def runSanityTests(salt_url, salt_credentials, test_set, output_dir) {
    def common = new com.mirantis.mk.Common()
    creds = common.getCredentials(salt_credentials)
    username = creds.username
    password = creds.password
    def script = ". ${env.WORKSPACE}/venv/bin/activate; pytest --junitxml ${output_dir}cvp_sanity.xml -sv ${env.WORKSPACE}/cvp-sanity-checks/cvp_checks/tests/${test_set}"
    withEnv(["SALT_USERNAME=${username}", "SALT_PASSWORD=${password}", "SALT_URL=${salt_url}"]) {
        def statusCode = sh script:script, returnStatus:true
    }
}

/**
 * Execute tempest tests
 *
 * @param target            Host to run tests
 * @param pattern           If not false, will run only tests matched the pattern
 * @param output_dir        Directory for results
 */
def runTempestTests(master, target, output_dir, pattern = "false") {
    def salt = new com.mirantis.mk.Salt()
    def output_file = 'docker-tempest.log'
    def path = '/opt/devops-qa-tools/generate_test_report/test_results'
    def jsonfile = 'tempest_results.json'
    def htmlfile = 'tempest_results.html'
    if (pattern == "false") {
        salt.cmdRun(master, target, "docker exec qa_tools rally verify start --pattern set=full " +
                "--detailed > ${output_file}")
    }
    else {
        salt.cmdRun(master, target, "docker exec qa_tools rally verify start --pattern ${pattern} " +
                "--detailed > ${output_file}")
    }
    salt.cmdRun(master, target, "docker exec qa_tools rally verify report --type json " +
                "--to ${path}/report-tempest.json")
    salt.cmdRun(master, target, "docker exec qa_tools rally verify report --type html " +
                "--to ${path}/report-tempest.html")

    salt.cmdRun(master, target, "docker cp qa_tools:${path}/report-tempest.json ${jsonfile}")
    salt.cmdRun(master, target, "docker cp qa_tools:${path}/report-tempest.html ${htmlfile}")
    def file_content = getFileContent(master, target, jsonfile)
    writeFile file: "${output_dir}/report-tempest.json", text: file_content
    file_content = getFileContent(master, target, htmlfile)
    writeFile file: "${output_dir}/report-tempest.html", text: file_content
    file_content = getFileContent(master, target, output_file)
    writeFile file: "${output_dir}${output_file}", text: file_content
}

/**
 * Execute rally tests
 *
 * @param target            Host to run tests
 * @param pattern           If not false, will run only tests matched the pattern
 * @param output_dir        Directory for results
 */
def runRallyTests(master, target, output_dir, pattern = "false") {
    def salt = new com.mirantis.mk.Salt()
    def output_file = 'docker-rally.log'
    def path = '/opt/devops-qa-tools/generate_test_report/test_results'
    def xmlfile = 'rally_results.xml'
    def htmlfile = 'rally_results.html'
    salt.cmdRun(master, target, "docker exec qa_tools rally task start combined_scenario.yaml --task-args-file " +
            "/opt/devops-qa-tools/rally-scenarios/task_arguments.yaml | tee ${output_file}")

    salt.cmdRun(master, target, "docker exec qa_tools rally task export --type junit-xml " +
                "--to ${path}/report-rally.xml")
    salt.cmdRun(master, target, "docker exec qa_tools rally task report --out ${path}/report-rally.html")
    salt.cmdRun(master, target, "docker cp qa_tools:${path}/report-rally.xml ${xmlfile}")
    salt.cmdRun(master, target, "docker cp qa_tools:${path}/report-rally.html ${htmlfile}")

    def file_content = getFileContent(master, target, xmlfile)
    writeFile file: "${output_dir}/report-rally.xml", text: file_content
    file_content = getFileContent(master, target, htmlfile)
    writeFile file: "${output_dir}/report-rally.html", text: file_content
    file_content = getFileContent(master, target, output_file)
    writeFile file: "${output_dir}${output_file}", text: file_content
}

/**
 * Generate test report
 *
 * @param target            Host to run script from
 * @param output_dir        Directory for results
 */
def generateTestReport(master, target, output_dir) {
    def report_file = 'jenkins_test_report.html'
    def path = '/opt/devops-qa-tools/generate_test_report/'
    def res_path = '/opt/devops-qa-tools/generate_test_report/test_results/'
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()

    // Create 'test_results' directory in case it doesn't exist in container
    def test_results = salt.cmdRun(master, target, "docker exec qa_tools bash -c \"if [ ! -d ${res_path} ]; " +
                "then echo Creating directory ${res_path}; mkdir ${res_path}; fi\"")

    def reports = ['report-tempest.json', 'report-rally.xml', 'report-k8s-e2e-tests.txt', 'report-ha.json', 'report-spt.txt']

    for (report in reports) {
        def _result = salt.cmdRun(master, target, "docker exec qa_tools bash -c \"if [ -f ${res_path}${report} ]; then echo 1; fi\"", checkResponse=false)
        res = _result['return'][0].values()[0]
        if ( res ) {
            common.infoMsg("File ${report} already exists in docker container")
            continue
        }
        if ( fileExists("${output_dir}${report}") ) {
            common.infoMsg("Copying ${report} to docker container")
            if ("${report}" == "report-tempest.json") {
                def temp_file = readJSON file: "${output_dir}/${report}"
                def tempest_cont = temp_file['verifications']
                def json = common.prettify(["verifications":tempest_cont])
                json = sh(script: "echo '${json}' | base64 -w 0", returnStdout: true)
                salt.cmdRun(master, target, "docker exec qa_tools bash -c \"echo \"${json}\" | base64 -d | tee ${res_path}${report}\"", false, null, true)
            }
            else if ( "${report}" == "report-k8s-e2e-tests.txt" ) {
                def k8s_content = sh(script: "cat ${output_dir}${report}| tail -20 | base64 -w 0", returnStdout: true)
                salt.cmdRun(master, target, "docker exec qa_tools bash -c \"echo ${k8s_content} | base64 -d | tee ${res_path}${report}\"", false, null, true)
            }
            else {
                def rep_content = sh(script: "cat ${output_dir}${report} | base64 -w 0", returnStdout: true)
                salt.cmdRun(master, target, "docker exec qa_tools bash -c \"echo \"${rep_content}\" | base64 -d | tee ${res_path}${report}\"", false, null, true)
            }
        }
    }
    salt.cmdRun(master, target, "docker exec qa_tools jenkins_report.py --path ${path}")
    salt.cmdRun(master, target, "docker cp qa_tools:/home/rally/${report_file} ${report_file}")

    def report_content = salt.getFileContent(master, target, report_file)
    writeFile file: "${output_dir}${report_file}", text: report_content
}

/**
 * Execute SPT tests
 *
 * @param target            Host to run tests
 * @param output_dir        Directory for results
 */
def runSptTests(master, target, output_dir) {
    def salt = new com.mirantis.mk.Salt()
    def output_file = 'docker-spt.log'
    def report_file = 'report-spt.txt'
    def report_file_hw = 'report-spt-hw.txt'
    def archive_file = 'results-spt.tar.gz'
    def path = '/opt/devops-qa-tools/generate_test_report/test_results'

    salt.cmdRun(master, target, "docker exec qa_tools sudo timmy -c simplified-performance-testing/config.yaml " +
            "--nodes-json nodes.json --log-file ${output_file}")
    salt.cmdRun(master, target, "docker exec qa_tools ./simplified-performance-testing/SPT_parser.sh > ${report_file}")
    salt.cmdRun(master, target, "docker exec qa_tools custom_spt_parser.sh > ${report_file_hw}")

    salt.cmdRun(master, target, "docker cp ${report_file} qa_tools:${path}/report-spt.txt")
    salt.cmdRun(master, target, "docker cp qa_tools:/home/rally/${output_file} ${output_file}")
    salt.cmdRun(master, target, "docker cp qa_tools:/tmp/timmy/archives/general.tar.gz ${archive_file}")

    def file_content = getFileContent(master, target, output_file)
    writeFile file: "${output_dir}${output_file}", text: file_content
    file_content = getFileContent(master, target, report_file)
    writeFile file: "${output_dir}${report_file}", text: file_content
    file_content = getFileContent(master, target, report_file_hw)
    writeFile file: "${output_dir}${report_file_hw}", text: file_content
}

/**
 * Cleanup
 *
 * @param target            Host to run commands
 * @param output_dir        Directory for results
 */
def runCleanup(master, target, output_dir) {
    def salt = new com.mirantis.mk.Salt()
    if ( salt.cmdRun(master, target, "docker ps -f name=qa_tools -q", false, null, false)['return'][0].values()[0] ) {
        salt.cmdRun(master, target, "docker rm -f qa_tools")
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
def prepareVenv(repo_url, proxy) {
    def python = new com.mirantis.mk.Python()
    repo_name = "${repo_url}".tokenize("/").last()
    sh "rm -rf ${repo_name}"
    withEnv(["HTTPS_PROXY=${proxy}", "HTTP_PROXY=${proxy}", "https_proxy=${proxy}", "http_proxy=${proxy}"]) {
        sh "git clone ${repo_url}"
        python.setupVirtualenv("${env.WORKSPACE}/venv", "python2", [], "${env.WORKSPACE}/${repo_name}/requirements.txt", true)
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
