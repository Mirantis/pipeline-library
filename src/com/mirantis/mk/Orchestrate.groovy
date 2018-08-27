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
    salt.enforceState(master, "I@salt:master ${extra_tgt}", ['reclass'])

    salt.enforceState(master, "I@salt:master ${extra_tgt}", ['linux.system'])
    salt.enforceState(master, "I@salt:master ${extra_tgt}", ['salt.master'], true, false, null, false, 120, 2)
    salt.fullRefresh(master, "* ${extra_tgt}")

    salt.enforceState(master, "I@salt:master ${extra_tgt}", ['salt.minion'], true, false, null, false, 60, 2)
    salt.enforceState(master, "I@salt:master ${extra_tgt}", ['salt.minion'])
    salt.fullRefresh(master, "* ${extra_tgt}")
    salt.enforceState(master, "* ${extra_tgt}", ['linux.network.proxy'], true, false, null, false, 60, 2)
    try {
        salt.enforceState(master, "* ${extra_tgt}", ['salt.minion.base'], true, false, null, false, 60, 2)
        sleep(5)
    } catch (Throwable e) {
        common.warningMsg('Salt state salt.minion.base is not present in the Salt-formula yet.')
    }
    common.retry(2,5){
        salt.enforceState(master, "* ${extra_tgt}", ['linux.system'])
    }
    if (staticMgmtNet) {
        salt.runSaltProcessStep(master, "* ${extra_tgt}", 'cmd.shell', ["salt-call state.sls linux.network; salt-call service.restart salt-minion"], null, true, 60)
    }
    common.retry(2,5){
        salt.enforceState(master, "I@linux:network:interface ${extra_tgt}", ['linux.network.interface'])
    }
    sleep(5)
    salt.enforceState(master, "I@linux:system ${extra_tgt}", ['linux', 'openssh', 'ntp', 'rsyslog'])
    salt.enforceState(master, "* ${extra_tgt}", ['salt.minion'], true, false, null, false, 60, 2)
    sleep(5)

    salt.fullRefresh(master, "* ${extra_tgt}")
    salt.runSaltProcessStep(master, "* ${extra_tgt}", 'mine.update', [], null, true)
    salt.enforceState(master, "* ${extra_tgt}", ['linux.network.host'])

    // Install and configure iptables
    if (salt.testTarget(master, "I@iptables:service ${extra_tgt}")) {
        salt.enforceState(master, "I@iptables:service ${extra_tgt}", 'iptables')
    }

    // Install and configure logrotate
    if (salt.testTarget(master, "I@logrotate:server ${extra_tgt}")) {
        salt.enforceState(master, "I@logrotate:server ${extra_tgt}", 'logrotate')
    }

    // Install and configure auditd
    if (salt.testTarget(master, "I@auditd:service ${extra_tgt}")) {
        salt.enforceState(master, "I@auditd:service ${extra_tgt}", 'auditd')
    }

    // Install and configure openscap
    if (salt.testTarget(master, "I@openscap:service ${extra_tgt}")) {
        salt.enforceState(master, "I@openscap:service ${extra_tgt}", 'openscap')
    }
}

def installFoundationInfraOnTarget(master, target, staticMgmtNet=false, extra_tgt = '') {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()

    salt.enforceState(master, "I@salt:master ${extra_tgt}", ['reclass'], true, false, null, false, 120, 2)
    salt.fullRefresh(master, target)
    salt.enforceState(master, target, ['linux.network.proxy'], true, false, null, false, 60, 2)
    try {
        salt.enforceState(master, target, ['salt.minion.base'], true, false, null, false, 60, 2)
        sleep(5)
    } catch (Throwable e) {
        common.warningMsg('Salt state salt.minion.base is not present in the Salt-formula yet.')
    }
    common.retry(2,5){
        salt.enforceState(master, target, ['linux.system'])
    }
    if (staticMgmtNet) {
        salt.runSaltProcessStep(master, target, 'cmd.shell', ["salt-call state.sls linux.network; salt-call service.restart salt-minion"], null, true, 60)
    }
    salt.enforceState(master, target, ['salt.minion'], true, false, null, false, 60, 2)
    salt.enforceState(master, target, ['salt.minion'])
    salt.enforceState(master, target, ['linux.network.interface'])
    sleep(5)
    salt.enforceState(master, target, ['linux', 'openssh', 'ntp', 'rsyslog'])
    sleep(5)

    salt.fullRefresh(master, target)
    salt.runSaltProcessStep(master, target, 'mine.update', [], null, true)
    salt.enforceState(master, target, ['linux.network.host'])
}

