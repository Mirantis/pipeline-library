package com.mirantis.mk

/**
 *
 * SSL functions
 *
 */

/**
 * Ensure entry in SSH known hosts
 *
 * @param url   url of remote host
 */
def ensureKnownHosts(url) {
    uri = new URI(url)
    port = uri.port ?: 22

    sh "test -f ~/.ssh/known_hosts && grep ${uri.host} ~/.ssh/known_hosts || ssh-keyscan -p ${port} ${uri.host} >> ~/.ssh/known_hosts"
}

/**
 * Execute command with ssh-agent
 *
 * @param cmd   Command to execute
 */
def runSshAgentCommand(cmd) {
    // if file exists, then we started ssh-agent
    if (fileExists("$HOME/.ssh/ssh-agent.sh")) {
        sh(". ~/.ssh/ssh-agent.sh && ${cmd}")
    } else {
    // we didn't start ssh-agent in prepareSshAgentKey() because some ssh-agent
    // is running. Let's re-use already running agent and re-construct
    //   * SSH_AUTH_SOCK
    //   * SSH_AGENT_PID
        sh """
        export SSH_AUTH_SOCK=`find /tmp/ -type s -name agent.\\* 2> /dev/null |  grep '/tmp/ssh-.*/agent.*' | head -n 1`
        export SSH_AGENT_PID=`echo \${SSH_AUTH_SOCK} | cut -d. -f2`
        ${cmd}
        """
    }
}

/**
 * Setup ssh agent and add private key
 *
 * @param credentialsId Jenkins credentials name to lookup private key
 */
def prepareSshAgentKey(credentialsId) {
    def common = new com.mirantis.mk.common()
    c = common.getSshCredentials(credentialsId)
    // create ~/.ssh and delete file ssh-agent.sh which can be stale
    sh('mkdir -p -m 700 ~/.ssh && rm -f ~/.ssh/ssh-agent.sh')
    sh('pgrep -l -u $USER -f ssh-agent\$ >/dev/null || ssh-agent|grep -v "Agent pid" > ~/.ssh/ssh-agent.sh')
    sh("set +x; echo '${c.getPrivateKey()}' > ~/.ssh/id_rsa_${credentialsId} && chmod 600 ~/.ssh/id_rsa_${credentialsId}; set -x")
    runSshAgentCommand("ssh-add ~/.ssh/id_rsa_${credentialsId}")
}

return this;
