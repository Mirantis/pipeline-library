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
def installKitchen(){
    sh """rbenv exec gem install bundler;
          rbenv exec gem install test-kitchen;"""
    sh """  test -e Gemfile || cat <<EOF > Gemfile
            source 'https://rubygems.org'
            gem 'rake'
            gem 'test-kitchen'
            gem 'kitchen-docker'
            gem 'kitchen-inspec'
            gem 'inspec'
            gem 'kitchen-salt', :git => 'https://github.com/salt-formulas/kitchen-salt.git'"""
    sh "rbenv exec bundler install --path vendor/bundle"
}

/**
 * Run kitchen tests in tests/integration
 */
def runKitchenTests(environment=""){
    def common = new com.mirantis.mk.Common()
    def kitchenTests=runKitchenCommand("list", environment)
    if(kitchenTests && kitchenTests != ""){
        def kitchenTestsList = kitchenTests.trim().tokenize("\n")
        def kitchenTestRuns = [:]
        for(int i=0;i<kitchenTestsList.size();i++){
            kitchenTestRuns["kitchen-run-${i}"]= {
                runKitchenCommand("converge ${kitchenTestsList[i]}", environment)
            }
        }
        parallel kitchenTestRuns
        runKitchenCommand("destroy", environment)
        runKitchenCommand("verify -t tests/integration", environment)
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
        return sh("rbenv exec bundler exec kitchen ${cmd}", returnStdout: true)
    }
}



