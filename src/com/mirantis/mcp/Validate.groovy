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
 * @param spt_variables     The set of variables for SPT
 */
def runContainerConfiguration(master, dockerImageLink, target, output_dir, spt_variables){
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def output_file = 'docker.log'
    def nodes = getNodeList(master)
    def nodes_hw = getNodeList(master, 'G@virtual:physical')
    def controller = salt.minionPresent(master, 'I@salt:master', 'ctl01', true, null, true, 200, 1)['return'][0].values()[0]
    def _pillar = salt.cmdRun(master, 'I@salt:master', "reclass-salt -o json -p ${controller} | " +
            "python -c 'import json,sys; print(json.dumps(json.loads(sys.stdin.read())[\"keystone\"][\"server\"]))'")
    def keystone = common.parseJSON(_pillar['return'][0].values()[0])
    def ssh_key = getFileContent(master, 'I@salt:master', '/root/.ssh/id_rsa')
    salt.cmdRun(master, target, "docker run -tid --net=host --name=qa_tools " +
            " ${spt_variables} " +
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
 * Execute tempest tests
 *
 * @param target            Host to run tests
 * @param pattern           If not false, will run only tests matched the pattern
 * @param output_dir        Directory for results
 */
def runTempestTests(master, target, output_dir, pattern = "false") {
    def salt = new com.mirantis.mk.Salt()
    def output_file = 'docker-tempest.log'
    if (pattern == "false") {
        salt.cmdRun(master, target, "docker exec qa_tools rally verify start --pattern set=full " +
                "--detailed > ${output_file}")
    }
    else {
        salt.cmdRun(master, target, "docker exec qa_tools rally verify start --pattern ${pattern} " +
                "--detailed > ${output_file}")
    }
    def file_content = getFileContent(master, target, output_file)
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
    salt.cmdRun(master, target, "docker exec qa_tools rally task start combined_scenario.yaml --task-args-file " +
            "/opt/devops-qa-tools/rally-scenarios/task_arguments.yaml | tee ${output_file}")
    def file_content = getFileContent(master, target, output_file)
    writeFile file: "${output_dir}${output_file}", text: file_content
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
    salt.cmdRun(master, target, "docker exec qa_tools sudo timmy -c simplified-performance-testing/config.yaml " +
            "--nodes-json nodes.json --log-file ${output_file}")
    salt.cmdRun(master, target, "docker exec qa_tools ./simplified-performance-testing/SPT_parser.sh > ${report_file}")
    salt.cmdRun(master, target, "docker exec qa_tools custom_spt_parser.sh > ${report_file_hw}")
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
    sh "rm -r ${output_dir}"
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
