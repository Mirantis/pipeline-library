package com.mirantis.mcp


/**
 * Checkout Calico repository stage
 *
 * @param config LinkedHashMap
 *        config includes next parameters:
 *          - project_name String, Calico project to clone
 *          - host String, gerrit host
 *          - projectNamespace String, gerrit namespace (optional)
 *          - commit String, Git commit to checkout (optional)
 *          - credentialsId String, gerrit credentials ID (optional)
 *          - refspec String, remote refs to be retrieved (optional)
 *
 * Usage example:
 *
 * def calico = new com.mirantis.mcp.Calico()
 * calico.checkoutCalico([
 *     project_name : 'cni-plugin',
 *     commit : 'mcp',
 *     host : 'gerrit.mcp.mirantis.net',
 * ])
 *
 */
def checkoutCalico(LinkedHashMap config) {

  def git = new com.mirantis.mcp.Git()

  def project_name = config.get('project_name')
  def projectNamespace = config.get('projectNamespace', 'projectcalico')
  def commit = config.get('commit', '*')
  def host = config.get('host')
  def credentialsId = config.get('credentialsId', 'mcp-ci-gerrit')
  def refspec = config.get('refspec')

  if (!project_name) {
    throw new RuntimeException("Parameter 'project_name' must be set for checkoutCalico() !")
  }
  if (!host) {
    throw new RuntimeException("Parameter 'host' must be set for checkoutCalico() !")
  }

  stage ("Checkout ${project_name}"){
    git.gitSSHCheckout([
      credentialsId : credentialsId,
      branch : commit,
      host : host,
      project : "${projectNamespace}/${project_name}",
      withWipeOut : true,
      refspec : refspec,
    ])
  }
}


/**
 * Build bird binaries stage
 *
 * Usage example:
 *
 * def calico = new com.mirantis.mcp.Calico()
 * calico.buildCalicoBird()
 *
 */
def buildCalicoBird() {
  stage ('Build bird binaries'){
    sh "/bin/sh -x build.sh"
  }
}


/**
 * Publish bird binaries stage
 *
 * @param config LinkedHashMap
 *        config includes next parameters:
 *          - artifactoryServerName String, artifactory server name
 *          - binaryRepo String, repository (artifactory) for binary files
 *          - projectNamespace String, artifactory server namespace (optional)
 *          - publishInfo Boolean, whether publish a build-info object to Artifactory (optional)
 *
 * Usage example:
 *
 * def calico = new com.mirantis.mcp.Calico()
 * calico.publishCalicoBird([
 *     artifactoryServerName : 'mcp-ci',
 *     binaryRepo : 'sandbox-binary-dev-local',
 * ])
 *
 */
def publishCalicoBird(LinkedHashMap config) {

  def common = new com.mirantis.mcp.Common()
  def git = new com.mirantis.mcp.Git()
  def artifactory = new com.mirantis.mcp.MCPArtifactory()

  def artifactoryServerName = config.get('artifactoryServerName')
  def binaryRepo = config.get('binaryRepo')
  def projectNamespace = config.get('projectNamespace', 'mirantis/projectcalico')
  def publishInfo = config.get('publishInfo', true)

  if (!artifactoryServerName) {
    throw new RuntimeException("Parameter 'artifactoryServerName' must be set for publishCalicoBird() !")
  }
  if (!binaryRepo) {
    throw new RuntimeException("Parameter 'binaryRepo' must be set for publishCalicoBird() !")
  }

  def artifactoryServer = Artifactory.server(artifactoryServerName)
  def buildInfo = Artifactory.newBuildInfo()

  stage('Publishing bird artifacts') {
    dir("artifacts"){
      // define tag for bird
      binaryTag = git.getGitDescribe(true) + "-" + common.getDatetime()
      sh """
        cp ../dist/bird bird-${binaryTag}
        cp ../dist/bird6 bird6-${binaryTag}
        cp ../dist/birdcl birdcl-${binaryTag}
      """
      writeFile file: "latest", text: "${binaryTag}"
      // define mandatory properties for binary artifacts
      // and some additional
      def properties = artifactory.getBinaryBuildProperties([
        "tag=${binaryTag}",
        "project=bird"
        ])

      def uploadSpec = """{
          "files": [
                  {
                      "pattern": "**",
                      "target": "${binaryRepo}/${projectNamespace}/bird/",
                      "props": "${properties}"
                  }
              ]
          }"""

      // Upload to Artifactory.
      artifactory.uploadBinariesToArtifactory(artifactoryServer, buildInfo, uploadSpec, publishInfo)
    }// dir
  }
  return binaryTag
}


