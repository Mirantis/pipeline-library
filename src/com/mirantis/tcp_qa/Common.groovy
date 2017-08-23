package com.mirantis.tcp_qa

/**
 * Get latest artifacts
 * @param imageRepoName is the repo name where image is located
 * @param imageTagName is the name of the image tag to be used
 */

def getLatestArtifacts(imageRepoName, imageTagName) {
    def imageRepo = env.getAt(imageRepoName)
    def imageTag = env.getAt(imageTagName)
    if ( imageTag != null && (! imageTag || imageTag.equals('latest')) ) {
        if ( imageRepo ) {
            def registry = imageRepo.replaceAll(/\/.*/, '')
            def image = imageRepo.minus(registry + '/')
            def hyperkubeImageTag = latestImageTagLookup(registry, image)
            return "${imageTagName}=${hyperkubeImageTag}"
        } else {
            echo "${imageRepoName} variable isn't set, can't inspect 'latest' image!"
            return null
        }
    }
}

def jsonParse(def json) {
    new groovy.json.JsonSlurperClassic().parseText(json)
}

/**
 * Get digest metadata
 * @param tag is the image tag to be used
 * @param registry is the url of registry
 * @param image is the image which info is looked for
 */

def get_digest(def tag, def registry, def image) {
    def digest_link = sprintf('https://%1$s/v2/%2$s/manifests/%3$s', [registry, image, tag])
    def digest_url = new URL(digest_link)
    def connection = digest_url.openConnection()
    connection.setRequestProperty('Accept', 'application/vnd.docker.distribution.manifest.v2+json')
    def digest = connection.getHeaderField("Docker-Content-Digest")
    return digest
}

/**
 * Get latest tag metadata
 * @param registry is the url of registry
 * @param image is the image which tags are looked for
 */

def latestImageTagLookup(registry, image) {
    def tags_link = sprintf('https://%1$s/v2/%2$s/tags/list', [registry, image])
    def tags_url = new URL(tags_link)
    def tags = jsonParse(tags_url.getText())['tags']
    def latest_digest = get_digest('latest', registry, image)
    def same_digest_tags = []

    for (tag in tags) {
        if (tag == 'latest') {
            continue
        }
        if (get_digest(tag, registry, image) == latest_digest) {
            same_digest_tags<< tag
        }
    }

    return same_digest_tags[0] ?: 'latest'
}


/**
 * Fetch custom refs
 * @param gerritUrl is url of gerrit
 * @param project is the name of project in gerrit
 * @param targetDir is dir where to fetch changes
 * @param refs is refs that need to be fetched
 */

def getCustomRefs(gerritUrl, project, targetDir, refs) {
    def remote = "${gerritUrl}/${project}"
    dir(targetDir) {
        for(int i=0; i<refs.size(); i++) {
            sh "git fetch ${remote} ${refs[i]} && git cherry-pick FETCH_HEAD"
        }
    }
}

/**
 * Set downstream k8s artifacts
 * @param jobSetParameters are current job parameters that can be extended with kubernetes tag
 */

def set_downstream_k8s_artifacts(jobSetParameters) {
    def k8sTag = getLatestArtifacts('HYPERKUBE_IMAGE_REPO', 'HYPERKUBE_IMAGE_TAG')
    if (k8sTag) {
        jobSetParameters.add(k8sTag)
    }
    return jobSetParameters
}

/**
 * Upload tests results to TestRail
 *
 * @param config LinkedHashMap
 *        config includes next parameters:
 *          - junitXml String, path to XML file with tests results
 *          - testPlanName String, name of test plan in TestRail
 *          - testSuiteName String, name of test suite in TestRail
 *          - testrailMilestone String, milestone name in TestRail
 *          - tesPlanDesc String, description of test plan in TestRail (optional)
 *          - jobURL String, URL of job build with tests (optional)
 *          - testrailURL String, TestRail URL (optional)
 *          - testrailProject String, project name in TestRail (optional)
 *
 *
 * Usage example:
 *
 * uploadResultsTestRail([
 *   junitXml: './nosetests.xml',
 *   testPlanName: 'MCP test plan #1',
 *   testSuiteName: 'Calico component tests',
 *   jobURL: 'jenkins.example.com/job/tests.mcp/1',
 * ])
 *
 */
def uploadResultsTestRail(config) {
  def venvPath = 'testrail-venv'
  // TODO: install 'testrail_reporter' pypi when new version with eee508d commit is released
  def testrailReporterPackage = 'git+git://github.com/gdyuldin/testrail_reporter.git'
  def testrailReporterVersion = 'eee508d'

  def requiredArgs = ['junitXml', 'testPlanName', 'testSuiteName', 'testrailMilestone']
  def missingArgs = []
  for (i in requiredArgs) { if (!config.containsKey(i)) { missingArgs << i }}
  if (missingArgs) { println "Required arguments are missing for '${funcName}': ${missingArgs.join(', ')}" }

  def junitXml = config.get('junitXml')
  def testPlanName = config.get('testPlanName')
  def testSuiteName = config.get('testSuiteName')
  def testrailMilestone = config.get('testrailMilestone')
  def testrailURL = config.get('testrailURL', 'https://mirantis.testrail.com')
  def testrailProject = config.get('testrailProject', 'Mirantis Cloud Platform')
  def tesPlanDesc = config.get('tesPlanDesc')
  def jobURL = config.get('jobURL')

  def reporterOptions = [
    "--verbose",
    "--testrail-run-update",
    "--testrail-url '${testrailURL}'",
    "--testrail-user \"\${TESTRAIL_USER}\"",
    "--testrail-password \"\${TESTRAIL_PASSWORD}\"",
    "--testrail-project '${testrailProject}'",
    "--testrail-plan-name '${testPlanName}'",
    "--testrail-milestone '${testrailMilestone}'",
    "--testrail-suite '${testSuiteName}'",
    "--xunit-name-template '{methodname}'",
    "--testrail-name-template '{custom_test_group}'",
  ]

  if (tesPlanDesc) { reporterOptions << "--env-description '${tesPlanDesc}'" }
  if (jobURL) { reporterOptions << "--test-results-link '${jobURL}'" }

  // Install testrail reporter
  sh """
    virtualenv ${venvPath}
    . ${venvPath}/bin/activate
    pip install --upgrade ${testrailReporterPackage}@${testrailReporterVersion}
  """

  def script = """
    . ${venvPath}/bin/activate
    report ${reporterOptions.join(' ')} ${junitXml}
  """

  def testrail_cred_id = params.TESTRAIL_CRED ?: 'testrail'

  withCredentials([
             [$class          : 'UsernamePasswordMultiBinding',
             credentialsId   : testrail_cred_id,
             passwordVariable: 'TESTRAIL_PASSWORD',
             usernameVariable: 'TESTRAIL_USER']
  ]) {
    return sh(script: script, returnStdout: true).trim().split().last()
  }
}
