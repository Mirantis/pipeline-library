package com.mirantis.mk

/**
 *
 * SaltStack functions
 *
 */

/**
 * Login to Salt API and return auth token
 *
 * @param url            Salt API server URL
 * @param params         Salt connection params
 */
def getSaltToken(url, params) {
    def http = new com.mirantis.mk.http()
    data = [
        'username': params.creds.username,
        'password': params.creds.password.toString(),
        'eauth': 'pam'
    ]
    authToken = http.sendHttpGetRequest("${url}/login", data, ['Accept': '*/*'])['return'][0]['token']
    return authToken
}

/**
 * Salt connection and context parameters
 *
 * @param url            Salt API server URL
 * @param credentialsID  ID of credentials store entry
 */
def createSaltConnection(url, credentialsId) {
    def common = new com.mirantis.mk.common()
    params = [
        "url": url,
        "credentialsId": credentialsId,
        "authToken": null,
        "creds": common.getPasswordCredentials(credentialsId)
    ]
    params["authToken"] = getSaltToken(url, params)

    return params
}

/**
 * Run action using Salt API
 *
 * @param master   Salt connection object
 * @param client   Client type
 * @param target   Target specification, eg. for compound matches by Pillar
 *                 data: ['expression': 'I@openssh:server', 'type': 'compound'])
 * @param function Function to execute (eg. "state.sls")
 * @param args     Additional arguments to function
 * @param kwargs   Additional key-value arguments to function
 */
@NonCPS
def runSaltCommand(master, client, target, function, batch = null, args = null, kwargs = null) {
    def http = new com.mirantis.mk.http()

    data = [
        'tgt': target.expression,
        'fun': function,
        'client': client,
        'expr_form': target.type,
    ]

    if (batch) {
        data['batch'] = batch
    }

    if (args) {
        data['arg'] = args
    }

    if (kwargs) {
        data['kwarg'] = kwargs
    }

    headers = [
      'X-Auth-Token': "${master.authToken}"
    ]

    return http.sendHttpPostRequest("${master.url}/", data, headers)
}

def getSaltPillar(master, target, pillar) {
    def out = runSaltCommand(master, 'local', target, 'pillar.get', [pillar.replace('.', ':')])
    return out
}

def enforceSaltState(master, target, state, output = false) {
    def run_states
    if (state instanceof String) {
        run_states = state
    } else {
        run_states = state.join(',')
    }

    def out = runSaltCommand(master, 'local', target, 'state.sls', null, [run_states])
    try {
        checkSaltResult(out)
    } finally {
        if (output == true) {
            printSaltResult(out)
        }
    }
    return out
}

def runSaltCmd(master, target, cmd) {
    return runSaltCommand(master, 'local', target, 'cmd.run', null, [cmd])
}

def syncSaltAll(master, target) {
    return runSaltCommand(master, 'local', target, 'saltutil.sync_all')
}

def enforceSaltApply(master, target, output = false) {
    def out = runSaltCommand(master, 'local', target, 'state.highstate')
    try {
        checkSaltResult(out)
    } finally {
        if (output == true) {
            printSaltResult(out)
        }
    }
    return out
}

def generateSaltNodeKey(master, target, host, keysize = 4096) {
    args = [host]
    kwargs = ['keysize': keysize]
    return runSaltCommand(master, 'wheel', target, 'key.gen_accept', null, args, kwargs)
}

def generateSaltNodeMetadata(master, target, host, classes, parameters) {
    args = [host, '_generated']
    kwargs = ['classes': classes, 'parameters': parameters]
    return runSaltCommand(master, 'local', target, 'reclass.node_create', null, args, kwargs)
}

def orchestrateSaltSystem(master, target, orchestrate) {
    return runSaltCommand(master, 'runner', target, 'state.orchestrate', null, [orchestrate])
}

/**
 * Check result for errors and throw exception if any found
 *
 * @param result    Parsed response of Salt API
 */
def checkSaltResult(result) {
    for (entry in result['return']) {
        if (!entry) {
            throw new Exception("Salt API returned empty response: ${result}")
        }
        for (node in entry) {
            for (resource in node.value) {
                if (resource instanceof String || resource.value.result.toString().toBoolean() != true) {
                    throw new Exception("Salt state on node ${node.key} failed: ${node.value}")
                }
            }
        }
    }
}