def installInfraKvm(master, extra_tgt = '') {
    def common = new com.mirantis.mk.Common()
    def salt = new com.mirantis.mk.Salt()
    def infra_compound = "I@salt:control ${extra_tgt}"
    def minions = []
    def wait_timeout = 10
    def retries = wait_timeout * 30

    salt.fullRefresh(master, "I@linux:system ${extra_tgt}")
    salt.enforceState(master, "I@salt:control ${extra_tgt}", ['salt.minion'], true, false, null, false, 60, 2)
    salt.enforceState(master, "I@salt:control ${extra_tgt}", ['linux.system', 'linux.network', 'ntp', 'rsyslog'])
    salt.enforceState(master, "I@salt:control ${extra_tgt}", 'libvirt')
    salt.enforceState(master, "I@salt:control ${extra_tgt}", 'salt.control')

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
        salt.enforceState(master, "I@glusterfs:server ${extra_tgt}", 'glusterfs.server.service')

        first_target = salt.getFirstMinion(master, "I@glusterfs:server ${extra_tgt}")
        salt.enforceState(master, "${first_target} ${extra_tgt}", 'glusterfs.server.setup', true, true, null, false, -1, 5)
        sleep(10)
        salt.cmdRun(master, "I@glusterfs:server ${extra_tgt}", "gluster peer status; gluster volume status")
    }

    // Ensure glusterfs clusters is ready
    if (salt.testTarget(master, "I@glusterfs:client ${extra_tgt}")) {
        salt.enforceState(master, "I@glusterfs:client ${extra_tgt}", 'glusterfs.client', true, true, null, false, -1, 2)
    }

    // Install galera
    if (salt.testTarget(master, "I@galera:master ${extra_tgt}") || salt.testTarget(master, "I@galera:slave ${extra_tgt}")) {
        salt.enforceState(master, "I@galera:master ${extra_tgt}", 'galera', true, true, null, false, -1, 2)
        salt.enforceState(master, "I@galera:slave ${extra_tgt}", 'galera', true, true, null, false, -1, 2)

        // Check galera status
        salt.runSaltProcessStep(master, "I@galera:master ${extra_tgt}", 'mysql.status')
        salt.runSaltProcessStep(master, "I@galera:slave ${extra_tgt}", 'mysql.status')
    // If galera is not enabled check if we need to install mysql:server
    } else if (salt.testTarget(master, "I@mysql:server ${extra_tgt}")){
        salt.enforceState(master, "I@mysql:server ${extra_tgt}", 'mysql.server')
        if (salt.testTarget(master, "I@mysql:client ${extra_tgt}")){
            salt.enforceState(master, "I@mysql:client ${extra_tgt}", 'mysql.client')
        }
    }
    installBackup(master, 'mysql', extra_tgt)

    // Install docker
    if (salt.testTarget(master, "I@docker:host ${extra_tgt}")) {
        salt.enforceState(master, "I@docker:host ${extra_tgt}", 'docker.host', true, true, null, false, -1, 3)
        salt.cmdRun(master, "I@docker:host and I@docker:host:enabled:true ${extra_tgt}", 'docker ps')
    }

    // Install keepalived
    if (salt.testTarget(master, "I@keepalived:cluster ${extra_tgt}")) {
        first_target = salt.getFirstMinion(master, "I@keepalived:cluster ${extra_tgt}")
        salt.enforceState(master, "${first_target} ${extra_tgt}", 'keepalived')
        salt.enforceState(master, "I@keepalived:cluster ${extra_tgt}", 'keepalived')
    }

    // Install rabbitmq
    if (salt.testTarget(master, "I@rabbitmq:server ${extra_tgt}")) {
        salt.enforceState(master, "I@rabbitmq:server ${extra_tgt}", 'rabbitmq', true, true, null, false, -1, 2)

        // Check the rabbitmq status
        common.retry(3,5){
             salt.cmdRun(master, "I@rabbitmq:server ${extra_tgt}", 'rabbitmqctl cluster_status')
        }
    }

    // Install haproxy
    if (salt.testTarget(master, "I@haproxy:proxy ${extra_tgt}")) {
        salt.enforceState(master, "I@haproxy:proxy ${extra_tgt}", 'haproxy')
        salt.runSaltProcessStep(master, "I@haproxy:proxy ${extra_tgt}", 'service.status', ['haproxy'])
        salt.runSaltProcessStep(master, "I@haproxy:proxy ${extra_tgt}", 'service.restart', ['rsyslog'])
    }

    // Install memcached
    if (salt.testTarget(master, "I@memcached:server ${extra_tgt}")) {
        salt.enforceState(master, "I@memcached:server ${extra_tgt}", 'memcached')
    }

    // Install etcd
    if (salt.testTarget(master, "I@etcd:server ${extra_tgt}")) {
        salt.enforceState(master, "I@etcd:server ${extra_tgt}", 'etcd.server.service')
        common.retry(3,5){
            salt.cmdRun(master, "I@etcd:server ${extra_tgt}", '. /var/lib/etcd/configenv && etcdctl cluster-health')
        }
    }

    // Install redis
    if (salt.testTarget(master, "I@redis:server ${extra_tgt}")) {
        if (salt.testTarget(master, "I@redis:cluster:role:master ${extra_tgt}")) {
            salt.enforceState(master, "I@redis:cluster:role:master ${extra_tgt}", 'redis')
        }
        salt.enforceState(master, "I@redis:server ${extra_tgt}", 'redis')
    }

    // Install DNS services
    if (salt.testTarget(master, "I@bind:server ${extra_tgt}")) {
        salt.enforceState(master, "I@bind:server ${extra_tgt}", 'bind.server')
    }
    if (salt.testTarget(master, "I@powerdns:server ${extra_tgt}")) {
        salt.enforceState(master, "I@powerdns:server ${extra_tgt}", 'powerdns.server')
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

    // Install horizon dashboard
    if (salt.testTarget(master, "I@horizon:server ${extra_tgt}")) {
        salt.enforceState(master, "I@horizon:server ${extra_tgt}", 'horizon')
    }
    // Install sphinx server
    if (salt.testTarget(master, "I@sphinx:server ${extra_tgt}")) {
        salt.enforceState(master, "I@sphinx:server ${extra_tgt}", 'sphinx')
    }
    if (salt.testTarget(master, "I@nginx:server ${extra_tgt}")) {
        salt.enforceState(master, "I@nginx:server ${extra_tgt}", 'salt.minion')
        salt.enforceState(master, "I@nginx:server ${extra_tgt}", 'nginx')
    }

    // setup keystone service
    if (salt.testTarget(master, "I@keystone:server ${extra_tgt}")) {
        salt.enforceState(master, "I@keystone:server:role:primary ${extra_tgt}", 'keystone.server')
        salt.enforceState(master, "I@keystone:server ${extra_tgt}", 'keystone.server')
        // populate keystone services/tenants/roles/users

        // keystone:client must be called locally
        //salt.runSaltProcessStep(master, 'I@keystone:client', 'cmd.run', ['salt-call state.sls keystone.client'], null, true)
        salt.runSaltProcessStep(master, "I@keystone:server ${extra_tgt}", 'service.restart', ['apache2'])
        sleep(30)
    }
    if (salt.testTarget(master, "I@keystone:client ${extra_tgt}")) {
        first_target = salt.getFirstMinion(master, "I@keystone:client ${extra_tgt}")
        salt.enforceState(master, "${first_target} ${extra_tgt}", 'keystone.client')
        salt.enforceState(master, "I@keystone:client ${extra_tgt}", 'keystone.client')
    }
    if (salt.testTarget(master, "I@keystone:server ${extra_tgt}")) {
        common.retry(3,5){
            salt.cmdRun(master, "I@keystone:server ${extra_tgt}", '. /root/keystonercv3; openstack service list')
        }
    }

    // Install glance
    if (salt.testTarget(master, "I@glance:server ${extra_tgt}")) {
        //runSaltProcessStep(master, 'I@glance:server', 'state.sls', ['glance.server'], 1)
        salt.enforceState(master, "I@glance:server:role:primary ${extra_tgt}", 'glance.server')
        salt.enforceState(master, "I@glance:server ${extra_tgt}", 'glance.server')
    }

    // Check glance service
    if (salt.testTarget(master, "I@glance:server ${extra_tgt}")) {
        common.retry(3,5){
            salt.cmdRun(master, "I@keystone:server ${extra_tgt}", '. /root/keystonercv3; glance image-list')
        }
    }

    // Create glance resources
    if (salt.testTarget(master, "I@glance:client ${extra_tgt}")) {
        salt.enforceState(master, "I@glance:client ${extra_tgt}", 'glance.client')
    }

    // Install and check nova service
    if (salt.testTarget(master, "I@nova:controller ${extra_tgt}")) {
        // run on first node first
        salt.enforceState(master, "I@nova:controller:role:primary ${extra_tgt}", 'nova.controller')
        salt.enforceState(master, "I@nova:controller ${extra_tgt}", 'nova.controller')
        if (salt.testTarget(master, "I@keystone:server ${extra_tgt}")) {
           common.retry(3,5){
               salt.cmdRun(master, "I@keystone:server ${extra_tgt}", '. /root/keystonercv3; nova service-list')
           }
        }
    }

    // Create nova resources
    if (salt.testTarget(master, "I@nova:client ${extra_tgt}")) {
        salt.enforceState(master, "I@nova:client ${extra_tgt}", 'nova.client')
    }

    // Install and check cinder service
    if (salt.testTarget(master, "I@cinder:controller ${extra_tgt}")) {
        // run on first node first
        salt.enforceState(master, "I@cinder:controller:role:primary ${extra_tgt}", 'cinder')
        salt.enforceState(master, "I@cinder:controller ${extra_tgt}", 'cinder')
        if (salt.testTarget(master, "I@keystone:server ${extra_tgt}")) {
            common.retry(3,5){
                salt.cmdRun(master, "I@keystone:server ${extra_tgt}", '. /root/keystonercv3; cinder list')
            }
        }
    }

    // Install neutron service
    if (salt.testTarget(master, "I@neutron:server ${extra_tgt}")) {
        // run on first node first
        salt.enforceState(master, "I@neutron:server:role:primary ${extra_tgt}", 'neutron.server')
        salt.enforceState(master, "I@neutron:server ${extra_tgt}", 'neutron.server')
        if (salt.testTarget(master, "I@keystone:server ${extra_tgt}")) {
            common.retry(3,5){
                salt.cmdRun(master, "I@keystone:server ${extra_tgt}",'. /root/keystonercv3; neutron agent-list')
            }
        }
    }

    // Install heat service
    if (salt.testTarget(master, "I@heat:server ${extra_tgt}")) {
        // run on first node first
        salt.enforceState(master, "I@heat:server:role:primary ${extra_tgt}", 'heat')
        salt.enforceState(master, "I@heat:server ${extra_tgt}", 'heat')
        if (salt.testTarget(master, "I@keystone:server ${extra_tgt}")) {
            common.retry(3,5){
                salt.cmdRun(master, "I@keystone:server ${extra_tgt}", '. /root/keystonercv3; openstack orchestration resource type list')
            }
        }
    }

    // Restart nova api
    if (salt.testTarget(master, "I@nova:controller ${extra_tgt}")) {
        salt.runSaltProcessStep(master, "I@nova:controller ${extra_tgt}", 'service.restart', ['nova-api'])
    }

    // Install ironic service
    if (salt.testTarget(master, "I@ironic:api ${extra_tgt}")) {
        salt.enforceState(master, "I@ironic:api:role:primary ${extra_tgt}", 'ironic.api')
        salt.enforceState(master, "I@ironic:api ${extra_tgt}", 'ironic.api')
    }

    // Install manila service
    if (salt.testTarget(master, "I@manila:api ${extra_tgt}")) {
        salt.enforceState(master, "I@manila:api:role:primary ${extra_tgt}", 'manila.api')
        salt.enforceState(master, "I@manila:api ${extra_tgt}", 'manila.api')
    }
    if (salt.testTarget(master, "I@manila:scheduler ${extra_tgt}")) {
        salt.enforceState(master, "I@manila:scheduler ${extra_tgt}", 'manila.scheduler')
    }

    // Install designate services
    if (salt.testTarget(master, "I@designate:server:enabled ${extra_tgt}")) {
        salt.enforceState(master, "I@designate:server:role:primary ${extra_tgt}", 'designate.server')
        salt.enforceState(master, "I@designate:server ${extra_tgt}", 'designate')
    }

    // Install octavia api service
    if (salt.testTarget(master, "I@octavia:api ${extra_tgt}")) {
        salt.enforceState(master, "I@octavia:api:role:primary ${extra_tgt}", 'octavia')
        salt.enforceState(master, "I@octavia:api ${extra_tgt}", 'octavia')
    }

    // Install DogTag server service
    if (salt.testTarget(master, "I@dogtag:server ${extra_tgt}")) {
        salt.enforceState(master, "I@dogtag:server:role:master ${extra_tgt}", 'dogtag.server')
        salt.enforceState(master, "I@dogtag:server ${extra_tgt}", 'dogtag.server')
    }

    // Install barbican server service
    if (salt.testTarget(master, "I@barbican:server ${extra_tgt}")) {
        salt.enforceState(master, "I@barbican:server:role:primary ${extra_tgt}", 'barbican.server')
        salt.enforceState(master, "I@barbican:server ${extra_tgt}", 'barbican.server')
    }
    // Install barbican client
    if (salt.testTarget(master, "I@barbican:client ${extra_tgt}")) {
        salt.enforceState(master, "I@barbican:client ${extra_tgt}", 'barbican.client')
    }

    // Install gnocchi server
    if (salt.testTarget(master, "I@gnocchi:server ${extra_tgt}")) {
        salt.enforceState(master, "I@gnocchi:server:role:primary ${extra_tgt}", 'gnocchi.server')
        salt.enforceState(master, "I@gnocchi:server ${extra_tgt}", 'gnocchi.server')
    }

    // Apply gnocchi client state to create gnocchi archive policies, due to possible
    // races, apply on the first node initially
    if (salt.testTarget(master, "I@gnocchi:client ${extra_tgt}")) {
        first_target = salt.getFirstMinion(master, "I@gnocchi:client ${extra_tgt}")
        salt.enforceState(master, "${first_target} ${extra_tgt}", 'gnocchi.client')
        salt.enforceState(master, "I@gnocchi:client ${extra_tgt}", 'gnocchi.client')
    }

    // Install gnocchi statsd
    if (salt.testTarget(master, "I@gnocchi:statsd ${extra_tgt}")) {
        first_target = salt.getFirstMinion(master, "I@gnocchi:statsd ${extra_tgt}")
        salt.enforceState(master, "${first_target} ${extra_tgt}", 'gnocchi.statsd')
        salt.enforceState(master, "I@gnocchi:statsd ${extra_tgt}", 'gnocchi.statsd')
    }

    // Install panko server
    if (salt.testTarget(master, "I@panko:server ${extra_tgt}")) {
        first_target = salt.getFirstMinion(master, "I@panko:server ${extra_tgt}")
        salt.enforceState(master, "${first_target} ${extra_tgt}", 'panko')
        salt.enforceState(master, "I@panko:server ${extra_tgt}", 'panko')
    }

    // Install ceilometer server
    if (salt.testTarget(master, "I@ceilometer:server ${extra_tgt}")) {
        salt.enforceState(master, "I@ceilometer:server:role:primary ${extra_tgt}", 'ceilometer')
        salt.enforceState(master, "I@ceilometer:server ${extra_tgt}", 'ceilometer')
    }

    // Install aodh server
    if (salt.testTarget(master, "I@aodh:server ${extra_tgt}")) {
        first_target = salt.getFirstMinion(master, "I@aodh:server ${extra_tgt}")
        salt.enforceState(master, "${first_target} ${extra_tgt}", 'aodh')
        salt.enforceState(master, "I@aodh:server ${extra_tgt}", 'aodh')
    }
}