/**
 * Test confd stage
 *
 *
 * Usage example:
 *
 * def calico = new com.mirantis.mcp.Calico()
 * calico.testCalicoConfd()
 *
 */
def testCalicoConfd() {
  stage ('Run unittest for confd'){
    sh """
    docker run --rm \
      -v \$(pwd):/usr/src/confd \
      -w /usr/src/confd \
      golang:1.7 \
      bash -c \
      \"go get github.com/constabulary/gb/...; gb test -v\"
    """
  }
}


/**
 * Build confd binaries stage
 *
 *
 * Usage example:
 *
 * def calico = new com.mirantis.mcp.Calico()
 * calico.buildCalicoConfd()
 *
 */
def buildCalicoConfd() {
  def container_src_dir = "/usr/src/confd"
  def src_suffix = "src/github.com/kelseyhightower/confd"
  def container_workdir = "${container_src_dir}/${src_suffix}"
  def container_gopath = "${container_src_dir}/vendor:${container_src_dir}"

  stage ('Build confd binary'){
    sh """
      docker run --rm \
        -v \$(pwd):${container_src_dir} \
        -w ${container_workdir} \
        -e GOPATH=${container_gopath} \
        golang:1.7 \
        bash -c \
        \"go build -a -installsuffix cgo -ldflags '-extld ld -extldflags -static' -a -x .\"
    """
  }
}


/**
 * Publish confd binaries stage
 *
 * @param config LinkedHashMap
 *        config includes next parameters:
 *          - artifactoryServerName String, artifactory server name
 *          - binaryRepo String, repository (artifactory) for binary files
 *          - projectNamespace String, artifactory server namespace (optional)
 *          - publishInfo Boolean, whether publish a build-info object to Artifactory (optional)
 *
 * Usage example:
 *
 * def calico = new com.mirantis.mcp.Calico()
 * calico.publishCalicoConfd([
 *     artifactoryServerName : 'mcp-ci',
 *     binaryRepo : 'sandbox-binary-dev-local',
 * ])
 *
 */
def publishCalicoConfd(LinkedHashMap config) {

  def common = new com.mirantis.mcp.Common()
  def git = new com.mirantis.mcp.Git()
  def artifactory = new com.mirantis.mcp.MCPArtifactory()

  def artifactoryServerName = config.get('artifactoryServerName')
  def binaryRepo = config.get('binaryRepo')
  def projectNamespace = config.get('projectNamespace', 'mirantis/projectcalico')
  def publishInfo = config.get('publishInfo', true)
  def src_suffix = "src/github.com/kelseyhightower/confd"

  if (!artifactoryServerName) {
    throw new RuntimeException("Parameter 'artifactoryServerName' must be set for publishCalicoConfd() !")
  }
  if (!binaryRepo) {
    throw new RuntimeException("Parameter 'binaryRepo' must be set for publishCalicoConfd() !")
  }

  def artifactoryServer = Artifactory.server(artifactoryServerName)
  def buildInfo = Artifactory.newBuildInfo()

  stage('Publishing confd artifacts') {

    dir("artifacts"){
      // define tag for confd
      binaryTag = git.getGitDescribe(true) + "-" + common.getDatetime()
      // create two files confd and confd+tag
      sh "cp ../${src_suffix}/confd confd-${binaryTag}"
      writeFile file: "latest", text: "${binaryTag}"

      // define mandatory properties for binary artifacts
      // and some additional
      def properties = artifactory.getBinaryBuildProperties([
        "tag=${binaryTag}",
        "project=confd"
        ])

      def uploadSpec = """{
          "files": [
                  {
                      "pattern": "**",
                      "target": "${binaryRepo}/${projectNamespace}/confd/",
                      "props": "${properties}"
                  }
              ]
          }"""

      // Upload to Artifactory.
      artifactory.uploadBinariesToArtifactory(artifactoryServer, buildInfo, uploadSpec, publishInfo)
    }// dir
  }
  return binaryTag
}


