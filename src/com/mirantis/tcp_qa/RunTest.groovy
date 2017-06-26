package com.mirantis.tcp_qa

/**
 * Run tcp-qa test by specified group
 * @param testGroup defines what tests to run, options are '-m test_mark', '-k test_expression'
 * @param jobSetParameters is additional params needed to run mcp-qa test
 */

def runTest(testGroup, jobSetParameters) {
    def testArgs = [ '-s', '-ra' ]
    testArgs.add(testGroup)
    jobSetParameters.add("TEST_ARGS=${testArgs.join(' ')}")
    echo("The current tags, args, which were set by job: ${jobSetParameters.join(' ')}")
    withEnv(jobSetParameters) {
        sh '''\
            . ${VENV_PATH}/bin/activate
            if ! py.test ${TEST_ARGS}; then
              echo "Tests failed!"
              exit 1
            fi
            '''.stripIndent()
    }
}
