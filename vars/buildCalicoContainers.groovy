def call(body) {
  // evaluate the body block, and collect configuration into the object
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  // we need to use Tools library for getting mandatory binary properties
  def tools = new ci.mcp.Tools()

  def server = Artifactory.server(config.artifactoryServerId ?: "mcp-ci")
  def buildInfo = Artifactory.newBuildInfo()
  buildInfo.env.capture = true
  buildInfo.env.filter.addInclude("*")
  buildInfo.env.collect()

  def dockerRepo = config.dockerRepo ?: "artifactory.mcp.mirantis.net:5001"
  def artifactoryURL = config.artifactoryURL ?: "https://artifactory.mcp.mirantis.net/artifactory/projectcalico"

  def nodeImage = config.nodeImage ?: "calico/node"
  def nodeImageTag = config.nodeImageTag ?: "v0.20.0"
  def nodeName = "${dockerRepo}/${nodeImage}:${nodeImageTag}"

  def ctlImage = config.ctlImage ?: "calico/ctl"
  def ctlImageTag = config.ctlImageTag ?: "v0.20.0"
  def ctlName = "${dockerRepo}/${ctlImage}:${ctlImageTag}"

  // calico/build goes from {artifactoryURL}/mcp/libcalico/
  def buildImage = config.buildImage ?: "${artifactoryURL}/mcp/libcalico/lastbuild".toURL().text.trim()
  // calico/felix goes from {artifactoryURL}/mcp/felix/
  def felixImage = config.felixImage ?: "${artifactoryURL}/mcp/felix/lastbuild".toURL().text.trim()

  def confdBuildId = config.confdBuildId ?: "${artifactoryURL}/mcp/confd/lastbuild".toURL().text.trim()
  def confdUrl = config.confdUrl ?: "${artifactoryURL}/mcp/confd/confd-${confdBuildId}"

  def birdBuildId = config.birdBuildId ?: "${artifactoryURL}/mcp/calico-bird/lastbuild".toURL().text.trim()
  def birdUrl = config.birdUrl ?: "${artifactoryURL}/mcp/calico-bird/bird-${birdBuildId}"
  def bird6Url = config.bird6Url ?: "${artifactoryURL}/mcp/calico-bird/bird6-${birdBuildId}"
  def birdclUrl = config.birdclUrl ?: "${artifactoryURL}/mcp/calico-bird/birdcl-${birdBuildId}"

  def gitCommit = sh(returnStdout: true, script: "git -C ${WORKSPACE} rev-parse --short HEAD").trim()

  def build = "${config.containersBuildId}-${gitCommit}"

  // return values
  def calicoNodeImageRepo="${dockerRepo}/${nodeImage}"
  def calicoCtlImageRepo="${dockerRepo}/${ctlImage}"
  def calicoVersion="${nodeImageTag}-${build}"

  // Start build section

  stage ('Build calico/ctl image'){
    sh """
      make ctl_image \
        CTL_CONTAINER_NAME=${ctlName}-${build} \
        BUILD_CONTAINER_NAME=${buildImage} \
        BIRDCL_URL=${birdclUrl}
    """
  }


  stage('Build calico/node'){
    sh """
      make node_image \
        NODE_CONTAINER_NAME=${nodeName}-${build} \
        BUILD_CONTAINER_NAME=${buildImage} \
        FELIX_CONTAINER_NAME=${felixImage} \
        CONFD_URL=${confdUrl} \
        BIRD_URL=${birdUrl} \
        BIRD6_URL=${bird6Url} \
        BIRDCL_URL=${birdclUrl}
    """
  }


  stage('Publishing containers artifacts'){

    withCredentials([
      [$class: 'UsernamePasswordMultiBinding',
        credentialsId: "${config.credentialsId}",
        passwordVariable: 'ARTIFACTORY_PASSWORD',
        usernameVariable: 'ARTIFACTORY_LOGIN']
    ]) {
      sh """
        echo 'Pushing images'
        docker login -u ${ARTIFACTORY_LOGIN} -p ${ARTIFACTORY_PASSWORD} ${dockerRepo}
        docker push ${nodeName}-${build}
        docker push ${ctlName}-${build}
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
      // Create the upload spec.
      def properties = tools.getBinaryBuildProperties()
      def uploadSpec = """{
          "files": [
                  {
                      "pattern": "**",
                      "target": "projectcalico/${config.containersBuildId}/calico-containers/",
                      "props": "${properties}"
                  }
              ]
          }"""
      // Upload to Artifactory.
      server.upload(uploadSpec, buildInfo)
      server.publishBuildInfo buildInfo

    } // dir artifacts
  } //stage

  return [
    CALICO_NODE_IMAGE_REPO:"${calicoNodeImageRepo}",
    CALICOCTL_IMAGE_REPO:"${calicoCtlImageRepo}",
    CALICO_VERSION: "${calicoVersion}"
  ]

}
