package com.mirantis.mk

/**
 *
 * SSH functions
 *
 */

/**
 * Ensure entry in SSH known hosts
 *
 * @param url   url of remote host
 */
def ensureKnownHosts(url) {
    def hostArray = getKnownHost(url)
    sh "test -f ~/.ssh/known_hosts && grep ${hostArray[0]} ~/.ssh/known_hosts || ssh-keyscan -H -p ${hostArray[1]} ${hostArray[0]} >> ~/.ssh/known_hosts"
}

@NonCPS
def getKnownHost(url){
     // test for git@github.com:organization/repository like URLs
    def p = ~/[a-z0-9\._\-]+@(.+\..+)\:{1}.*/
    def result = p.matcher(url)
    def host = ""
    if (result.matches()) {
        host = result.group(1)
        port = 22
    } else {
        // test for protocol
        if(url.indexOf("://") == -1){
            url="ssh://" + url
        }
        parsed = new URI(url)
        host = parsed.host
        port = parsed.port && parsed.port > 0 ? parsed.port: 22
    }
    return [host,port]
}

/**
 * Execute command with ssh-agent
 *
 * @param cmd   Command to execute
 * @return STDOUT output
 */
def runSshAgentCommand(cmd) {
    // if file exists, then we started ssh-agent
    if (fileExists("$HOME/.ssh/ssh-agent.sh")) {
        return sh(script:". ~/.ssh/ssh-agent.sh && ${cmd}", returnStdout:true)
    } else {
    // we didn't start ssh-agent in prepareSshAgentKey() because some ssh-agent
    // is running. Let's re-use already running agent and re-construct
    //   * SSH_AUTH_SOCK
    //   * SSH_AGENT_PID
        return sh(script:"""
        export SSH_AUTH_SOCK=`find /tmp/ -type s -name agent.\\* 2> /dev/null |  grep '/tmp/ssh-.*/agent.*' | head -n 1`
        export SSH_AGENT_PID=`echo \${SSH_AUTH_SOCK} | cut -d. -f2`
        ${cmd}
        """, returnStdout: true)
    }
}

/**
 * Execute command with ssh-agent (shortcut for runSshAgentCommand)
 *
 * @param cmd   Command to execute
 * @return STDOUT output
 */
def agentSh(cmd) {
    return runSshAgentCommand(cmd)
}

/**
 * Setup ssh agent and add private key
 *
 * @param credentialsId Jenkins credentials name to lookup private key
 */
def prepareSshAgentKey(credentialsId) {
    def common = new com.mirantis.mk.Common()
    c = common.getSshCredentials(credentialsId)
    // create ~/.ssh and delete file ssh-agent.sh which can be stale
    sh('mkdir -p -m 700 ~/.ssh && rm -f ~/.ssh/ssh-agent.sh')
    sh('pgrep -l -u `id -u` -f ssh-agent\$ >/dev/null || ssh-agent|grep -v "Agent pid" > ~/.ssh/ssh-agent.sh')
    sh("set +x; echo '${c.getPrivateKey()}' > ~/.ssh/id_rsa_${credentialsId} && chmod 600 ~/.ssh/id_rsa_${credentialsId}; set -x")
    runSshAgentCommand("ssh-add ~/.ssh/id_rsa_${credentialsId}")
}

return this;
