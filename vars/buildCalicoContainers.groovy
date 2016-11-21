def call(body) {
  // evaluate the body block, and collect configuration into the object
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()


  def dockerRepo = config.dockerRepo ?: "artifactory.mcp.mirantis.net:5001"
  def artifactoryUrl = config.artifactoryURL ?: "https://artifactory.mcp.mirantis.net/artifactory/projectcalico"

  def nodeImage = config.nodeImage ?: "calico/node"
  def nodeImageTag = config.nodeImageTag ?: "v1.0.0-beta"
  def nodeName = "${dockerRepo}/${nodeImage}:${nodeImageTag}"

  def ctlImage = config.ctlImage ?: "calico/ctl"
  def ctlImageTag = config.ctlImageTag ?: "v1.0.0-beta"
  def ctlName = "${dockerRepo}/${ctlImage}:${ctlImageTag}"

  // calico/build goes from {artifactoryUrl}/mcp/libcalico/
  def buildImage = config.buildImage ?: "${artifactoryUrl}/mcp/libcalico/lastbuild".toURL().text.trim()
  // calico/felix goes from {artifactoryUrl}/mcp/felix/
  def felixImage = config.felixImage ?: "${artifactoryUrl}/mcp/felix/lastbuild".toURL().text.trim()

  def confdBuildId = config.confdBuildId ?: "${artifactoryUrl}/mcp/confd/lastbuild".toURL().text.trim()
  def confdUrl = config.confdUrl ?: "${artifactoryUrl}/mcp/confd/confd-${confdBuildId}"

  def birdBuildId = config.birdBuildId ?: "${artifactoryUrl}/mcp/calico-bird/lastbuild".toURL().text.trim()
  def birdUrl = config.birdUrl ?: "${artifactoryUrl}/mcp/calico-bird/bird-${birdBuildId}"
  def bird6Url = config.bird6Url ?: "${artifactoryUrl}/mcp/calico-bird/bird6-${birdBuildId}"
  def birdclUrl = config.birdclUrl ?: "${artifactoryUrl}/mcp/calico-bird/birdcl-${birdBuildId}"

  def gitCommit = sh(returnStdout: true, script: "git rev-parse --short HEAD").trim()

  def build = "${config.containersBuildId}-${gitCommit}"

  // return values
  def calicoNodeImageRepo = "${dockerRepo}/${nodeImage}"
  def calicoCtlImageRepo = "${dockerRepo}/${ctlImage}"
  def calicoVersion = "${nodeImageTag}-${build}"
  def ctlContainerName = "${ctlName}-${build}"
  def nodeContainerName = "${nodeName}-${build}"

  // Start build section

  stage ('Build calico/ctl image'){
    sh """
      make calico/ctl \
        CTL_CONTAINER_NAME=${ctlContainerName} \
        PYTHON_BUILD_CONTAINER_NAME=${buildImage} \
        BIRDCL_URL=${birdclUrl}
    """
  }


  stage('Build calico/node'){
    sh """
      make calico/node \
        NODE_CONTAINER_NAME=${nodeContainerName} \
        PYTHON_BUILD_CONTAINER_NAME=${buildImage} \
        FELIX_CONTAINER_NAME=${felixImage} \
        CONFD_URL=${confdUrl} \
        BIRD_URL=${birdUrl} \
        BIRD6_URL=${bird6Url} \
        BIRDCL_URL=${birdclUrl}
    """
  }


  dir("artifacts"){
    // Save the last build ID
    writeFile file: "lastbuild", text: "${build}"
    // Create config yaml for Kargo
    writeFile file: "calico-containers-${build}.yaml",
              text: """\
                calico_node_image_repo: ${calicoNodeImageRepo}
                calicoctl_image_repo: ${calicoCtlImageRepo}
                calico_version: ${calicoVersion}
              """.stripIndent()
  } // dir artifacts

  return [
    CTL_CONTAINER_NAME:"${ctlContainerName}",
    NODE_CONTAINER_NAME:"${nodeContainerName}",
    CALICO_NODE_IMAGE_REPO:"${calicoNodeImageRepo}",
    CALICOCTL_IMAGE_REPO:"${calicoCtlImageRepo}",
    CALICO_VERSION: "${calicoVersion}"
  ]

}