/**
 * Test libcalico stage
 *
 * Usage example:
 *
 * def calico = new com.mirantis.mcp.Calico()
 * calico.testLibcalico()
 *
 */
def testLibcalico() {
  stage ('Run libcalico unittests'){
    sh "make test"
  }
}


/**
 * Build calico/build image stage
 *
 * @param config LinkedHashMap
 *        config includes next parameters:
 *          - dockerRegistry String, Docker registry host to push image to (optional)
 *          - projectNamespace String, artifactory server namespace (optional)
 *          - buildImageTag String, calico/build image name (optional)
 *          - imageTag String, tag of docker image (optional)
 *
 * Usage example:
 *
 * def calicoFunc = new com.mirantis.mcp.Calico()
 * calicoFunc.buildLibcalico([
 *     dockerRegistry : 'sandbox-docker-dev-virtual.docker.mirantis.net',
 * ])
 *
 */
def buildLibcalico(LinkedHashMap config) {

  def common = new com.mirantis.mcp.Common()
  def docker = new com.mirantis.mcp.Docker()
  def git = new com.mirantis.mcp.Git()

  def dockerRegistry = config.get('dockerRegistry')
  def projectNamespace = config.get('projectNamespace', 'mirantis/projectcalico')

  def buildImage = config.get('buildImage', "calico/build")
  def buildImageTag = config.get('buildImageTag', git.getGitDescribe(true) + "-" + common.getDatetime())

  def buildContainerName = dockerRegistry ?  "${dockerRegistry}/${projectNamespace}/${buildImage}:${buildImageTag}" : "${buildImage}:${buildImageTag}"

  stage ('Build calico/build image') {
    docker.setDockerfileLabels("./Dockerfile", ["docker.imgTag=${buildImageTag}"])
    sh """
       make calico/build BUILD_CONTAINER_NAME=${buildContainerName}
       """
  }
  return [buildImage : buildImage,
          buildImageTag : buildImageTag]
}


/**
 * Switch Calico to use dowstream libcalico-go repository stage
 *
 * @param libCalicoGoCommit String, libcalico-go repository commit to checkout to
 * @param host String, gerrit host
 * @param glideLockFilePath String, relative path to glide.lock file
 *
 * Usage example:
 *
 * def calico = new com.mirantis.mcp.Calico()
 * // Checkout calico code using calico.checkoutCalico() and then call this method from the same dir
 * calico.switchCalicoToDownstreamLibcalicoGo('mcp', 'gerrit.mcp.mirantis.net', './glide.lock')
 *
 */
def switchCalicoToDownstreamLibcalicoGo(String libCalicoGoCommit, String host, String glideLockFilePath) {
  def common = new com.mirantis.mcp.Common()
  def git = new com.mirantis.mcp.Git()

  stage ('Switch to downstream libcalico-go') {
    def libcalicogo_path = "${env.WORKSPACE}/tmp_libcalico-go"

    git.gitSSHCheckout([
      credentialsId : "mcp-ci-gerrit",
      branch : libCalicoGoCommit,
      host : host,
      project : "projectcalico/libcalico-go",
      targetDir : libcalicogo_path,
      withWipeOut : true,
    ])

    //FIXME(skulanov) we need to clean local cache for libcalico-go
    sh "rm -rf ~/.glide/cache/src/file-*"

    sh "cp ${glideLockFilePath} ${glideLockFilePath}.bak"
    def glideLockFileContent = readFile file: glideLockFilePath
    def glideMap = common.loadYAML(glideLockFileContent)

    for (goImport in glideMap['imports']) {
      if (goImport['name'].contains('libcalico-go')) {
        goImport['repo'] = 'file:///go/src/github.com/projectcalico/libcalico-go'
        goImport['vcs'] = 'git'
      }
    }

    writeFile file: glideLockFilePath, text: common.dumpYAML(glideMap)

    sh "LIBCALICOGO_PATH=${libcalicogo_path} make vendor"
    // need this to reset glide.lock changes (vendor dir is already compiled)
    // otherwise binaries will be versioned with '-dirty' suffix
    sh "git checkout ."
  }
}


