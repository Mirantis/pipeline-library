package com.mirantis.mk

/**
 *
 * Python functions
 *
 */

/**
 * Install python virtualenv
 *
 * @param path Path to virtualenv
 * @param python Version of Python (python/python3)
 * @param reqs Environment requirements in list format
 * @param reqs_path Environment requirements path in str format
 */
def setupVirtualenv(path, python = 'python2', reqs = [], reqs_path = null, clean = false, useSystemPackages = false) {
    def common = new com.mirantis.mk.Common()

    def offlineDeployment = env.getEnvironment().containsKey("OFFLINE_DEPLOYMENT") && env["OFFLINE_DEPLOYMENT"].toBoolean()
    def virtualenv_cmd = "virtualenv ${path} --python ${python}"
    if (useSystemPackages) {
        virtualenv_cmd += " --system-site-packages"
    }
    if (clean) {
        common.infoMsg("Cleaning venv directory " + path)
        sh("rm -rf \"${path}\"")
    }

    if (offlineDeployment) {
        virtualenv_cmd += " --no-download"
    }
    common.infoMsg("[Python ${path}] Setup ${python} environment")
    sh(returnStdout: true, script: virtualenv_cmd)
    if (!offlineDeployment) {
        try {
            def pipPackage = 'pip'
            if (python == 'python2') {
                pipPackage = "\"pip<=19.3.1\""
                common.infoMsg("Pinning pip package due to end of life of Python2 to ${pipPackage} version.")
            }
            // NOTE(vsaienko): pin setuptools explicitly for latest version that works with python2
            runVirtualenvCommand(path, "pip install -U \"setuptools<45.0.0\" ${pipPackage}")
        } catch (Exception e) {
            common.warningMsg("Setuptools and pip cannot be updated, you might be offline but OFFLINE_DEPLOYMENT global property not initialized!")
        }
    }
    if (reqs_path == null) {
        def args = ""
        for (req in reqs) {
            args = args + "${req}\n"
        }
        writeFile file: "${path}/requirements.txt", text: args
        reqs_path = "${path}/requirements.txt"
    }
    runVirtualenvCommand(path, "pip install -r ${reqs_path}", true)
}

/**
 * Run command in specific python virtualenv
 *
 * @param path   Path to virtualenv
 * @param cmd    Command to be executed
 * @param silent dont print any messages (optional, default false)
 * @param flexAnswer return answer like a dict, with format ['status' : int, 'stderr' : str, 'stdout' : str ]
 */
def runVirtualenvCommand(path, cmd, silent = false, flexAnswer = false) {
    def common = new com.mirantis.mk.Common()
    def res
    def virtualenv_cmd = "set +x; . ${path}/bin/activate; ${cmd}"
    if (!silent) {
        common.infoMsg("[Python ${path}] Run command ${cmd}")
    }
    if (flexAnswer) {
        res = common.shCmdStatus(virtualenv_cmd)
    } else {
        res = sh(
            returnStdout: true,
            script: virtualenv_cmd
        ).trim()
    }
    return res
}

/**
 * Another command runner to control outputs and exit code
 *
 * - always print the executing command to control the pipeline execution
 * - always allows to get the stdout/stderr/status in the result, even with enabled console enabled
 * - throws an exception with stderr content, so it could be read from the job status and processed
 *
 * @param cmd           String, command to be executed
 * @param virtualenv    String, path to Python virtualenv (optional, default: '')
 * @param verbose       Boolean, true: (default) mirror stdout to console and to the result['stdout'] at the same time,
 *                               false: store stdout only to result['stdout']
 * @param check_status  Boolean, true: (default) throw an exception which contains result['stderr'] if exit code is not 0,
 *                               false: only print stderr if not empty, and return the result
 * @return              Map, ['status' : int, 'stderr' : str, 'stdout' : str ]
 */
