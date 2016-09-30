def call(body) {
  // evaluate the body block, and collect configuration into the object
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()


  def merge = config.withMerge ?: false
  def wipe = config.withWipeOut ?: false

  // default parameters
  def scmExtensions = [
    [$class: 'CleanCheckout'],
    [$class: 'BuildChooserSetting', buildChooser: [$class: 'GerritTriggerBuildChooser']]
  ]
  // if we need to "merge" code from patchset to GERRIT_BRANCH branch
  if (merge) {
    scmExtensions.add([$class: 'LocalBranch', localBranch: "${GERRIT_BRANCH}"])
  }
  // we need wipe workspace before checkout
  if (wipe) {
    scmExtensions.add([$class: 'WipeWorkspace'])
  }

  checkout(
    scm: [
      $class: 'GitSCM',
      branches: [[name: "${GERRIT_BRANCH}"]],
      extensions: scmExtensions,
      userRemoteConfigs: [[
        credentialsId: "${config.credentialsId}",
        name: 'gerrit',
        url: "ssh://${GERRIT_NAME}@${GERRIT_HOST}:${GERRIT_PORT}/${GERRIT_PROJECT}.git",
        refspec: "${GERRIT_REFSPEC}"
      ]]
    ]
  )
}
