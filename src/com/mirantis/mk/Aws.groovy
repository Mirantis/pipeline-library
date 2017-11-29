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

def getEnvVars(credentials, region = 'us-west-2') {
    def common = new com.mirantis.mk.Common()

    def creds
    def username
    def password

    if (credentials.contains(':')) {
        // we have key and secret in string (delimited by :)
        creds = credentials.tokenize(':')
        username = creds[0]
        password = creds[1]
    } else {
        // we have creadentials_id
        creds = common.getCredentials(credentials)
        username = creds.username
        password = creds.password
    }

    return [
        "AWS_ACCESS_KEY_ID=${username}",
        "AWS_SECRET_ACCESS_KEY=${password}",
        "AWS_DEFAULT_REGION=${region}"
    ]
}


/**
 *
 * CloudFormation stacks (cloudformation)
 *
 */

def createStack(venv_path, env_vars, template_file, stack_name, parameters = []) {
    def python = new com.mirantis.mk.Python()

    def cmd = "aws cloudformation create-stack --stack-name ${stack_name} --template-body file://template/${template_file} --capabilities CAPABILITY_NAMED_IAM"

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
        def resources = out_json['Stacks'][0]
        common.prettyPrint(resources)

        return resources
    }
}

def describeStackResources(venv_path, env_vars, stack_name) {
    def python = new com.mirantis.mk.Python()
    def common = new com.mirantis.mk.Common()

    def cmd = "aws cloudformation describe-stack-resources --stack-name ${stack_name}"

    withEnv(env_vars) {
        def out = python.runVirtualenvCommand(venv_path, cmd)
        def out_json = common.parseJSON(out)
        def resources = out_json['StackResources']
        common.prettyPrint(resources)

        return resources
    }
}

def waitForStatus(venv_path, env_vars, stack_name, state, state_failed = ['ROLLBACK_COMPLETE'], max_timeout = 1200, loop_sleep = 30) {
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

                // print stack resources
                aws.describeStackResources(venv_path, env_vars, stack_name)

                // wait for next loop
                sleep(loop_sleep)
            }
        }
    }
}

def getOutputs(venv_path, env_vars, stack_name, key = '') {
    def aws = new com.mirantis.mk.Aws()
    def common = new com.mirantis.mk.Common()
    def output = [:]

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

/**
 *
 * Autoscaling groups (autoscaling)
 *
 */

def describeAutoscalingGroup(venv_path, env_vars, group_name) {
    def python = new com.mirantis.mk.Python()
    def common = new com.mirantis.mk.Common()

    def cmd = "aws autoscaling describe-auto-scaling-groups --auto-scaling-group-name ${group_name}"

    withEnv(env_vars) {
        def out = python.runVirtualenvCommand(venv_path, cmd)
        def out_json = common.parseJSON(out)
        def info = out_json['AutoScalingGroups'][0]
        common.prettyPrint(info)

        return info
    }
}

def updateAutoscalingGroup(venv_path, env_vars, group_name, parameters = []) {
    def python = new com.mirantis.mk.Python()
    def common = new com.mirantis.mk.Common()

    if (parameters == null || parameters.size() == 0) {
        throw new Exception("Missing parameter")
    }

    def cmd = "aws autoscaling update-auto-scaling-group --auto-scaling-group-name ${group_name} " + parameters.join(' ')

    withEnv(env_vars) {
        def out = python.runVirtualenvCommand(venv_path, cmd)
        return out
    }
}

def waitForAutoscalingInstances(venv_path, env_vars, group_name, max_timeout = 600, loop_sleep = 20) {
    def aws = new com.mirantis.mk.Aws()
    def common = new com.mirantis.mk.Common()

    timeout(time: max_timeout, unit: 'SECONDS') {
        withEnv(env_vars) {
            while (true) {
                // get instances in autoscaling group
                def out = aws.describeAutoscalingGroup(venv_path, env_vars, group_name)
                print(common.prettyPrint(out))
                def instances = out['Instances']

                // check all instances are InService
                if (common.countHashMapEquals(instances, 'LifecycleState', 'InService') == out['DesiredCapacity']) {
                    break
                }

                // wait for next loop
                sleep(loop_sleep)
            }
        }
    }
}

/**
 *
 * Load balancers (elb)
 *
 */

def registerIntanceWithLb(venv_path, env_vars, lb, instances = []) {
    def python = new com.mirantis.mk.Python()

    def cmd = "aws elb register-instances-with-load-balancer --load-balancer-name ${lb} --instances " + instances.join(' ')

    withEnv(env_vars) {
        def out = python.runVirtualenvCommand(venv_path, cmd)
        return out
    }
}

def deregisterIntanceWithLb(venv_path, env_vars, lb, instances = []) {
    def python = new com.mirantis.mk.Python()

    def cmd = "aws elb deregister-instances-with-load-balancer --load-balancer-name ${lb} --instances " + instances.join(' ')

    withEnv(env_vars) {
        def out = python.runVirtualenvCommand(venv_path, cmd)
        return out
    }
}