/**
 * Test Felix stage
 *
 * Usage example:
 *
 * def calico = new com.mirantis.mcp.Calico()
 * calico.testFelix()
 *
 */
def testFelix() {
  stage ('Run felix unittests'){
    // inject COMPARE_BRANCH variable for felix tests coverage (python code) check
    def COMPARE_BRANCH = env.GERRIT_BRANCH ? "gerrit/${env.GERRIT_BRANCH}" : "origin/mcp"
    sh "make ut UT_COMPARE_BRANCH=${COMPARE_BRANCH}"
  }
}


/**
 * Build calico/felix image stage
 *
 * @param config LinkedHashMap
 *        config includes next parameters:
 *          - dockerRegistry String, Docker registry host to push image to (optional)
 *          - projectNamespace String, artifactory server namespace (optional)
 *          - felixImage String, calico/felix image name (optional)
 *          - felixImageTag String, tag of docker image (optional)
 *          - dockerFilePath String, path to the Dockerfile for image (optional)
 *
 * Usage example:
 *
 * def calicoFunc = new com.mirantis.mcp.Calico()
 * calicoFunc.buildFelix([
 *     dockerRegistry : 'sandbox-docker-dev-virtual.docker.mirantis.net',
 * ])
 *
 */
def buildFelix(LinkedHashMap config) {

  def common = new com.mirantis.mcp.Common()
  def docker = new com.mirantis.mcp.Docker()
  def git = new com.mirantis.mcp.Git()

  def dockerRegistry = config.get('dockerRegistry')
  def projectNamespace = config.get('projectNamespace', 'mirantis/projectcalico')

  def felixImage = config.get('felixImage', "calico/felix")
  def felixImageTag = config.get('felixImageTag', git.getGitDescribe(true) + "-" + common.getDatetime())
  def dockerFilePath = config.get('dockerFilePath', "./docker-image/Dockerfile")

  def felixContainerName = dockerRegistry ?  "${dockerRegistry}/${projectNamespace}/${felixImage}:${felixImageTag}" : "${felixImage}:${felixImageTag}"

  stage ('Build calico/felix image') {
    docker.setDockerfileLabels(dockerFilePath, ["docker.imgTag=${felixImageTag}"])
    sh """
       make calico/felix
       docker tag calico/felix ${felixContainerName}
       """
  }
  return [felixImage : felixImage,
          felixImageTag : felixImageTag]
}

/**
 * Test Calicoctl stage
 *
 * Usage example:
 *
 * def calico = new com.mirantis.mcp.Calico()
 * calico.testCalicoctl()
 *
 */
def testCalicoctl() {
  stage ('Run calicoctl unittests'){
    sh "make test-containerized"
  }
}


/**
 * Run Calico system tests stage
 *
 * @param nodeImage String, docker image for calico/node container
 * @param ctlImage String, docker image with calicoctl binary
 * @param failOnErrors Boolean, raise exception if some tests fail (default true)
 *
 * Usage example:
 *
 * def calico = new com.mirantis.mcp.Calico()
 * calico.systestCalico('calico/node:latest', 'calico/ctl:latest')
 *
 */
