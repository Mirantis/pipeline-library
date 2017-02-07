package com.mirantis.mcp

/*
  Example usage:

  def ccpCiCd = new com.mirantis.mcp.CCPCICD().newInstance(this, env)

  ccpCiCd.applyPipelineParameters()

  ccpCiCd.fetchEnvConfiguration()
  ccpCiCd.parametrizeConfig()

  ccpCiCd.build()
  ccpCiCd.deploy()
  ccpCiCd.cleanup()
*/

/*
  Since groovy-cps demands that local variables may be serialized,
  any locally defined classes must also be serializable

  More details: https://issues.jenkins-ci.org/browse/JENKINS-32213
*/

public class ccpCICD implements Serializable {

  /*
    Endless loop in DefaultInvoker.getProperty when accessing field
    via getter/setter without @

    This issue fixed in groovy pipeline plugin 2.25

    More details https://issues.jenkins-ci.org/browse/JENKINS-31484
  */

  /*
    nodeContext - is a context of which node the job is executed
    through this context, CCPCICD class able to use
    dir() writeFile() sh() and other workflow basic steps

    See more https://jenkins.io/doc/pipeline/steps/workflow-basic-steps/

    nodeContext must be passed in class constructor
  */
  def nodeContext = null

  /*
    Job parameters are accessed through environment
  */
  def env = null

  /*
    Directory where stored environment configuration
  */
  def envConfigurationPath = null
  def File envConfiguration = null

  /*
    Path to entry point yaml in environment directory
  */
  def ccpConfigPath = null

  def File virtualEnv = null // Currently not supported

  def URI configurationRepo = null

  /*
    parsed `ccp config dump` output
  */

  def Map ccpConfig = null

  /*
    Part of ccp parameters which will be overwritten by job
    parametrizeConfig() method

  */
  def Map ciConfig = [
    'registry'    : [
      'address'   : null,
      'username'  : null,
      'password'  : null,
    ],
    'kubernetes'  : [
      'namespace' : 'ccp',
      'server'    : null,
      'insecure'  : true,
      'username'  : null,
      'password'  : null
    ],
    'repositories': [
      'path'      : null
    ]

  ]

  // Using loadYAML and dumpYAML from common library
  def common = new com.mirantis.mcp.Common()

  // default CCP repo (using in upgrade)
  def URI ccpSource = new URI('git+https://gerrit.mcp.mirantis.net/ccp/fuel-ccp')

  def public void setConfURL(URI confURI) {
    if (confURI == null) {
      throw new IllegalArgumentException('Invalid argument')
    }
    this.@configurationRepo = confURI
  }

  def public URI getConfURL() {
    return this.@configurationRepo
  }

  /*
    According JENKINS-31314 constructor can be used only for assignments properties
    More details: https://issues.jenkins-ci.org/browse/JENKINS-31314
  */
  def public ccpCICD(nodeContext, env) {
    this.@nodeContext = nodeContext
    this.@env = env
  }

  /*
    Transform jenkins job arguments to ccp configuration
    Parameters can be overriden with customParameters Map

    Example usage:
    applyPipelineParameters([

      'images': [
        'image_specs': [
            'etcd': [
                'tag': '41a45e5a9db5'
            ]
        ]
      ]

    ])

  */

  def public void applyPipelineParameters(Map customParameters = null) {

    if (this.@env.CONF_GERRIT_URL != null) {
      this.setConfURL(new URI(this.@env.CONF_GERRIT_URL))
    }


    if (this.@env.CONF_ENTRYPOINT != null) {
      this.setCcpConfigPath(new File(this.@env.CONF_ENTRYPOINT))
    }

    if (this.@env.KUBERNETES_URL != null) {
      this.setKubernetesURL(new URI(this.@env.KUBERNETES_URL), this.@env.CREDENTIALS_ID)
    } else {
      this.@ciConfig.remove('kubernetes')
    }

    if (this.@env.DOCKER_REGISTRY != null) {
      this.setRegistry(new URI(this.@env.DOCKER_REGISTRY), this.env.DOCKER_REGISTRY_CREDENTIAL_ID ? this.env.DOCKER_REGISTRY_CREDENTIAL_ID : 'artifactory')
    } else {
      this.@ciConfig.remove('registry')
    }


    this.@ciConfig['repositories']['path'] = env.WORKSPACE + '/repos'

    if (customParameters != null) {
      this.@ciConfig = this.@ciConfig + customParameters
    }
  }

  def public File getCcpConfigPath() {
    return this.@ccpConfigPath
  }

  def public void setCcpConfigPath(File configPath) {
    this.@ccpConfigPath = configPath
  }

  /*
    Set k8s endpoint and credentials

    Example usage:
      this.setKubernetesURL(new URI('https://host:443'), 'kubernetes-api')
  */

  def public void setKubernetesURL(URI kubernetesURL, String credentialId) {
    if (credentialId != null) {
      this.@nodeContext.withCredentials([
        [
          $class           : 'UsernamePasswordMultiBinding',
          credentialsId    :  credentialId,
          passwordVariable : 'K8S_PASSWORD',
          usernameVariable : 'K8S_USERNAME'
        ]
      ]) {
        this.@ciConfig['kubernetes']['username'] = env.K8S_USERNAME
        this.@ciConfig['kubernetes']['password'] = env.K8S_PASSWORD
      }
    }

    /* override parameters from URI (if present) */
    if (kubernetesURL.getUserInfo()) {
        //TODO(sryabin) properly parse return from getUserInfo()
        this.@ciConfig['kubernetes']['username'] = kubernetesURL.getUserInfo()
    }

    this.@ciConfig['kubernetes']['server'] = kubernetesURL.toString()
  }

