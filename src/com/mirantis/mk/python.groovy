package com.mirantis.mk

/**
 *
 * Python functions
 *
 */

/**
 * Install python virtualenv
 *
 * @param path     Path to virtualenv
 * @param python   Version of Python (python/python3)
 * @param reqs     Environment requirements in list format    
 */
def setupVirtualenv(path, python = 'python2', reqs = []) {
    virtualenv_cmd = "virtualenv ${path} --python ${python}"

    echo("[Python ${path}] Setup ${python} environment")
    sh(returnStdout: true, script: virtualenv_cmd)
    args = ""
    for (req in reqs) {
        args = args + "${req}\n"
    }
    writeFile file: "${path}/requirements.txt", text: args
    runVirtualenvCommand(path, "pip install -r ${path}/requirements.txt")
}

/**
 * Run command in specific python virtualenv
 *
 * @param path   Path to virtualenv
 * @param cmd    Command to be executed
 */
def runVirtualenvCommand(path, cmd) {
    virtualenv_cmd = ". ${path}/bin/activate; ${cmd}"
    echo("[Python ${path}] Run command ${cmd}")
    output = sh(
        returnStdout: true,
        script: virtualenv_cmd
    ).trim()
    return output
}

@NonCPS
def loadJson(rawData) {
    return new groovy.json.JsonSlurperClassic().parseText(rawData)
}

/**
 * Parse content from markup-text tables to variables
 *
 * @param tableStr   String representing the table
 * @param mode       Either list (1st row are keys) or item (key, value rows)
 * @param format     Format of the table
 */
def parseTextTable(tableStr, type = 'item', format = 'rest') {
    parserScript = "${env.WORKSPACE}/scripts/parse_text_table.py"
    tableFile = "${env.WORKSPACE}/prettytable.txt"
    writeFile file: tableFile, text: tableStr
    rawData = sh (
        script: "python ${parserScript} --file '${tableFile}' --type ${type}",
        returnStdout: true
    ).trim()
    data = loadJson(rawData)
    echo("[Parsed table] ${data}")
    return data
}

/**
 * Install cookiecutter in isolated environment
 *
 * @param path        Path where virtualenv is created
 */
def setupCookiecutterVirtualenv(path) {
    requirements = [
        'cookiecutter',
    ]
    setupVirtualenv(path, 'python2', requirements)
}

/**
 * Generate the cookiecutter templates with given context
 *
 * @param path        Path where virtualenv is created
 */
def buildCookiecutterTemplate (template, context, path = none) {
    contextFile = "default_context.json"
    contextString = "parameters:\n"
    for (parameter in context) {
      contextString = "${contextString}  ${parameter.key}: ${parameter.value}\n"
    }
    writeFile file: contextFile, text: contextString
    command = ". ./${work_dir}/bin/activate; cookiecutter --config-file ${cookiecutter_context_file} --overwrite-if-exists --verbose --no-input ${template_dir}"
    output = sh (returnStdout: true, script: command)
    echo("[Cookiecutter build] Output: ${output}")
}

/**
 * Install jinja rendering in isolated environment
 *
 * @param path        Path where virtualenv is created
 */
def setupJinjaVirtualenv(path) {
    requirements = [
      'jinja2-cli',
      'pyyaml',
    ]
    setupVirtualenv(path, 'python2', requirements)
}

/**
 * Generate the Jinja templates with given context
 *
 * @param path        Path where virtualenv is created
 */
def jinjaBuildTemplate (template, context, path = none) {
    contextFile = "jinja_context.yml"
    contextString = ""
    for (parameter in context) {
        contextString = "${contextString}${parameter.key}: ${parameter.value}\n"
    }
    writeFile file: contextFile, text: contextString
    cmd = "jinja2 ${template} ${contextFile} --format=yaml"
    data = sh (returnStdout: true, script: cmd)
    echo(data)
    return data
}