def installIronicConductor(master, extra_tgt = ''){
    def salt = new com.mirantis.mk.Salt()

    if (salt.testTarget(master, "I@ironic:conductor ${extra_tgt}")) {
        salt.enforceState(master, "I@ironic:conductor ${extra_tgt}", 'ironic.conductor')
        salt.enforceState(master, "I@ironic:conductor ${extra_tgt}", 'apache')
    }
    if (salt.testTarget(master, "I@tftpd_hpa:server ${extra_tgt}")) {
        salt.enforceState(master, "I@tftpd_hpa:server ${extra_tgt}", 'tftpd_hpa')
    }

    if (salt.testTarget(master, "I@nova:compute ${extra_tgt}")) {
        salt.runSaltProcessStep(master, "I@nova:compute ${extra_tgt}", 'service.restart', ['nova-compute'])
    }

    if (salt.testTarget(master, "I@baremetal_simulator:enabled ${extra_tgt}")) {
        salt.enforceState(master, "I@baremetal_simulator:enabled ${extra_tgt}", 'baremetal_simulator')
    }
    if (salt.testTarget(master, "I@ironic:client ${extra_tgt}")) {
        salt.enforceState(master, "I@ironic:client ${extra_tgt}", 'ironic.client')
    }
}

def installManilaShare(master, extra_tgt = ''){
    def salt = new com.mirantis.mk.Salt()

    if (salt.testTarget(master, "I@manila:share ${extra_tgt}")) {
        salt.enforceState(master, "I@manila:share ${extra_tgt}", 'manila.share')
    }
    if (salt.testTarget(master, "I@manila:data ${extra_tgt}")) {
        salt.enforceState(master, "I@manila:data ${extra_tgt}", 'manila.data')
    }

    if (salt.testTarget(master, "I@manila:client ${extra_tgt}")) {
        salt.enforceState(master, "I@manila:client ${extra_tgt}", 'manila.client')
    }
}


