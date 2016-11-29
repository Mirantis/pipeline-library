def call(body) {
  // evaluate the body block, and collect configuration into the object
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()


  def dockerRepo = config.dockerRepo ?: "artifactory.mcp.mirantis.net:5007"
  def projectNamespace = "mirantis/projectcalico"

  def git = new com.mirantis.mcp.Git()
  def common = new com.mirantis.mcp.Common()
  def imgTag = config.imageTag ?: git.getGitDescribe(true) + "-" + common.getDatetime()

  def nodeImage = config.nodeImage ?: "${dockerRepo}/${projectNamespace}/calico/node"
  def nodeName = "${nodeImage}:${imgTag}"

  def ctlImage = config.ctlImage ?: "${dockerRepo}/${projectNamespace}/calico/ctl"
  def ctlName = "${ctlImage}:${imgTag}"

  // FIXME(skulanov): new schema should be used after binary promotion
  //  // calico/build goes from libcalico
  // def buildImage = config.buildImage ?: "${dockerRepo}/mirantis/projectcalico/calico/build:latest"
  // calico/felix felix
  // def felixImage = config.felixImage ?: "${dockerRepo}/mirantis/projectcalico/calico/felix:latest"
  // def artifactoryUrl = config.artifactoryURL ?: "https://artifactory.mcp.mirantis.net/artifactory/binary-prod-virtual"
  // def confdBuildId = config.confdBuildId ?: "${artifactoryUrl}/${projectNamespace}/confd/latest".toURL().text.trim()
  // def confdUrl = config.confdUrl ?: "${artifactoryUrl}/${projectNamespace}/confd/confd-${confdBuildId}"

  // def birdBuildId = config.birdBuildId ?: "${artifactoryUrl}/${projectNamespace}/bird/latest".toURL().text.trim()
  // def birdUrl = config.birdUrl ?: "${artifactoryUrl}/${projectNamespace}/bird/bird-${birdBuildId}"
  // def bird6Url = config.bird6Url ?: "${artifactoryUrl}/${projectNamespace}/bird/bird6-${birdBuildId}"
  // def birdclUrl = config.birdclUrl ?: "${artifactoryUrl}/${projectNamespace}/bird/birdcl-${birdBuildId}"
  def artifactoryUrl = config.artifactoryURL ?: "https://artifactory.mcp.mirantis.net/artifactory/projectcalico"

  def buildImage = config.buildImage ?: "${artifactoryUrl}/mcp/libcalico/lastbuild".toURL().text.trim()
  def felixImage = config.felixImage ?: "${artifactoryUrl}/mcp/felix/lastbuild".toURL().text.trim()

  def artifactoryBirdUrl = "https://artifactory.mcp.mirantis.net/artifactory/binary-prod-local"

  def confdBuildId = config.confdBuildId ?: "${artifactoryBirdUrl}/${projectNamespace}/confd/latest".toURL().text.trim()
  def confdUrl = config.confdUrl ?: "${artifactoryBirdUrl}/${projectNamespace}/confd/confd-${confdBuildId}"

  def birdBuildId = config.birdBuildId ?: "${artifactoryBirdUrl}/${projectNamespace}/bird/latest".toURL().text.trim()
  def birdUrl = config.birdUrl ?: "${artifactoryBirdUrl}/${projectNamespace}/bird/bird-${birdBuildId}"
  def bird6Url = config.bird6Url ?: "${artifactoryBirdUrl}/${projectNamespace}/bird/bird6-${birdBuildId}"
  def birdclUrl = config.birdclUrl ?: "${artifactoryBirdUrl}/${projectNamespace}/bird/birdcl-${birdBuildId}"

  // add LABELs to dockerfiles
  def docker = new com.mirantis.mcp.Docker()
  docker.setDockerfileLabels("./calicoctl/Dockerfile.calicoctl",
                            ["docker.imgTag=${imgTag}",
                             "calico.buildImage=${buildImage}",
                             "calico.birdclUrl=${birdclUrl}"])

  docker.setDockerfileLabels("./calico_node/Dockerfile",
                            ["docker.imgTag=${imgTag}",
                             "calico.buildImage=${buildImage}",
                             "calico.felixImage=${felixImage}",
                             "calico.confdUrl=${confdUrl}",
                             "calico.birdUrl=${birdUrl}",
                             "calico.bird6Url=${bird6Url}",
                             "calico.birdclUrl=${birdclUrl}"])

  // Start build section
  stage ('Build calico/ctl image'){
    sh """
      make calico/ctl \
        CTL_CONTAINER_NAME=${ctlName} \
        PYTHON_BUILD_CONTAINER_NAME=${buildImage} \
        BIRDCL_URL=${birdclUrl}
    """
  }


  stage('Build calico/node'){
    sh """
      make calico/node \
        NODE_CONTAINER_NAME=${nodeName} \
        PYTHON_BUILD_CONTAINER_NAME=${buildImage} \
        FELIX_CONTAINER_NAME=${felixImage} \
        CONFD_URL=${confdUrl} \
        BIRD_URL=${birdUrl} \
        BIRD6_URL=${bird6Url} \
        BIRDCL_URL=${birdclUrl}
    """
  }


  return [
    CTL_CONTAINER_NAME:"${ctlName}",
    NODE_CONTAINER_NAME:"${nodeName}",
    CALICO_NODE_IMAGE_REPO:"${nodeImage}",
    CALICOCTL_IMAGE_REPO:"${ctlImage}",
    CALICO_VERSION: "${imgTag}"
  ]

}
