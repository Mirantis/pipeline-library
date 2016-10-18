package ci.mcp

/**
 * https://issues.jenkins-ci.org/browse/JENKINS-26481
 * fix groovy List.collect()
**/
@NonCPS
def constructString(ArrayList options, String keyOption, String separator = " ") {
  return options.collect{ keyOption + it }.join(separator).replaceAll("\n", "")
}

/**
 * Build command line options, e.g:
 *    cmd_opts=["a=b", "c=d", "e=f"]
 *    key = "--build-arg "
 *    separator = " "
 *    def options = getCommandBuilder(cmd_opts, key, separator)
 *    println options
 *    > --build-arg a=b --build-arg c=d --build-arg e=f
 *
 * @param options List of Strings (options that should be populated)
 * @param keyOption key that should be added before each option
 * @param separator Separator between key+Option pairs
 */
def getCommandBuilder(ArrayList options, String keyOption, String separator = " ") {
  return constructString(options, keyOption)
}

/**
* Return string of mandatory build properties for binaries
* User can also add some custom properties
*
* @param customProperties a Array of Strings that should be added to mandatory props
*        in format ["prop1=value1", "prop2=value2"]
**/
def getBinaryBuildProperties(ArrayList customProperties) {

  def namespace = "com.mirantis."
  def properties = [
    "gerritProject=${env.GERRIT_PROJECT}",
    "gerritChangeNumber=${env.GERRIT_CHANGE_NUMBER}",
    "gerritPatchsetNumber=${env.GERRIT_PATCHSET_NUMBER}",
    "gerritChangeId=${env.GERRIT_CHANGE_ID}",
    "gerritPatchsetRevision=${env.GERRIT_PATCHSET_REVISION}"
  ]

  if (customProperties){
    properties.addAll(customProperties)
  }

  return constructString(properties, namespace, ";")
}
