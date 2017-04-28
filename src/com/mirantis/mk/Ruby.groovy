package com.mirantis.mk

/**
 * Ruby functions
 */

/**
 * Ensures Ruby environment with given version (install it if necessary)
 * @param rubyVersion target ruby version (optional, default 2.3.2)
 */
def ensureRubyEnv(rubyVersion="2.3.2"){
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
          rbenv exec gem install test-kitchen"""
}

/**
 * Run kitchen tests in tests/integration
 */
def runKitchenTests(){
    runKitchenCommand("converge")
    runKitchenCommand("verify -t tests/integration")
}

/**
 * Run kitchen command
 * @param cmd kitchen command
 */
def runKitchenCommand(cmd){
    sh "rbenv exec bundler exec kitchen ${cmd}"
}



