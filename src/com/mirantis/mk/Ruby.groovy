package com.mirantis.mk

/**
 * Ruby functions
 */

def ensureRubyEnv(rubyVersion="2.4.0"){
    sh """rbenv install ${rubyVersion};
          rbenv local ${rubyVersion};
          rbenv exec gem update --system"""
}
def installKitchen(){
    sh """rbenv exec gem install bundler;
          rbenv exec gem install test-kitchen"""
}

def runKitchenTests(){
    runKitchenCommand("converge")
    runKitchenCommand("verify -t tests/integration")
}


def runKitchenCommand(cmd){
    sh "rbenv exec bundler exec kitchen ${cmd}"
}