def systestCalico(nodeImage, ctlImage, failOnErrors = true) {
  stage ('Run Calico system tests'){
    try {
      // create fake targets to avoid execution of unneeded operations
      sh """
        mkdir -p calicoctl_home/vendor
        mkdir -p calico_home/vendor
      """
      // pull calico/ctl image and extract calicoctl binary from it
      sh """
        cd calicoctl_home
        mkdir -p dist
        docker run --rm -u \$(id -u):\$(id -g) --entrypoint /bin/cp -v \$(pwd)/dist:/dist ${ctlImage} /calicoctl /dist/calicoctl
        touch dist/calicoctl dist/calicoctl-linux-amd64
      """
      // pull calico/node image and extract required binaries
      sh """
        cd calico_home
        mkdir -p dist
        mkdir -p calico_node/filesystem/bin
        for calico_binary in startup allocate-ipip-addr calico-felix bird calico-bgp-daemon confd libnetwork-plugin; do
          docker run --rm -u \$(id -u):\$(id -g) --entrypoint /bin/cp -v \$(pwd)/calico_node/filesystem/bin:/calicobin ${nodeImage} /bin/\${calico_binary} /calicobin/
        done
        cp calico_node/filesystem/bin/startup dist/
        cp calico_node/filesystem/bin/allocate-ipip-addr dist/
        touch calico_node/filesystem/bin/*
        touch calico_node/.calico_node.created
      """
      // execute systests against calico/node
      sh """
        cd calico_home/calico_node
        NODE_CONTAINER_NAME=${nodeImage} make st
      """
    } catch (Exception e) {
      sh """
        cd calico_home/calico_node
        make stop-etcd
        make clean
        """
      if (failOnErrors) {
        throw e
      }
    }
  }
}


/**
 * Build Calico containers stages
 *
 * @param config LinkedHashMap
 *        config includes next parameters:
 *          - dockerRegistry String, repo with docker images
 *          - projectNamespace String, artifactory server namespace
 *          - artifactoryURL String, URL to repo with calico-binaries
 *          - imageTag String, tag of images
 *          - nodeImage String, Calico Node image name
 *          - ctlImage String, Calico CTL image name
 *          - buildImage String, Calico Build image name
 *          - felixImage String, Calico Felix image name
 *          - confdBuildId String, Version of Calico Confd
 *          - confdUrl String, URL to Calico Confd
 *          - birdUrl, URL to Calico Bird
 *          - birdBuildId, Version of Calico Bird
 *          - bird6Url, URL to Calico Bird6
 *          - birdclUrl, URL to Calico BirdCL
 *
 * Usage example:
 *
 * def calicoFunc = new com.mirantis.mcp.Calico()
 * calicoFunc.buildCalicoContainers([
 *     dockerRegistry : 'sandbox-docker-dev-virtual.docker.mirantis.net',
 *     artifactoryURL : 'https://artifactory.mcp.mirantis.net/artifactory/sandbox',
 * ])
 *
 */
