def call(body) {
  // evaluate the body block, and collect configuration into the object
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  def merge = config.withMerge ?: false
  def wipe = config.withWipeOut ?: false
  def targetDir = config.targetDir ?: "./"
  def port = config.port ?: "29418"

  // default parameters
  def scmExtensions = [
    [$class: 'CleanCheckout'],
    [$class: 'RelativeTargetDirectory', relativeTargetDir: "${targetDir}"]
  ]

  // https://issues.jenkins-ci.org/browse/JENKINS-6856
  if (merge) {
    scmExtensions.add([$class: 'LocalBranch', localBranch: "${config.branch}"])
  }
  // we need wipe workspace before checkout
  if (wipe) {
    scmExtensions.add([$class: 'WipeWorkspace'])
  }

  checkout(
    scm: [
      $class: 'GitSCM',
      branches: [[name: "${config.branch}"]],
      extensions: scmExtensions,
      userRemoteConfigs: [[
        credentialsId: "${config.credentialsId}",
        name: 'origin',
        url: "ssh://${config.credentialsId}@${config.host}:${port}/${config.project}.git"
      ]]
    ]
  )
}