def installOpenstackNetwork(master, extra_tgt = '') {
    def salt = new com.mirantis.mk.Salt()
    //run full neutron state on neutron.gateway - this will install
    //neutron agents in addition to neutron server. Once neutron agents
    //are up neutron resources can be created without hitting the situation when neutron resources are created
    //prior to neutron agents which results in creating ports in non-usable state
    if (salt.testTarget(master, "I@neutron:gateway ${extra_tgt}")) {
            salt.enforceState(master, "I@neutron:gateway ${extra_tgt}", 'neutron')
    }

    // Create neutron resources - this step was moved here to ensure that
    //neutron resources are created after neutron agens are up. In this case neutron ports will be in
    //usable state. More information: https://bugs.launchpad.net/neutron/+bug/1399249
    if (salt.testTarget(master, "I@neutron:client ${extra_tgt}")) {
        salt.enforceState(master, "I@neutron:client ${extra_tgt}", 'neutron.client')
    }

    salt.enforceHighstate(master, "I@neutron:gateway ${extra_tgt}")

    // install octavia manager services
    if (salt.testTarget(master, "I@octavia:manager ${extra_tgt}")) {
        salt.runSaltProcessStep(master, "I@salt:master ${extra_tgt}", 'mine.update', ['*'])
        salt.enforceState(master, "I@octavia:manager ${extra_tgt}", 'octavia')
        salt.enforceState(master, "I@octavia:manager ${extra_tgt}", 'salt.minion.ca')
        salt.enforceState(master, "I@octavia:manager ${extra_tgt}", 'salt.minion.cert')
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
                salt.enforceHighstateWithExclude(master, hightstateTarget, 'opencontrail.client')
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
                        salt.enforceHighstateWithExclude(master, target, 'opencontrail.client')
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
                        salt.enforceHighstateWithExclude(master, target, 'opencontrail.client')
                    }
                }
            }
        }
    }

    // Run nova:controller to map cmp with cells
    if (salt.testTarget(master, "I@nova:controller ${extra_tgt}")) {
      salt.enforceState(master, "I@nova:controller:role:primary ${extra_tgt}", 'nova.controller')
    }
}


def installContrailNetwork(master, extra_tgt = '') {
    def common = new com.mirantis.mk.Common()
    def salt = new com.mirantis.mk.Salt()
    def first_target

    // Install opencontrail database services
    first_target = salt.getFirstMinion(master, "I@opencontrail:database ${extra_tgt}")
    salt.enforceState(master, "${first_target} ${extra_tgt}", 'opencontrail.database')
    salt.enforceState(master, "I@opencontrail:database ${extra_tgt}", 'opencontrail.database')

    // Install opencontrail control services
    first_target = salt.getFirstMinion(master, "I@opencontrail:control ${extra_tgt}")
    salt.enforceStateWithExclude(master, "${first_target} ${extra_tgt}", "opencontrail", "opencontrail.client")
    salt.enforceStateWithExclude(master, "I@opencontrail:control ${extra_tgt}", "opencontrail", "opencontrail.client")
    first_target = salt.getFirstMinion(master, "I@opencontrail:collector ${extra_tgt}")
    salt.enforceStateWithExclude(master, "${first_target} ${extra_tgt}", "opencontrail", "opencontrail.client")
    salt.enforceStateWithExclude(master, "I@opencontrail:collector ${extra_tgt}", "opencontrail", "opencontrail.client")

    if (salt.testTarget(master, "I@docker:client and I@opencontrail:control ${extra_tgt}")) {
        salt.enforceState(master, "( I@opencontrail:control or I@opencontrail:collector ) ${extra_tgt}", 'docker.client')
    }
    installBackup(master, 'contrail', extra_tgt)
}


def installContrailCompute(master, extra_tgt = '') {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    // Configure compute nodes
    // Provision opencontrail control services
    salt.enforceState(master, "I@opencontrail:database:id:1 ${extra_tgt}", 'opencontrail.client')
    // Provision opencontrail virtual routers

    // Generate script /usr/lib/contrail/if-vhost0 for up vhost0
    if (salt.testTarget(master, "I@opencontrail:compute ${extra_tgt}")) {
        salt.enforceStateWithExclude(master, "I@opencontrail:compute ${extra_tgt}", "opencontrail", "opencontrail.client")
    }

    if (salt.testTarget(master, "I@nova:compute ${extra_tgt}")) {
        salt.cmdRun(master, "I@nova:compute ${extra_tgt}", 'exec 0>&-; exec 1>&-; exec 2>&-; nohup bash -c "ip link | grep vhost && echo no_reboot || sleep 5 && reboot & "', false)
    }

    sleep(300)
    if (salt.testTarget(master, "I@opencontrail:compute ${extra_tgt}")) {
        salt.enforceState(master, "I@opencontrail:compute ${extra_tgt}", 'opencontrail.client')
        salt.enforceState(master, "I@opencontrail:compute ${extra_tgt}", 'opencontrail')
    }
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
    salt.enforceState(master, "I@kubernetes:pool ${extra_tgt}", 'linux')
    salt.enforceState(master, "I@kubernetes:pool ${extra_tgt}", 'salt.minion')
    salt.enforceState(master, "I@kubernetes:pool ${extra_tgt}", ['openssh', 'ntp'])

    // Create and distribute SSL certificates for services using salt state
    salt.enforceState(master, "I@kubernetes:pool ${extra_tgt}", 'salt.minion.cert')

    // Install docker
    salt.enforceState(master, "I@docker:host ${extra_tgt}", 'docker.host')

    // Install Kubernetes pool and Calico
    salt.enforceState(master, "I@kubernetes:master ${extra_tgt}", 'kubernetes.master.kube-addons')
    salt.enforceState(master, "I@kubernetes:pool ${extra_tgt}", 'kubernetes.pool')

    if (salt.testTarget(master, "I@etcd:server:setup ${extra_tgt}")) {
        // Setup etcd server
        first_target = salt.getFirstMinion(master, "I@kubernetes:master ${extra_tgt}")
        salt.enforceState(master, "${first_target} ${extra_tgt}", 'etcd.server.setup')
    }

    // Run k8s master at *01* to simplify namespaces creation
    first_target = salt.getFirstMinion(master, "I@kubernetes:master ${extra_tgt}")
    salt.enforceStateWithExclude(master, "${first_target} ${extra_tgt}", "kubernetes.master", "kubernetes.master.setup")

    // Run k8s without master.setup
    salt.enforceStateWithExclude(master, "I@kubernetes:master ${extra_tgt}", "kubernetes", "kubernetes.master.setup")

    // Run k8s master setup
    first_target = salt.getFirstMinion(master, "I@kubernetes:master ${extra_tgt}")
    salt.enforceState(master, "${first_target} ${extra_tgt}", 'kubernetes.master.setup')

    // Restart kubelet
    salt.runSaltProcessStep(master, "I@kubernetes:pool ${extra_tgt}", 'service.restart', ['kubelet'])
}