/**
 * Print Salt run results in human-friendly form
 *
 * @param result        Parsed response of Salt API
 * @param onlyChanges   If true (default), print only changed resources
 * @param raw           Simply pretty print what we have, no additional
 *                      parsing
 */
def printSaltResult(result, onlyChanges = true, raw = false) {
    if (raw == true) {
        print new groovy.json.JsonBuilder(result).toPrettyString()
    } else {
        def out = [:]
        for (entry in result['return']) {
            for (node in entry) {
                out[node.key] = [:]
                for (resource in node.value) {
                    if (resource instanceof String) {
                        out[node.key] = node.value
                    } else if (resource.value.result.toString().toBoolean() == false || resource.value.changes || onlyChanges == false) {
                        out[node.key][resource.key] = resource.value
                    }
                }
            }
        }

        for (node in out) {
            if (node.value) {
                println "Node ${node.key} changes:"
                print new groovy.json.JsonBuilder(node.value).toPrettyString()
            } else {
                println "No changes for node ${node.key}"
            }
        }
    }
}


def runSaltProcessStep(master, tgt, fun, arg = [], batch = null) {
    if (batch) {
        result = runSaltCommand(master, 'local_batch', ['expression': tgt, 'type': 'compound'], fun, String.valueOf(batch), arg)
    }
    else {
        result = runSaltCommand(master, 'local', ['expression': tgt, 'type': 'compound'], fun, batch, arg)
    }
    echo("${result}")
}


def validateFoundationInfra(master) {
    runSaltProcessStep(master, 'I@salt:master', 'cmd.run', ['salt-key'])
    runSaltProcessStep(master, 'I@salt:minion', 'test.version')
    runSaltProcessStep(master, 'I@salt:master', 'cmd.run', ['reclass-salt --top'])
    runSaltProcessStep(master, 'I@reclass:storage', 'reclass.inventory')
    runSaltProcessStep(master, 'I@salt:minion', 'state.show_top')
}


def installFoundationInfra(master) {
    runSaltProcessStep(master, 'I@salt:master', 'state.sls', ['salt.master,reclass'])
    runSaltProcessStep(master, 'I@linux:system', 'saltutil.refresh_pillar')
    runSaltProcessStep(master, 'I@linux:system', 'saltutil.sync_all')
    runSaltProcessStep(master, 'I@linux:system', 'state.sls', ['linux,openssh,salt.minion,ntp'])
}


def installOpenstackMkInfra(master) {
    // Install keepaliveds
    //runSaltProcessStep(master, 'I@keepalived:cluster', 'state.sls', ['keepalived'], 1)
    runSaltProcessStep(master, 'ctl01*', 'state.sls', ['keepalived'])
    runSaltProcessStep(master, 'I@keepalived:cluster', 'state.sls', ['keepalived'])
    // Check the keepalived VIPs
    runSaltProcessStep(master, 'I@keepalived:cluster', 'cmd.run', ['ip a | grep 172.16.10.2'])
    // Install glusterfs
    runSaltProcessStep(master, 'I@glusterfs:server', 'state.sls', ['glusterfs.server.service'])
    //runSaltProcessStep(master, 'I@glusterfs:server', 'state.sls', ['glusterfs.server.setup'], 1)
    runSaltProcessStep(master, 'ctl01*', 'state.sls', ['glusterfs.server.setup'])
    runSaltProcessStep(master, 'ctl02*', 'state.sls', ['glusterfs.server.setup'])
    runSaltProcessStep(master, 'ctl03*', 'state.sls', ['glusterfs.server.setup'])
    runSaltProcessStep(master, 'I@glusterfs:server', 'cmd.run', ['gluster peer status'])
    runSaltProcessStep(master, 'I@glusterfs:server', 'cmd.run', ['gluster volume status'])
    // Install rabbitmq
    runSaltProcessStep(master, 'I@rabbitmq:server', 'state.sls', ['rabbitmq'])
    // Check the rabbitmq status
    runSaltProcessStep(master, 'I@rabbitmq:server', 'cmd.run', ['rabbitmqctl cluster_status'])
    // Install galera
    runSaltProcessStep(master, 'I@galera:master', 'state.sls', ['galera'])
    runSaltProcessStep(master, 'I@galera:slave', 'state.sls', ['galera'])
    // Check galera status
    runSaltProcessStep(master, 'I@galera:master', 'mysql.status')
    runSaltProcessStep(master, 'I@galera:slave', 'mysql.status')
    // Install haproxy
    runSaltProcessStep(master, 'I@haproxy:proxy', 'state.sls', ['haproxy'])
    runSaltProcessStep(master, 'I@haproxy:proxy', 'service.status', ['haproxy'])
    runSaltProcessStep(master, 'I@haproxy:proxy', 'service.restart', ['rsyslog'])
    // Install memcached
    runSaltProcessStep(master, 'I@memcached:server', 'state.sls', ['memcached'])
}


