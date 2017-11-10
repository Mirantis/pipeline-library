package com.mirantis.mcp

/**
 *
 * Tests providing functions
 *
 */

/**
 * Run docker container with basic (keystone) parameters
 *
 * @param target            Host to run container
 * @param dockerImageLink   Docker image link. May be custom or default rally image
 */
def runBasicContainer(master, target, dockerImageLink="rallyforge/rally"){
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def _pillar = salt.getPillar(master, 'I@keystone:server', 'keystone:server')
    def keystone = _pillar['return'][0].values()[0]
    if ( salt.cmdRun(master, target, "docker ps -f name=cvp -q", false, null, false)['return'][0].values()[0] ) {
        salt.cmdRun(master, target, "docker rm -f cvp")
    }
    salt.cmdRun(master, target, "docker run -tid --net=host --name=cvp " +
            "-u root -e OS_USERNAME=${keystone.admin_name} " +
            "-e OS_PASSWORD=${keystone.admin_password} -e OS_TENANT_NAME=${keystone.admin_tenant} " +
            "-e OS_AUTH_URL=http://${keystone.bind.private_address}:${keystone.bind.private_port}/v2.0 " +
            "-e OS_REGION_NAME=${keystone.region} -e OS_ENDPOINT_TYPE=admin  ${dockerImageLink} /bin/bash")
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
 * Create list of nodes in JSON format.
 *
 * @param filter            The Salt's matcher
 * @return                  JSON list of nodes
 */
def getNodeList(master, filter = null) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
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
 * @param dockerImageLink   Docker image link
 * @param pattern           If not false, will run only tests matched the pattern
 * @param output_dir        Directory for results
 * @param confRepository    Git repository with configuration files for Tempest
 * @param confBranch        Git branch which will be used during the checkout
 * @param repository        Git repository with Tempest
 * @param version           Version of Tempest (tag, branch or commit)
 */
def runTempestTests(master, target, dockerImageLink, output_dir, confRepository, confBranch, repository, version, pattern = "false") {
    def salt = new com.mirantis.mk.Salt()
    def output_file = 'docker-tempest.log'
    def results = '/root/qa_results'
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
    salt.cmdRun(master, target, "docker run -i --rm --net=host -e ${env_vars} " +
        "-v ${results}:${dest_folder} ${dockerImageLink} " +
        "/bin/bash -c \"${cmd}\" > ${results}/${output_file}")
    addFiles(master, target, results, output_dir)
}

/**
 * Execute rally tests
 *
 * @param target            Host to run tests
 * @param dockerImageLink   Docker image link
 * @param output_dir        Directory for results
 * @param repository        Git repository with files for Rally
 * @param branch            Git branch which will be used during the checkout
 * @param ext_variables     The list of external variables
 */
def runRallyTests(master, target, dockerImageLink, output_dir, repository, branch, ext_variables = []) {
    def salt = new com.mirantis.mk.Salt()
    def output_file = 'docker-rally.log'
    def results = '/root/qa_results'
    def dest_folder = '/home/rally/qa_results'
    salt.runSaltProcessStep(master, target, 'file.remove', ["${results}"])
    salt.runSaltProcessStep(master, target, 'file.mkdir', ["${results}", "mode=777"])
    def _pillar = salt.getPillar(master, 'I@keystone:server', 'keystone:server')
    def keystone = _pillar['return'][0].values()[0]
    def env_vars = ( ['tempest_version=15.0.0',
                      "OS_USERNAME=${keystone.admin_name}",
                      "OS_PASSWORD=${keystone.admin_password}",
                      "OS_TENANT_NAME=${keystone.admin_tenant}",
                      "OS_AUTH_URL=http://${keystone.bind.private_address}:${keystone.bind.private_port}/v2.0",
                      "OS_REGION_NAME=${keystone.region}",
                      'OS_ENDPOINT_TYPE=admin'] + ext_variables ).join(' -e ')
    def cmd = '/opt/devops-qa-tools/deployment/configure.sh; ' +
        'rally task start combined_scenario.yaml ' +
        '--task-args-file /opt/devops-qa-tools/rally-scenarios/task_arguments.yaml; '
    if (repository != '' ) {
        cmd = "git clone -b ${branch ?: 'master'} ${repository} test_config; " +
            'rally deployment create --file=test_config/rally/existing.json --name=existing; ' +
            'rally deployment config; ' +
            'rally task start test_config/rally/scenario.yaml ' +
            '--task-args-file test_config/rally/task_arguments.yaml; '
    }
    cmd += "rally task export --type junit-xml --to ${dest_folder}/report-rally.xml; " +
        "rally task report --out ${dest_folder}/report-rally.html"
    salt.cmdRun(master, target, "docker run -i --rm --net=host -e ${env_vars} " +
        "-v ${results}:${dest_folder} ${dockerImageLink} " +
        "/bin/bash -c \"${cmd}\" > ${results}/${output_file}")
    addFiles(master, target, results, output_dir)
}

/**
 * Generate test report
 *
 * @param target            Host to run script from
 * @param dockerImageLink   Docker image link
 * @param output_dir        Directory for results
 */
def generateTestReport(master, target, dockerImageLink, output_dir) {
    def report_file = 'jenkins_test_report.html'
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def results = '/root/qa_results'
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
 * Execute SPT tests
 *
 * @param target            Host to run tests
 * @param dockerImageLink   Docker image link
 * @param output_dir        Directory for results
 * @param ext_variables     The list of external variables
 */
def runSptTests(master, target, dockerImageLink, output_dir, ext_variables = []) {
    def salt = new com.mirantis.mk.Salt()
    def results = '/root/qa_results'
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
 * @param tempest_repo         		Url to tempest for cloning. Can be github or internal gerrit. If not specified, tempest will not be configured.
 * @param tempest_endpoint_type         internalURL or adminURL or publicURL
 * @param tempest_version	        Version of tempest to use
 */
def configureContainer(master, target, proxy, testing_tools_repo, tempest_repo,
                       tempest_endpoint_type="internalURL", tempest_version="15.0.0",
                       configure_script="/home/rally/testing-stuff/configure.sh", ext_variables = []) {
    def salt = new com.mirantis.mk.Salt()
    if (testing_tools_repo != "" ) {
        salt.cmdRun(master, target, "docker exec cvp git clone ${testing_tools_repo}")
    }
    salt.cmdRun(master, target, "docker exec -e tempest_version=${tempest_version} -e PROXY=${proxy} " +
                                " -e TEMPEST_ENDPOINT=${tempest_repo} -e TEMPEST_ENDPOINT_TYPE=${tempest_endpoint_type} " +
                                ext_variables.join(' -e ') +
                                " cvp bash -c ${configure_script}")
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
    def log_file = "${output_filename}.log"
    skip_list_cmd = ''
    if (skip_list != '') {
        skip_list_cmd = "--skip-list ${skip_list}"
    }
    salt.cmdRun(master, target, "docker exec cvp rally verify start --pattern ${test_pattern} ${skip_list_cmd} " +
                                "--detailed > ${log_file}", false)
    salt.cmdRun(master, target, "cat ${log_file}")
    salt.cmdRun(master, target, "docker exec cvp rally verify report --type junit-xml --to /home/rally/${xml_file}")
    salt.cmdRun(master, target, "docker cp cvp:/home/rally/${xml_file} ${output_dir}")
    return salt.cmdRun(master, target, "docker exec cvp rally verify list | tail -n 2 | grep -v '+' | awk '{print \$16}'")['return'][0].values()[0].split()[0]
}

/**
 * Run Rally
 *
 * @param target                        Host to run container
 * @param test_pattern                  Test pattern to run
 * @param scenarios_path                Path to Rally scenarios
 * @param output_dir                    Directory on target host for storing results (containers is not a good place)
 */
def runCVPrally(master, target, scenarios_path, output_dir, output_filename="docker-rally") {
    def salt = new com.mirantis.mk.Salt()
    def xml_file = "${output_filename}.xml"
    def log_file = "${output_filename}.log"
    def html_file = "${output_filename}.html"
    salt.cmdRun(master, target, "docker exec cvp rally task start ${scenarios_path} > ${log_file}", false)
    salt.cmdRun(master, target, "cat ${log_file}")
    salt.cmdRun(master, target, "docker exec cvp rally task report --out ${html_file}")
    salt.cmdRun(master, target, "docker exec cvp rally task export --type junit-xml --to ${xml_file}")
    salt.cmdRun(master, target, "docker cp cvp:/home/rally/${xml_file} ${output_dir}")
    salt.cmdRun(master, target, "docker cp cvp:/home/rally/${html_file} ${output_dir}")
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
    def list = salt.runSaltProcessStep(master, "${target}", 'cmd.run', ["ip a | grep global | grep -v brd"])['return'][0]
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
 */
def openstack_cleanup(master, target, script_path="/home/rally/testing-stuff/clean.sh") {
    def salt = new com.mirantis.mk.Salt()
    salt.runSaltProcessStep(master, "${target}", 'cmd.run', ["docker exec cvp bash -c ${script_path}"])
}


/**
 * Cleanup
 *
 * @param target            Host to run commands
 */
def runCleanup(master, target) {
    def salt = new com.mirantis.mk.Salt()
    if ( salt.cmdRun(master, target, "docker ps -f name=qa_tools -q", false, null, false)['return'][0].values()[0] ) {
        salt.cmdRun(master, target, "docker rm -f qa_tools")
    }
    if ( salt.cmdRun(master, target, "docker ps -f name=cvp -q", false, null, false)['return'][0].values()[0] ) {
        salt.cmdRun(master, target, "docker rm -f cvp")
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
