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
def runSaltCommand(master, client, target, function, args = null, kwargs = null) {
    data = [
        'tgt': target.expression,
        'fun': function,
        'client': client,
        'expr_form': target.type,
    ]

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

    def out = runSaltCommand(master, 'local', target, 'state.sls', [run_states])
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
    return runSaltCommand(master, 'local', target, 'cmd.run', [cmd])
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
    return runSaltCommand(master, 'wheel', target, 'key.gen_accept', args, kwargs)
}

def generateSaltNodeMetadata(master, target, host, classes, parameters) {
    args = [host, '_generated']
    kwargs = ['classes': classes, 'parameters': parameters]
    return runSaltCommand(master, 'local', target, 'reclass.node_create', args, kwargs)
}

def orchestrateSaltSystem(master, target, orchestrate) {
    return runSaltCommand(master, 'runner', target, 'state.orchestrate', [orchestrate])
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

@NonCPS
def getSaltProcess(saltProcess) {

    def process_def = [
        'validate_foundation_infra': [
            [tgt: 'I@salt:master', fun: 'cmd.run', arg: ['salt-key']],
            [tgt: 'I@salt:minion', fun: 'test.version'],
            [tgt: 'I@salt:master', fun: 'cmd.run', arg: ['reclass-salt --top']],
            [tgt: 'I@reclass:storage', fun: 'reclass.inventory'],
            [tgt: 'I@salt:minion', fun: 'state.show_top'],
        ],
        'install_foundation_infra': [
            [tgt: 'I@salt:master', fun: 'state.sls', arg: ['salt.master,reclass']],
            [tgt: 'I@linux:system', fun: 'saltutil.refresh_pillar'],
            [tgt: 'I@linux:system', fun: 'saltutil.sync_all'],
            [tgt: 'I@linux:system', fun: 'state.sls', arg: ['linux,openssh,salt.minion,ntp']],
        ],
        'install_openstack_mk_infra': [
            // Install keepaliveds
            [tgt: 'I@keepalived:cluster', fun: 'state.sls', arg: ['keepalived'], batch:1],
            // Check the keepalived VIPs
            [tgt: 'I@keepalived:cluster', fun: 'cmd.run', arg: ['ip a | grep 172.16.10.2']],
            // Install glusterfs
            [tgt: 'I@glusterfs:server', fun: 'state.sls', arg: ['glusterfs.server.service']],
            [tgt: 'I@glusterfs:server', fun: 'state.sls', arg: ['glusterfs.server.setup'], batch:1],
            [tgt: 'I@glusterfs:server', fun: 'cmd.run', arg: ['gluster peer status']],
            [tgt: 'I@glusterfs:server', fun: 'cmd.run', arg: ['gluster volume status']],
            // Install rabbitmq
            [tgt: 'I@rabbitmq:server', fun: 'state.sls', arg: ['rabbitmq']],
            // Check the rabbitmq status
            [tgt: 'I@rabbitmq:server', fun: 'cmd.run', arg: ['rabbitmqctl cluster_status']],
            // Install galera
            [tgt: 'I@galera:master', fun: 'state.sls', arg: ['galera']],
            [tgt: 'I@galera:slave', fun: 'state.sls', arg: ['galera']],
            // Check galera status
            [tgt: 'I@galera:master', fun: 'mysql.status'],
            [tgt: 'I@galera:slave', fun: 'mysql.status'],
            // Install haproxy
            [tgt: 'I@haproxy:proxy', fun: 'state.sls', arg: ['haproxy']],
            [tgt: 'I@haproxy:proxy', fun: 'service.status', arg: ['haproxy']],
            [tgt: 'I@haproxy:proxy', fun: 'service.restart', arg: ['rsyslog']],
            // Install memcached
            [tgt: 'I@memcached:server', fun: 'state.sls', arg: ['memcached']],
        ],
        'install_openstack_mk_control': [
            // setup keystone service
            [tgt: 'I@keystone:server', fun: 'state.sls', arg: ['keystone.server'], batch:1],
            // populate keystone services/tenants/roles/users
            [tgt: 'I@keystone:client', fun: 'state.sls', arg: ['keystone.client']],
            [tgt: 'I@keystone:server', fun: 'cmd.run', arg: ['. /root/keystonerc; keystone service-list']],
            // Install glance and ensure glusterfs clusters
            [tgt: 'I@glance:server', fun: 'state.sls', arg: ['glance.server'], batch:1],
            [tgt: 'I@glance:server', fun: 'state.sls', arg: ['glusterfs.client']],
            // Update fernet tokens before doing request on keystone server
            [tgt: 'I@keystone:server', fun: 'state.sls', arg: ['keystone.server']],
            // Check glance service
            [tgt: 'I@keystone:server', fun: 'cmd.run', arg: ['. /root/keystonerc; glance image-list']],
            // Install and check nova service
            [tgt: 'I@nova:controller', fun: 'state.sls', arg: ['nova'], batch:1],
            [tgt: 'I@keystone:server', fun: 'cmd.run', arg: ['. /root/keystonerc; nova service-list']],
            // Install and check cinder service
            [tgt: 'I@cinder:controller', fun: 'state.sls', arg: ['cinder'], batch:1],
            [tgt: 'I@keystone:server', fun: 'cmd.run', arg: ['. /root/keystonerc; cinder list']],
            // Install neutron service
            [tgt: 'I@neutron:server', fun: 'state.sls', arg: ['neutron'], batch:1],
            [tgt: 'I@keystone:server', fun: 'cmd.run', arg: ['. /root/keystonerc; neutron agent-list']],
            // Install heat service
            [tgt: 'I@heat:server', fun: 'state.sls', arg: ['heat'], batch:1],
            [tgt: 'I@keystone:server', fun: 'cmd.run', arg: ['. /root/keystonerc; heat resource-type-list']],
            // Install horizon dashboard
            [tgt: 'I@horizon:server', fun: 'state.sls', arg: ['horizon']],
            [tgt: 'I@nginx:server', fun: 'state.sls', arg: ['nginx']],
        ],
        'install_openstack_mk_network': [
            // Install opencontrail database services
            [tgt: 'I@opencontrail:database', fun: 'state.sls', arg: ['opencontrail.database'], batch:1],
            // Install opencontrail control services
            [tgt: 'I@opencontrail:control', fun: 'state.sls', arg: ['opencontrail'], batch:1],
            // Provision opencontrail control services
            [tgt: 'I@opencontrail:control:id:1', fun: 'cmd.run', arg: ['/usr/share/contrail-utils/provision_control.py --api_server_ip 172.16.10.254 --api_server_port 8082 --host_name ctl01 --host_ip 172.16.10.101 --router_asn 64512 --admin_password workshop --admin_user admin --admin_tenant_name admin --oper add']],
            [tgt: 'I@opencontrail:control:id:1', fun: 'cmd.run', arg: ['/usr/share/contrail-utils/provision_control.py --api_server_ip 172.16.10.254 --api_server_port 8082 --host_name ctl02 --host_ip 172.16.10.102 --router_asn 64512 --admin_password workshop --admin_user admin --admin_tenant_name admin --oper add']],
            [tgt: 'I@opencontrail:control:id:1', fun: 'cmd.run', arg: ['/usr/share/contrail-utils/provision_control.py --api_server_ip 172.16.10.254 --api_server_port 8082 --host_name ctl03 --host_ip 172.16.10.103 --router_asn 64512 --admin_password workshop --admin_user admin --admin_tenant_name admin --oper add']],
            // Test opencontrail
            [tgt: 'I@opencontrail:control', fun: 'cmd.run', arg: ['contrail-status']],
            [tgt: 'I@keystone:server', fun: 'cmd.run', arg: ['. /root/keystonerc; neutron net-list']],
            [tgt: 'I@keystone:server', fun: 'cmd.run', arg: ['. /root/keystonerc; nova net-list']],
        ],
        'install_openstack_mk_compute': [
            // Configure compute nodes
            [tgt: 'I@nova:compute', fun: 'state.apply'],
            [tgt: 'I@nova:compute', fun: 'state.apply'],
            // Provision opencontrail virtual routers
            [tgt: 'I@opencontrail:control:id:1', fun: 'cmd.run', arg: ['/usr/share/contrail-utils/provision_vrouter.py --host_name cmp01 --host_ip 172.16.10.105 --api_server_ip 172.16.10.254 --oper add --admin_user admin --admin_password workshop --admin_tenant_name admin']],
            [tgt: 'I@nova:compute', fun: 'system.reboot'],
        ],
        'install_openstack_mcp_infra': [
            // Comment nameserver
            [tgt: 'I@kubernetes:master', fun: 'cmd.run', arg: ["sed -i 's/nameserver 10.254.0.10/#nameserver 10.254.0.10/g' /etc/resolv.conf"]],
            // Install glusterfs
            [tgt: 'I@glusterfs:server', fun: 'state.sls', arg: ['glusterfs.server.service']],
            // Install keepalived
            [tgt: 'I@keepalived:cluster', fun: 'state.sls', arg: ['keepalived'], batch:1],
            // Check the keepalived VIPs
            [tgt: 'I@keepalived:cluster', fun: 'cmd.run', arg: ['ip a | grep 172.16.10.2']],
            // Setup glusterfs
            [tgt: 'I@glusterfs:server', fun: 'state.sls', arg: ['glusterfs.server.setup'], batch:1],
            [tgt: 'I@glusterfs:server', fun: 'cmd.run', arg: ['gluster peer status']],
            [tgt: 'I@glusterfs:server', fun: 'cmd.run', arg: ['gluster volume status']],
            // Install haproxy
            [tgt: 'I@haproxy:proxy', fun: 'state.sls', arg: ['haproxy']],
            [tgt: 'I@haproxy:proxy', fun: 'service.status', arg: ['haproxy']],
            // Install docker
            [tgt: 'I@docker:host', fun: 'state.sls', arg: ['docker.host']],
            [tgt: 'I@docker:host', fun: 'cmd.run', arg: ['docker ps']],
            // Install bird
            [tgt: 'I@bird:server', fun: 'state.sls', arg: ['bird']],
            // Install etcd
            [tgt: 'I@etcd:server', fun: 'state.sls', arg: ['etcd.server.service']],
            [tgt: 'I@etcd:server', fun: 'cmd.run', arg: ['etcdctl cluster-health']],
        ],
        'install_stacklight_control': [
            [tgt: 'I@elasticsearch:server', fun: 'state.sls', arg: ['elasticsearch.server'], batch:1],
            [tgt: 'I@influxdb:server', fun: 'state.sls', arg: ['influxdb'], batch:1],
            [tgt: 'I@kibana:server', fun: 'state.sls', arg: ['kibana.server'], batch:1],
            [tgt: 'I@grafana:server', fun: 'state.sls', arg: ['grafana'], batch:1],
            [tgt: 'I@nagios:server', fun: 'state.sls', arg: ['nagios'], batch:1],
            [tgt: 'I@elasticsearch:client', fun: 'state.sls', arg: ['elasticsearch.client'], batch:1],
            [tgt: 'I@kibana:client', fun: 'state.sls', arg: ['kibana.client'], batch:1],
        ],
        'install_stacklight_client': [
        ]
    ]
    return process_def[saltProcess]
}

/**
 * Run predefined salt process
 *
 * @param master      Salt connection object
 * @param process     Process name to be run
 */
def runSaltProcess(master, process) {

    tasks = getSaltProcess(process)

    for (i = 0; i <tasks.size(); i++) {
        task = tasks[i]
        infoMsg("[Salt master ${master.url}] Task ${task}")
        if (task.containsKey('arg')) {
            result = runSaltCommand(master, 'local', ['expression': task.tgt, 'type': 'compound'], task.fun, task.arg)
        }
        else {
            result = runSaltCommand(master, 'local', ['expression': task.tgt, 'type': 'compound'], task.fun)
        }
        if (task.fun == 'state.sls') {
            printSaltResult(result, false)
        }
        else {
            echo("${result}")
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