def installKubernetesCompute(master, extra_tgt = '') {
    def salt = new com.mirantis.mk.Salt()
    salt.fullRefresh(master, "*")

    // Bootstrap all nodes
    salt.enforceState(master, "I@kubernetes:pool ${extra_tgt}", 'linux')
    salt.enforceState(master, "I@kubernetes:pool ${extra_tgt}", 'salt.minion')
    salt.enforceState(master, "I@kubernetes:pool ${extra_tgt}", ['openssh', 'ntp'])

    // Create and distribute SSL certificates for services using salt state
    salt.enforceState(master, "I@kubernetes:pool ${extra_tgt}", 'salt.minion.cert')

    // Install docker
    salt.enforceState(master, "I@docker:host ${extra_tgt}", 'docker.host')

    // Install Kubernetes and Calico
    salt.enforceState(master, "I@kubernetes:pool ${extra_tgt}", 'kubernetes.pool')

    // Install Tiller and all configured releases
    if (salt.testTarget(master, "I@helm:client ${extra_tgt}")) {
        salt.enforceState(master, "I@helm:client ${extra_tgt}", 'helm')
    }
}


def installDockerSwarm(master, extra_tgt = '') {
    def salt = new com.mirantis.mk.Salt()

    //Install and Configure Docker
    if (salt.testTarget(master, "I@docker:swarm ${extra_tgt}")) {
        salt.enforceState(master, "I@docker:swarm ${extra_tgt}", 'docker.host')
        salt.enforceState(master, "I@docker:swarm:role:master ${extra_tgt}", 'docker.swarm')
        salt.enforceState(master, "I@docker:swarm ${extra_tgt}", 'salt.minion.grains')
        salt.runSaltProcessStep(master, "I@docker:swarm ${extra_tgt}", 'mine.update')
        salt.runSaltProcessStep(master, "I@docker:swarm ${extra_tgt}", 'saltutil.refresh_modules')
        sleep(5)
        salt.enforceState(master, "I@docker:swarm:role:master ${extra_tgt}", 'docker.swarm')
        if (salt.testTarget(master, "I@docker:swarm:role:manager ${extra_tgt}")){
            salt.enforceState(master, "I@docker:swarm:role:manager ${extra_tgt}", 'docker.swarm')
        }
        sleep(10)
        salt.cmdRun(master, "I@docker:swarm:role:master ${extra_tgt}", 'docker node ls')
    }
}


