package com.mirantis.mcp

/**
 * https://issues.jenkins-ci.org/browse/JENKINS-26481
 * fix groovy List.collect()
 **/
@NonCPS
def constructString(ArrayList options, String keyOption, String separator = " ") {
    return options.collect { keyOption + it }.join(separator).replaceAll("\n", "")
}

/**
 * Generate current timestamp
 *
 * @param format Defaults to yyyyMMddHHmmss
 */
def getDatetime(format = "yyyyMMddHHmmss") {
    def now = new Date();
    return now.format(format, TimeZone.getTimeZone('UTC'));
}