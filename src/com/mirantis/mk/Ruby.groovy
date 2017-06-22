package com.mirantis.mk

/**
 * Ruby functions
 */

/**
 * Ensures Ruby environment with given version (install it if necessary)
 * @param rubyVersion target ruby version (optional, default 2.2.3)
 */
def ensureRubyEnv(rubyVersion="2.2.3"){
    if(!fileExists("/var/lib/jenkins/.rbenv/versions/${rubyVersion}/bin/ruby")){
        sh "rbenv install ${rubyVersion}";
    }
    sh "rbenv local ${rubyVersion};rbenv exec gem update --system"
}

/**
 * Install kitchen tools
 */
def installKitchen(kitchenInit=""){
    sh """rbenv exec gem install bundler --conservative;
          rbenv exec gem install test-kitchen --conservative;"""
    if(kitchenInit!=""){
        sh kitchenInit
    }else{
        sh """  test -e Gemfile || cat <<EOF > Gemfile
                source 'https://rubygems.org'
                gem 'rake'
                gem 'test-kitchen'
                gem 'kitchen-docker'
                gem 'kitchen-inspec'
                gem 'inspec'
                gem 'kitchen-salt', :git => 'https://github.com/salt-formulas/kitchen-salt.git'"""
        }
    sh "rbenv exec bundler install --path vendor/bundle"
}

/**
 * Run kitchen tests in tests/integration
 * @param environment kitchen environment (optional can be empty)
 * @param parallelTesting run kitchen test suites in parallel (optional, default true)
 */
def runKitchenTests(environment="", parallelTesting = true){
    def common = new com.mirantis.mk.Common()
    def kitchenTests=runKitchenCommand("list -b 2>/dev/null \"\$SUITE_PATTERN\"", environment)
    if(kitchenTests && kitchenTests != ""){
        def kitchenTestsList = kitchenTests.trim().tokenize("\n")
        def kitchenTestRuns = [:]
        common.infoMsg(String.format("Found %s kitchen test suites", kitchenTestsList.size()))
        for(int i=0;i<kitchenTestsList.size();i++){
            def testSuite = kitchenTestsList[i]
            kitchenTestRuns["kitchen-${testSuite}-${i}"] = {
                common.infoMsg("Running kitchen test ${testSuite}")
                println(runKitchenCommand("converge ${testSuite}", environment))
                println runKitchenCommand("verify ${testSuite} -t tests/integration", environment)
                println runKitchenCommand("destroy", environment)
            }
        }
        if(parallelTesting){
            parallel kitchenTestRuns
        }else{
            common.serial(kitchenTestRuns)
        }
    }else{
        common.errorMsg("Cannot found kitchen test suites, kitchen list command returns bad output")
    }
}

/**
 * Run kitchen command
 * @param cmd kitchen command
 * @param environment kitchen environment properties  (will be used before kitchen command), example: PLATFORM=ubuntu-16-04
 * @return return kitchen output
 */
def runKitchenCommand(cmd, environment = null){
    if(environment && environment != ""){
        return sh(script: "${environment} rbenv exec bundler exec kitchen ${cmd}", returnStdout: true)
    }else{
        return sh(script: "rbenv exec bundler exec kitchen ${cmd}", returnStdout: true)
    }
}