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
    sh(". ~/.ssh/ssh-agent.sh && ${cmd}")
}

/**
 * Setup ssh agent and add private key
 *
 * @param credentialsId Jenkins credentials name to lookup private key
 */
def prepareSshAgentKey(credentialsId) {
    def common = new com.mirantis.mk.common()
    c = common.getSshCredentials(credentialsId)
    sh("test -d ~/.ssh || mkdir -m 700 ~/.ssh")
    sh('pgrep -l -u $USER -f | grep -e ssh-agent\$ >/dev/null || ssh-agent|grep -v "Agent pid" > ~/.ssh/ssh-agent.sh')
    sh("echo '${c.getPrivateKey()}' > ~/.ssh/id_rsa_${credentialsId} && chmod 600 ~/.ssh/id_rsa_${credentialsId}")
    runSshAgentCommand("ssh-add ~/.ssh/id_rsa_${credentialsId}")
}

return this;
