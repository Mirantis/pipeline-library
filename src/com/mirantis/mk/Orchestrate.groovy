package com.mirantis.mk
/**
 * Orchestration functions
 *
*/

/**
 * Function runs Salt states to check infra
 * @param master Salt Connection object or pepperEnv
 * @param extra_tgt Extra target - adds ability to address commands using extra targeting to different clouds, e.g.: salt -C 'I@keystone:server and *ogrudev-deploy-heat-os-ha-ovs-82*' ...
 */
def validateFoundationInfra(master, extra_tgt = '') {
    def salt = new com.mirantis.mk.Salt()

    salt.cmdRun(master, "I@salt:master ${extra_tgt}" ,'salt-key')
    salt.runSaltProcessStep(master, "I@salt:minion ${extra_tgt}", 'test.version')
    salt.cmdRun(master, "I@salt:master ${extra_tgt}" ,'reclass-salt --top')
    salt.runSaltProcessStep(master, "I@reclass:storage ${extra_tgt}", 'reclass.inventory')
    salt.runSaltProcessStep(master, "I@salt:minion ${extra_tgt}", 'state.show_top')
}

def installFoundationInfra(master, staticMgmtNet=false, extra_tgt = '') {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()

    // NOTE(vsaienko) Apply reclass first, it may update cluster model
    // apply linux and salt.master salt.minion states afterwards to make sure
    // correct cluster model is used.
    salt.enforceState([saltId: master, target: "I@salt:master ${extra_tgt}", state: ['reclass']])

    salt.enforceState([saltId: master, target: "I@salt:master ${extra_tgt}", state: ['linux.system']])
    salt.enforceState([saltId: master, target: "I@salt:master ${extra_tgt}", state: ['salt.master'], failOnError: false, read_timeout: 120, retries: 2])
    salt.fullRefresh(master, "* ${extra_tgt}")

    salt.enforceState([saltId: master, target: "I@salt:master ${extra_tgt}", state: ['salt.minion'], failOnError: false, read_timeout: 60, retries: 2])
    salt.enforceState([saltId: master, target: "I@salt:master ${extra_tgt}", state: ['salt.minion']])
    salt.fullRefresh(master, "* ${extra_tgt}")
    salt.enforceState([saltId: master, target: "* ${extra_tgt}", state: ['linux.network.proxy'], failOnError: false, read_timeout: 60, retries: 2])
    // Make sure all repositories are in place before proceeding with package installation from other states
    salt.enforceState([saltId: master, target: "* ${extra_tgt}", state: ['linux.system.repo'], failOnError: false, read_timeout: 60, retries: 2])
    try {
        salt.enforceState([saltId: master, target: "* ${extra_tgt}", state: ['salt.minion.base'], failOnError: false, read_timeout: 60, retries: 2])
        sleep(5)
    } catch (Throwable e) {
        common.warningMsg('Salt state salt.minion.base is not present in the Salt-formula yet.')
    }
    salt.enforceState([saltId: master, target: "* ${extra_tgt}", state: ['linux.system'], retries: 2])
    if (staticMgmtNet) {
        salt.runSaltProcessStep(master, "* ${extra_tgt}", 'cmd.shell', ["salt-call state.sls linux.network; salt-call service.restart salt-minion"], null, true, 60)
    }
    salt.enforceState([saltId: master, target: "I@linux:network:interface ${extra_tgt}", state: ['linux.network.interface'], retries: 2])
    sleep(5)
    salt.enforceState([saltId: master, target: "I@linux:system ${extra_tgt}", state: ['linux', 'openssh', 'ntp', 'rsyslog']])


    salt.enforceState([saltId: master, target: "* ${extra_tgt}", state: ['salt.minion'], failOnError: false, read_timeout: 60, retries: 2])

    sleep(5)

    salt.fullRefresh(master, "* ${extra_tgt}")
    salt.runSaltProcessStep(master, "* ${extra_tgt}", 'mine.update', [], null, true)
    salt.enforceState([saltId: master, target: "* ${extra_tgt}", state: ['linux.network.host']])

    // Install and configure iptables
    salt.enforceStateWithTest([saltId: master, target: "I@iptables:service ${extra_tgt}", state: 'iptables'])

    // Install and configure logrotate
    salt.enforceStateWithTest([saltId: master, target: "I@logrotate:server ${extra_tgt}", state: 'logrotate'])

    // Install and configure auditd
    salt.enforceStateWithTest([saltId: master, target: "I@auditd:service ${extra_tgt}", state: 'auditd'])

    // Install and configure openscap
    salt.enforceStateWithTest([saltId: master, target: "I@openscap:service ${extra_tgt}", state: 'openscap'])
}

def installFoundationInfraOnTarget(master, target, staticMgmtNet=false, extra_tgt = '') {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()

    salt.enforceState([saltId: master, target: "I@salt:master ${extra_tgt}", state: ['reclass'], failOnError: false, read_timeout: 120, retries: 2])
    salt.fullRefresh(master, target)
    salt.enforceState([saltId: master, target: target, state: ['linux.network.proxy'], failOnError: false, read_timeout: 60, retries: 2])
    try {
        salt.enforceState([saltId: master, target: target, state: ['salt.minion.base'], failOnError: false, read_timeout: 60, retries: 2])
        sleep(5)
    } catch (Throwable e) {
        common.warningMsg('Salt state salt.minion.base is not present in the Salt-formula yet.')
    }
    salt.enforceState([saltId: master, target: target, state: ['linux.system'], retries: 2])
    if (staticMgmtNet) {
        salt.runSaltProcessStep(master, target, 'cmd.shell', ["salt-call state.sls linux.network; salt-call service.restart salt-minion"], null, true, 60)
    }
    salt.enforceState([saltId: master, target: target, state: ['salt.minion'], failOnError: false, read_timeout: 60, retries: 2])
    salt.enforceState([saltId: master, target: target, state: ['salt.minion']])
    salt.enforceState([saltId: master, target: target, state: ['linux.network.interface']])
    sleep(5)
    salt.enforceState([saltId: master, target: target, state: ['linux', 'openssh', 'ntp', 'rsyslog']])
    sleep(5)

    salt.fullRefresh(master, target)
    salt.runSaltProcessStep(master, target, 'mine.update', [], null, true)
    salt.enforceState([saltId: master, target: target, state: ['linux.network.host']])
}