def installCicd(master, extra_tgt = '') {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def gerrit_compound = "I@gerrit:client and ci* ${extra_tgt}"
    def jenkins_compound = "I@jenkins:client and ci* ${extra_tgt}"

    salt.fullRefresh(master, gerrit_compound)
    salt.fullRefresh(master, jenkins_compound)

    salt.enforceState(master, "I@docker:swarm:role:master and I@jenkins:client ${extra_tgt}", 'docker.client', true, true, null, false, -1, 2)

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

      def host_pillar = salt.getPillar(master, gerrit_compound, 'gerrit:client:server:host')
      gerrit_host = salt.getReturnValues(host_pillar)

      def port_pillar = salt.getPillar(master, gerrit_compound, 'gerrit:client:server:http_port')
      gerrit_http_port = salt.getReturnValues(port_pillar)

      def scheme_pillar = salt.getPillar(master, gerrit_compound, 'gerrit:client:server:protocol')
      gerrit_http_scheme = salt.getReturnValues(scheme_pillar)

      gerrit_master_url = gerrit_http_scheme + '://' + gerrit_host + ':' + gerrit_http_port

    }

    timeout(wait_timeout) {
      common.infoMsg('Waiting for Gerrit to come up..')
      def check_gerrit_cmd = 'while true; do curl -sI -m 3 -o /dev/null -w' + " '" + '%{http_code}' + "' " + gerrit_master_url + '/ | grep 200 && break || sleep 1; done'
      salt.cmdRun(master, gerrit_compound, 'timeout ' + (wait_timeout*60+3) + ' /bin/sh -c -- ' + '"' + check_gerrit_cmd + '"')
    }

    // Jenkins
    def jenkins_master_url_pillar = salt.getPillar(master, jenkins_compound, '_param:jenkins_master_url')
    jenkins_master_url = salt.getReturnValues(jenkins_master_url_pillar)

    timeout(wait_timeout) {
      common.infoMsg('Waiting for Jenkins to come up..')
      def check_jenkins_cmd = 'while true; do curl -sI -m 3 -o /dev/null -w' + " '" + '%{http_code}' + "' " + jenkins_master_url + '/whoAmI/ | grep 200 && break || sleep 1; done'
      salt.cmdRun(master, jenkins_compound, 'timeout ' + (wait_timeout*60+3) + ' /bin/sh -c -- ' + '"' + check_jenkins_cmd + '"')
    }

    if (salt.testTarget(master, "I@openldap:client ${extra_tgt}")) {
        salt.enforceState(master, "I@openldap:client ${extra_tgt}", 'openldap', true, true, null, false, -1, 2)
    }

    if (salt.testTarget(master, "I@python:environment ${extra_tgt}")) {
        salt.enforceState(master, "I@python:environment ${extra_tgt}", 'python')
    }

    withEnv(['ASK_ON_ERROR=false']){
        retry(2){
            try{
                salt.enforceState(master, "I@gerrit:client ${extra_tgt}", 'gerrit')
            }catch(e){
                salt.fullRefresh(master, "I@gerrit:client ${extra_tgt}")
                throw e //rethrow for retry handler
            }
        }
        retry(2){
            try{
                salt.enforceState(master, "I@jenkins:client ${extra_tgt}", 'jenkins')
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
    def retries_wait = 20
    def retries = 15
    def first_target

    // Install core services for K8S environments:
    // HAProxy, Nginx and lusterFS clients
    // In case of OpenStack, those are already installed
    if (common.checkContains('STACK_INSTALL', 'k8s')) {
        salt.enforceState(master, "I@haproxy:proxy ${extra_tgt}", 'haproxy')
        salt.runSaltProcessStep(master, "I@haproxy:proxy ${extra_tgt}", 'service.status', ['haproxy'])

        if (salt.testTarget(master, "I@nginx:server ${extra_tgt}")) {
            salt.enforceState(master, "I@nginx:server ${extra_tgt}", 'nginx')
        }

        if (salt.testTarget(master, "I@glusterfs:client ${extra_tgt}")) {
            salt.enforceState(master, "I@glusterfs:client ${extra_tgt}", 'glusterfs.client', true, true, null, false, -1, 2)
        }
    }

    // Install MongoDB for Alerta
    if (salt.testTarget(master, "I@mongodb:server ${extra_tgt}")) {
        salt.enforceState(master, "I@mongodb:server ${extra_tgt}", 'mongodb.server')

        // Initialize mongodb replica set
        common.retry(5,20){
             salt.enforceState(master, "I@mongodb:server ${extra_tgt}", 'mongodb.cluster')
        }
    }

    //Install Telegraf
    salt.enforceState(master, "( I@telegraf:agent or I@telegraf:remote_agent ) ${extra_tgt}", 'telegraf')

    // Install Prometheus exporters
    if (salt.testTarget(master, "I@prometheus:exporters ${extra_tgt}")) {
        salt.enforceState(master, "I@prometheus:exporters ${extra_tgt}", 'prometheus')
    }

    //Install Elasticsearch and Kibana
    if (salt.testTarget(master, "I@elasticsearch:server:enabled:true ${extra_tgt}")) {
        first_target = salt.getFirstMinion(master, "I@elasticsearch:server:enabled:true ${extra_tgt}")
        salt.enforceState(master, "${first_target} ${extra_tgt}", 'elasticsearch.server')
    }
    if (salt.testTarget(master, "I@elasticsearch:server:enabled:true ${extra_tgt}")) {
        salt.enforceState(master, "I@elasticsearch:server:enabled:true ${extra_tgt}", 'elasticsearch.server')
    }
    if (salt.testTarget(master, "I@kibana:server:enabled:true ${extra_tgt}")) {
        first_target = salt.getFirstMinion(master, "I@kibana:server:enabled:true ${extra_tgt}")
        salt.enforceState(master, "${first_target} ${extra_tgt}", 'kibana.server')
    }
    if (salt.testTarget(master, "I@kibana:server:enabled:true ${extra_tgt}")) {
        salt.enforceState(master, "I@kibana:server:enabled:true ${extra_tgt}", 'kibana.server')
    }
    // Check ES health cluster status
    def pillar = salt.getPillar(master, "I@elasticsearch:client ${extra_tgt}", 'elasticsearch:client:server:host')
    def elasticsearch_vip
    if(!pillar['return'].isEmpty()) {
        elasticsearch_vip = pillar['return'][0].values()[0]
    } else {
        common.errorMsg('[ERROR] Elasticsearch VIP address could not be retrieved')
    }
    pillar = salt.getPillar(master, "I@elasticsearch:client ${extra_tgt}", 'elasticsearch:client:server:port')
    def elasticsearch_port
    if(!pillar['return'].isEmpty()) {
        elasticsearch_port = pillar['return'][0].values()[0]
    } else {
        common.errorMsg('[ERROR] Elasticsearch VIP port could not be retrieved')
    }
    common.retry(retries,retries_wait) {
        common.infoMsg('Waiting for Elasticsearch to become green..')
        salt.cmdRun(master, "I@elasticsearch:client ${extra_tgt}", "curl -sf ${elasticsearch_vip}:${elasticsearch_port}/_cat/health | awk '{print \$4}' | grep green")
    }

    common.retry(retries,retries_wait) {
        salt.enforceState(master, "I@elasticsearch:client ${extra_tgt}", 'elasticsearch.client')
    }

    common.retry(retries,retries_wait) {
        salt.enforceState(master, "I@kibana:client ${extra_tgt}", 'kibana.client')
    }

    //Install InfluxDB
    if (salt.testTarget(master, "I@influxdb:server ${extra_tgt}")) {
        first_target = salt.getFirstMinion(master, "I@influxdb:server ${extra_tgt}")
        salt.enforceState(master, "${first_target} ${extra_tgt}", 'influxdb')
        salt.enforceState(master, "I@influxdb:server ${extra_tgt}", 'influxdb')
    }

    // Install service for the log collection
    if (salt.testTarget(master, "I@fluentd:agent ${extra_tgt}")) {
        salt.enforceState(master, "I@fluentd:agent ${extra_tgt}", 'fluentd')
    } else {
        salt.enforceState(master, "I@heka:log_collector ${extra_tgt}", 'heka.log_collector')
    }

    // Install heka ceilometer collector
    if (salt.testTarget(master, "I@heka:ceilometer_collector:enabled ${extra_tgt}")) {
        salt.enforceState(master, "I@heka:ceilometer_collector:enabled ${extra_tgt}", 'heka.ceilometer_collector')
        salt.runSaltProcessStep(master, "I@heka:ceilometer_collector:enabled ${extra_tgt}", 'service.restart', ['ceilometer_collector'], null, true)
    }

    // Install galera
    if (common.checkContains('STACK_INSTALL', 'k8s')) {
        salt.enforceState(master, "I@galera:master ${extra_tgt}", 'galera', true, true, null, false, -1, 2)
        salt.enforceState(master, "I@galera:slave ${extra_tgt}", 'galera', true, true, null, false, -1, 2)

        // Check galera status
        salt.runSaltProcessStep(master, "I@galera:master ${extra_tgt}", 'mysql.status')
        salt.runSaltProcessStep(master, "I@galera:slave ${extra_tgt}", 'mysql.status')
    }

    //Collect Grains
    salt.enforceState(master, "I@salt:minion ${extra_tgt}", 'salt.minion.grains')
    salt.runSaltProcessStep(master, "I@salt:minion ${extra_tgt}", 'saltutil.refresh_modules')
    salt.runSaltProcessStep(master, "I@salt:minion ${extra_tgt}", 'mine.update')
    sleep(5)

    // Configure Prometheus in Docker Swarm
    salt.enforceState(master, "I@docker:swarm and I@prometheus:server ${extra_tgt}", 'prometheus')

    //Configure Remote Collector in Docker Swarm for Openstack deployments
    if (!common.checkContains('STACK_INSTALL', 'k8s')) {
        salt.enforceState(master, "I@docker:swarm and I@prometheus:server ${extra_tgt}", 'heka.remote_collector', true, false)
    }

    // Launch containers
    salt.enforceState(master, "I@docker:swarm:role:master and I@prometheus:server ${extra_tgt}", 'docker.client')
    salt.runSaltProcessStep(master, "I@docker:swarm and I@prometheus:server ${extra_tgt}", 'dockerng.ps')

    //Install Prometheus LTS
    if (salt.testTarget(master, "I@prometheus:relay ${extra_tgt}")) {
        salt.enforceState(master, "I@prometheus:relay ${extra_tgt}", 'prometheus')
    }

    // Install sphinx server
    if (salt.testTarget(master, "I@sphinx:server ${extra_tgt}")) {
        salt.enforceState(master, "I@sphinx:server ${extra_tgt}", 'sphinx')
    }

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
    salt.enforceState(master, "I@grafana:client ${extra_tgt}", 'grafana.client')
}

def installStacklightv1Control(master, extra_tgt = '') {
    def salt = new com.mirantis.mk.Salt()

    // infra install
    // Install the StackLight backends
    salt.enforceState(master, "*01* and I@elasticsearch:server ${extra_tgt}", 'elasticsearch.server')
    salt.enforceState(master, "I@elasticsearch:server ${extra_tgt}", 'elasticsearch.server')

    salt.enforceState(master, "*01* and I@influxdb:server ${extra_tgt}", 'influxdb')
    salt.enforceState(master, "I@influxdb:server ${extra_tgt}", 'influxdb')

    salt.enforceState(master, "*01* and I@kibana:server ${extra_tgt}", 'kibana.server')
    salt.enforceState(master, "I@kibana:server ${extra_tgt}", 'kibana.server')

    salt.enforceState(master, "*01* and I@grafana:server ${extra_tgt}",'grafana.server')
    salt.enforceState(master, "I@grafana:server ${extra_tgt}",'grafana.server')

    def alarming_service_pillar = salt.getPillar(master, "mon*01* ${extra_tgt}", '_param:alarming_service')
    def alarming_service = alarming_service_pillar['return'][0].values()[0]

    switch (alarming_service) {
        case 'sensu':
            // Update Sensu
            salt.enforceState(master, "I@sensu:server and I@rabbitmq:server ${extra_tgt}", 'rabbitmq')
            salt.enforceState(master, "I@redis:cluster:role:master ${extra_tgt}", 'redis')
            salt.enforceState(master, "I@redis:server ${extra_tgt}", 'redis')
            salt.enforceState(master, "I@sensu:server ${extra_tgt}", 'sensu')
        default:
            // Update Nagios
            salt.enforceState(master, "I@nagios:server ${extra_tgt}", 'nagios.server')
            // Stop the Nagios service because the package starts it by default and it will
            // started later only on the node holding the VIP address
            salt.runSaltProcessStep(master, "I@nagios:server ${extra_tgt}", 'service.stop', ['nagios3'], null, true)
    }

    salt.enforceState(master, "I@elasticsearch:client ${extra_tgt}", 'elasticsearch.client.service')
    salt.enforceState(master, "I@kibana:client ${extra_tgt}", 'kibana.client')

    sleep(10)
}

def installStacklightv1Client(master, extra_tgt = '') {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()

    salt.cmdRun(master, "I@elasticsearch:client ${extra_tgt}", 'salt-call state.sls elasticsearch.client')
    // salt.enforceState(master, "I@elasticsearch:client", 'elasticsearch.client", true)
    salt.cmdRun(master, "I@kibana:client ${extra_tgt}", 'salt-call state.sls kibana.client')
    // salt.enforceState(master, "I@kibana:client", 'kibana.client", true)

    // Install collectd, heka and sensu services on the nodes, this will also
    // generate the metadata that goes into the grains and eventually into Salt Mine
    salt.enforceState(master, "* ${extra_tgt}", 'collectd')
    salt.enforceState(master, "* ${extra_tgt}", 'salt.minion')
    salt.enforceState(master, "* ${extra_tgt}", 'heka')

    // Gather the Grafana metadata as grains
    salt.enforceState(master, "I@grafana:collector ${extra_tgt}", 'grafana.collector', true)

    // Update Salt Mine
    salt.enforceState(master, "* ${extra_tgt}", 'salt.minion.grains')
    salt.runSaltProcessStep(master, "* ${extra_tgt}", 'saltutil.refresh_modules')
    salt.runSaltProcessStep(master, "* ${extra_tgt}", 'mine.update')

    sleep(5)

    // Update Heka
    salt.enforceState(master, "( I@heka:aggregator:enabled:True or I@heka:remote_collector:enabled:True ) ${extra_tgt}", 'heka')

    // Update collectd
    salt.enforceState(master, "I@collectd:remote_client:enabled:True ${extra_tgt}", 'collectd')

    def alarming_service_pillar = salt.getPillar(master, "mon*01* ${extra_tgt}", '_param:alarming_service')
    def alarming_service = alarming_service_pillar['return'][0].values()[0]

    switch (alarming_service) {
        case 'sensu':
            // Update Sensu
            // TODO for stacklight team, should be fixed in model
            salt.enforceState(master, "I@sensu:client ${extra_tgt}", 'sensu')
        default:
            break
            // Default is nagios, and was enforced in installStacklightControl()
    }

    salt.cmdRun(master, "I@grafana:client and *01* ${extra_tgt}", 'salt-call state.sls grafana.client')
    // salt.enforceState(master, "I@grafana:client and *01*", 'grafana.client", true)

    // Finalize the configuration of Grafana (add the dashboards...)
    salt.enforceState(master, "I@grafana:client and *01* ${extra_tgt}", 'grafana.client')
    salt.enforceState(master, "I@grafana:client and *02* ${extra_tgt}", 'grafana.client')
    salt.enforceState(master, "I@grafana:client and *03* ${extra_tgt}", 'grafana.client')
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
            salt.enforceState(master, "I@backupninja:client ${extra_tgt}", 'salt.minion.grains')
            salt.runSaltProcessStep(master, "I@backupninja:client ${extra_tgt}", 'saltutil.sync_grains')
            salt.runSaltProcessStep(master, "I@backupninja:client ${extra_tgt}", 'mine.flush')
            salt.runSaltProcessStep(master, "I@backupninja:client ${extra_tgt}", 'mine.update')
            salt.enforceState(master, "I@backupninja:client ${extra_tgt}", 'backupninja')
        }
        if (salt.testTarget(master, "I@backupninja:server ${extra_tgt}")) {
            salt.enforceState(master, "I@backupninja:server ${extra_tgt}", 'salt.minion.grains')
            salt.enforceState(master, "I@backupninja:server ${extra_tgt}", 'backupninja')
        }
    } else if (component == 'mysql') {
        // Install Xtrabackup
        if (salt.testTarget(master, "I@xtrabackup:client ${extra_tgt}")) {
            salt.enforceState(master, "I@xtrabackup:client ${extra_tgt}", 'salt.minion.grains')
            salt.runSaltProcessStep(master, "I@xtrabackup:client ${extra_tgt}", 'saltutil.sync_grains')
            salt.runSaltProcessStep(master, "I@xtrabackup:client ${extra_tgt}", 'mine.flush')
            salt.runSaltProcessStep(master, "I@xtrabackup:client ${extra_tgt}", 'mine.update')
            salt.enforceState(master, "I@xtrabackup:client ${extra_tgt}", 'xtrabackup')
        }
        if (salt.testTarget(master, "I@xtrabackup:server ${extra_tgt}")) {
            salt.enforceState(master, "I@xtrabackup:server ${extra_tgt}", 'xtrabackup')
        }
    } else if (component == 'contrail') {

        // Install Cassandra backup
        if (salt.testTarget(master, "I@cassandra:backup:client ${extra_tgt}")) {
            salt.enforceState(master, "I@cassandra:backup:client ${extra_tgt}", 'salt.minion.grains')
            salt.runSaltProcessStep(master, "I@cassandra:backup:client ${extra_tgt}", 'saltutil.sync_grains')
            salt.runSaltProcessStep(master, "I@cassandra:backup:client ${extra_tgt}", 'mine.flush')
            salt.runSaltProcessStep(master, "I@cassandra:backup:client ${extra_tgt}", 'mine.update')
            salt.enforceState(master, "I@cassandra:backup:client ${extra_tgt}", 'cassandra.backup')
        }
        if (salt.testTarget(master, "I@cassandra:backup:server ${extra_tgt}")) {
            salt.enforceState(master, "I@cassandra:backup:server ${extra_tgt}", 'cassandra.backup')
        }
        // Install Zookeeper backup
        if (salt.testTarget(master, "I@zookeeper:backup:client ${extra_tgt}")) {
            salt.enforceState(master, "I@zookeeper:backup:client ${extra_tgt}", 'salt.minion.grains')
            salt.runSaltProcessStep(master, "I@zookeeper:backup:client ${extra_tgt}", 'saltutil.sync_grains')
            salt.runSaltProcessStep(master, "I@zookeeper:backup:client ${extra_tgt}", 'mine.flush')
            salt.runSaltProcessStep(master, "I@zookeeper:backup:client ${extra_tgt}", 'mine.update')
            salt.enforceState(master, "I@zookeeper:backup:client ${extra_tgt}", 'zookeeper.backup')
        }
        if (salt.testTarget(master, "I@zookeeper:backup:server ${extra_tgt}")) {
            salt.enforceState(master, "I@zookeeper:backup:server ${extra_tgt}", 'zookeeper.backup')
        }
    } else if (component == 'ceph') {
        // Install Ceph backup
        if (salt.testTarget(master, "I@ceph:backup:client ${extra_tgt}")) {
            salt.enforceState(master, "I@ceph:backup:client ${extra_tgt}", 'salt.minion.grains')
            salt.runSaltProcessStep(master, "I@ceph:backup:client ${extra_tgt}", 'saltutil.sync_grains')
            salt.runSaltProcessStep(master, "I@ceph:backup:client ${extra_tgt}", 'mine.flush')
            salt.runSaltProcessStep(master, "I@ceph:backup:client ${extra_tgt}", 'mine.update')
            salt.enforceState(master, "I@ceph:backup:client ${extra_tgt}", 'ceph.backup')
        }
        if (salt.testTarget(master, "I@ceph:backup:server ${extra_tgt}")) {
            salt.enforceState(master, "I@ceph:backup:server ${extra_tgt}", 'ceph.backup')
        }
    }

}

