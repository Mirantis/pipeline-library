package ci.mcp

/**
 * https://issues.jenkins-ci.org/browse/JENKINS-26481
 * fix groovy List.collect()
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

@NonCPS
def getCommandBuilder(ArrayList options, String keyOption, String separator = " ") {
  return options.collect{ keyOption + it }.join(separator).replaceAll("\n", "")
}
