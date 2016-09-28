def call(String env=null) {
  // Run tox with or without specified environment
  if (env==null) {
    sh "tox -v"
  } else {
    sh "tox -v -e ${env}"
  }
}
