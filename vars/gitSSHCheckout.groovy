def call(body) {
  // evaluate the body block, and collect configuration into the object
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  def target_dir = config.target_dir ?: "./"
  def port = config.port ?: "29418"

  stage("Git Checkout"){
    checkout(
      scm: [
        $class: 'GitSCM',
        branches: [[name: "${config.branch}"]],
        extensions: [
          [$class: 'CleanCheckout'],
          [$class: 'RelativeTargetDirectory', relativeTargetDir: "${target_dir}"]
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