//
// Ceph
//

def installCephMon(master, target="I@ceph:mon", extra_tgt = '') {
    def salt = new com.mirantis.mk.Salt()

    salt.enforceState(master, "I@ceph:common ${extra_tgt}", 'salt.minion.grains')

    // generate keyrings
    if (salt.testTarget(master, "( I@ceph:mon:keyring:mon or I@ceph:common:keyring:admin ) ${extra_tgt}")) {
        salt.enforceState(master, "( I@ceph:mon:keyring:mon or I@ceph:common:keyring:admin ) ${extra_tgt}", 'ceph.mon')
        salt.runSaltProcessStep(master, "I@ceph:mon ${extra_tgt}", 'saltutil.sync_grains')
        salt.runSaltProcessStep(master, "( I@ceph:mon:keyring:mon or I@ceph:common:keyring:admin ) ${extra_tgt}", 'mine.update')
        sleep(5)
    }
    // install Ceph Mons
    salt.enforceState(master, target, 'ceph.mon')
    if (salt.testTarget(master, "I@ceph:mgr ${extra_tgt}")) {
        salt.enforceState(master, "I@ceph:mgr ${extra_tgt}", 'ceph.mgr')
    }
}

def installCephOsd(master, target="I@ceph:osd", setup=true, extra_tgt = '') {
    def salt = new com.mirantis.mk.Salt()

    // install Ceph OSDs
    salt.enforceState(master, target, 'ceph.osd')
    salt.runSaltProcessStep(master, "I@ceph:osd ${extra_tgt}", 'saltutil.sync_grains')
    salt.enforceState(master, target, 'ceph.osd.custom')
    salt.runSaltProcessStep(master, "I@ceph:osd ${extra_tgt}", 'saltutil.sync_grains')
    salt.runSaltProcessStep(master, "I@ceph:osd ${extra_tgt}", 'mine.update')
    installBackup(master, 'ceph')

    // setup pools, keyrings and maybe crush
    if (salt.testTarget(master, "I@ceph:setup ${extra_tgt}") && setup) {
        sleep(5)
        salt.enforceState(master, "I@ceph:setup ${extra_tgt}", 'ceph.setup')
    }
}

