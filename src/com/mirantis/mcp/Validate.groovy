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
 */
def runContainerConfiguration(master, dockerImageLink, target, output_dir){
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def output_file = 'docker.log'
    def controller = salt.minionPresent(master, 'I@salt:master', 'ctl01', true, null, true, 200, 1)['return'][0].values()[0]
    def _pillar = salt.cmdRun(master, 'I@salt:master', "reclass-salt -o json -p ${controller} | " +
            "python -c 'import json,sys; print(json.dumps(json.loads(sys.stdin.read())[\"keystone\"][\"server\"]))'")
    def keystone = common.parseJSON(_pillar['return'][0].values()[0])
    salt.cmdRun(master, target, "docker run -tid --net=host --name=qa_tools " +
            "-e tempest_version=15.0.0 -e OS_USERNAME=${keystone.admin_name} " +
            "-e OS_PASSWORD=${keystone.admin_password} -e OS_TENANT_NAME=${keystone.admin_tenant} " +
            "-e OS_AUTH_URL=http://${keystone.bind.private_address}:${keystone.bind.private_port}/v2.0 " +
            "-e OS_REGION_NAME=${keystone.region} -e OS_ENDPOINT_TYPE=admin ${dockerImageLink} /bin/bash")
    salt.cmdRun(master, target, "docker exec qa_tools bash -c /opt/devops-qa-tools/deployment/configure.sh > ${output_file}")
    def file_content = salt.getFileContent(master, target, output_file)
    writeFile file: "${output_dir}${output_file}", text: file_content
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
    def file_content = salt.getFileContent(master, target, output_file)
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
    def file_content = salt.getFileContent(master, target, output_file)
    writeFile file: "${output_dir}${output_file}", text: file_content
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
