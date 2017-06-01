package com.mirantis.mk



/**
 *
 * AWS function functions
 *
 */

def setupVirtualEnv(venv_path = 'aws_venv') {
    def python = new com.mirantis.mk.Python()

    def requirements = [
        'awscli'
    ]

    python.setupVirtualenv(venv_path, 'python2', requirements)
}

def getEnvVars(credentials_id, region = 'us-west-2') {
    def common = new com.mirantis.mk.Common()

    def creds = common.getCredentials(credentials_id)

    return [
        "AWS_ACCESS_KEY_ID=${creds.username}",
        "AWS_SECRET_ACCESS_KEY=${creds.password}",
        "AWS_DEFAULT_REGION=${region}"
    ]
}

def createStack(venv_path, env_vars, template_file, stack_name, parameters = []) {
    def python = new com.mirantis.mk.Python()

    def cmd = "aws cloudformation create-stack --stack-name ${stack_name} --template-body file://template/${template_file}"

    if (parameters != null && parameters.size() > 0) {
        cmd = "${cmd} --parameters"

        for (int i=0; i<parameters.size(); i++) {
           cmd = "${cmd} ${parameters[i]}"
        }
    }

    withEnv(env_vars) {
        def out = python.runVirtualenvCommand(venv_path, cmd)
    }
}

def deleteStack(venv_path, env_vars, stack_name) {
    def python = new com.mirantis.mk.Python()

    def cmd = "aws cloudformation delete-stack --stack-name ${stack_name}"

    withEnv(env_vars) {
        def out = python.runVirtualenvCommand(venv_path, cmd)
    }
}

def describeStack(venv_path, env_vars, stack_name) {
    def python = new com.mirantis.mk.Python()
    def common = new com.mirantis.mk.Common()

    def cmd = "aws cloudformation describe-stacks --stack-name ${stack_name}"

    withEnv(env_vars) {
        def out = python.runVirtualenvCommand(venv_path, cmd)
        def out_json = common.parseJSON(out)
        def stack_info = out_json['Stacks'][0]
        common.prettyPrint(stack_info)

        return stack_info
    }
}

def waitForStatus(venv_path, env_vars, stack_name, state, state_failed = [], max_timeout = 600, loop_sleep = 30) {
    def aws = new com.mirantis.mk.Aws()
    def common = new com.mirantis.mk.Common()
    def python = new com.mirantis.mk.Python()

    timeout(time: max_timeout, unit: 'SECONDS') {
        withEnv(env_vars) {
            while (true) {
                // get stack state
                def stack_info = aws.describeStack(venv_path, env_vars, stack_name)
                common.infoMsg('Stack status is ' + stack_info['StackStatus'])

                // check for desired state
                if (stack_info['StackStatus'] == state) {
                    common.successMsg("Stack ${stack_name} in in state ${state}")
                    common.prettyPrint(stack_info)
                    break
                }

                // check for failed state
                if (state_failed.contains(stack_info['StackStatus'])) {
                    throw new Exception("Stack ${stack_name} in in failed state")
                }

                // wait for next loop
                sleep(loop_sleep)
            }
        }
    }
}

def getOutputs(venv_path, env_vars, stack_name, key = '') {
    def aws = new com.mirantis.mk.Aws()
    def common = new com.mirantis.mk.Common()
    def output = {}

    def stack_info = aws.describeStack(venv_path, env_vars, stack_name)
    common.prettyPrint(stack_info)

    for (int i=0; i<stack_info['Outputs'].size(); i++) {
        output[stack_info['Outputs'][i]['OutputKey']] = stack_info['Outputs'][i]['OutputValue']
    }

    if (key != null && key != '') {
        return output[key]
    } else {
        return output
    }
}
