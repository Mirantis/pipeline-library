package com.mirantis.mcp

@Grab(group='org.yaml', module='snakeyaml', version='1.17')
import org.yaml.snakeyaml.Yaml

/**
 * https://issues.jenkins-ci.org/browse/JENKINS-26481
 * fix groovy List.collect()
 **/
@NonCPS
def constructString(ArrayList options, String keyOption, String separator = ' ') {
    return options.collect { keyOption + it }.join(separator).replaceAll('\n', '')
}

/**
 * Generate current timestamp
 *
 * @param format Defaults to yyyyMMddHHmmss
 */
def getDatetime(format = "yyyyMMddHHmmss") {
    def now = new Date()
    return now.format(format, TimeZone.getTimeZone('UTC'))
}

/**
 * Run tox with or without specified environment
 * @param env String, name of environment
 */
def runTox(String env = null) {
  if (env) {
    sh "tox -v -e ${env}"
  } else {
    sh 'tox -v'
  }
}

/**
 * Convert YAML document to Map object
 * @param data YAML string
 */
@NonCPS
def loadYAML(String data) {
  def yaml = new Yaml()
  return yaml.load(data)
}

/**
 * Convert Map object to YAML string
 * @param map Map object
 */
@NonCPS
def dumpYAML(Map map) {
  def yaml = new Yaml()
  return yaml.dump(map)
}

/**
 * Render jinja template
 * @param templateVars String, A dict, a dict subclass, json or some keyword arguments
 * @param templateFile String, jinja template file path
 * @param resultFile String, result/generate file path
 *
 * Usage example:
 *
 * def common = new com.mirantis.mcp.Common()
 * common.renderJinjaTemplate(
 *     "${NODE_JSON}",
 *     "${WORKSPACE}/inventory/inventory.cfg",
 *     "${WORKSPACE}/inventory/inventory.cfg")
 * where NODE_JSON= data in json format
 */
def renderJinjaTemplate(String templateVars, String templateFile, String resultFile) {

  sh """
    python -c "
import sys
import jinja2
from jinja2 import Template

# Useful for very coarse version differentiation.
PY2 = sys.version_info[0] == 2
PY3 = sys.version_info[0] == 3
PY34 = sys.version_info[0:2] >= (3, 4)

if PY3:
    string_types = str,
else:
    string_types = basestring


def to_bool(a):
    ''' return a bool for the arg '''
    if a is None or type(a) == bool:
        return a
    if isinstance(a, string_types):
        a = a.lower()
    if a in ['yes', 'on', '1', 'true', 1]:
        return True
    else:
        return False


def generate(templateVars, templateFile, resultFile):
    templateLoader = jinja2.FileSystemLoader(searchpath='/')
    templateEnv = jinja2.Environment(loader=templateLoader)
    templateEnv.filters['bool'] = to_bool
    template = templateEnv.get_template(templateFile)
    outputText = template.render(templateVars)
    Template(outputText).stream().dump(resultFile)

generate(${templateVars}, '${templateFile}', '${resultFile}')
"
  cat ${resultFile}
  """
}

/**
 * Run function on k8s cluster
 *
 * @param config LinkedHashMap
 *        config includes next parameters:
 *          - label, pod label
 *          - function, code that should be run on k8s cluster
 *          - jnlpImg, jnlp slave image
 *          - slaveImg, slave image
 *
 * Usage example:
 *
 * def runFunc = new com.mirantis.mcp.Common()
 * runFunc.runOnKubernetes ([
 *   function : this.&buildCalicoContainers,
 *   jnlpImg: 'docker-prod-virtual.docker.mirantis.net/mirantis/jenkins-slave-images/jnlp-slave:latest',
 *   slaveImg : 'sandbox-docker-dev-local.docker.mirantis.net/skulanov/jenkins-slave-images/calico-slave:1'
 * ])
 * // promotion example. In case of promotion we need only jnlp container
 * def runFunc = new com.mirantis.mcp.Common()
 * runFunc.runOnKubernetes ([
 *   jnlpImg: 'docker-prod-virtual.docker.mirantis.net/mirantis/jenkins-slave-images/jnlp-slave:latest',
 *   function : this.&promote_artifacts
 * ])
 */
def runOnKubernetes(LinkedHashMap config) {


  def jenkinsSlaveImg = config.get('slaveImg', 'none')
  def jnlpSlaveImg = config.get('jnlpImg', 'none')
  def lbl = config.get('label', "buildpod.${env.JOB_NAME}.${env.BUILD_NUMBER}".replace('-', '_').replace('/', '_'))
  def toRun = config.get('function', 'none')

  if (jnlpSlaveImg == 'none') {
    error('jnlp Slave image MUST be defined')
  }

  if (toRun == 'none'){
    error('Code that should be run on k8s MUST be passed along with function parameter')
  }

  if (jenkinsSlaveImg == 'none'){
    // we are running jnlp container only, since no jenkinsSlaveImg is specified, so
    // we are in promotion mode
    podTemplate(label: lbl,
      containers: [
          containerTemplate(
              name: 'jnlp',
              image: jnlpSlaveImg,
              args: '${computer.jnlpmac} ${computer.name}'
          )
      ],
      ) {
      node(lbl){
        container('jnlp') {
          toRun()
        }
      }
    }

  } else {
    podTemplate(label: lbl,
      containers: [
          containerTemplate(
              name: 'jnlp',
              image: jnlpSlaveImg,
              args: '${computer.jnlpmac} ${computer.name}'
          ),
          containerTemplate(
              name: 'k8s-slave',
              image: jenkinsSlaveImg,
              alwaysPullImage: false,
              ttyEnabled: true,
              privileged: true
          )
      ],
      ) {
      node(lbl){
        container('k8s-slave') {
          return toRun()
        }
      }
    }
  } //else

}
