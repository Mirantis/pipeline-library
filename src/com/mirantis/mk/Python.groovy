package com.mirantis.mk

/**
 *
 * Python functions
 *
 */

/**
 * Install python virtualenv
 *
 * @param path          Path to virtualenv
 * @param python        Version of Python (python/python3)
 * @param reqs          Environment requirements in list format
 * @param reqs_path     Environment requirements path in str format
 */
def setupVirtualenv(path, python = 'python2', reqs=[], reqs_path=null, clean=false) {
    def common = new com.mirantis.mk.Common()

    def virtualenv_cmd = "virtualenv ${path} --python ${python}"

    if (clean) {
        common.infoMsg("Cleaning venv directory " + path)
        sh("rm -rf \"${path}\"")
    }

    common.infoMsg("[Python ${path}] Setup ${python} environment")
    sh(returnStdout: true, script: virtualenv_cmd)
    if (reqs_path==null) {
        def args = ""
        for (req in reqs) {
            args = args + "${req}\n"
        }
        writeFile file: "${path}/requirements.txt", text: args
        reqs_path = "${path}/requirements.txt"
    }
    runVirtualenvCommand(path, "pip install -r ${reqs_path}")
}

/**
 * Run command in specific python virtualenv
 *
 * @param path   Path to virtualenv
 * @param cmd    Command to be executed
 */
def runVirtualenvCommand(path, cmd) {
    def common = new com.mirantis.mk.Common()

    virtualenv_cmd = ". ${path}/bin/activate > /dev/null; ${cmd}"
    common.infoMsg("[Python ${path}] Run command ${cmd}")
    output = sh(
        returnStdout: true,
        script: virtualenv_cmd
    ).trim()
    return output
}


/**
 * Install docutils in isolated environment
 *
 * @param path        Path where virtualenv is created
 */
def setupDocutilsVirtualenv(path) {
    requirements = [
      'docutils',
    ]
    setupVirtualenv(path, 'python2', requirements)
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
    }
    else {
        rawData = sh (
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
 * @param path        Path where virtualenv is created
 */
def setupCookiecutterVirtualenv(path) {
    requirements = [
        'cookiecutter',
        'jinja2==2.8.1',
        'PyYAML==3.12'
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
    configFile = "default_config.yaml"
    configString = "default_context:\n"
    writeFile file: configFile, text: context
    command = ". ${path}/bin/activate; if [ -f ${templatePath}/generate.py ]; then python ${templatePath}/generate.py --config-file ${configFile} --template ${template} --output-dir ${outputDir}; else cookiecutter --config-file ${configFile} --output-dir ${outputDir} --overwrite-if-exists --verbose --no-input ${template}; fi"
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