def installCephClient(master, extra_tgt = '') {
    def salt = new com.mirantis.mk.Salt()

    // install Ceph Radosgw
    if (salt.testTarget(master, "I@ceph:radosgw ${extra_tgt}")) {
        salt.runSaltProcessStep(master, "I@ceph:radosgw ${extra_tgt}", 'saltutil.sync_grains')
        salt.enforceState(master, "I@ceph:radosgw ${extra_tgt}", 'ceph.radosgw')
    }
    // setup Keystone service and endpoints for swift or / and S3
    if (salt.testTarget(master, "I@keystone:client ${extra_tgt}")) {
        salt.enforceState(master, "I@keystone:client ${extra_tgt}", 'keystone.client')
    }
}

def connectCeph(master, extra_tgt = '') {
    def salt = new com.mirantis.mk.Salt()

    // connect Ceph to the env
    if (salt.testTarget(master, "I@ceph:common and I@glance:server ${extra_tgt}")) {
        salt.enforceState(master, "I@ceph:common and I@glance:server ${extra_tgt}", ['ceph.common', 'ceph.setup.keyring', 'glance'])
        salt.runSaltProcessStep(master, "I@ceph:common and I@glance:server ${extra_tgt}", 'service.restart', ['glance-api'])
    }
    if (salt.testTarget(master, "I@ceph:common and I@cinder:controller ${extra_tgt}")) {
        salt.enforceState(master, "I@ceph:common and I@cinder:controller ${extra_tgt}", ['ceph.common', 'ceph.setup.keyring', 'cinder'])
        salt.runSaltProcessStep(master, "I@ceph:common and I@cinder:controller ${extra_tgt}", 'service.restart', ['cinder-volume'])
    }
    if (salt.testTarget(master, "I@ceph:common and I@nova:compute ${extra_tgt}")) {
        salt.enforceState(master, "I@ceph:common and I@nova:compute ${extra_tgt}", ['ceph.common', 'ceph.setup.keyring'])
        salt.runSaltProcessStep(master, "I@ceph:common and I@nova:compute ${extra_tgt}", 'saltutil.sync_grains')
        salt.enforceState(master, "I@ceph:common and I@nova:compute ${extra_tgt}", ['nova'])
        salt.runSaltProcessStep(master, "I@ceph:common and I@nova:compute ${extra_tgt}", 'service.restart', ['nova-compute'])
    }
}

def installOssInfra(master, extra_tgt = '') {
  def common = new com.mirantis.mk.Common()
  def salt = new com.mirantis.mk.Salt()

  if (salt.testTarget(master, "I@devops_portal:config ${extra_tgt}")) {
    salt.enforceState(master, "I@devops_portal:config ${extra_tgt}", 'devops_portal.config')
    salt.enforceState(master, "I@rundeck:client ${extra_tgt}", ['linux.system.user', 'openssh'])
    salt.enforceState(master, "I@rundeck:server ${extra_tgt}", 'rundeck.server')
  }
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
  salt.enforceState(master, "I@postgresql:client ${extra_tgt}", 'postgresql.client', true, false)

  // Setup postgres database with integration between
  // Pushkin notification service and Security Monkey security audit service
  timeout(10) {
    common.infoMsg("Waiting for Pushkin to come up..")
    salt.cmdRun(master, "I@postgresql:client ${extra_tgt}", "while true; do curl -sf ${oss_vip}:8887/apps >/dev/null && break; done")
  }
  salt.enforceState(master, "I@postgresql:client ${extra_tgt}", 'postgresql.client')

  // Rundeck
  timeout(10) {
    common.infoMsg("Waiting for Rundeck to come up..")
    salt.cmdRun(master, "I@rundeck:client ${extra_tgt}", "while true; do curl -sf ${oss_vip}:4440 >/dev/null && break; done")
  }
  salt.enforceState(master, "I@rundeck:client ${extra_tgt}", 'rundeck.client')

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
  salt.enforceState(master, "I@elasticsearch:client ${extra_tgt}", 'elasticsearch.client')
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