def runCmd(String cmd, String virtualenv='', Boolean verbose=true, Boolean check_status=true) {
    def common = new com.mirantis.mk.Common()

    def script
    def redirect_output
    def result = [:]
    def stdout_path = sh(script: '#!/bin/bash +x\nmktemp', returnStdout: true).trim()
    def stderr_path = sh(script: '#!/bin/bash +x\nmktemp', returnStdout: true).trim()

    if (verbose) {
        // show stdout to console and store to stdout_path
        redirect_output = " 1> >(tee -a ${stdout_path}) 2>${stderr_path}"
    } else {
        // only store stdout to stdout_path
        redirect_output = " 1>${stdout_path} 2>${stderr_path}"
    }

    if (virtualenv) {
        common.infoMsg("Run shell command in Python virtualenv [${virtualenv}]:\n" + cmd)
        script = """#!/bin/bash +x
            . ${virtualenv}/bin/activate
            ( ${cmd.stripIndent()} ) ${redirect_output}
        """
    } else {
        common.infoMsg('Run shell command:\n' + cmd)
        script = """#!/bin/bash +x
            ( ${cmd.stripIndent()} ) ${redirect_output}
        """
    }

    result['status'] = sh(script: script, returnStatus: true)
    result['stdout'] = readFile(stdout_path)
    result['stderr'] = readFile(stderr_path)
    def cleanup_script = """#!/bin/bash +x
        rm ${stdout_path} || true
        rm ${stderr_path} || true
    """
    sh(script: cleanup_script)

    if (result['status'] != 0 && check_status) {
        def error_message = '\nScript returned exit code: ' + result['status'] + '\n<<<<<< STDERR: >>>>>>\n' + result['stderr']
        common.errorMsg(error_message)
        common.printMsg('', 'reset')
        throw new Exception(error_message)
    }

    if (result['stderr'] && verbose) {
        def warning_message = '\nScript returned exit code: ' + result['status'] + '\n<<<<<< STDERR: >>>>>>\n' + result['stderr']
        common.warningMsg(warning_message)
        common.printMsg('', 'reset')
    }

    return result
}

/**
 * Install docutils in isolated environment
 *
 * @param path Path where virtualenv is created
 */
def setupDocutilsVirtualenv(path, python="python2") {
    requirements = [
        'docutils==0.16',
    ]
    setupVirtualenv(path, python, requirements)
}


@NonCPS
def loadJson(rawData) {
    return new groovy.json.JsonSlurperClassic().parseText(rawData)
}

/**
 * Parse content from markup-text tables to variables
 *
 * @param tableStr String representing the table
 * @param mode Either list (1st row are keys) or item (key, value rows)
 * @param format Format of the table
 */
def parseTextTable(tableStr, type = 'item', format = 'rest', path = none) {
    parserFile = "${env.WORKSPACE}/textTableParser.py"
    parserScript = """import json
import argparse
from docutils.parsers.rst import tableparser
from docutils import statemachine

def parse_item_table(raw_data):
    i = 1
    pretty_raw_data = []
    for datum in raw_data:
        if datum != "":
            if datum[3] != ' ' and i > 4:
                pretty_raw_data.append(raw_data[0])
            if i == 3:
                pretty_raw_data.append(datum.replace('-', '='))
            else:
                pretty_raw_data.append(datum)
            i += 1
    parser = tableparser.GridTableParser()
    block = statemachine.StringList(pretty_raw_data)
    docutils_data = parser.parse(block)
    final_data = {}
    for line in docutils_data[2]:
        key = ' '.join(line[0][3]).strip()
        value = ' '.join(line[1][3]).strip()
        if key != "":
            try:
                value = json.loads(value)
            except:
                pass
            final_data[key] = value
        i+=1
    return final_data

def parse_list_table(raw_data):
    i = 1
    pretty_raw_data = []
    for datum in raw_data:
        if datum != "":
            if datum[3] != ' ' and i > 4:
                pretty_raw_data.append(raw_data[0])
            if i == 3:
                pretty_raw_data.append(datum.replace('-', '='))
            else:
                pretty_raw_data.append(datum)
            i += 1
    parser = tableparser.GridTableParser()
    block = statemachine.StringList(pretty_raw_data)
    docutils_data = parser.parse(block)
    final_data = []
    keys = []
    for line in docutils_data[1]:
        for item in line:
             keys.append(' '.join(item[3]).strip())
    for line in docutils_data[2]:
        final_line = {}
        key = ' '.join(line[0][3]).strip()
        value = ' '.join(line[1][3]).strip()
        if key != "":
            try:
                value = json.loads(value)
            except:
                pass
            final_data[key] = value
        i+=1
    return final_data

def parse_list_table(raw_data):
    i = 1
    pretty_raw_data = []
    for datum in raw_data:
        if datum != "":
            if datum[3] != ' ' and i > 4:
                pretty_raw_data.append(raw_data[0])
            if i == 3:
                pretty_raw_data.append(datum.replace('-', '='))
            else:
                pretty_raw_data.append(datum)
            i += 1
    parser = tableparser.GridTableParser()
    block = statemachine.StringList(pretty_raw_data)
    docutils_data = parser.parse(block)
    final_data = []
    keys = []
    for line in docutils_data[1]:
        for item in line:
             keys.append(' '.join(item[3]).strip())
    for line in docutils_data[2]:
        final_line = {}
        i = 0
        for item in line:
            value = ' '.join(item[3]).strip()
            try:
                value = json.loads(value)
            except:
                pass
            final_line[keys[i]] = value
            i += 1
        final_data.append(final_line)
    return final_data

def read_table_file(file):
    table_file = open(file, 'r')
    raw_data = table_file.read().split('\\n')
    table_file.close()
    return raw_data

parser = argparse.ArgumentParser()
parser.add_argument('-f','--file', help='File with table data', required=True)
parser.add_argument('-t','--type', help='Type of table (list/item)', required=True)
args = vars(parser.parse_args())

raw_data = read_table_file(args['file'])

if args['type'] == 'list':
  final_data = parse_list_table(raw_data)
else:
  final_data = parse_item_table(raw_data)

print json.dumps(final_data)
"""
    writeFile file: parserFile, text: parserScript
    tableFile = "${env.WORKSPACE}/prettytable.txt"
    writeFile file: tableFile, text: tableStr

    cmd = "python ${parserFile} --file '${tableFile}' --type ${type}"
    if (path) {
        rawData = runVirtualenvCommand(path, cmd)
    } else {
        rawData = sh(
            script: cmd,
            returnStdout: true
        ).trim()
    }
    data = loadJson(rawData)
    echo("[Parsed table] ${data}")
    return data
}

