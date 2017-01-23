package com.mirantis.mcp

/**
 * https://issues.jenkins-ci.org/browse/JENKINS-26481
 * fix groovy List.collect()
 **/
@NonCPS
def constructString(ArrayList options, String keyOption, String separator = " ") {
    return options.collect { keyOption + it }.join(separator).replaceAll("\n", "")
}

/**
 * Generate current timestamp
 *
 * @param format Defaults to yyyyMMddHHmmss
 */
def getDatetime(format = "yyyyMMddHHmmss") {
    def now = new Date();
    return now.format(format, TimeZone.getTimeZone('UTC'));
}

/**
 * Run tox with or without specified environment
 * @param env String, name of environment
 */
def runTox(String env = null) {
  if (env) {
    sh "tox -v -e ${env}"
  } else {
    sh "tox -v"
  }
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
