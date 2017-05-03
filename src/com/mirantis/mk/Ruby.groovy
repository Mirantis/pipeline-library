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
            gem 'kitchen-salt', :git => 'https://github.com/epcim/kitchen-salt.git', :branch => 'dependencis-pkg-repo2'
            #Waiting for PR#78
            #gem 'kitchen-salt', '>=0.2.25'"""
    sh "rbenv exec bundler install --path vendor/bundle"
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



