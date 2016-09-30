def call(body) {
  // evaluate the body block, and collect configuration into the object
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  def targetDir = config.targetDir ?: "./"
  def port = config.port ?: "29418"

  stage("Git Checkout"){
    checkout(
      scm: [
        $class: 'GitSCM',
        branches: [[name: "${config.branch}"]],
        extensions: [
          [$class: 'CleanCheckout'],
          [$class: 'RelativeTargetDirectory', relativeTargetDir: "${targetDir}"]
        ],
        userRemoteConfigs: [[
          credentialsId: "${config.credentialsId}",
          name: 'origin',
          url: "ssh://${config.credentialsId}@${config.host}:${port}/${config.project}.git"
        ]]
      ]
    )
  }
}
