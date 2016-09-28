def call(body) {
  // evaluate the body block, and collect configuration into the object
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  stage("Gerrit Patchset Checkout") {
    checkout(
      scm: [
        $class: 'GitSCM',
        branches: [[name: "${GERRIT_BRANCH}"]],
        extensions: [
          [$class: 'CleanCheckout'],
          [$class: 'BuildChooserSetting', buildChooser: [$class: 'GerritTriggerBuildChooser']]
        ],
        userRemoteConfigs: [[
          credentialsId: "${config.credentialsId}",
          name: 'gerrit',
          url: "ssh://${GERRIT_NAME}@${GERRIT_HOST}:${GERRIT_PORT}/${GERRIT_PROJECT}.git",
          refspec: "${GERRIT_REFSPEC}"
        ]]
      ]
    )
  }
}