def installOpenstackMkControl(master) {
    // setup keystone service
    //runSaltProcessStep(master, 'I@keystone:server', 'state.sls', ['keystone.server'], 1)
    runSaltProcessStep(master, 'ctl01*', 'state.sls', ['keystone.server'])
    runSaltProcessStep(master, 'I@keystone:server', 'state.sls', ['keystone.server'])
    // populate keystone services/tenants/roles/users
    runSaltProcessStep(master, 'I@keystone:client', 'state.sls', ['keystone.client'])
    runSaltProcessStep(master, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; keystone service-list'])
    // Install glance and ensure glusterfs clusters
    //runSaltProcessStep(master, 'I@glance:server', 'state.sls', ['glance.server'], 1)
    runSaltProcessStep(master, 'ctl01*', 'state.sls', ['glance.server'])
    runSaltProcessStep(master, 'I@glance:server', 'state.sls', ['glance.server'])
    runSaltProcessStep(master, 'I@glance:server', 'state.sls', ['glusterfs.client'])
    // Update fernet tokens before doing request on keystone server
    runSaltProcessStep(master, 'I@keystone:server', 'state.sls', ['keystone.server'])
    // Check glance service
    runSaltProcessStep(master, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; glance image-list'])
    // Install and check nova service
    //runSaltProcessStep(master, 'I@nova:controller', 'state.sls', ['nova'], 1)
    runSaltProcessStep(master, 'ctl01*', 'state.sls', ['nova'])
    runSaltProcessStep(master, 'I@nova:controller', 'state.sls', ['nova'])
    runSaltProcessStep(master, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; nova service-list'])
    // Install and check cinder service
    //runSaltProcessStep(master, 'I@cinder:controller', 'state.sls', ['cinder'], 1)
    runSaltProcessStep(master, 'ctl01*', 'state.sls', ['cinder'])
    runSaltProcessStep(master, 'I@cinder:controller', 'state.sls', ['cinder'])
    runSaltProcessStep(master, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; cinder list'])
    // Install neutron service
    //runSaltProcessStep(master, 'I@neutron:server', 'state.sls', ['neutron'], 1)
    runSaltProcessStep(master, 'ctl01*', 'state.sls', ['neutron'])
    runSaltProcessStep(master, 'I@neutron:server', 'state.sls', ['neutron'])
    runSaltProcessStep(master, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; neutron agent-list'])
    // Install heat service
    //runSaltProcessStep(master, 'I@heat:server', 'state.sls', ['heat'], 1)
    runSaltProcessStep(master, 'ctl01*', 'state.sls', ['heat'])
    runSaltProcessStep(master, 'I@heat:server', 'state.sls', ['heat'])
    runSaltProcessStep(master, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; heat resource-type-list'])
    // Install horizon dashboard
    runSaltProcessStep(master, 'I@horizon:server', 'state.sls', ['horizon'])
    runSaltProcessStep(master, 'I@nginx:server', 'state.sls', ['nginx'])
}


def installOpenstackMkNetwork(master) {
    // Install opencontrail database services
    //runSaltProcessStep(master, 'I@opencontrail:database', 'state.sls', ['opencontrail.database'], 1)
    runSaltProcessStep(master, 'ntw01*', 'state.sls', ['opencontrail.database'])
    runSaltProcessStep(master, 'I@opencontrail:database', 'state.sls', ['opencontrail.database'])
    // Install opencontrail control services
    //runSaltProcessStep(master, 'I@opencontrail:control', 'state.sls', ['opencontrail'], 1)
    runSaltProcessStep(master, 'ntw01*', 'state.sls', ['opencontrail'])
    runSaltProcessStep(master, 'I@opencontrail:control', 'state.sls', ['opencontrail'])
    // Provision opencontrail control services
    runSaltProcessStep(master, 'I@opencontrail:control:id:1', 'cmd.run', ['/usr/share/contrail-utils/provision_control.py --api_server_ip 172.16.10.254 --api_server_port 8082 --host_name ctl01 --host_ip 172.16.10.101 --router_asn 64512 --admin_password workshop --admin_user admin --admin_tenant_name admin --oper add'])
    runSaltProcessStep(master, 'I@opencontrail:control:id:1', 'cmd.run', ['/usr/share/contrail-utils/provision_control.py --api_server_ip 172.16.10.254 --api_server_port 8082 --host_name ctl02 --host_ip 172.16.10.102 --router_asn 64512 --admin_password workshop --admin_user admin --admin_tenant_name admin --oper add'])
    runSaltProcessStep(master, 'I@opencontrail:control:id:1', 'cmd.run', ['/usr/share/contrail-utils/provision_control.py --api_server_ip 172.16.10.254 --api_server_port 8082 --host_name ctl03 --host_ip 172.16.10.103 --router_asn 64512 --admin_password workshop --admin_user admin --admin_tenant_name admin --oper add'])
    // Test opencontrail
    runSaltProcessStep(master, 'I@opencontrail:control', 'cmd.run', ['contrail-status'])
    runSaltProcessStep(master, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; neutron net-list'])
    runSaltProcessStep(master, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; nova net-list'])
}


def installOpenstackMkCompute(master) {
    // Configure compute nodes
    runSaltProcessStep(master, 'I@nova:compute', 'state.apply')
    runSaltProcessStep(master, 'I@nova:compute', 'state.apply')
    // Provision opencontrail virtual routers
    runSaltProcessStep(master, 'I@opencontrail:control:id:1', 'cmd.run', ['/usr/share/contrail-utils/provision_vrouter.py --host_name cmp01 --host_ip 172.16.10.105 --api_server_ip 172.16.10.254 --oper add --admin_user admin --admin_password workshop --admin_tenant_name admin'])
    runSaltProcessStep(master, 'I@nova:compute', 'system.reboot')
}


def installOpenstackMcpInfra(master) {
    // Comment nameserver
    runSaltProcessStep(master, 'I@kubernetes:master', 'cmd.run', ["sed -i 's/nameserver 10.254.0.10/#nameserver 10.254.0.10/g' /etc/resolv.conf"])
    // Install glusterfs
    runSaltProcessStep(master, 'I@glusterfs:server', 'state.sls', ['glusterfs.server.service'])
    // Install keepalived
    runSaltProcessStep(master, 'ctl01*', 'state.sls', ['keepalived'])
    runSaltProcessStep(master, 'I@keepalived:cluster', 'state.sls', ['keepalived'])
    // Check the keepalived VIPs
    runSaltProcessStep(master, 'I@keepalived:cluster', 'cmd.run', ['ip a | grep 172.16.10.2'])
    // Setup glusterfs
    runSaltProcessStep(master, 'ctl01*', 'state.sls', ['glusterfs.server.setup'])
    runSaltProcessStep(master, 'ctl02*', 'state.sls', ['glusterfs.server.setup'])
    runSaltProcessStep(master, 'ctl03*', 'state.sls', ['glusterfs.server.setup'])
    runSaltProcessStep(master, 'I@glusterfs:server', 'cmd.run', ['gluster peer status'])
    runSaltProcessStep(master, 'I@glusterfs:server', 'cmd.run', ['gluster volume status'])
    // Install haproxy
    runSaltProcessStep(master, 'I@haproxy:proxy', 'state.sls', ['haproxy'])
    runSaltProcessStep(master, 'I@haproxy:proxy', 'service.status', ['haproxy'])
    // Install docker
    runSaltProcessStep(master, 'I@docker:host', 'state.sls', ['docker.host'])
    runSaltProcessStep(master, 'I@docker:host', 'cmd.run', ['docker ps'])
    // Install bird
    runSaltProcessStep(master, 'I@bird:server', 'state.sls', ['bird'])
    // Install etcd
    runSaltProcessStep(master, 'I@etcd:server', 'state.sls', ['etcd.server.service'])
    runSaltProcessStep(master, 'I@etcd:server', 'cmd.run', ['etcdctl cluster-health'])
}


def installOpenstackMcpControl(master) {

    // Install Kubernetes pool and Calico
    runSaltProcessStep(master, 'I@kubernetes:pool', 'state.sls', ['kubernetes.pool'])
    runSaltProcessStep(master, 'I@kubernetes:pool', 'cmd.run', ['calicoctl node status'])

    // Setup etcd server
    runSaltProcessStep(master, 'I@kubernetes:master', 'state.sls', ['etcd.server.setup'])

    // Run k8s without master.setup
    runSaltProcessStep(master, 'I@kubernetes:master', 'state.sls', ['kubernetes', 'exclude=kubernetes.master.setup'])

    // Run k8s master setup
    runSaltProcessStep(master, 'I@kubernetes:master', 'state.sls', ['kubernetes.master.setup'], 1)

    // Revert comment nameserver
    runSaltProcessStep(master, 'I@kubernetes:master', 'cmd.run', ["sed -i 's/nameserver 10.254.0.10/#nameserver 10.254.0.10/g' /etc/resolv.conf"])

    // Set route
    runSaltProcessStep(master, 'I@kubernetes:pool', 'cmd.run', ['ip r a 10.254.0.0/16 dev ens4'])

    // Restart kubelet
    runSaltProcessStep(master, 'I@kubernetes:pool', 'service.restart', ['kubelet'])
}


def installOpenstackMcpCompute(master) {
    // Install opencontrail
    runSaltProcessStep(master, 'I@opencontrail:compute', 'state.sls', ['opencontrail'])
    // Reboot compute nodes
    runSaltProcessStep(master, 'I@opencontrail:compute', 'system.reboot')
}


def installStacklightControl(master) {
    runSaltProcessStep(master, 'I@elasticsearch:server', 'state.sls', ['elasticsearch.server'])
    runSaltProcessStep(master, 'I@influxdb:server', 'state.sls', ['influxdb'])
    runSaltProcessStep(master, 'I@kibana:server', 'state.sls', ['kibana.server'])
    runSaltProcessStep(master, 'I@grafana:server', 'state.sls', ['grafana'])
    runSaltProcessStep(master, 'I@nagios:server', 'state.sls', ['nagios'])
    runSaltProcessStep(master, 'I@elasticsearch:client', 'state.sls', ['elasticsearch.client'])
    runSaltProcessStep(master, 'I@kibana:client', 'state.sls', ['kibana.client'])
}


/**
 * Print Salt state run results in human-friendly form
 *
 * @param result        Parsed response of Salt API
 * @param onlyChanges   If true (default), print only changed resources
 *                      parsing
 */
def printSaltStateResult(result, onlyChanges = true) {
    def out = [:]
    for (entry in result['return']) {
        for (node in entry) {
            out[node.key] = [:]
            for (resource in node.value) {
                if (resource instanceof String) {
                    out[node.key] = node.value
                } else if (resource.value.result.toString().toBoolean() == false || resource.value.changes || onlyChanges == false) {
                    out[node.key][resource.key] = resource.value
                }
            }
        }
    }

    for (node in out) {
        if (node.value) {
            println "Node ${node.key} changes:"
            print new groovy.json.JsonBuilder(node.value).toPrettyString()
        } else {
            println "No changes for node ${node.key}"
        }
    }
}

/**
 * Print Salt state run results in human-friendly form
 *
 * @param result        Parsed response of Salt API
 * @param onlyChanges   If true (default), print only changed resources
 *                      parsing
 */
def printSaltCommandResult(result, onlyChanges = true) {
    def out = [:]
    for (entry in result['return']) {
        for (node in entry) {
            out[node.key] = [:]
            for (resource in node.value) {
                out[node.key] = node.value
            }
        }
    }

    for (node in out) {
        if (node.value) {
            println "Node ${node.key} changes:"
            print new groovy.json.JsonBuilder(node.value).toPrettyString()
        } else {
            println "No changes for node ${node.key}"
        }
    }
}