def buildCalicoContainers(LinkedHashMap config) {

  def common = new com.mirantis.mcp.Common()
  def docker = new com.mirantis.mcp.Docker()
  def git = new com.mirantis.mcp.Git()

  def dockerRegistry = config.get('dockerRegistry')
  def projectNamespace = config.get('projectNamespace', 'mirantis/projectcalico')
  def artifactoryURL = config.get('artifactoryURL')

  if (! dockerRegistry ) {
      error('dockerRegistry parameter has to be set.')
  }

  if (! artifactoryURL ) {
      error('artifactoryURL parameter has to be set.')
  }

  def ctlImgTag = null
  def nodeImgTag = null

  def nodeImage = config.get('nodeImage', "calico/node")
  def nodeRepo = "${dockerRegistry}/${projectNamespace}/${nodeImage}"
  def nodeName = null

  def ctlImage = config.get('ctlImage', "calico/ctl")
  def ctlRepo = "${dockerRegistry}/${projectNamespace}/${ctlImage}"
  def ctlName = null

   // calico/build goes from libcalico
  def buildImage = config.get('buildImage',"${dockerRegistry}/${projectNamespace}/calico/build:latest")
  // calico/felix goes from felix
  def felixImage = config.get('felixImage', "${dockerRegistry}/${projectNamespace}/calico/felix:latest")

  def confdBuildId = config.get('confdBuildId', "${artifactoryURL}/${projectNamespace}/confd/latest".toURL().text.trim())
  def confdUrl = config.get('confdUrl', "${artifactoryURL}/${projectNamespace}/confd/confd-${confdBuildId}")

  def birdBuildId = config.get('birdBuildId', "${artifactoryURL}/${projectNamespace}/bird/latest".toURL().text.trim())
  def birdUrl = config.get('birdUrl', "${artifactoryURL}/${projectNamespace}/bird/bird-${birdBuildId}")
  def bird6Url = config.get('bird6Url', "${artifactoryURL}/${projectNamespace}/bird/bird6-${birdBuildId}")
  def birdclUrl = config.get('birdclUrl', "${artifactoryURL}/${projectNamespace}/bird/birdcl-${birdBuildId}")

  
  // Configure and build calico/ctl image
  dir("./calicoctl_home"){
    ctlImgTag = config.get('imageTag', git.getGitDescribe(true) + "-" + common.getDatetime())
    ctlName = "${ctlRepo}:${ctlImgTag}"

    // Add LABELs to dockerfile
    docker.setDockerfileLabels("./calicoctl/Dockerfile.calicoctl",
                               ["docker.imgTag=${ctlImgTag}",
                                "calico.buildImage=${buildImage}",
                                "calico.birdclUrl=${birdclUrl}"])

    // Start build process
    stage ('Build calico/ctl image'){

      withEnv(["CTL_CONTAINER_NAME=${ctlName}",
               "PYTHON_BUILD_CONTAINER_NAME=${buildImage}",
               "BIRDCL_URL=${birdclUrl}"]){
        sh "make calico/ctl"
      }
    }

  }

  // Configure and build calico/node image
  dir("./calico_home"){
    nodeImgTag = config.get('imageTag', git.getGitDescribe(true) + "-" + common.getDatetime())
    nodeName = "${nodeRepo}:${nodeImgTag}"

    // Add LABELs to dockerfile
    docker.setDockerfileLabels("./calico_node/Dockerfile",
                               ["docker.imgTag=${nodeImgTag}",
                                "calico.buildImage=${buildImage}",
                                "calico.felixImage=${felixImage}",
                                "calico.confdUrl=${confdUrl}",
                                "calico.birdUrl=${birdUrl}",
                                "calico.bird6Url=${bird6Url}",
                                "calico.birdclUrl=${birdclUrl}"])

    // Start build process
    stage('Build calico/node'){

      withEnv(["NODE_CONTAINER_NAME=${nodeName}",
               "PYTHON_BUILD_CONTAINER_NAME=${buildImage}",
               "FELIX_CONTAINER_NAME=${felixImage}",
               "CONFD_URL=${confdUrl}",
               "BIRD_URL=${birdUrl}",
               "BIRD6_URL=${bird6Url}",
               "BIRDCL_URL=${birdclUrl}"]){
        sh "make -C calico_node calico/node"
      }
    }

  }


  return [
    CTL_CONTAINER_NAME:"${ctlImage}",
    NODE_CONTAINER_NAME:"${nodeImage}",
    CALICO_NODE_IMAGE_REPO:"${nodeRepo}",
    CALICOCTL_IMAGE_REPO:"${ctlRepo}",
    CALICO_NODE_VERSION: "${nodeImgTag}",
    CALICOCTL_VERSION: "${ctlImgTag}"
  ]

}


/**
 * Test Calico CNI plugin stage
 *
 * Usage example:
 *
 * def calico = new com.mirantis.mcp.Calico()
 * calico.testCniPlugin()
 *
 */
def testCniPlugin() {
  stage ('Run cni-plugin unittests'){
    // 'static-checks-containerized' target is removed from master
    // and kept here only for backward compatibility
    sh "make static-checks || make static-checks-containerized"
    sh "make stop-etcd stop-kubernetes-master"
    // 'stop-k8s-apiserver' target doesn't exist in Calico v2.0.0,
    // so do not fail the stage if it's not found
    sh "make stop-k8s-apiserver || true"
    sh "make test-containerized"
  }
}


/**
 * Build calico/cni image stage
 *
 * @param config LinkedHashMap
 *        config includes next parameters:
 *          - dockerRegistry String, Docker registry host to push image to (optional)
 *          - projectNamespace String, artifactory server namespace (optional)
 *          - cniImage String, calico/cni image name (optional)
 *          - cniImageTag String, tag of docker image (optional)
 *
 * Usage example:
 *
 * def calicoFunc = new com.mirantis.mcp.Calico()
 * calicoFunc.buildFelix([
 *     dockerRegistry : 'sandbox-docker-dev-virtual.docker.mirantis.net',
 * ])
 *
 */