  def public void setRegistry(String registryEndpoint, String credentialId) {
    if (credentialId) {
      this.@nodeContext.withCredentials([
        [
          $class           : 'UsernamePasswordMultiBinding',
          credentialsId    :  credentialId,
          passwordVariable : 'REGISTRY_PASSWORD',
          usernameVariable : 'REGISTRY_USERNAME'
        ]
      ]) {
        this.@ciConfig['registry']['username'] = env.REGISTRY_USERNAME
        this.@ciConfig['registry']['password'] = env.REGISTRY_PASSWORD
      }
    } else {
      this.@ciConfig['registry'].remove('username')
      this.@ciConfig['registry'].remove('password')
    }

    this.@ciConfig['registry']['address'] = registryEndpoint;
  }

  def public setEnvConfigurationDir() {
    // TODO(sryabin) cleanup this dir in desctructor
    this.@envConfigurationPath = File.createTempDir(this.@env.WORKSPACE + '/envConfiguration', 'ccpci')
  }

  def public File getEnvConfigurationDir() {
    return this.@envConfigurationPath
  }



  def public File fetchEnvConfiguration() {

    def gitTools = new com.mirantis.mcp.Git()

    this.setEnvConfigurationDir()

    gitTools.gitSSHCheckout ([
        credentialsId : this.@env.CONF_GERRIT_CREDENTIAL_ID,
        branch : "master",
        host : this.getConfURL().getHost(),
        port : this.getConfURL().getPort(),
        project: this.getConfURL().getPath(),
        targetDir: this.getEnvConfigurationDir().toString()
    ])

    return this.getEnvConfigurationDir()
  }

  def public String configDump() {
    return this.ccpExecute('config dump')
  }

  /*
    merge ccp configuration from repo and custom parameters from job arguments
  */
  def public Map parametrizeConfig() {
    this.fetchEnvConfiguration()
    this.@ccpConfig = this.@common.loadYAML(this.configDump())
    this.setCcpConfigPath(new File('parametrized.yaml'));
    this.@nodeContext.writeFile file: this.getEnvConfigurationDir().toString() + '/' + this.getCcpConfigPath().toString(), \
      text: this.@common.dumpYAML(this.@ccpConfig + this.@ciConfig)
  }

  def public Map getConfig() {
    return this.@ccpConfig
  }


  /*
    Example usage:
      ci.ccpExecute('config dump')
  */
  def public String ccpExecute(args) {
    // TODO(sryabin) pass custom args from class property, like debug
    def String output = null;
    this.@nodeContext.dir(this.getEnvConfigurationDir().toString()) {
      output = this.@nodeContext.sh(
        script: "PYTHONWARNINGS='ignore:Unverified HTTPS request' ccp --config-file " + this.getCcpConfigPath().toString() +  " ${args}",
        returnStdout: true
      )
    }
    return output
  }

  /*
    Update fuel-ccp

    @param virtualEnv File, path to python virtual environment, currently not supported
    @param source URI, if not present, use upstream
    @param upgradePip Boolean, upgrade pip if required

    Usage example:
      upgradeCcp(new File('/home/ubuntu/.venv'), new URI('http://github.com/openstack/fuel-ccp'))
  */
  def public void upgradeCcp(File virtualEnv = null, URI source, Boolean upgradePip = false) {
    if (virtualEnv) {
      throw new UnsupportedOperationException('Python virtual environments not implemented')
    }

    try {
      def output = this.@nodeContext.sh(
        script: 'pip install --user --upgrade ' + ((source != null) ? source : this.@ccpSource).toString(),
        returnStdout: true
      )
    } catch (e) {
      // TODO(sryabin) catch in stderr "You should consider upgrading via the 'pip install --upgrade pip' command."
      if (upgradePip == true) {
        this.@nodeContext.sh(
          script: 'pip install --upgrade pip',
          returnStdout: true
        )
      }
      // FIXME(sryabin) infinity loop
      //this.upgradeCcp(virtualEnv, source, false)
    }
  }
  def public void upgradeCcp() {
    this.upgradeCcp(null, null, true)
  }

  def build() {
    //TODO(sryabin) implement build -c functionality
    return this.ccpExecute('build')
  }

  def deploy() {
    //TODO(sryabin) implement build -c functionality
    return this.ccpExecute('deploy')
  }

  def cleanup() {
    try {
      this.ccpExecute('cleanup')
    } catch (err) {
      this.ccpExecute('cleanup --skip-os-cleanup')
    }
  }

  def fetch() {
    // TODO(sryabin) implement fetch -f functioanlity
    return this.ccpExecute('fetch')
  }
}

/*
  Workaround for scope limitation, and
    org.jenkinsci.plugins.scriptsecurity.sandbox.RejectedAccessException: unclassified new

  def ccpCiCd = new com.mirantis.mcp.CCPCICD()
  def CCPCICD ci = new ccpCiCd.ccpCICD(this, env) return no such CCPCICD()

  Example usage:
    def ccpCiCd = new com.mirantis.mcp.CCPCICD().newInstance(this, env)

*/

def newInstance(nodeContext, env) {
  return new ccpCICD(nodeContext,env)
}