/**
 * Install cookiecutter in isolated environment
 *
 * @param path Path where virtualenv is created
 */
def setupCookiecutterVirtualenv(path) {
    requirements = [
        'cookiecutter',
        'jinja2==2.8.1',
        'PyYAML==3.12',
        'python-gnupg==0.4.3'
    ]
    setupVirtualenv(path, 'python2', requirements)
}

/**
 * Generate the cookiecutter templates with given context
 *
 * @param template template
 * @param context template context
 * @param path Path where virtualenv is created (optional)
 * @param templatePath path to cookiecutter template repo (optional)
 */
def buildCookiecutterTemplate(template, context, outputDir = '.', path = null, templatePath = ".") {
    def common = new com.mirantis.mk.Common()
    configFile = "default_config.yaml"
    writeFile file: configFile, text: context
    common.warningMsg('Old Cookiecutter env detected!')
    command = ". ${path}/bin/activate; if [ -f ${templatePath}/generate.py ]; then python ${templatePath}/generate.py --config-file ${configFile} --template ${template} --output-dir ${outputDir}; else cookiecutter --config-file ${configFile} --output-dir ${outputDir} --overwrite-if-exists --verbose --no-input ${template}; fi"
    output = sh(returnStdout: true, script: command)
    common.infoMsg('[Cookiecutter build] Result:' + output)
}

/**
 *
 * @param context - context template
 * @param contextName - context template name
 * @param saltMasterName - hostname of Salt Master node
 * @param virtualenv - pyvenv with CC and dep's
 * @param templateEnvDir - root of CookieCutter
 * @return
 */