def buildCniPlugin(LinkedHashMap config) {

  def common = new com.mirantis.mcp.Common()
  def docker = new com.mirantis.mcp.Docker()
  def git = new com.mirantis.mcp.Git()

  def dockerRegistry = config.get('dockerRegistry')
  def projectNamespace = config.get('projectNamespace', 'mirantis/projectcalico')

  def cniImage = config.get('cniImage', "calico/cni")
  def cniImageTag = config.get('cniImageTag', git.getGitDescribe(true) + "-" + common.getDatetime())

  def cniContainerName = dockerRegistry ?  "${dockerRegistry}/${projectNamespace}/${cniImage}:${cniImageTag}" : "${cniImage}:${cniImageTag}"

  stage ('Build calico/cni image') {
    docker.setDockerfileLabels("./Dockerfile", ["docker.imgTag=${cniImageTag}"])
    sh """
       make docker-image
       docker tag calico/cni ${cniContainerName}
       """
  }
  return [cniImage : cniImage,
          cniImageTag : cniImageTag]
}


/**
 * Publish calico docker image stage
 *
 * @param config LinkedHashMap
 *        config includes next parameters:
 *          - artifactoryServerName String, artifactory server name
 *          - dockerRegistry String, Docker registry host to push image to
 *          - dockerRepo String, repository (artifactory) for docker images, must not be Virtual
 *          - imageName String, Docker image name
 *          - imageTag String, Docker image tag
 *          - projectNamespace String, artifactory server namespace (optional)
 *          - publishInfo Boolean, whether publish a build-info object to Artifactory (optional)
 *
 * Usage example:
 *
 * def calico = new com.mirantis.mcp.Calico()
 * calico.publishCalicoImage([
 *     artifactoryServerName : 'mcp-ci',
 *     dockerRegistry : 'sandbox-docker-dev-local.docker.mirantis.net'
 *     dockerRepo : 'sandbox-docker-dev-local',
 *     imageName : 'calico/node',
 *     imageTag : 'v.1.0.0',
 * ])
 *
 */
def publishCalicoImage(LinkedHashMap config) {
  def artifactory = new com.mirantis.mcp.MCPArtifactory()

  def artifactoryServerName = config.get('artifactoryServerName')
  def dockerRegistry = config.get('dockerRegistry')
  def dockerRepo = config.get('dockerRepo')
  def imageName = config.get('imageName')
  def imageTag = config.get('imageTag')
  def projectNamespace = config.get('projectNamespace', 'mirantis/projectcalico')
  def publishInfo = config.get('publishInfo', true)

  if (!artifactoryServerName) {
    throw new RuntimeException("Parameter 'artifactoryServerName' must be set for publishCalicoImage() !")
  }
  if (!dockerRegistry) {
    throw new RuntimeException("Parameter 'dockerRegistry' must be set for publishCalicoImage() !")
  }
  if (!dockerRepo) {
    throw new RuntimeException("Parameter 'dockerRepo' must be set for publishCalicoImage() !")
  }
  if (!imageName) {
    throw new RuntimeException("Parameter 'imageName' must be set for publishCalicoImage() !")
  }
  if (!imageTag) {
    throw new RuntimeException("Parameter 'imageTag' must be set for publishCalicoImage() !")
  }

  def artifactoryServer = Artifactory.server(artifactoryServerName)
  def buildInfo = publishInfo ? Artifactory.newBuildInfo() : null

  stage("Publishing ${imageName}") {
    artifactory.uploadImageToArtifactory(artifactoryServer,
                                         dockerRegistry,
                                         "${projectNamespace}/${imageName}",
                                         imageTag,
                                         dockerRepo,
                                         buildInfo)
  }
  return "${dockerRegistry}/${projectNamespace}/${imageName}:${imageTag}"
}