def installInfraKvm(master, extra_tgt = '') {
    def common = new com.mirantis.mk.Common()
    def salt = new com.mirantis.mk.Salt()
    def infra_compound = "I@salt:control ${extra_tgt}"
    def minions = []
    def wait_timeout = 10
    def retries = wait_timeout * 30

    salt.fullRefresh(master, "I@linux:system ${extra_tgt}")
    salt.enforceState([saltId: master, target: "I@salt:control ${extra_tgt}", state: ['salt.minion'], failOnError: false, read_timeout: 60, retries: 2])
    salt.enforceState([saltId: master, target: "I@salt:control ${extra_tgt}", state: ['linux.system', 'linux.network', 'ntp', 'rsyslog']])
    salt.enforceState([saltId: master, target: "I@salt:control ${extra_tgt}", state: 'libvirt'])
    salt.enforceState([saltId: master, target: "I@salt:control ${extra_tgt}", state: 'salt.control'])

    common.infoMsg("Building minions list...")
    if (salt.testTarget(master, infra_compound)) {
        // Gathering minions
        for ( infra_node in salt.getMinionsSorted(master, infra_compound) ) {
            def pillar = salt.getPillar(master, infra_node, 'salt:control:cluster')
            if ( !pillar['return'].isEmpty() ) {
                for ( cluster in pillar['return'][0].values() ) {
                    def engine = cluster.values()[0]['engine']
                    def domain = cluster.values()[0]['domain']
                    def node = cluster.values()[0]['node']
                    if ( engine == "virt" ) {
                        def nodes = node.values()
                        if ( !nodes.isEmpty() ) {
                            for ( vm in nodes ) {
                                if ( vm['name'] != null ) {
                                    def vm_fqdn = vm['name'] + '.' + domain
                                    if ( !minions.contains(vm_fqdn) ) {
                                        minions.add(vm_fqdn)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    def minions_compound = minions.join(' or ')

    common.infoMsg("Waiting for next minions to register within ${wait_timeout} minutes: " + minions_compound)
    timeout(time: wait_timeout, unit: 'MINUTES') {
        salt.minionsPresentFromList(master, "I@salt:master ${extra_tgt}", minions, true, null, true, retries, 1)
    }

    common.infoMsg('Waiting for minions to respond')
    timeout(time: wait_timeout, unit: 'MINUTES') {
        salt.minionsReachable(master, "I@salt:master ${extra_tgt}", minions_compound)
    }

    common.infoMsg("All minions are up.")
    salt.fullRefresh(master, "* and not kvm* ${extra_tgt}")

}

def installInfra(master, extra_tgt = '') {
    def common = new com.mirantis.mk.Common()
    def salt = new com.mirantis.mk.Salt()
    def first_target

    // Install glusterfs
    if (salt.testTarget(master, "I@glusterfs:server ${extra_tgt}")) {
        salt.enforceState([saltId: master, target: "I@glusterfs:server ${extra_tgt}", state: 'glusterfs.server.service'])

        salt.enforceState([saltId: master, target: "I@glusterfs:server:role:primary ${extra_tgt}", state: 'glusterfs.server.setup', retries: 5])
        sleep(10)
        salt.cmdRun(master, "I@glusterfs:server ${extra_tgt}", "gluster peer status; gluster volume status")
    }

    // Ensure glusterfs clusters is ready
    salt.enforceStateWithTest([saltId: master, target: "I@glusterfs:client ${extra_tgt}", state: 'glusterfs.client', retries: 2])

    // Install galera
    if (salt.testTarget(master, "I@galera:master ${extra_tgt}") || salt.testTarget(master, "I@galera:slave ${extra_tgt}")) {
        salt.enforceState([saltId: master, target: "I@galera:master ${extra_tgt}", state: 'galera', retries: 2])
        salt.enforceStateWithTest([saltId: master, target: "I@galera:slave ${extra_tgt}", state: 'galera', retries: 2])

        // Check galera status
        salt.runSaltProcessStep(master, "I@galera:master ${extra_tgt}", 'mysql.status')
        if (salt.testTarget(master, "I@galera:slave ${extra_tgt}")) {
            salt.runSaltProcessStep(master, "I@galera:slave ${extra_tgt}", 'mysql.status')
        }

    // If galera is not enabled check if we need to install mysql:server
    } else {

    salt.enforceStateWithTest([saltId: master, target: "I@mysql:server ${extra_tgt}", state: 'mysql.server'])
    salt.enforceStateWithTest([saltId: master, target: "I@mysql:client ${extra_tgt}", state: 'mysql.client'])

    }
    installBackup(master, 'mysql', extra_tgt)

    // Install docker
    if (salt.testTarget(master, "I@docker:host ${extra_tgt}")) {
        salt.enforceState([saltId: master, target: "I@docker:host ${extra_tgt}", state: 'docker.host', retries: 3])
        salt.cmdRun(master, "I@docker:host and I@docker:host:enabled:true ${extra_tgt}", 'docker ps')
    }

    // Install keepalived
    if (salt.testTarget(master, "I@keepalived:cluster ${extra_tgt}")) {
        first_target = salt.getFirstMinion(master, "I@keepalived:cluster ${extra_tgt}")
        salt.enforceState([saltId: master, target: "${first_target} ${extra_tgt}", state: 'keepalived'])
        salt.enforceState([saltId: master, target: "I@keepalived:cluster ${extra_tgt}", state: 'keepalived'])
    }

    // Install rabbitmq
    if (salt.testTarget(master, "I@rabbitmq:server ${extra_tgt}")) {
        salt.enforceState([saltId: master, target: "I@rabbitmq:server ${extra_tgt}", state: 'rabbitmq', retries: 2])

        // Check the rabbitmq status
        common.retry(3,5){
             salt.cmdRun(master, "I@rabbitmq:server ${extra_tgt}", 'rabbitmqctl cluster_status')
        }
    }

    // Install haproxy
    if (salt.testTarget(master, "I@haproxy:proxy ${extra_tgt}")) {
        salt.enforceState([saltId: master, target: "I@haproxy:proxy ${extra_tgt}", state: 'haproxy'])
        salt.runSaltProcessStep(master, "I@haproxy:proxy ${extra_tgt}", 'service.status', ['haproxy'])
        salt.runSaltProcessStep(master, "I@haproxy:proxy ${extra_tgt}", 'service.restart', ['rsyslog'])
    }

    // Install memcached
    salt.enforceStateWithTest([saltId: master, target: "I@memcached:server ${extra_tgt}", state: 'memcached'])

    // Install etcd
    if (salt.testTarget(master, "I@etcd:server ${extra_tgt}")) {
        salt.enforceState([saltId: master, target: "I@etcd:server ${extra_tgt}", state: 'etcd.server.service'])
        common.retry(3,5){
            salt.cmdRun(master, "I@etcd:server ${extra_tgt}", '. /var/lib/etcd/configenv && etcdctl cluster-health')
        }
    }

    // Install redis
    if (salt.testTarget(master, "I@redis:server ${extra_tgt}")) {
        salt.enforceStateWithTest([saltId: master, target: "I@redis:cluster:role:master ${extra_tgt}", state: 'redis'])
        salt.enforceState([saltId: master, target: "I@redis:server ${extra_tgt}", state: 'redis'])
    }

    // Install DNS services
    if (salt.testTarget(master, "I@bind:server ${extra_tgt}")) {
        salt.enforceState([saltId: master, target: "I@bind:server ${extra_tgt}", state: 'bind.server'])
    }
    if (salt.testTarget(master, "I@powerdns:server ${extra_tgt}")) {
        salt.enforceState([saltId: master, target: "I@powerdns:server ${extra_tgt}", state: 'powerdns.server'])
    }

    installBackup(master, 'common', extra_tgt)
}

def installOpenstackInfra(master, extra_tgt = '') {
    def common = new com.mirantis.mk.Common()
    common.warningMsg("You calling orchestrate.installOpenstackInfra(). This function is deprecated please use orchestrate.installInfra() directly")
    installInfra(master, extra_tgt)
}


def installOpenstackControl(master, extra_tgt = '') {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def first_target

    // Install sphinx server
    salt.enforceStateWithTest([saltId: master, target: "I@sphinx:server ${extra_tgt}", state: 'sphinx'])
    // Running minion states in a batch to avoid races related to certificates which are placed on glusterfs
    // Details on races: https://mirantis.jira.com/browse/PROD-25796
    // TODO: Run in parallel when glusterfs for certificates is dropped in cookiecutter
    salt.enforceStateWithTest([saltId: master, target: "I@nginx:server ${extra_tgt}", state: 'salt.minion', batch: 1, failOnError: false, retries: 2])
    salt.enforceStateWithTest([saltId: master, target: "I@nginx:server ${extra_tgt}", state: 'salt.minion', batch: 1, failOnError: true, retries: 1])

    salt.enforceStateWithTest([saltId: master, target: "I@nginx:server ${extra_tgt}", state: 'nginx'])

    // setup keystone service
    if (salt.testTarget(master, "I@keystone:server ${extra_tgt}")) {
        salt.enforceState([saltId: master, target: "I@keystone:server:role:primary ${extra_tgt}", state: 'keystone.server'])
        salt.enforceState([saltId: master, target: "I@keystone:server ${extra_tgt}", state: 'keystone.server'])
        // populate keystone services/tenants/roles/users

        // keystone:client must be called locally
        //salt.runSaltProcessStep(master, 'I@keystone:client', 'cmd.run', ['salt-call state.sls keystone.client'], null, true)
        salt.runSaltProcessStep(master, "I@keystone:server ${extra_tgt}", 'service.restart', ['apache2'])
        sleep(30)
    }
    if (salt.testTarget(master, "I@keystone:client ${extra_tgt}")) {
        first_target = salt.getFirstMinion(master, "I@keystone:client ${extra_tgt}")
        salt.enforceState([saltId: master, target: "${first_target} ${extra_tgt}", state: 'keystone.client'])
        salt.enforceState([saltId: master, target: "I@keystone:client ${extra_tgt}", state: 'keystone.client'])
    }
    if (salt.testTarget(master, "I@keystone:server ${extra_tgt}")) {
        common.retry(3,5){
            salt.cmdRun(master, "I@keystone:server ${extra_tgt}", '. /root/keystonercv3; openstack service list')
        }
    }

    // Install glance
    salt.enforceStateWithTest([saltId: master, target: "I@glance:server:role:primary ${extra_tgt}", state: 'glance.server', testTargetMatcher: "I@glance:server ${extra_tgt}"])
    salt.enforceStateWithTest([saltId: master, target: "I@glance:server ${extra_tgt}", state: 'glance.server'])

    // Check glance service
    if (salt.testTarget(master, "I@glance:server ${extra_tgt}")) {
        common.retry(10,5){
            salt.cmdRun(master, "I@keystone:server ${extra_tgt}", '. /root/keystonercv3; glance image-list')
        }
    }

    // Create glance resources
    salt.enforceStateWithTest([saltId: master, target: "I@glance:client ${extra_tgt}", state: 'glance.client'])

    // Install and check nova service
    // run on first node first
    salt.enforceStateWithTest([saltId: master, target: "I@nova:controller:role:primary ${extra_tgt}", state: 'nova.controller', testTargetMatcher: "I@nova:controller ${extra_tgt}"])
    salt.enforceStateWithTest([saltId: master, target: "I@nova:controller ${extra_tgt}", state: 'nova.controller'])
    if (salt.testTarget(master, "I@keystone:server and I@nova:controller ${extra_tgt}")) {
        common.retry(3,5){
            salt.cmdRun(master, "I@keystone:server ${extra_tgt}", '. /root/keystonercv3; nova service-list')
        }
    }

    // Create nova resources
    salt.enforceStateWithTest([saltId: master, target: "I@nova:client ${extra_tgt}", state: 'nova.client'])

    // Install and check cinder service
    // run on first node first
    salt.enforceStateWithTest([saltId: master, target: "I@cinder:controller:role:primary ${extra_tgt}", state: 'cinder', testTargetMatcher: "I@cinder:controller ${extra_tgt}"])
    salt.enforceStateWithTest([saltId: master, target: "I@cinder:controller ${extra_tgt}", state: 'cinder'])
    if (salt.testTarget(master, "I@keystone:server and I@cinder:controller ${extra_tgt}")) {
        common.retry(3,5){
            salt.cmdRun(master, "I@keystone:server ${extra_tgt}", '. /root/keystonercv3; cinder list')
        }
    }

    // Install neutron service
    // run on first node first
    salt.enforceStateWithTest([saltId: master, target: "I@neutron:server:role:primary ${extra_tgt}", state: 'neutron.server', testTargetMatcher: "I@neutron:server ${extra_tgt}"])
    salt.enforceStateWithTest([saltId: master, target: "I@neutron:server ${extra_tgt}", state: 'neutron.server'])
    if (salt.testTarget(master, "I@keystone:server and I@neutron:server ${extra_tgt}")) {
        common.retry(10,5){
            salt.cmdRun(master, "I@keystone:server ${extra_tgt}",'. /root/keystonercv3; neutron agent-list')
        }
    }

    // Install heat service
    salt.enforceStateWithTest([saltId: master, target: "I@heat:server:role:primary ${extra_tgt}", state: 'heat', testTargetMatcher: "I@heat:server ${extra_tgt}"])
    salt.enforceStateWithTest([saltId: master, target: "I@heat:server ${extra_tgt}", state: 'heat'])
    if (salt.testTarget(master, "I@keystone:server and I@heat:server ${extra_tgt}")) {
        common.retry(10,5){
            salt.cmdRun(master, "I@keystone:server ${extra_tgt}", '. /root/keystonercv3; openstack orchestration resource type list')
        }
    }

    // Restart nova api
    if (salt.testTarget(master, "I@nova:controller ${extra_tgt}")) {
        salt.runSaltProcessStep(master, "I@nova:controller ${extra_tgt}", 'service.restart', ['nova-api'])
    }

    // Install ironic service
    salt.enforceStateWithTest([saltId: master, target: "I@ironic:api:role:primary ${extra_tgt}", state: 'ironic.api', testTargetMatcher: "I@ironic:api ${extra_tgt}"])
    salt.enforceStateWithTest([saltId: master, target: "I@ironic:api ${extra_tgt}", state: 'ironic.api'])

    // Install manila service
    salt.enforceStateWithTest([saltId: master, target: "I@manila:api:role:primary ${extra_tgt}", state: 'manila.api', testTargetMatcher: "I@manila:api ${extra_tgt}"])
    salt.enforceStateWithTest([saltId: master, target: "I@manila:api ${extra_tgt}", state: 'manila.api'])
    salt.enforceStateWithTest([saltId: master, target: "I@manila:scheduler ${extra_tgt}", state: 'manila.scheduler'])

    // Install designate services
    if (salt.testTarget(master, "I@designate:server:enabled ${extra_tgt}")) {
        salt.enforceState([saltId: master, target: "I@designate:server:role:primary ${extra_tgt}", state: 'designate.server'])
        salt.enforceState([saltId: master, target: "I@designate:server ${extra_tgt}", state: 'designate'])
    }

    // Install octavia api service
    salt.enforceStateWithTest([saltId: master, target: "I@octavia:api:role:primary ${extra_tgt}", state: 'octavia.api', testTargetMatcher: "I@octavia:api ${extra_tgt}"])
    salt.enforceStateWithTest([saltId: master, target: "I@octavia:api ${extra_tgt}", state: 'octavia.api'])

    // Install DogTag server service
    salt.enforceStateWithTest([saltId: master, target: "I@dogtag:server:role:master ${extra_tgt}", state: 'dogtag.server', testTargetMatcher: "I@dogtag:server ${extra_tgt}"])
    // Run dogtag state on slaves in serial to avoid races during replications PROD-26810
    salt.enforceStateWithTest([saltId: master, target: "I@dogtag:server ${extra_tgt}", state: 'dogtag.server', batch: 1])

    // Install barbican server service
    salt.enforceStateWithTest([saltId: master, target: "I@barbican:server:role:primary ${extra_tgt}", state: 'barbican.server', testTargetMatcher: "I@barbican:server ${extra_tgt}"])
    salt.enforceStateWithTest([saltId: master, target: "I@barbican:server ${extra_tgt}", state: 'barbican.server'])

    if (salt.testTarget(master, "I@barbican:server ${extra_tgt}")) {
      // Restart apache to make sure we don't have races between barbican-api and barbican-worker on db init.
      // For more info please see PROD-26988
      // The permanent fix is prepared to barbican formula https://gerrit.mcp.mirantis.com/#/c/35097/ but due to rush in release
      // add this workaround here as well.
      // TODO(vsaienko): cleanup once release passed in favor of permanent fix.
      salt.runSaltProcessStep(master, "I@barbican:server ${extra_tgt}", 'service.restart', ['apache2'])
      sleep(30)
    }

    // Install barbican client
    salt.enforceStateWithTest([saltId: master, target: "I@barbican:client ${extra_tgt}", state: 'barbican.client'])

    // Install gnocchi server
    salt.enforceStateWithTest([saltId: master, target: "I@gnocchi:server:role:primary ${extra_tgt}", state: 'gnocchi.server', testTargetMatcher: "I@gnocchi:server ${extra_tgt}"])
    salt.enforceStateWithTest([saltId: master, target: "I@gnocchi:server ${extra_tgt}", state: 'gnocchi.server'])

    // Apply gnocchi client state to create gnocchi archive policies, due to possible
    // races, apply on the first node initially
    if (salt.testTarget(master, "I@gnocchi:client ${extra_tgt}")) {
        first_target = salt.getFirstMinion(master, "I@gnocchi:client ${extra_tgt}")
        salt.enforceState([saltId: master, target: "${first_target} ${extra_tgt}", state: 'gnocchi.client'])
        salt.enforceState([saltId: master, target: "I@gnocchi:client ${extra_tgt}", state: 'gnocchi.client'])
    }

    // Install gnocchi statsd
    if (salt.testTarget(master, "I@gnocchi:statsd ${extra_tgt}")) {
        first_target = salt.getFirstMinion(master, "I@gnocchi:statsd ${extra_tgt}")
        salt.enforceState([saltId: master, target: "${first_target} ${extra_tgt}", state: 'gnocchi.statsd'])
        salt.enforceState([saltId: master, target: "I@gnocchi:statsd ${extra_tgt}", state: 'gnocchi.statsd'])
    }

    // Install panko server
    if (salt.testTarget(master, "I@panko:server ${extra_tgt}")) {
        first_target = salt.getFirstMinion(master, "I@panko:server ${extra_tgt}")
        salt.enforceState([saltId: master, target: "${first_target} ${extra_tgt}", state: 'panko'])
        salt.enforceState([saltId: master, target: "I@panko:server ${extra_tgt}", state: 'panko'])
    }

    // Install ceilometer server
    salt.enforceStateWithTest([saltId: master, target: "I@ceilometer:server:role:primary ${extra_tgt}", state: 'ceilometer', testTargetMatcher: "I@ceilometer:server ${extra_tgt}"])
    salt.enforceStateWithTest([saltId: master, target: "I@ceilometer:server ${extra_tgt}", state: 'ceilometer'])

    // Install aodh server
    if (salt.testTarget(master, "I@aodh:server ${extra_tgt}")) {
        first_target = salt.getFirstMinion(master, "I@aodh:server ${extra_tgt}")
        salt.enforceState([saltId: master, target: "${first_target} ${extra_tgt}", state: 'aodh'])
        salt.enforceState([saltId: master, target: "I@aodh:server ${extra_tgt}", state: 'aodh'])
    }

    // Install horizon dashboard
    salt.enforceStateWithTest([saltId: master, target: "I@horizon:server ${extra_tgt}", state: 'horizon'])
}


def installIronicConductor(master, extra_tgt = ''){
    def salt = new com.mirantis.mk.Salt()

    salt.enforceStateWithTest([saltId: master, target: "I@ironic:conductor ${extra_tgt}", state: 'ironic.conductor'])
    salt.enforceStateWithTest([saltId: master, target: "I@ironic:conductor ${extra_tgt}", state: 'apache'])
    salt.enforceStateWithTest([saltId: master, target: "I@tftpd_hpa:server ${extra_tgt}", state: 'tftpd_hpa'])

    if (salt.testTarget(master, "I@nova:compute ${extra_tgt}")) {
        salt.runSaltProcessStep(master, "I@nova:compute ${extra_tgt}", 'service.restart', ['nova-compute'])
    }

    salt.enforceStateWithTest([saltId: master, target: "I@baremetal_simulator:enabled ${extra_tgt}", state: 'baremetal_simulator'])
    salt.enforceStateWithTest([saltId: master, target: "I@ironic:client ${extra_tgt}", state: 'ironic.client'])
}

def installManilaShare(master, extra_tgt = ''){
    def salt = new com.mirantis.mk.Salt()

    salt.enforceStateWithTest([saltId: master, target: "I@manila:share ${extra_tgt}", state: 'manila.share'])
    salt.enforceStateWithTest([saltId: master, target: "I@manila:data ${extra_tgt}", state: 'manila.data'])
    salt.enforceStateWithTest([saltId: master, target: "I@manila:client ${extra_tgt}", state: 'manila.client'])
}


def installOpenstackNetwork(master, extra_tgt = '') {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()

    //run full neutron state on neutron.gateway - this will install
    //neutron agents in addition to neutron server. Once neutron agents
    //are up neutron resources can be created without hitting the situation when neutron resources are created
    //prior to neutron agents which results in creating ports in non-usable state
    salt.enforceStateWithTest([saltId: master, target: "I@neutron:gateway ${extra_tgt}", state: 'neutron'])

    // Create neutron resources - this step was moved here to ensure that
    //neutron resources are created after neutron agens are up. In this case neutron ports will be in
    //usable state. More information: https://bugs.launchpad.net/neutron/+bug/1399249
    salt.enforceStateWithTest([saltId: master, target: "I@neutron:client ${extra_tgt}", state: 'neutron.client'])

    if (salt.testTarget(master, "I@neutron:gateway ${extra_tgt}")) {
        salt.enforceHighstate(master, "I@neutron:gateway ${extra_tgt}")
    }

    // install octavia manager services
    if (salt.testTarget(master, "I@octavia:manager ${extra_tgt}")) {
        salt.runSaltProcessStep(master, "I@neutron:client ${extra_tgt}", 'mine.update')
        salt.enforceState([saltId: master, target: "I@octavia:manager ${extra_tgt}", state: 'octavia.manager'])
        salt.enforceState([saltId: master, target: "I@octavia:client ${extra_tgt}", state: 'octavia.client'])
    }
}


def installOpenstackCompute(master, extra_tgt = '') {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    // Configure compute nodes
    def compute_compound = "I@nova:compute ${extra_tgt}"
    if (salt.testTarget(master, compute_compound)) {
        // In case if infrastructure nodes are used as nova computes too
        def gluster_compound = "I@glusterfs:server ${extra_tgt}"
        def salt_ca_compound = "I@salt:minion:ca:salt_master_ca ${extra_tgt}"
        // Enforce highstate asynchronous only on compute nodes which are not glusterfs and not salt ca servers
        def hightstateTarget = "${compute_compound} and not ${gluster_compound} and not ${salt_ca_compound}"
        if (salt.testTarget(master, hightstateTarget)) {
            retry(2) {
                salt.enforceHighstate(master, hightstateTarget)
            }
        } else {
            common.infoMsg("No minions matching highstate target found for target ${hightstateTarget}")
        }
        // Iterate through salt ca servers and check if they have compute role
        // TODO: switch to batch once salt 2017.7+ would be used
        common.infoMsg("Checking whether ${salt_ca_compound} minions have ${compute_compound} compound")
        for ( target in salt.getMinionsSorted(master, salt_ca_compound) ) {
            for ( cmp_target in salt.getMinionsSorted(master, compute_compound) ) {
                if ( target == cmp_target ) {
                    // Enforce highstate one by one on salt ca servers which are compute nodes
                    retry(2) {
                        salt.enforceHighstate(master, target)
                    }
                }
            }
        }
        // Iterate through glusterfs servers and check if they have compute role
        // TODO: switch to batch once salt 2017.7+ would be used
        common.infoMsg("Checking whether ${gluster_compound} minions have ${compute_compound} compound")
        for ( target in salt.getMinionsSorted(master, gluster_compound) ) {
            for ( cmp_target in salt.getMinionsSorted(master, compute_compound) ) {
                if ( target == cmp_target ) {
                    // Enforce highstate one by one on glusterfs servers which are compute nodes
                    retry(2) {
                        salt.enforceHighstate(master, target)
                    }
                }
            }
        }
    }

    // Run nova:controller to map cmp with cells
    salt.enforceStateWithTest([saltId: master, target: "I@nova:controller:role:primary ${extra_tgt}", state: 'nova.controller', testTargetMatcher: "I@nova:controller ${extra_tgt}"])
}


def installContrailNetwork(master, extra_tgt = '') {
    def common = new com.mirantis.mk.Common()
    def salt = new com.mirantis.mk.Salt()
    def first_target

    // Install opencontrail database services
    first_target = salt.getFirstMinion(master, "I@opencontrail:database ${extra_tgt}")
    salt.enforceState([saltId: master, target: "${first_target} ${extra_tgt}", state: 'opencontrail.database'])
    salt.enforceState([saltId: master, target: "I@opencontrail:database ${extra_tgt}", state: 'opencontrail.database'])

    // Install opencontrail control services
    first_target = salt.getFirstMinion(master, "I@opencontrail:control ${extra_tgt}")
    salt.enforceStateWithExclude([saltId: master, target: "${first_target} ${extra_tgt}", state: "opencontrail", excludedStates: "opencontrail.client"])
    salt.enforceStateWithExclude([saltId: master, target: "I@opencontrail:control ${extra_tgt}", state: "opencontrail", excludedStates: "opencontrail.client"])
    first_target = salt.getFirstMinion(master, "I@opencontrail:collector ${extra_tgt}")
    salt.enforceStateWithExclude([saltId: master, target: "${first_target} ${extra_tgt}", state: "opencontrail", excludedStates: "opencontrail.client"])
    salt.enforceStateWithExclude([saltId: master, target: "I@opencontrail:collector ${extra_tgt}", state: "opencontrail", excludedStates: "opencontrail.client"])

    salt.enforceStateWithTest([saltId: master, target: "( I@opencontrail:control or I@opencontrail:collector ) ${extra_tgt}", state: 'docker.client', testTargetMatcher: "I@docker:client and I@opencontrail:control ${extra_tgt}"])
}


def checkContrailApiReadiness(master, extra_tgt = '') {
    def common = new com.mirantis.mk.Common()
    def salt = new com.mirantis.mk.Salt()

    def apiCheckResult = salt.getReturnValues(salt.runSaltProcessStep(master, "I@opencontrail:control:role:primary ${extra_tgt}", 'contrail_health.get_api_status', ['wait_for=900', 'tries=50']))
    if (!apiCheckResult){
        throw new Exception("Contrail is not working after deployment: contrail-api service is not in healthy state")
    } else {
        common.infoMsg('Contrail API is ready to service requests')
    }
}


def installContrailCompute(master, extra_tgt = '') {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    // Configure compute nodes
    // Provision opencontrail control services
    salt.enforceState([saltId: master, target: "I@opencontrail:database:id:1 ${extra_tgt}", state: 'opencontrail.client'])
    // Provision opencontrail virtual routers

    // Generate script /usr/lib/contrail/if-vhost0 for up vhost0
    if (salt.testTarget(master, "I@opencontrail:compute ${extra_tgt}")) {
        salt.enforceStateWithExclude([saltId: master, target: "I@opencontrail:compute ${extra_tgt}", state: "opencontrail", excludedStates: "opencontrail.client"])
    }

    if (salt.testTarget(master, "I@nova:compute ${extra_tgt}")) {
        salt.cmdRun(master, "I@nova:compute ${extra_tgt}", 'exec 0>&-; exec 1>&-; exec 2>&-; nohup bash -c "ip link | grep vhost && echo no_reboot || reboot & "', false)
    }

    sleep(300)
    salt.enforceStateWithTest([saltId: master, target: "I@opencontrail:compute ${extra_tgt}", state: 'opencontrail.client'])
    salt.enforceStateWithTest([saltId: master, target: "I@opencontrail:compute ${extra_tgt}", state: 'opencontrail'])
}


def installKubernetesInfra(master, extra_tgt = '') {
    def common = new com.mirantis.mk.Common()
    common.warningMsg("You calling orchestrate.installKubernetesInfra(). This function is deprecated please use orchestrate.installInfra() directly")
    installInfra(master, extra_tgt)
}


def installKubernetesControl(master, extra_tgt = '') {
    def salt = new com.mirantis.mk.Salt()
    def first_target
    salt.fullRefresh(master, "* ${extra_tgt}")

    // Bootstrap all nodes
    salt.enforceState([saltId: master, target: "I@kubernetes:master ${extra_tgt}", state: 'linux'])
    salt.enforceState([saltId: master, target: "I@kubernetes:master ${extra_tgt}", state: 'salt.minion'])
    salt.enforceState([saltId: master, target: "I@kubernetes:master ${extra_tgt}", state: ['openssh', 'ntp']])

    // Create and distribute SSL certificates for services using salt state
    salt.enforceState([saltId: master, target: "I@kubernetes:master ${extra_tgt}", state: 'salt.minion.cert'])

    // Install docker
    salt.enforceStateWithTest([saltId: master, target: "I@docker:host ${extra_tgt}", state: 'docker.host'])

     // If network engine is not opencontrail, run addons state for kubernetes
    if (!salt.getReturnValues(salt.getPillar(master, "I@kubernetes:master ${extra_tgt}", 'kubernetes:master:network:opencontrail:enabled'))) {
        salt.enforceState([saltId: master, target: "I@kubernetes:master ${extra_tgt}", state: 'kubernetes.master.kube-addons'])
    }

    // Install Kubernetes pool and Calico
    salt.enforceState([saltId: master, target: "I@kubernetes:master ${extra_tgt}", state: 'kubernetes.pool'])

    if (salt.testTarget(master, "I@etcd:server:setup ${extra_tgt}")) {
        // Setup etcd server
        first_target = salt.getFirstMinion(master, "I@kubernetes:master ${extra_tgt}")
        salt.enforceState([saltId: master, target: "${first_target} ${extra_tgt}", state: 'etcd.server.setup'])
    }

    // Run k8s master at *01* to simplify namespaces creation
    first_target = salt.getFirstMinion(master, "I@kubernetes:master ${extra_tgt}")

    // If network engine is opencontrail, run master state for kubernetes without kube-addons
    // The kube-addons state will be called later only in case of opencontrail
    if (salt.getReturnValues(salt.getPillar(master, "I@kubernetes:master ${extra_tgt}", 'kubernetes:master:network:opencontrail:enabled'))) {
        // Run k8s on first node without master.setup and master.kube-addons
        salt.enforceStateWithExclude([saltId: master, target: "${first_target} ${extra_tgt}", state: "kubernetes.master", excludedStates: "kubernetes.master.setup,kubernetes.master.kube-addons"])
        // Run k8s without master.setup and master.kube-addons
        salt.enforceStateWithExclude([saltId: master, target: "I@kubernetes:master ${extra_tgt}", state: "kubernetes", excludedStates: "kubernetes.master.setup,kubernetes.master.kube-addons,kubernetes.client"])
    } else {
        // Run k8s on first node without master.setup and master.kube-addons
        salt.enforceStateWithExclude([saltId: master, target: "${first_target} ${extra_tgt}", state: "kubernetes.master", excludedStates: "kubernetes.master.setup,kubernetes.master.kube-addons"])
        // Run k8s without master.setup
        salt.enforceStateWithExclude([saltId: master, target: "I@kubernetes:master ${extra_tgt}", state: "kubernetes", excludedStates: "kubernetes.master.setup,kubernetes.client,kubernetes.control*"])
        // Run k8s control on first node only
        salt.enforceState([saltId: master, target: "${first_target} ${extra_tgt}", state: 'kubernetes.control'])
    }

    // Run k8s master setup
    salt.enforceState([saltId: master, target: "I@kubernetes:master ${extra_tgt}", state: 'kubernetes.master.setup'])

    // Restart kubelet
    salt.runSaltProcessStep(master, "I@kubernetes:master ${extra_tgt}", 'service.restart', ['kubelet'])
}


def installKubernetesCompute(master, extra_tgt = '') {
    def salt = new com.mirantis.mk.Salt()
    salt.fullRefresh(master, "*")

    // Bootstrap all nodes
    salt.enforceState([saltId: master, target: "I@kubernetes:pool and not I@kubernetes:master ${extra_tgt}", state: 'linux'])
    salt.enforceState([saltId: master, target: "I@kubernetes:pool and not I@kubernetes:master ${extra_tgt}", state: 'salt.minion'])
    salt.enforceState([saltId: master, target: "I@kubernetes:pool and not I@kubernetes:master ${extra_tgt}", state: ['openssh', 'ntp']])

    // Create and distribute SSL certificates for services using salt state
    salt.enforceState([saltId: master, target: "I@kubernetes:pool and not I@kubernetes:master ${extra_tgt}", state: 'salt.minion.cert'])

    // Install docker
    salt.enforceStateWithTest([saltId: master, target: "I@docker:host ${extra_tgt}", state: 'docker.host'])

    // Install Kubernetes and Calico
    salt.enforceState([saltId: master, target: "I@kubernetes:pool and not I@kubernetes:master ${extra_tgt}", state: 'kubernetes.pool'])
    salt.runSaltProcessStep(master, "I@kubernetes:pool and not I@kubernetes:master ${extra_tgt}", 'service.restart', ['kubelet'])
}

def installKubernetesClient(master, extra_tgt = '') {
    def salt = new com.mirantis.mk.Salt()

    // Install kubernetes client
    salt.enforceStateWithTest([saltId: master, target: "I@kubernetes:client ${extra_tgt}", state: 'kubernetes.client'])
}


def installDockerSwarm(master, extra_tgt = '') {
    def salt = new com.mirantis.mk.Salt()

    //Install and Configure Docker
    if (salt.testTarget(master, "I@docker:swarm ${extra_tgt}")) {
        salt.enforceState([saltId: master, target: "I@docker:swarm ${extra_tgt}", state: 'docker.host'])
        salt.enforceState([saltId: master, target: "I@docker:swarm:role:master ${extra_tgt}", state: 'docker.swarm'])
        salt.enforceState([saltId: master, target: "I@docker:swarm ${extra_tgt}", state: 'salt.minion.grains'])
        salt.runSaltProcessStep(master, "I@docker:swarm ${extra_tgt}", 'mine.update')
        salt.runSaltProcessStep(master, "I@docker:swarm ${extra_tgt}", 'saltutil.refresh_modules')
        sleep(5)
        salt.enforceState([saltId: master, target: "I@docker:swarm:role:master ${extra_tgt}", state: 'docker.swarm'])
        salt.enforceStateWithTest([saltId: master, target: "I@docker:swarm:role:manager ${extra_tgt}", state: 'docker.swarm'])
        sleep(10)
        salt.cmdRun(master, "I@docker:swarm:role:master ${extra_tgt}", 'docker node ls')
    }
}

// Setup addons for kubernetes - For OpenContrail network engine
// Use after compute nodes are ready, because K8s addons like DNS should be placed on cmp nodes
def setupKubeAddonForContrail(master, extra_tgt = '') {
    def salt = new com.mirantis.mk.Salt()

    if (salt.getPillar(master, "I@kubernetes:master ${extra_tgt}", 'kubernetes:master:network:opencontrail:enabled')){
        // Setup  Addons for Kubernetes only in case of OpenContrail is used as neteork engine
        salt.enforceState([saltId: master, target: "I@kubernetes:master ${extra_tgt}", state: 'kubernetes.master.kube-addons'])
    }
}

def installCicd(master, extra_tgt = '') {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def gerrit_compound = "I@gerrit:client ${extra_tgt}"
    def jenkins_compound = "I@jenkins:client ${extra_tgt}"

    salt.fullRefresh(master, gerrit_compound)
    salt.fullRefresh(master, jenkins_compound)

    // Temporary exclude cfg node from docker.client state (PROD-24934)
    def dockerClientExclude = !salt.getPillar(master, 'I@salt:master', 'docker:client:stack:jenkins').isEmpty() ? 'and not I@salt:master' : ''
    // Pull images first if any
    def listCIMinions = salt.getMinions(master, "* ${dockerClientExclude} ${extra_tgt}")
    for (int i = 0; i < listCIMinions.size(); i++) {
        if (!salt.getReturnValues(salt.getPillar(master, listCIMinions[i], 'docker:client:images')).isEmpty()) {
            salt.enforceStateWithTest([saltId: master, target: listCIMinions[i], state: 'docker.client.images', retries: 2])
        }
    }
    salt.enforceStateWithTest([saltId: master, target: "I@docker:swarm:role:master and I@jenkins:client ${dockerClientExclude} ${extra_tgt}", state: 'docker.client', retries: 2])

    // API timeout in minutes
    def wait_timeout = 10

    // Gerrit
    def gerrit_master_url = salt.getPillar(master, gerrit_compound, '_param:gerrit_master_url')

    if(!gerrit_master_url['return'].isEmpty()) {
      gerrit_master_url = gerrit_master_url['return'][0].values()[0]
    } else {
      gerrit_master_url = ''
    }

    if (gerrit_master_url != '') {
      common.infoMsg('Gerrit master url "' + gerrit_master_url + '" retrieved at _param:gerrit_master_url')
    } else {

      common.infoMsg('Gerrit master url could not be retrieved at _param:gerrit_master_url. Falling back to gerrit pillar')

      def gerrit_host
      def gerrit_http_port
      def gerrit_http_scheme
      def gerrit_http_prefix

      def host_pillar = salt.getPillar(master, gerrit_compound, 'gerrit:client:server:host')
      gerrit_host = salt.getReturnValues(host_pillar)

      def port_pillar = salt.getPillar(master, gerrit_compound, 'gerrit:client:server:http_port')
      gerrit_http_port = salt.getReturnValues(port_pillar)

      def scheme_pillar = salt.getPillar(master, gerrit_compound, 'gerrit:client:server:protocol')
      gerrit_http_scheme = salt.getReturnValues(scheme_pillar)

      def prefix_pillar = salt.getPillar(master, gerrit_compound, 'gerrit:client:server:url_prefix')
      gerrit_http_prefix = salt.getReturnValues(prefix_pillar)

      gerrit_master_url = gerrit_http_scheme + '://' + gerrit_host + ':' + gerrit_http_port + gerrit_http_prefix

    }

    timeout(wait_timeout) {
      common.infoMsg('Waiting for Gerrit to come up..')
      def check_gerrit_cmd = 'while true; do curl -sI -m 3 -o /dev/null -w' + " '" + '%{http_code}' + "' " + gerrit_master_url + '/ | grep 200 && break || sleep 1; done'
      salt.cmdRun(master, gerrit_compound, 'timeout ' + (wait_timeout*60+3) + ' /bin/sh -c -- ' + '"' + check_gerrit_cmd + '"')
    }

    // Jenkins
    def jenkins_master_host = salt.getReturnValues(salt.getPillar(master, jenkins_compound, '_param:jenkins_master_host'))
    def jenkins_master_port = salt.getReturnValues(salt.getPillar(master, jenkins_compound, '_param:jenkins_master_port'))
    def jenkins_master_protocol = salt.getReturnValues(salt.getPillar(master, jenkins_compound, '_param:jenkins_master_protocol'))
    def jenkins_master_url_prefix = salt.getReturnValues(salt.getPillar(master, jenkins_compound, '_param:jenkins_master_url_prefix'))
    jenkins_master_url = "${jenkins_master_protocol}://${jenkins_master_host}:${jenkins_master_port}${jenkins_master_url_prefix}"

    timeout(wait_timeout) {
      common.infoMsg('Waiting for Jenkins to come up..')
      def check_jenkins_cmd = 'while true; do curl -sI -m 3 -o /dev/null -w' + " '" + '%{http_code}' + "' " + jenkins_master_url + '/whoAmI/ | grep 200 && break || sleep 1; done'
      salt.cmdRun(master, jenkins_compound, 'timeout ' + (wait_timeout*60+3) + ' /bin/sh -c -- ' + '"' + check_jenkins_cmd + '"')
    }

    salt.enforceStateWithTest([saltId: master, target: "I@openldap:client ${extra_tgt}", state: 'openldap', retries: 2])

    salt.enforceStateWithTest([saltId: master, target: "I@python:environment ${extra_tgt}", state: 'python'])

    withEnv(['ASK_ON_ERROR=false']){
        retry(2){
            try{
                salt.enforceStateWithTest([saltId: master, target: "I@gerrit:client ${extra_tgt}", state: 'gerrit'])
            }catch(e){
                salt.fullRefresh(master, "I@gerrit:client ${extra_tgt}")
                throw e //rethrow for retry handler
            }
        }
        retry(2){
            try{
                salt.enforceStateWithTest([saltId: master, target: "I@jenkins:client ${extra_tgt}", state: 'jenkins'])
            }catch(e){
                salt.fullRefresh(master, "I@jenkins:client ${extra_tgt}")
                throw e //rethrow for retry handler
            }
        }
    }
}


def installStacklight(master, extra_tgt = '') {
    def common = new com.mirantis.mk.Common()
    def salt = new com.mirantis.mk.Salt()
    def step_retries_wait = 20
    def step_retries = 15
    def first_target

    // Install core services for K8S environments:
    // HAProxy, Nginx and glusterFS clients.
    // glusterFS clients must be first one, since nginx should store certs on it.
    // In case of OpenStack, those are already installed
    if (common.checkContains('STACK_INSTALL', 'k8s')) {
        salt.enforceStateWithTest([saltId: master, target: "I@glusterfs:client ${extra_tgt}", state: 'glusterfs.client', retries: 2])
        salt.enforceState([saltId: master, target: "I@nginx:server ${extra_tgt}", state: 'salt.minion.cert', retries: 3])

        salt.enforceState([saltId: master, target: "I@haproxy:proxy ${extra_tgt}", state: 'haproxy'])
        salt.runSaltProcessStep(master, "I@haproxy:proxy ${extra_tgt}", 'service.status', ['haproxy'])

        salt.enforceStateWithTest([saltId: master, target: "I@nginx:server ${extra_tgt}", state: 'nginx'])
    }

    // Install MongoDB for Alerta
    if (salt.testTarget(master, "I@mongodb:server ${extra_tgt}")) {
        salt.enforceState([saltId: master, target: "I@mongodb:server ${extra_tgt}", state: 'mongodb.server'])

        // Initialize mongodb replica set
        salt.enforceState([saltId: master, target: "I@mongodb:server ${extra_tgt}", state: 'mongodb.cluster', retries: 5, retries_wait: 20])
    }

    //Install Telegraf
    salt.enforceState([saltId: master, target: "( I@telegraf:agent or I@telegraf:remote_agent ) ${extra_tgt}", state: 'telegraf'])

    // Install Prometheus exporters
    salt.enforceStateWithTest([saltId: master, target: "I@prometheus:exporters ${extra_tgt}", state: 'prometheus'])

    //Install Elasticsearch and Kibana
    if (salt.testTarget(master, "I@elasticsearch:server:enabled:true ${extra_tgt}")) {
        first_target = salt.getFirstMinion(master, "I@elasticsearch:server:enabled:true ${extra_tgt}")
        salt.enforceState([saltId: master, target: "${first_target} ${extra_tgt}", state: 'elasticsearch.server'])
    }
    salt.enforceStateWithTest([saltId: master, target: "I@elasticsearch:server:enabled:true ${extra_tgt}", state: 'elasticsearch.server'])
    if (salt.testTarget(master, "I@kibana:server:enabled:true ${extra_tgt}")) {
        first_target = salt.getFirstMinion(master, "I@kibana:server:enabled:true ${extra_tgt}")
        salt.enforceState([saltId: master, target: "${first_target} ${extra_tgt}", state: 'kibana.server'])
    }
    salt.enforceStateWithTest([saltId: master, target: "I@kibana:server:enabled:true ${extra_tgt}", state: 'kibana.server'])

    // Check ES health cluster status
    def pillar = salt.getReturnValues(salt.getPillar(master, "I@elasticsearch:client ${extra_tgt}", 'elasticsearch:client:server:host'))
    def elasticsearch_vip
    if(pillar) {
        elasticsearch_vip = pillar
    } else {
        common.errorMsg('[ERROR] Elasticsearch VIP address could not be retrieved')
    }

    pillar = salt.getReturnValues(salt.getPillar(master, "I@elasticsearch:client ${extra_tgt}", 'elasticsearch:client:server:port'))
    def elasticsearch_port
    if(pillar) {
        elasticsearch_port = pillar
    } else {
        common.errorMsg('[ERROR] Elasticsearch VIP port could not be retrieved')
    }

    pillar = salt.getReturnValues(salt.getPillar(master, "I@elasticsearch:client ${extra_tgt}", 'elasticsearch:client:server:scheme'))
    def elasticsearch_scheme
    if(pillar) {
        elasticsearch_scheme = pillar
        common.infoMsg("[INFO] Using elasticsearch scheme: ${elasticsearch_scheme}")
    } else {
        common.infoMsg('[INFO] No pillar with Elasticsearch server scheme, using scheme: http')
        elasticsearch_scheme = "http"
    }

    common.retry(step_retries,step_retries_wait) {
        common.infoMsg('Waiting for Elasticsearch to become green..')
        salt.cmdRun(master, "I@elasticsearch:client ${extra_tgt}", "curl -skf ${elasticsearch_scheme}://${elasticsearch_vip}:${elasticsearch_port}/_cat/health | awk '{print \$4}' | grep green")
    }

    salt.enforceState([saltId: master, target: "I@elasticsearch:client ${extra_tgt}", state: 'elasticsearch.client', retries: step_retries, retries_wait: step_retries_wait])

    salt.enforceState([saltId: master, target: "I@kibana:client ${extra_tgt}", state: 'kibana.client', retries: step_retries, retries_wait: step_retries_wait])

    //Install InfluxDB
    if (salt.testTarget(master, "I@influxdb:server ${extra_tgt}")) {
        first_target = salt.getFirstMinion(master, "I@influxdb:server ${extra_tgt}")
        salt.enforceState([saltId: master, target: "${first_target} ${extra_tgt}", state: 'influxdb'])
        salt.enforceState([saltId: master, target: "I@influxdb:server ${extra_tgt}", state: 'influxdb'])
    }

    // Install service for the log collection
    if (salt.testTarget(master, "I@fluentd:agent ${extra_tgt}")) {
        salt.enforceState([saltId: master, target: "I@fluentd:agent ${extra_tgt}", state: 'fluentd'])
    } else {
        salt.enforceState([saltId: master, target: "I@heka:log_collector ${extra_tgt}", state: 'heka.log_collector'])
    }

    // Install heka ceilometer collector
    if (salt.testTarget(master, "I@heka:ceilometer_collector:enabled ${extra_tgt}")) {
        salt.enforceState([saltId: master, target: "I@heka:ceilometer_collector:enabled ${extra_tgt}", state: 'heka.ceilometer_collector'])
        salt.runSaltProcessStep(master, "I@heka:ceilometer_collector:enabled ${extra_tgt}", 'service.restart', ['ceilometer_collector'], null, true)
    }

    // Install galera
    if (common.checkContains('STACK_INSTALL', 'k8s')) {
        salt.enforceState([saltId: master, target: "I@galera:master ${extra_tgt}", state: 'galera', retries: 2])
        salt.enforceState([saltId: master, target: "I@galera:slave ${extra_tgt}", state: 'galera', retries: 2])

        // Check galera status
        salt.runSaltProcessStep(master, "I@galera:master ${extra_tgt}", 'mysql.status')
        salt.runSaltProcessStep(master, "I@galera:slave ${extra_tgt}", 'mysql.status')
    }

    //Collect Grains
    salt.enforceState([saltId: master, target: "I@salt:minion ${extra_tgt}", state: 'salt.minion.grains'])
    salt.runSaltProcessStep(master, "I@salt:minion ${extra_tgt}", 'saltutil.refresh_modules')
    salt.runSaltProcessStep(master, "I@salt:minion ${extra_tgt}", 'mine.update')
    sleep(5)

    // Configure Prometheus in Docker Swarm
    salt.enforceState([saltId: master, target: "I@docker:swarm and I@prometheus:server ${extra_tgt}", state: 'prometheus'])

    //Configure Remote Collector in Docker Swarm for Openstack deployments
    if (salt.testTarget(master, "I@heka:remote_collector ${extra_tgt}")) {
        salt.enforceState([saltId: master, target: "I@docker:swarm and I@prometheus:server ${extra_tgt}", state: 'heka.remote_collector', failOnError: false])
    }

    // Launch containers
    // Pull images first if any
    def listMinions = salt.getMinions(master, "I@docker:swarm and I@prometheus:server ${extra_tgt}")
    for (int i = 0; i < listMinions.size(); i++) {
        if (!salt.getReturnValues(salt.getPillar(master, listMinions[i], 'docker:client:images')).isEmpty()) {
            salt.enforceState([saltId: master, target: listMinions[i], state: 'docker.client.images', retries: 2])
        }
    }
    salt.enforceState([saltId: master, target: "I@docker:swarm:role:master and I@prometheus:server ${extra_tgt}", state: 'docker.client'])
    salt.runSaltProcessStep(master, "I@docker:swarm and I@prometheus:server ${extra_tgt}", 'dockerng.ps')

    //Install Prometheus LTS
    salt.enforceStateWithTest([saltId: master, target: "I@prometheus:relay ${extra_tgt}", state: 'prometheus'])

    // Install sphinx server
    salt.enforceStateWithTest([saltId: master, target: "I@sphinx:server ${extra_tgt}", state: 'sphinx'])

    //Configure Grafana
    pillar = salt.getPillar(master, "ctl01* ${extra_tgt}", '_param:stacklight_monitor_address')
    common.prettyPrint(pillar)

    def stacklight_vip
    if(!pillar['return'].isEmpty()) {
        stacklight_vip = pillar['return'][0].values()[0]
    } else {
        common.errorMsg('[ERROR] Stacklight VIP address could not be retrieved')
    }

    common.infoMsg("Waiting for service on http://${stacklight_vip}:15013/ to start")
    sleep(120)

    salt.enforceState([saltId: master, target: "I@grafana:client ${extra_tgt}", state: 'grafana.client', retries: step_retries, retries_wait: step_retries_wait])
}

def installStacklightv1Control(master, extra_tgt = '') {
    def salt = new com.mirantis.mk.Salt()

    // infra install
    // Install the StackLight backends
    salt.enforceState([saltId: master, target: "*01* and I@elasticsearch:server ${extra_tgt}", state: 'elasticsearch.server'])
    salt.enforceState([saltId: master, target: "I@elasticsearch:server ${extra_tgt}", state: 'elasticsearch.server'])

    salt.enforceState([saltId: master, target: "*01* and I@influxdb:server ${extra_tgt}", state: 'influxdb'])
    salt.enforceState([saltId: master, target: "I@influxdb:server ${extra_tgt}", state: 'influxdb'])

    salt.enforceState([saltId: master, target: "*01* and I@kibana:server ${extra_tgt}", state: 'kibana.server'])
    salt.enforceState([saltId: master, target: "I@kibana:server ${extra_tgt}", state: 'kibana.server'])

    salt.enforceState([saltId: master, target: "*01* and I@grafana:server ${extra_tgt}",state: 'grafana.server'])
    salt.enforceState([saltId: master, target: "I@grafana:server ${extra_tgt}", state: 'grafana.server'])

    def alarming_service_pillar = salt.getPillar(master, "mon*01* ${extra_tgt}", '_param:alarming_service')
    def alarming_service = alarming_service_pillar['return'][0].values()[0]

    switch (alarming_service) {
        case 'sensu':
            // Update Sensu
            salt.enforceState([saltId: master, target: "I@sensu:server and I@rabbitmq:server ${extra_tgt}", state: 'rabbitmq'])
            salt.enforceState([saltId: master, target: "I@redis:cluster:role:master ${extra_tgt}", state: 'redis'])
            salt.enforceState([saltId: master, target: "I@redis:server ${extra_tgt}", state: 'redis'])
            salt.enforceState([saltId: master, target: "I@sensu:server ${extra_tgt}", state: 'sensu'])
        default:
            // Update Nagios
            salt.enforceState([saltId: master, target: "I@nagios:server ${extra_tgt}", state: 'nagios.server'])
            // Stop the Nagios service because the package starts it by default and it will
            // started later only on the node holding the VIP address
            salt.runSaltProcessStep(master, "I@nagios:server ${extra_tgt}", 'service.stop', ['nagios3'], null, true)
    }

    salt.enforceState([saltId: master, target: "I@elasticsearch:client ${extra_tgt}", state: 'elasticsearch.client.service'])
    salt.enforceState([saltId: master, target: "I@kibana:client ${extra_tgt}", state: 'kibana.client'])

    sleep(10)
}

def installStacklightv1Client(master, extra_tgt = '') {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()

    salt.cmdRun(master, "I@elasticsearch:client ${extra_tgt}", 'salt-call state.sls elasticsearch.client')
    // salt.enforceState([saltId: master, target: "I@elasticsearch:client", state: 'elasticsearch.client", true])
    salt.cmdRun(master, "I@kibana:client ${extra_tgt}", 'salt-call state.sls kibana.client')
    // salt.enforceState([saltId: master, target: "I@kibana:client", state: 'kibana.client", true])

    // Install collectd, heka and sensu services on the nodes, this will also
    // generate the metadata that goes into the grains and eventually into Salt Mine
    salt.enforceState([saltId: master, target: "* ${extra_tgt}", state: 'collectd'])
    salt.enforceState([saltId: master, target: "* ${extra_tgt}", state: 'salt.minion', retries: 2])
    salt.enforceState([saltId: master, target: "* ${extra_tgt}", state: 'heka'])

    // Gather the Grafana metadata as grains
    salt.enforceState([saltId: master, target: "I@grafana:collector ${extra_tgt}", state: 'grafana.collector'])

    // Update Salt Mine
    salt.enforceState([saltId: master, target: "* ${extra_tgt}", state: 'salt.minion.grains'])
    salt.runSaltProcessStep(master, "* ${extra_tgt}", 'saltutil.refresh_modules')
    salt.runSaltProcessStep(master, "* ${extra_tgt}", 'mine.update')

    sleep(5)

    // Update Heka
    salt.enforceState([saltId: master, target: "( I@heka:aggregator:enabled:True or I@heka:remote_collector:enabled:True ) ${extra_tgt}", state: 'heka'])

    // Update collectd
    salt.enforceState([saltId: master, target: "I@collectd:remote_client:enabled:True ${extra_tgt}", state: 'collectd'])

    def alarming_service_pillar = salt.getPillar(master, "mon*01* ${extra_tgt}", '_param:alarming_service')
    def alarming_service = alarming_service_pillar['return'][0].values()[0]

    switch (alarming_service) {
        case 'sensu':
            // Update Sensu
            // TODO for stacklight team, should be fixed in model
            salt.enforceState([saltId: master, target: "I@sensu:client ${extra_tgt}", state: 'sensu'])
        default:
            break
            // Default is nagios, and was enforced in installStacklightControl()
    }

    salt.cmdRun(master, "I@grafana:client and *01* ${extra_tgt}", 'salt-call state.sls grafana.client')
    // salt.enforceState([saltId: master, target: "I@grafana:client and *01*", state: 'grafana.client"])

    // Finalize the configuration of Grafana (add the dashboards...)
    salt.enforceState([saltId: master, target: "I@grafana:client and *01* ${extra_tgt}", state: 'grafana.client'])
    salt.enforceState([saltId: master, target: "I@grafana:client and *02* ${extra_tgt}", state: 'grafana.client'])
    salt.enforceState([saltId: master, target: "I@grafana:client and *03* ${extra_tgt}", state: 'grafana.client'])
    // nw salt -C "I@grafana:client' --async service.restart salt-minion; sleep 10

    // Get the StackLight monitoring VIP addres
    //vip=$(salt-call pillar.data _param:stacklight_monitor_address --out key|grep _param: |awk '{print $2}')
    //vip=${vip:=172.16.10.253}
    def pillar = salt.getPillar(master, "ctl01* ${extra_tgt}", '_param:stacklight_monitor_address')
    common.prettyPrint(pillar)
    def stacklight_vip = pillar['return'][0].values()[0]

    if (stacklight_vip) {
        // (re)Start manually the services that are bound to the monitoring VIP
        common.infoMsg("restart services on node with IP: ${stacklight_vip}")
        salt.runSaltProcessStep(master, "G@ipv4:${stacklight_vip} ${extra_tgt}", 'service.restart', ['remote_collectd'])
        salt.runSaltProcessStep(master, "G@ipv4:${stacklight_vip} ${extra_tgt}", 'service.restart', ['remote_collector'])
        salt.runSaltProcessStep(master, "G@ipv4:${stacklight_vip} ${extra_tgt}", 'service.restart', ['aggregator'])
        salt.runSaltProcessStep(master, "G@ipv4:${stacklight_vip} ${extra_tgt}", 'service.restart', ['nagios3'])
    } else {
        throw new Exception("Missing stacklight_vip")
    }
}

//
// backups
//

def installBackup(master, component='common', extra_tgt = '') {
    def salt = new com.mirantis.mk.Salt()
    if (component == 'common') {
        // Install Backupninja
        if (salt.testTarget(master, "I@backupninja:client ${extra_tgt}")) {
            salt.enforceState([saltId: master, target: "I@backupninja:client ${extra_tgt}", state: 'salt.minion.grains'])
            salt.runSaltProcessStep(master, "I@backupninja:client ${extra_tgt}", 'saltutil.sync_grains')
            salt.runSaltProcessStep(master, "I@backupninja:client ${extra_tgt}", 'mine.flush')
            salt.runSaltProcessStep(master, "I@backupninja:client ${extra_tgt}", 'mine.update')
            salt.enforceState([saltId: master, target: "I@backupninja:client ${extra_tgt}", state: 'backupninja'])
        }
        salt.enforceStateWithTest([saltId: master, target: "I@backupninja:server ${extra_tgt}", state: 'salt.minion.grains'])
        salt.enforceStateWithTest([saltId: master, target: "I@backupninja:server ${extra_tgt}", state: 'backupninja'])
    } else if (component == 'mysql') {
        // Install Xtrabackup
        if (salt.testTarget(master, "I@xtrabackup:client ${extra_tgt}")) {
            salt.enforceState([saltId: master, target: "I@xtrabackup:client ${extra_tgt}", state: 'salt.minion.grains'])
            salt.runSaltProcessStep(master, "I@xtrabackup:client ${extra_tgt}", 'saltutil.sync_grains')
            salt.runSaltProcessStep(master, "I@xtrabackup:client ${extra_tgt}", 'mine.flush')
            salt.runSaltProcessStep(master, "I@xtrabackup:client ${extra_tgt}", 'mine.update')
            salt.enforceState([saltId: master, target: "I@xtrabackup:client ${extra_tgt}", state: 'xtrabackup'])
        }
        salt.enforceStateWithTest([saltId: master, target: "I@xtrabackup:server ${extra_tgt}", state: 'xtrabackup'])
    } else if (component == 'contrail') {

        // Install Cassandra backup
        if (salt.testTarget(master, "I@cassandra:backup:client ${extra_tgt}")) {
            salt.enforceState([saltId: master, target: "I@cassandra:backup:client ${extra_tgt}", state: 'salt.minion.grains'])
            salt.runSaltProcessStep(master, "I@cassandra:backup:client ${extra_tgt}", 'saltutil.sync_grains')
            salt.runSaltProcessStep(master, "I@cassandra:backup:client ${extra_tgt}", 'mine.flush')
            salt.runSaltProcessStep(master, "I@cassandra:backup:client ${extra_tgt}", 'mine.update')
            salt.enforceState([saltId: master, target: "I@cassandra:backup:client ${extra_tgt}", state: 'cassandra.backup'])
        }
        salt.enforceStateWithTest([saltId: master, target: "I@cassandra:backup:server ${extra_tgt}", state: 'cassandra.backup'])
        // Install Zookeeper backup
        if (salt.testTarget(master, "I@zookeeper:backup:client ${extra_tgt}")) {
            salt.enforceState([saltId: master, target: "I@zookeeper:backup:client ${extra_tgt}", state: 'salt.minion.grains'])
            salt.runSaltProcessStep(master, "I@zookeeper:backup:client ${extra_tgt}", 'saltutil.sync_grains')
            salt.runSaltProcessStep(master, "I@zookeeper:backup:client ${extra_tgt}", 'mine.flush')
            salt.runSaltProcessStep(master, "I@zookeeper:backup:client ${extra_tgt}", 'mine.update')
            salt.enforceState([saltId: master, target: "I@zookeeper:backup:client ${extra_tgt}", state: 'zookeeper.backup'])
        }
        salt.enforceStateWithTest([saltId: master, target: "I@zookeeper:backup:server ${extra_tgt}", state: 'zookeeper.backup'])
    } else if (component == 'ceph') {
        // Install Ceph backup
        if (salt.testTarget(master, "I@ceph:backup:client ${extra_tgt}")) {
            salt.enforceState([saltId: master, target: "I@ceph:backup:client ${extra_tgt}", state: 'salt.minion.grains'])
            salt.runSaltProcessStep(master, "I@ceph:backup:client ${extra_tgt}", 'saltutil.sync_grains')
            salt.runSaltProcessStep(master, "I@ceph:backup:client ${extra_tgt}", 'mine.flush')
            salt.runSaltProcessStep(master, "I@ceph:backup:client ${extra_tgt}", 'mine.update')
            salt.enforceState([saltId: master, target: "I@ceph:backup:client ${extra_tgt}", state: 'ceph.backup'])
        }
        salt.enforceStateWithTest([saltId: master, target: "I@ceph:backup:server ${extra_tgt}", state: 'ceph.backup'])
    }

}

//
// Ceph
//

def installCephMon(master, target="I@ceph:mon", extra_tgt = '') {
    def salt = new com.mirantis.mk.Salt()

    salt.enforceState([saltId: master, target: "I@ceph:common ${extra_tgt}", state: 'salt.minion.grains'])

    // generate keyrings
    if (salt.testTarget(master, "( I@ceph:mon:keyring:mon or I@ceph:common:keyring:admin ) ${extra_tgt}")) {
        salt.enforceState([saltId: master, target: "( I@ceph:mon:keyring:mon or I@ceph:common:keyring:admin ) ${extra_tgt}", state: 'ceph.mon'])
        salt.runSaltProcessStep(master, "I@ceph:mon ${extra_tgt}", 'saltutil.sync_grains')
        salt.runSaltProcessStep(master, "( I@ceph:mon:keyring:mon or I@ceph:common:keyring:admin ) ${extra_tgt}", 'mine.update')

        // on target nodes mine is used to get pillar from 'ceph:common:keyring:admin' via grain.items
        // we need to refresh all pillar/grains to make data sharing work correctly
        salt.fullRefresh(master, "( I@ceph:mon:keyring:mon or I@ceph:common:keyring:admin ) ${extra_tgt}")

        sleep(5)
    }
    // install Ceph Mons
    salt.enforceState([saltId: master, target: target, state: 'ceph.mon'])
    salt.enforceStateWithTest([saltId: master, target: "I@ceph:mgr ${extra_tgt}", state: 'ceph.mgr'])
}

def installCephOsd(master, target="I@ceph:osd", setup=true, extra_tgt = '') {
    def salt = new com.mirantis.mk.Salt()

    // install Ceph OSDs
    salt.enforceState([saltId: master, target: target, state: 'ceph.osd'])
    salt.runSaltProcessStep(master, "I@ceph:osd ${extra_tgt}", 'saltutil.sync_grains')
    salt.enforceState([saltId: master, target: target, state: 'ceph.osd.custom'])
    salt.runSaltProcessStep(master, "I@ceph:osd ${extra_tgt}", 'saltutil.sync_grains')
    salt.runSaltProcessStep(master, "I@ceph:osd ${extra_tgt}", 'mine.update')
    installBackup(master, 'ceph')

    // setup pools, keyrings and maybe crush
    if (salt.testTarget(master, "I@ceph:setup ${extra_tgt}") && setup) {
        sleep(5)
        salt.enforceState([saltId: master, target: "I@ceph:setup ${extra_tgt}", state: 'ceph.setup'])
    }
}

def installCephClient(master, extra_tgt = '') {
    def salt = new com.mirantis.mk.Salt()

    // install Ceph Radosgw
    if (salt.testTarget(master, "I@ceph:radosgw ${extra_tgt}")) {
        salt.runSaltProcessStep(master, "I@ceph:radosgw ${extra_tgt}", 'saltutil.sync_grains')
        salt.enforceState([saltId: master, target: "I@ceph:radosgw ${extra_tgt}", state: 'ceph.radosgw'])
    }

    // setup keyring for Openstack services
    salt.enforceStateWithTest([saltId: master, target: "I@ceph:common and I@glance:server ${extra_tgt}", state: ['ceph.common', 'ceph.setup.keyring']])

    salt.enforceStateWithTest([saltId: master, target: "I@ceph:common and I@cinder:controller ${extra_tgt}", state: ['ceph.common', 'ceph.setup.keyring']])

    if (salt.testTarget(master, "I@ceph:common and I@nova:compute ${extra_tgt}")) {
        salt.enforceState([saltId: master, target: "I@ceph:common and I@nova:compute ${extra_tgt}", state: ['ceph.common', 'ceph.setup.keyring']])
        salt.runSaltProcessStep(master, "I@ceph:common and I@nova:compute ${extra_tgt}", 'saltutil.sync_grains')
    }

    salt.enforceStateWithTest([saltId: master, target: "I@ceph:common and I@gnocchi:server ${extra_tgt}", state: ['ceph.common', 'ceph.setup.keyring']])
}

def connectCeph(master, extra_tgt = '') {
    def salt = new com.mirantis.mk.Salt()

    // setup Keystone service and endpoints for swift or / and S3
    salt.enforceStateWithTest([saltId: master, target: "I@keystone:client ${extra_tgt}", state: 'keystone.client'])

    // connect Ceph to the env
    if (salt.testTarget(master, "I@ceph:common and I@glance:server ${extra_tgt}")) {
        salt.enforceState([saltId: master, target: "I@ceph:common and I@glance:server ${extra_tgt}", state: ['glance']])
        salt.runSaltProcessStep(master, "I@ceph:common and I@glance:server ${extra_tgt}", 'service.restart', ['glance-api'])
    }
    if (salt.testTarget(master, "I@ceph:common and I@cinder:controller ${extra_tgt}")) {
        salt.enforceState([saltId: master, target: "I@ceph:common and I@cinder:controller ${extra_tgt}", state: ['cinder']])
        salt.runSaltProcessStep(master, "I@ceph:common and I@cinder:controller ${extra_tgt}", 'service.restart', ['cinder-volume'])
    }
    if (salt.testTarget(master, "I@ceph:common and I@nova:compute ${extra_tgt}")) {
        salt.enforceState([saltId: master, target: "I@ceph:common and I@nova:compute ${extra_tgt}", state: ['nova']])
        salt.runSaltProcessStep(master, "I@ceph:common and I@nova:compute ${extra_tgt}", 'service.restart', ['nova-compute'])
    }
    if (salt.testTarget(master, "I@ceph:common and I@gnocchi:server ${extra_tgt}")) {
        salt.enforceState([saltId: master, target: "I@ceph:common and I@gnocchi:server:role:primary ${extra_tgt}", state: 'gnocchi.server'])
        salt.enforceState([saltId: master, target: "I@ceph:common and I@gnocchi:server ${extra_tgt}", state: 'gnocchi.server'])
    }
}

def installOssInfra(master, extra_tgt = '') {
  def common = new com.mirantis.mk.Common()
  def salt = new com.mirantis.mk.Salt()

  salt.enforceStateWithTest([saltId: master, target: "I@devops_portal:config ${extra_tgt}", state: 'devops_portal.config'])
  salt.enforceStateWithTest([saltId: master, target: "I@rundeck:client ${extra_tgt}", state: ['linux.system.user', 'openssh'], testTargetMatcher: "I@devops_portal:config ${extra_tgt}"])
  salt.enforceStateWithTest([saltId: master, target: "I@rundeck:server ${extra_tgt}", state: 'rundeck.server', testTargetMatcher: "I@devops_portal:config ${extra_tgt}"])
}

def installOss(master, extra_tgt = '') {
  def common = new com.mirantis.mk.Common()
  def salt = new com.mirantis.mk.Salt()

  //Get oss VIP address
  def pillar = salt.getPillar(master, "cfg01* ${extra_tgt}", '_param:stacklight_monitor_address')
  common.prettyPrint(pillar)

  def oss_vip
  if(!pillar['return'].isEmpty()) {
      oss_vip = pillar['return'][0].values()[0]
  } else {
      common.errorMsg('[ERROR] Oss VIP address could not be retrieved')
  }

  // Postgres client - initialize OSS services databases
  timeout(120){
    common.infoMsg("Waiting for postgresql database to come up..")
    salt.cmdRun(master, "I@postgresql:client ${extra_tgt}", 'while true; do if docker service logs postgresql_postgresql-db 2>&1 | grep "ready to accept"; then break; else sleep 5; fi; done')
  }
  // XXX: first run usually fails on some inserts, but we need to create databases at first
  salt.enforceState([saltId: master, target: "I@postgresql:client ${extra_tgt}", state: 'postgresql.client', failOnError: false])

  // Setup postgres database with integration between
  // Pushkin notification service and Security Monkey security audit service
  timeout(10) {
    common.infoMsg("Waiting for Pushkin to come up..")
    salt.cmdRun(master, "I@postgresql:client ${extra_tgt}", "while true; do curl -sf ${oss_vip}:8887/apps >/dev/null && break; done")
  }
  salt.enforceState([saltId: master, target: "I@postgresql:client ${extra_tgt}", state: 'postgresql.client'])

  // Rundeck
  timeout(10) {
    common.infoMsg("Waiting for Rundeck to come up..")
    salt.cmdRun(master, "I@rundeck:client ${extra_tgt}", "while true; do curl -sf ${oss_vip}:4440 >/dev/null && break; done")
  }
  salt.enforceState([saltId: master, target: "I@rundeck:client ${extra_tgt}", state: 'rundeck.client'])

  // Elasticsearch
  pillar = salt.getPillar(master, "I@elasticsearch:client ${extra_tgt}", 'elasticsearch:client:server:host')
  def elasticsearch_vip
  if(!pillar['return'].isEmpty()) {
    elasticsearch_vip = pillar['return'][0].values()[0]
  } else {
    common.errorMsg('[ERROR] Elasticsearch VIP address could not be retrieved')
  }

  timeout(10) {
    common.infoMsg('Waiting for Elasticsearch to come up..')
    salt.cmdRun(master, "I@elasticsearch:client ${extra_tgt}", "while true; do curl -sf ${elasticsearch_vip}:9200 >/dev/null && break; done")
  }
  salt.enforceState([saltId: master, target: "I@elasticsearch:client ${extra_tgt}", state: 'elasticsearch.client'])
}

/**
 * Function receives connection string, target and configuration yaml pattern
 * and retrieves config fom salt minion according to pattern. After that it
 * sorts applications according to priorities and runs orchestration states
 * @param master Salt Connection object or pepperEnv
 * @param tgt Target
 * @param conf Configuration pattern
 */
def OrchestrateApplications(master, tgt, conf) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def _orch = salt.getConfig(master, tgt, conf)
    if ( !_orch['return'][0].values()[0].isEmpty() ) {
      Map<String,Integer> _orch_app = [:]
      for (k in _orch['return'][0].values()[0].keySet()) {
        _orch_app[k] = _orch['return'][0].values()[0][k].values()[0].toInteger()
      }
      def _orch_app_sorted = common.SortMapByValueAsc(_orch_app)
      common.infoMsg("Applications will be deployed in following order:"+_orch_app_sorted.keySet())
      for (app in _orch_app_sorted.keySet()) {
        salt.orchestrateSystem(master, ['expression': tgt, 'type': 'compound'], "${app}.orchestrate.deploy")
      }
    }
    else {
      common.infoMsg("No applications found for orchestration")
    }
}