def generateModel(context, contextName, saltMasterName, virtualenv, modelEnv, templateEnvDir, multiModels = true) {
    def common = new com.mirantis.mk.Common()
    def generatedModel = multiModels ? "${modelEnv}/${contextName}" : modelEnv
    def templateContext = readYaml text: context
    def clusterDomain = templateContext.default_context.cluster_domain
    def clusterName = templateContext.default_context.cluster_name
    def outputDestination = "${generatedModel}/classes/cluster/${clusterName}"
    def templateBaseDir = templateEnvDir
    def templateDir = "${templateEnvDir}/dir"
    def templateOutputDir = templateBaseDir
    dir(templateEnvDir) {
        if (fileExists(new File(templateEnvDir, 'tox.ini').toString())) {
            def tempContextFile = new File(templateEnvDir, 'tempContext.yaml').toString()
            writeFile file: tempContextFile, text: context
            common.warningMsg('Generating models using context:\n')
            print(context)
            withEnv(["CONFIG_FILE=$tempContextFile",
                     "OUTPUT_DIR=${modelEnv}",
            ]) {
                print('[Cookiecutter build] Result:\n' +
                    sh(returnStdout: true, script: 'tox -ve generate_auto'))
            }
        } else {
            common.warningMsg("Old format: Generating model from context ${contextName}")
            def productList = ["infra", "cicd", "kdt", "opencontrail", "kubernetes", "openstack", "oss", "stacklight", "ceph"]
            for (product in productList) {
                // get templateOutputDir and productDir
                templateOutputDir = "${templateEnvDir}/output/${product}"
                productDir = product
                templateDir = "${templateEnvDir}/cluster_product/${productDir}"
                // Bw for 2018.8.1 and older releases
                if (product.startsWith("stacklight") && (!fileExists(templateDir))) {
                    common.warningMsg("Old release detected! productDir => 'stacklight2' ")
                    productDir = "stacklight2"
                    templateDir = "${templateEnvDir}/cluster_product/${productDir}"
                }
                // generate infra unless its explicitly disabled
                if ((product == "infra" && templateContext.default_context.get("infra_enabled", "True").toBoolean())
                    || (templateContext.default_context.get(product + "_enabled", "False").toBoolean())) {

                    common.infoMsg("Generating product " + product + " from " + templateDir + " to " + templateOutputDir)

                    sh "rm -rf ${templateOutputDir} || true"
                    sh "mkdir -p ${templateOutputDir}"
                    sh "mkdir -p ${outputDestination}"

                    buildCookiecutterTemplate(templateDir, context, templateOutputDir, virtualenv, templateBaseDir)
                    sh "mv -v ${templateOutputDir}/${clusterName}/* ${outputDestination}"
                } else {
                    common.warningMsg("Product " + product + " is disabled")
                }
            }

            def localRepositories = templateContext.default_context.local_repositories
            localRepositories = localRepositories ? localRepositories.toBoolean() : false
            def offlineDeployment = templateContext.default_context.offline_deployment
            offlineDeployment = offlineDeployment ? offlineDeployment.toBoolean() : false
            if (localRepositories && !offlineDeployment) {
                def mcpVersion = templateContext.default_context.mcp_version
                def aptlyModelUrl = templateContext.default_context.local_model_url
                def ssh = new com.mirantis.mk.Ssh()
                dir(path: modelEnv) {
                    ssh.agentSh "git submodule add \"${aptlyModelUrl}\" \"classes/cluster/${clusterName}/cicd/aptly\""
                    if (!(mcpVersion in ["nightly", "testing", "stable"])) {
                        ssh.agentSh "cd \"classes/cluster/${clusterName}/cicd/aptly\";git fetch --tags;git checkout ${mcpVersion}"
                    }
                }
            }

            def nodeFile = "${generatedModel}/nodes/${saltMasterName}.${clusterDomain}.yml"
            def nodeString = """classes:
- cluster.${clusterName}.infra.config
parameters:
  _param:
    linux_system_codename: xenial
    reclass_data_revision: master
  linux:
    system:
      name: ${saltMasterName}
      domain: ${clusterDomain}
    """
            sh "mkdir -p ${generatedModel}/nodes/"
            writeFile(file: nodeFile, text: nodeString)
        }
    }
}

/**
 * Install jinja rendering in isolated environment
 *
 * @param path Path where virtualenv is created
 */
def setupJinjaVirtualenv(path) {
    requirements = [
        'jinja2-cli==0.7.0',
        'pyyaml==5.3',
    ]
    setupVirtualenv(path, 'python2', requirements)
}

/**
 * Generate the Jinja templates with given context
 *
 * @param path Path where virtualenv is created
 */
def jinjaBuildTemplate(template, context, path = none) {
    contextFile = "jinja_context.yml"
    contextString = ""
    for (parameter in context) {
        contextString = "${contextString}${parameter.key}: ${parameter.value}\n"
    }
    writeFile file: contextFile, text: contextString
    cmd = "jinja2 ${template} ${contextFile} --format=yaml"
    data = sh(returnStdout: true, script: cmd)
    echo(data)
    return data
}

/**
 * Install salt-pepper in isolated environment
 *
 * @param path Path where virtualenv is created
 * @param url SALT_MASTER_URL
 * @param credentialsId Credentials to salt api
 */
def setupPepperVirtualenv(path, url, credentialsId, python_version = 'python2') {
    def common = new com.mirantis.mk.Common()

    // virtualenv setup
    // pin pepper till https://mirantis.jira.com/browse/PROD-18188 is fixed
    requirements = ['salt-pepper>=0.5.2,<0.5.4']
    setupVirtualenv(path, python_version, requirements, null, true, true)

    // pepperrc creation
    rcFile = "${path}/pepperrc"
    creds = common.getPasswordCredentials(credentialsId)
    rc = """\
[main]
SALTAPI_EAUTH=pam
SALTAPI_URL=${url}
SALTAPI_USER=${creds.username}
SALTAPI_PASS=${creds.password.toString()}
"""
    writeFile file: rcFile, text: rc
    return rcFile
}

/**
 * Install devops in isolated environment
 *
 * @param path Path where virtualenv is created
 * @param clean Define to true is the venv have to cleaned up before install a new one
 */
def setupDevOpsVenv(venv, clean = false) {
    requirements = ['git+https://github.com/openstack/fuel-devops.git']
    setupVirtualenv(venv, 'python2', requirements, null, false, clean)
}