/**
 * Promote calico docker image stage
 *
 * @param config LinkedHashMap
 *        config includes next parameters:
 *          - imageProperties Map, docker image search properties in artifactory
 *          - artifactoryServerName String, artifactory server name
 *          - dockerLookupRepo String, docker repository (artifactory) to take image from
 *          - dockerPromoteRepo String, docker repository (artifactory) to promote image to
 *          - imageName String, Docker image name to promote with
 *          - imageTag String, Docker image tag to promote with
 *          - projectNamespace String, artifactory server namespace (optional)
 *          - defineLatest Boolean, promote with latest tag if true, default false (optional)
 *
 * Usage example:
 *
 * def calico = new com.mirantis.mcp.Calico()
 * calico.promoteCalicoImage([
 *     imageProperties: [
 *       'com.mirantis.targetImg': 'mirantis/projectcalico/calico/node',
 *       'com.mirantis.targetTag': 'v1.0.0-2017010100000',
 *     ]
 *     artifactoryServerName : 'mcp-ci',
 *     dockerLookupRepo : 'sandbox-docker-dev-local',
 *     dockerPromoteRepo: 'sandbox-docker-prod-local',
 *     imageName: 'calico/node',
 *     imageTag: 'v1.0.0',
 *     defineLatest: true
 * ])
 *
 */
def promoteCalicoImage (LinkedHashMap config) {
  def common = new com.mirantis.mcp.Common()
  def git = new com.mirantis.mcp.Git()
  def artifactory = new com.mirantis.mcp.MCPArtifactory()

  def imageProperties = config.get('imageProperties')
  def artifactoryServerName = config.get('artifactoryServerName')
  def dockerLookupRepo = config.get('dockerLookupRepo')
  def dockerPromoteRepo = config.get('dockerPromoteRepo')
  def imageName = config.get('imageName')
  def imageTag = config.get('imageTag')
  def projectNamespace = config.get('projectNamespace', 'mirantis/projectcalico')
  def defineLatest = config.get('defineLatest', false)

if (!imageProperties) {
    throw new RuntimeException("Parameter 'imageProperties' must be set for promoteCalicoImage() !")
  }
  if (!artifactoryServerName) {
    throw new RuntimeException("Parameter 'artifactoryServerName' must be set for promoteCalicoImage() !")
  }
  if (!dockerLookupRepo) {
    throw new RuntimeException("Parameter 'dockerLookupRepo' must be set for promoteCalicoImage() !")
  }
  if (!dockerPromoteRepo) {
    throw new RuntimeException("Parameter 'dockerPromoteRepo' must be set for promoteCalicoImage() !")
  }
  if (!imageName) {
    throw new RuntimeException("Parameter 'imageName' must be set for promoteCalicoImage() !")
  }
  if (!imageTag) {
    throw new RuntimeException("Parameter 'imageTag' must be set for promoteCalicoImage() !")
  }

  def artifactoryServer = Artifactory.server(artifactoryServerName)
  def artifactURI = artifactory.uriByProperties(artifactoryServer.getUrl(), imageProperties)

  stage("Promote ${imageName}") {
    if ( artifactURI ) {
      def buildProperties = artifactory.getPropertiesForArtifact(artifactURI)
      if (defineLatest) {
        artifactory.promoteDockerArtifact(
          artifactoryServer.getUrl(),
          dockerLookupRepo,
          dockerPromoteRepo,
          "${projectNamespace}/${imageName}",
          buildProperties.get('com.mirantis.targetTag').join(','),
          'latest',
          true
        )
      }
      artifactory.promoteDockerArtifact(
        artifactoryServer.getUrl(),
        dockerLookupRepo,
        dockerPromoteRepo,
        "${projectNamespace}/${imageName}",
        buildProperties.get('com.mirantis.targetTag').join(','),
        "${imageTag}",
        false
      )
    }
    else {
      throw new RuntimeException("Artifacts were not found, nothing to promote! "
                                 +"Given image properties: ${imageProperties}")
    }
  }
}


def calicoFixOwnership() {
  // files created inside container could be owned by root, fixing that
  sh "sudo chown -R \$(id -u):\$(id -g) ${env.WORKSPACE} ${env.HOME}/.glide || true"
}