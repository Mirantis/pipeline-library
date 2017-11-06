package com.mirantis.mk

/**
 * Ruby functions
 */

/**
 * Ensures Ruby environment with given version (install it if necessary)
 * @param rubyVersion target ruby version (optional, default 2.2.3)
 */
def ensureRubyEnv(rubyVersion="2.4.1"){
    if(!fileExists("/var/lib/jenkins/.rbenv/versions/${rubyVersion}/bin/ruby")){
        //XXX: patch ruby-build because debian package is quite old
        sh "git clone https://github.com/rbenv/ruby-build.git ~/.rbenv/plugins/ruby-build"
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
 * @param suite name of test suite for kitchen
 */
def runKitchenTests(environment="", suite= ""){
    def common = new com.mirantis.mk.Common()
    common.infoMsg("Running kitchen test ${suite}")
    println(runKitchenCommand("converge ${suite}", environment))
    println runKitchenCommand("verify ${suite} -t tests/integration", environment)
    println runKitchenCommand("destroy", environment)
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

/**
 * Returns suite name from given env
 * @param kitchenEnv kitchen env string
 * @return suite name of empty string if no suite found
 */
def getSuiteName(kitchenEnv){
  def suitePattern = java.util.regex.Pattern.compile("\\s?SUITE=([^\\s]*)")
  def suiteMatcher = suitePattern.matcher(kitchenEnv)
  if (suiteMatcher.find()) {
      def suite = suiteMatcher.group(1)
      if(suite && suite != ""){
          return suite.replaceAll("_", "-")
      }
  }
  return ""
}
