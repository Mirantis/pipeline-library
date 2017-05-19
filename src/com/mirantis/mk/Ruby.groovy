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
def runKitchenTests(platform=""){
    runKitchenCommand("converge ${platform}")
    runKitchenCommand("verify -t tests/integration ${platform}")
    runKitchenCommand("destroy ${platform}");
}

/**
 * Run kitchen command
 * @param cmd kitchen command
 */
def runKitchenCommand(cmd, platform = null){
    if(platform){
        sh "PLATFORM=${platform} rbenv exec bundler exec kitchen ${cmd}"
    }else{
        sh "rbenv exec bundler exec kitchen ${cmd}"
    }
}



