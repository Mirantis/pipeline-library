package com.mirantis.mk
/**
 * Orchestration functions
 *
*/

def validateFoundationInfra(master) {
    def salt = new com.mirantis.mk.Salt()

    salt.cmdRun(master, 'I@salt:master' ,'salt-key')
    salt.runSaltProcessStep(master, 'I@salt:minion', 'test.version')
    salt.cmdRun(master, 'I@salt:master' ,'reclass-salt --top')
    salt.runSaltProcessStep(master, 'I@reclass:storage', 'reclass.inventory')
    salt.runSaltProcessStep(master, 'I@salt:minion', 'state.show_top')
}

def installFoundationInfra(master, staticMgmtNet=false) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()

    // NOTE(vsaienko) Apply reclass first, it may update cluster model
    // apply linux and salt.master salt.minion states afterwards to make sure
    // correct cluster model is used.
    salt.enforceState(master, 'I@salt:master', ['reclass'])

    salt.enforceState(master, 'I@salt:master', ['linux.system'])
    salt.enforceState(master, 'I@salt:master', ['salt.master'], true, false, null, false, 120, 2)
    salt.fullRefresh(master, "*")

    salt.enforceState(master, 'I@salt:master', ['salt.minion'], true, false, null, false, 60, 2)
    salt.enforceState(master, 'I@salt:master', ['salt.minion'])
    salt.fullRefresh(master, "*")
    salt.enforceState(master, '*', ['linux.network.proxy'], true, false, null, false, 60, 2)
    try {
        salt.enforceState(master, '*', ['salt.minion.base'], true, false, null, false, 60, 2)
        sleep(5)
    } catch (Throwable e) {
        common.warningMsg('Salt state salt.minion.base is not present in the Salt-formula yet.')
    }
    salt.enforceState(master, '*', ['linux.system'])
    if (staticMgmtNet) {
        salt.runSaltProcessStep(master, '*', 'cmd.shell', ["salt-call state.sls linux.network; salt-call service.restart salt-minion"], null, true, 60)
    }
    salt.enforceState(master, 'I@linux:system', ['linux', 'openssh', 'ntp', 'rsyslog'])
    salt.enforceState(master, '*', ['salt.minion'], true, false, null, false, 60, 2)
    sleep(5)
    salt.runSaltProcessStep(master, '*', 'mine.update', [], null, true)
    salt.enforceState(master, '*', ['linux.network.host'])

    // Install and configure iptables
    if (salt.testTarget(master, 'I@iptables:service')) {
        salt.enforceState(master, 'I@iptables:service', 'iptables')
    }
}

def installFoundationInfraOnTarget(master, target, staticMgmtNet=false) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()

    salt.enforceState(master, 'I@salt:master', ['reclass'], true, false, null, false, 120, 2)
    salt.fullRefresh(master, target)
    salt.enforceState(master, target, ['linux.network.proxy'], true, false, null, false, 60, 2)
    try {
        salt.enforceState(master, target, ['salt.minion.base'], true, false, null, false, 60, 2)
        sleep(5)
    } catch (Throwable e) {
        common.warningMsg('Salt state salt.minion.base is not present in the Salt-formula yet.')
    }
    salt.enforceState(master, target, ['linux.system'])
    if (staticMgmtNet) {
        salt.runSaltProcessStep(master, target, 'cmd.shell', ["salt-call state.sls linux.network; salt-call service.restart salt-minion"], null, true, 60)
    }
    salt.enforceState(master, target, ['salt.minion'], true, false, null, false, 60, 2)
    salt.enforceState(master, target, ['salt.minion'])

    salt.enforceState(master, target, ['linux', 'openssh', 'ntp', 'rsyslog'])
    sleep(5)
    salt.runSaltProcessStep(master, target, 'mine.update', [], null, true)
    salt.enforceState(master, target, ['linux.network.host'])
}

def installInfraKvm(master) {
    def salt = new com.mirantis.mk.Salt()
    salt.fullRefresh(master, 'I@linux:system')

    salt.enforceState(master, 'I@salt:control', ['salt.minion'], true, false, null, false, 60, 2)
    salt.enforceState(master, 'I@salt:control', ['linux.system', 'linux.network', 'ntp', 'rsyslog'])
    salt.enforceState(master, 'I@salt:control', 'libvirt')
    salt.enforceState(master, 'I@salt:control', 'salt.control')

    sleep(600)

    salt.fullRefresh(master, '* and not kvm*')
}

def installInfra(master) {
    def common = new com.mirantis.mk.Common()
    def salt = new com.mirantis.mk.Salt()

    // Install glusterfs
    if (salt.testTarget(master, 'I@glusterfs:server')) {
        salt.enforceState(master, 'I@glusterfs:server', 'glusterfs.server.service')

        salt.enforceState(master, 'I@glusterfs:server and *01*', 'glusterfs.server.setup', true, true, null, false, -1, 5)
        sleep(10)
        salt.cmdRun(master, 'I@glusterfs:server', "gluster peer status; gluster volume status")
    }

    // Ensure glusterfs clusters is ready
    if (salt.testTarget(master, 'I@glusterfs:client')) {
        salt.enforceState(master, 'I@glusterfs:client', 'glusterfs.client')
    }

    // Install galera
    if (salt.testTarget(master, 'I@galera:master') || salt.testTarget(master, 'I@galera:slave')) {
        salt.enforceState(master, 'I@galera:master', 'galera', true, true, null, false, -1, 2)
        salt.enforceState(master, 'I@galera:slave', 'galera', true, true, null, false, -1, 2)

        // Check galera status
        salt.runSaltProcessStep(master, 'I@galera:master', 'mysql.status')
        salt.runSaltProcessStep(master, 'I@galera:slave', 'mysql.status')
    // If galera is not enabled check if we need to install mysql:server
    } else if (salt.testTarget(master, 'I@mysql:server')){
        salt.enforceState(master, 'I@mysql:server', 'mysql.server')
        if (salt.testTarget(master, 'I@mysql:client')){
            salt.enforceState(master, 'I@mysql:client', 'mysql.client')
        }
    }
    installBackup(master, 'mysql')

    // Install docker
    if (salt.testTarget(master, 'I@docker:host')) {
        salt.enforceState(master, 'I@docker:host', 'docker.host')
        salt.cmdRun(master, 'I@docker:host', 'docker ps')
    }

    // Install keepalived
    if (salt.testTarget(master, 'I@keepalived:cluster')) {
        salt.enforceState(master, 'I@keepalived:cluster and *01*', 'keepalived')
        salt.enforceState(master, 'I@keepalived:cluster', 'keepalived')
    }

    // Install rabbitmq
    if (salt.testTarget(master, 'I@rabbitmq:server')) {
        salt.enforceState(master, 'I@rabbitmq:server', 'rabbitmq', true, true, null, false, -1, 2)

        // Check the rabbitmq status
        common.retry(3,5){
             salt.cmdRun(master, 'I@rabbitmq:server', 'rabbitmqctl cluster_status')
        }
    }

    // Install haproxy
    if (salt.testTarget(master, 'I@haproxy:proxy')) {
        salt.enforceState(master, 'I@haproxy:proxy', 'haproxy')
        salt.runSaltProcessStep(master, 'I@haproxy:proxy', 'service.status', ['haproxy'])
        salt.runSaltProcessStep(master, 'I@haproxy:proxy', 'service.restart', ['rsyslog'])
    }

    // Install memcached
    if (salt.testTarget(master, 'I@memcached:server')) {
        salt.enforceState(master, 'I@memcached:server', 'memcached')
    }

    // Install etcd
    if (salt.testTarget(master, 'I@etcd:server')) {
        salt.enforceState(master, 'I@etcd:server', 'etcd.server.service')
        common.retry(3,5){
            salt.cmdRun(master, 'I@etcd:server', '. /var/lib/etcd/configenv && etcdctl cluster-health')
        }
    }

    // Install redis
    if (salt.testTarget(master, 'I@redis:server')) {
        if (salt.testTarget(master, 'I@redis:cluster:role:master')) {
            salt.enforceState(master, 'I@redis:cluster:role:master', 'redis')
        }
        salt.enforceState(master, 'I@redis:server', 'redis')
    }
    installBackup(master, 'common')
}

def installOpenstackInfra(master) {
    def common = new com.mirantis.mk.Common()
    common.warningMsg("You calling orchestrate.installOpenstackInfra(). This function is deprecated please use orchestrate.installInfra() directly")
    installInfra(master)
}


def installOpenstackControl(master) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()

    // Install horizon dashboard
    if (salt.testTarget(master, 'I@horizon:server')) {
        salt.enforceState(master, 'I@horizon:server', 'horizon')
    }
    // Install sphinx server
    if (salt.testTarget(master, 'I@sphinx:server')) {
        salt.enforceState(master, 'I@sphinx:server', 'sphinx')
    }
    if (salt.testTarget(master, 'I@nginx:server')) {
        salt.enforceState(master, 'I@nginx:server', 'salt.minion')
        salt.enforceState(master, 'I@nginx:server', 'nginx')
    }

    // setup keystone service
    if (salt.testTarget(master, 'I@keystone:server')) {
        salt.enforceState(master, 'I@keystone:server and *01*', 'keystone.server')
        salt.enforceState(master, 'I@keystone:server', 'keystone.server')
        // populate keystone services/tenants/roles/users

        // keystone:client must be called locally
        //salt.runSaltProcessStep(master, 'I@keystone:client', 'cmd.run', ['salt-call state.sls keystone.client'], null, true)
        salt.runSaltProcessStep(master, 'I@keystone:server', 'service.restart', ['apache2'])
        sleep(30)
    }
    if (salt.testTarget(master, 'I@keystone:client')) {
        salt.enforceState(master, 'I@keystone:client and *01*', 'keystone.client')
        salt.enforceState(master, 'I@keystone:client', 'keystone.client')
    }
    if (salt.testTarget(master, 'I@keystone:server')) {
        common.retry(3,5){
            salt.cmdRun(master, 'I@keystone:server', '. /root/keystonercv3; openstack service list')
        }
    }

    // Install glance
    if (salt.testTarget(master, 'I@glance:server')) {
        //runSaltProcessStep(master, 'I@glance:server', 'state.sls', ['glance.server'], 1)
        salt.enforceState(master, 'I@glance:server and *01*', 'glance.server')
       salt.enforceState(master, 'I@glance:server', 'glance.server')
    }

    // Check glance service
    if (salt.testTarget(master, 'I@glance:server')){
        common.retry(3,5){
            salt.cmdRun(master, 'I@keystone:server','. /root/keystonercv3; glance image-list')
        }
    }

    // Create glance resources
    if (salt.testTarget(master, 'I@glance:client')) {
        salt.enforceState(master, 'I@glance:client', 'glance.client')
    }

    // Install and check nova service
    if (salt.testTarget(master, 'I@nova:controller')) {
        // run on first node first
        salt.enforceState(master, 'I@nova:controller and *01*', 'nova.controller')
        salt.enforceState(master, 'I@nova:controller', 'nova.controller')
        if (salt.testTarget(master, 'I@keystone:server')) {
           common.retry(3,5){
               salt.cmdRun(master, 'I@keystone:server', '. /root/keystonercv3; nova service-list')
           }
        }
    }

    // Create nova resources
    if (salt.testTarget(master, 'I@nova:client')) {
        salt.enforceState(master, 'I@nova:client', 'nova.client')
    }

    // Install and check cinder service
    if (salt.testTarget(master, 'I@cinder:controller')) {
        // run on first node first
        salt.enforceState(master, 'I@cinder:controller and *01*', 'cinder')
        salt.enforceState(master, 'I@cinder:controller', 'cinder')
        if (salt.testTarget(master, 'I@keystone:server')) {
            common.retry(3,5){
                salt.cmdRun(master, 'I@keystone:server', '. /root/keystonercv3; cinder list')
            }
        }
    }

    // Install neutron service
    if (salt.testTarget(master, 'I@neutron:server')) {
        // run on first node first
        salt.enforceState(master, 'I@neutron:server and *01*', 'neutron.server')
        salt.enforceState(master, 'I@neutron:server', 'neutron.server')
        if (salt.testTarget(master, 'I@keystone:server')) {
            common.retry(3,5){
                salt.cmdRun(master, 'I@keystone:server','. /root/keystonercv3; neutron agent-list')
            }
        }
    }

    // Install heat service
    if (salt.testTarget(master, 'I@heat:server')) {
        // run on first node first
        salt.enforceState(master, 'I@heat:server and *01*', 'heat')
        salt.enforceState(master, 'I@heat:server', 'heat')
        if (salt.testTarget(master, 'I@keystone:server')) {
            common.retry(3,5){
                salt.cmdRun(master, 'I@keystone:server', '. /root/keystonercv3; heat resource-type-list')
            }
        }
    }

    // Restart nova api
    if (salt.testTarget(master, 'I@nova:controller')) {
        salt.runSaltProcessStep(master, 'I@nova:controller', 'service.restart', ['nova-api'])
    }

    // Install ironic service
    if (salt.testTarget(master, 'I@ironic:api')) {
        salt.enforceState(master, 'I@ironic:api and *01*', 'ironic.api')
        salt.enforceState(master, 'I@ironic:api', 'ironic.api')
    }

    // Install manila service
    if (salt.testTarget(master, 'I@manila:api')) {
        salt.enforceState(master, 'I@manila:api and *01*', 'manila.api')
        salt.enforceState(master, 'I@manila:api', 'manila.api')
    }
    if (salt.testTarget(master, 'I@manila:scheduler')) {
        salt.enforceState(master, 'I@manila:scheduler', 'manila.scheduler')
    }

    // Install designate services
    if (salt.testTarget(master, 'I@designate:server:enabled')) {
        if (salt.testTarget(master, 'I@designate:server:backend:bind9')) {
            salt.enforceState(master, 'I@bind:server', 'bind.server')
        }
        if (salt.testTarget(master, 'I@designate:server:backend:pdns4')) {
            salt.enforceState(master, 'I@powerdns:server', 'powerdns.server')
        }
        salt.enforceState(master, 'I@designate:server and *01*', 'designate.server')
        salt.enforceState(master, 'I@designate:server', 'designate')
    }

    // Install octavia api service
    if (salt.testTarget(master, 'I@octavia:api')) {
        salt.enforceState(master, 'I@octavia:api and *01*', 'octavia')
        salt.enforceState(master, 'I@octavia:api', 'octavia')
    }

    // Install DogTag server service
    if (salt.testTarget(master, 'I@dogtag:server')) {
        salt.enforceState(master, 'I@dogtag:server and *01*', 'dogtag.server')
        salt.enforceState(master, 'I@dogtag:server', 'dogtag.server')
    }

    // Install barbican server service
    if (salt.testTarget(master, 'I@barbican:server')) {
        salt.enforceState(master, 'I@barbican:server and *01*', 'barbican.server')
        salt.enforceState(master, 'I@barbican:server', 'barbican.server')
    }
    // Install barbican client
    if (salt.testTarget(master, 'I@barbican:client')) {
        salt.enforceState(master, 'I@barbican:client', 'barbican.client')
    }

    // Install gnocchi server
    if (salt.testTarget(master, 'I@gnocchi:server')) {
        salt.enforceState(master, 'I@gnocchi:server and *01*', 'gnocchi.server')
        salt.enforceState(master, 'I@gnocchi:server', 'gnocchi.server')
    }

    // Install gnocchi statsd
    if (salt.testTarget(master, 'I@gnocchi:statsd')) {
        salt.enforceState(master, 'I@gnocchi:statsd and *01*', 'gnocchi.statsd')
        salt.enforceState(master, 'I@gnocchi:statsd', 'gnocchi.statsd')
    }

    // Install panko server
    if (salt.testTarget(master, 'I@panko:server')) {
        salt.enforceState(master, 'I@panko:server and *01*', 'panko')
        salt.enforceState(master, 'I@panko:server', 'panko')
    }

    // Install ceilometer server
    if (salt.testTarget(master, 'I@ceilometer:server')) {
        salt.enforceState(master, 'I@ceilometer:server and *01*', 'ceilometer')
        salt.enforceState(master, 'I@ceilometer:server', 'ceilometer')
    }

    // Install aodh server
    if (salt.testTarget(master, 'I@aodh:server')) {
        salt.enforceState(master, 'I@aodh:server and *01*', 'aodh')
        salt.enforceState(master, 'I@aodh:server', 'aodh')
    }
}


def installIronicConductor(master){
    def salt = new com.mirantis.mk.Salt()

    if (salt.testTarget(master, 'I@ironic:conductor')) {
        salt.enforceState(master, 'I@ironic:conductor', 'ironic.conductor')
        salt.enforceState(master, 'I@ironic:conductor', 'apache')
    }
    if (salt.testTarget(master, 'I@tftpd_hpa:server')) {
        salt.enforceState(master, 'I@tftpd_hpa:server', 'tftpd_hpa')
    }

    if (salt.testTarget(master, 'I@nova:compute')) {
        salt.runSaltProcessStep(master, 'I@nova:compute', 'service.restart', ['nova-compute'])
    }

    if (salt.testTarget(master, 'I@baremetal_simulator:enabled')) {
        salt.enforceState(master, 'I@baremetal_simulator:enabled', 'baremetal_simulator')
    }
    if (salt.testTarget(master, 'I@ironic:client')) {
        salt.enforceState(master, 'I@ironic:client', 'ironic.client')
    }
}

def installManilaShare(master){
    def salt = new com.mirantis.mk.Salt()

    if (salt.testTarget(master, 'I@manila:share')) {
        salt.enforceState(master, 'I@manila:share', 'manila.share')
    }
    if (salt.testTarget(master, 'I@manila:data')) {
        salt.enforceState(master, 'I@manila:data', 'manila.data')
    }

    if (salt.testTarget(master, 'I@manila:client')) {
        salt.enforceState(master, 'I@manila:client', 'manila.client')
    }
}


def installOpenstackNetwork(master, physical = "false") {
    def salt = new com.mirantis.mk.Salt()
    //run full neutron state on neutron.gateway - this will install
    //neutron agents in addition to neutron server. Once neutron agents
    //are up neutron resources can be created without hitting the situation when neutron resources are created
    //prior to neutron agents which results in creating ports in non-usable state
    if (salt.testTarget(master, 'I@neutron:gateway')) {
            salt.enforceState(master, 'I@neutron:gateway', 'neutron')
    }

    // Create neutron resources - this step was moved here to ensure that
    //neutron resources are created after neutron agens are up. In this case neutron ports will be in
    //usable state. More information: https://bugs.launchpad.net/neutron/+bug/1399249
    if (salt.testTarget(master, 'I@neutron:client')) {
        salt.enforceState(master, 'I@neutron:client', 'neutron.client')
    }

    salt.enforceHighstate(master, 'I@neutron:gateway')

    // install octavia manager services
    if (salt.testTarget(master, 'I@octavia:manager')) {
        salt.runSaltProcessStep(master, 'I@salt:master', 'mine.update', ['*'])
        salt.enforceState(master, 'I@octavia:manager', 'octavia')
        salt.enforceState(master, 'I@octavia:manager', 'salt.minion.ca')
        salt.enforceState(master, 'I@octavia:manager', 'salt.minion.cert')
    }
}


def installOpenstackCompute(master) {
    def salt = new com.mirantis.mk.Salt()
    // Configure compute nodes
    def compute_compound = 'I@nova:compute'
    if (salt.testTarget(master, compute_compound)) {
        // In case if infrastructure nodes are used as nova computes too
        def gluster_compound = 'I@glusterfs:server'
        // Enforce highstate asynchronous only on compute nodes which are not glusterfs servers
        retry(2) {
            salt.enforceHighstateWithExclude(master, compute_compound + ' and not ' + gluster_compound, 'opencontrail.client')
        }
        // Iterate through glusterfs servers and check if they have compute role
        // TODO: switch to batch once salt 2017.7+ would be used
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
}


def installContrailNetwork(master) {
    def common = new com.mirantis.mk.Common()
    def salt = new com.mirantis.mk.Salt()


    // Install opencontrail database services
    salt.enforceState(master, 'I@opencontrail:database and *01*', 'opencontrail.database')
    salt.enforceState(master, 'I@opencontrail:database', 'opencontrail.database')

    // Install opencontrail control services
    salt.enforceStateWithExclude(master, "I@opencontrail:control and *01*", "opencontrail", "opencontrail.client")
    salt.enforceStateWithExclude(master, "I@opencontrail:control", "opencontrail", "opencontrail.client")
    salt.enforceStateWithExclude(master, "I@opencontrail:collector and *01*", "opencontrail", "opencontrail.client")

    if (salt.testTarget(master, 'I@docker:client and I@opencontrail:control')) {
        salt.enforceState(master, 'I@opencontrail:control or I@opencontrail:collector', 'docker.client')
    }
    installBackup(master, 'contrail')
}


def installContrailCompute(master) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    // Configure compute nodes
    // Provision opencontrail control services
    salt.enforceState(master, 'I@opencontrail:database:id:1', 'opencontrail.client')
    // Provision opencontrail virtual routers

    // Generate script /usr/lib/contrail/if-vhost0 for up vhost0
    if (salt.testTarget(master, 'I@opencontrail:compute')) {
        salt.enforceStateWithExclude(master, "I@opencontrail:compute", "opencontrail", "opencontrail.client")
    }

    if (salt.testTarget(master, 'I@nova:compute')) {
        salt.cmdRun(master, 'I@nova:compute', 'exec 0>&-; exec 1>&-; exec 2>&-; nohup bash -c "ip link | grep vhost && echo no_reboot || sleep 5 && reboot & "', false)
    }

    sleep(300)
    if (salt.testTarget(master, 'I@opencontrail:compute')) {
        salt.enforceState(master, 'I@opencontrail:compute', 'opencontrail.client')
        salt.enforceState(master, 'I@opencontrail:compute', 'opencontrail')
    }
}


def installKubernetesInfra(master) {
    def common = new com.mirantis.mk.Common()
    common.warningMsg("You calling orchestrate.installKubernetesInfra(). This function is deprecated please use orchestrate.installInfra() directly")
    installInfra(master)
}


def installKubernetesControl(master) {
    def salt = new com.mirantis.mk.Salt()

    // Install Kubernetes pool and Calico
    salt.enforceState(master, 'I@kubernetes:master', 'kubernetes.master.kube-addons')
    salt.enforceState(master, 'I@kubernetes:pool', 'kubernetes.pool')

    if (salt.testTarget(master, 'I@etcd:server:setup')) {
        // Setup etcd server
        salt.enforceState(master, 'I@kubernetes:master and *01*', 'etcd.server.setup')
    }

    // Run k8s without master.setup
    salt.enforceStateWithExclude(master, 'I@kubernetes:master', "kubernetes", "kubernetes.master.setup")

    // Run k8s master setup
    salt.enforceState(master, 'I@kubernetes:master and *01*', 'kubernetes.master.setup')

    // Restart kubelet
    salt.runSaltProcessStep(master, 'I@kubernetes:pool', 'service.restart', ['kubelet'])
}


def installKubernetesCompute(master) {
    def salt = new com.mirantis.mk.Salt()
    salt.fullRefresh(master, "*")

    // Bootstrap all nodes
    salt.enforceState(master, 'I@kubernetes:pool', 'linux')
    salt.enforceState(master, 'I@kubernetes:pool', 'salt.minion')
    salt.enforceState(master, 'I@kubernetes:pool', ['openssh', 'ntp'])

    // Create and distribute SSL certificates for services using salt state
    salt.enforceState(master, 'I@kubernetes:pool', 'salt.minion.cert')

    // Install docker
    salt.enforceState(master, 'I@docker:host', 'docker.host')

    // Install Kubernetes and Calico
    salt.enforceState(master, 'I@kubernetes:pool', 'kubernetes.pool')

    // Install Tiller and all configured releases
    if (salt.testTarget(master, 'I@helm:client')) {
        salt.enforceState(master, 'I@helm:client', 'helm')
    }
}


def installDockerSwarm(master) {
    def salt = new com.mirantis.mk.Salt()

    //Install and Configure Docker
    salt.enforceState(master, 'I@docker:swarm', 'docker.host')
    salt.enforceState(master, 'I@docker:swarm:role:master', 'docker.swarm')
    salt.enforceState(master, 'I@docker:swarm', 'salt.minion.grains')
    salt.runSaltProcessStep(master, 'I@docker:swarm', 'mine.update')
    salt.runSaltProcessStep(master, 'I@docker:swarm', 'saltutil.refresh_modules')
    sleep(5)
    salt.enforceState(master, 'I@docker:swarm:role:master', 'docker.swarm')
    salt.enforceState(master, 'I@docker:swarm:role:manager', 'docker.swarm')
    sleep(10)
    salt.cmdRun(master, 'I@docker:swarm:role:master', 'docker node ls')
}


def installCicd(master) {
    def salt = new com.mirantis.mk.Salt()
    salt.fullRefresh(master, 'I@jenkins:client or I@gerrit:client')

    if (salt.testTarget(master, 'I@aptly:publisher')) {
        salt.enforceState(master, 'I@aptly:publisher', 'aptly.publisher',true, null, false, -1, 2)
    }

    salt.enforceState(master, 'I@docker:swarm:role:master and I@jenkins:client', 'docker.client', true, true, null, false, -1, 2)
    sleep(500)

    if (salt.testTarget(master, 'I@aptly:server')) {
        salt.enforceState(master, 'I@aptly:server', 'aptly', true, true, null, false, -1, 2)
    }

    if (salt.testTarget(master, 'I@openldap:client')) {
        salt.enforceState(master, 'I@openldap:client', 'openldap', true, true, null, false, -1, 2)
    }

    if (salt.testTarget(master, 'I@python:environment')) {
        salt.enforceState(master, 'I@python:environment', 'python')
    }

    withEnv(['ASK_ON_ERROR=false']){
        retry(2){
            try{
                salt.enforceState(master, 'I@gerrit:client', 'gerrit')
            }catch(e){
                salt.fullRefresh(master, 'I@gerrit:client')
                throw e //rethrow for retry handler
            }
        }
        retry(2){
            try{
                salt.enforceState(master, 'I@jenkins:client', 'jenkins')
            }catch(e){
                salt.fullRefresh(master, 'I@jenkins:client')
                throw e //rethrow for retry handler
            }
        }
    }
}


def installStacklight(master) {
    def common = new com.mirantis.mk.Common()
    def salt = new com.mirantis.mk.Salt()

    // Install core services for K8S environments:
    // HAProxy, Nginx and lusterFS clients
    // In case of OpenStack, those are already installed
    if (common.checkContains('STACK_INSTALL', 'k8s')) {
        salt.enforceState(master, 'I@haproxy:proxy', 'haproxy')
        salt.runSaltProcessStep(master, 'I@haproxy:proxy', 'service.status', ['haproxy'])

        if (salt.testTarget(master, 'I@nginx:server')) {
            salt.enforceState(master, 'I@nginx:server', 'nginx')
        }

        if (salt.testTarget(master, 'I@glusterfs:client')) {
            salt.enforceState(master, 'I@glusterfs:client', 'glusterfs.client')
        }
    }

    // Launch containers
    salt.enforceState(master, 'I@docker:swarm:role:master and I@prometheus:server', 'docker.client')
    salt.runSaltProcessStep(master, 'I@docker:swarm and I@prometheus:server', 'dockerng.ps')

    //Install Telegraf
    salt.enforceState(master, 'I@telegraf:agent or I@telegraf:remote_agent', 'telegraf')

    // Install Prometheus exporters
    if (salt.testTarget(master, 'I@prometheus:exporters')) {
        salt.enforceState(master, 'I@prometheus:exporters', 'prometheus')
    }

    //Install Elasticsearch and Kibana
    salt.enforceState(master, '*01* and  I@elasticsearch:server', 'elasticsearch.server')
    salt.enforceState(master, 'I@elasticsearch:server', 'elasticsearch.server')
    salt.enforceState(master, '*01* and I@kibana:server', 'kibana.server')
    salt.enforceState(master, 'I@kibana:server', 'kibana.server')
    salt.enforceState(master, 'I@elasticsearch:client', 'elasticsearch.client')
    salt.enforceState(master, 'I@kibana:client', 'kibana.client')

    //Install InfluxDB
    if (salt.testTarget(master, 'I@influxdb:server')) {
        salt.enforceState(master, '*01* and I@influxdb:server', 'influxdb')
        salt.enforceState(master, 'I@influxdb:server', 'influxdb')
    }

    //Install Prometheus LTS
    if (salt.testTarget(master, 'I@prometheus:relay')) {
        salt.enforceState(master, 'I@prometheus:relay', 'prometheus')
    }

    // Install service for the log collection
    if (salt.testTarget(master, 'I@fluentd:agent')) {
        salt.enforceState(master, 'I@fluentd:agent', 'fluentd')
    } else {
        salt.enforceState(master, 'I@heka:log_collector', 'heka.log_collector')
    }

    // Install heka ceilometer collector
    if (salt.testTarget(master, 'I@heka:ceilometer_collector:enabled')) {
        salt.enforceState(master, 'I@heka:ceilometer_collector:enabled', 'heka.ceilometer_collector')
        salt.runSaltProcessStep(master, 'I@heka:ceilometer_collector:enabled', 'service.restart', ['ceilometer_collector'], null, true)
    }

    // Install galera
    if (common.checkContains('STACK_INSTALL', 'k8s')) {
        salt.enforceState(master, 'I@galera:master', 'galera', true, true, null, false, -1, 2)
        salt.enforceState(master, 'I@galera:slave', 'galera', true, true, null, false, -1, 2)

        // Check galera status
        salt.runSaltProcessStep(master, 'I@galera:master', 'mysql.status')
        salt.runSaltProcessStep(master, 'I@galera:slave', 'mysql.status')
    }

    //Collect Grains
    salt.enforceState(master, 'I@salt:minion', 'salt.minion.grains')
    salt.runSaltProcessStep(master, 'I@salt:minion', 'saltutil.refresh_modules')
    salt.runSaltProcessStep(master, 'I@salt:minion', 'mine.update')
    sleep(5)

    // Configure Prometheus in Docker Swarm
    salt.enforceState(master, 'I@docker:swarm and I@prometheus:server', 'prometheus')

    //Configure Remote Collector in Docker Swarm for Openstack deployments
    if (!common.checkContains('STACK_INSTALL', 'k8s')) {
        salt.enforceState(master, 'I@docker:swarm and I@prometheus:server', 'heka.remote_collector', true, false)
    }

    // Install sphinx server
    if (salt.testTarget(master, 'I@sphinx:server')) {
        salt.enforceState(master, 'I@sphinx:server', 'sphinx')
    }

    //Configure Grafana
    def pillar = salt.getPillar(master, 'ctl01*', '_param:stacklight_monitor_address')
    common.prettyPrint(pillar)

    def stacklight_vip
    if(!pillar['return'].isEmpty()) {
        stacklight_vip = pillar['return'][0].values()[0]
    } else {
        common.errorMsg('[ERROR] Stacklight VIP address could not be retrieved')
    }

    common.infoMsg("Waiting for service on http://${stacklight_vip}:15013/ to start")
    sleep(120)
    salt.enforceState(master, 'I@grafana:client', 'grafana.client')
}

def installStacklightv1Control(master) {
    def salt = new com.mirantis.mk.Salt()

    // infra install
    // Install the StackLight backends
    salt.enforceState(master, '*01* and  I@elasticsearch:server', 'elasticsearch.server')
    salt.enforceState(master, 'I@elasticsearch:server', 'elasticsearch.server')

    salt.enforceState(master, '*01* and I@influxdb:server', 'influxdb')
    salt.enforceState(master, 'I@influxdb:server', 'influxdb')

    salt.enforceState(master, '*01* and I@kibana:server', 'kibana.server')
    salt.enforceState(master, 'I@kibana:server', 'kibana.server')

    salt.enforceState(master, '*01* and I@grafana:server','grafana.server')
    salt.enforceState(master, 'I@grafana:server','grafana.server')

    def alarming_service_pillar = salt.getPillar(master, 'mon*01*', '_param:alarming_service')
    def alarming_service = alarming_service_pillar['return'][0].values()[0]

    switch (alarming_service) {
        case 'sensu':
            // Update Sensu
            salt.enforceState(master, 'I@sensu:server and I@rabbitmq:server', 'rabbitmq')
            salt.enforceState(master, 'I@redis:cluster:role:master', 'redis')
            salt.enforceState(master, 'I@redis:server', 'redis')
            salt.enforceState(master, 'I@sensu:server', 'sensu')
        default:
            // Update Nagios
            salt.enforceState(master, 'I@nagios:server', 'nagios.server')
            // Stop the Nagios service because the package starts it by default and it will
            // started later only on the node holding the VIP address
            salt.runSaltProcessStep(master, 'I@nagios:server', 'service.stop', ['nagios3'], null, true)
    }

    salt.enforceState(master, 'I@elasticsearch:client', 'elasticsearch.client.service')
    salt.enforceState(master, 'I@kibana:client', 'kibana.client')

    sleep(10)
}

def installStacklightv1Client(master) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()

    salt.cmdRun(master, 'I@elasticsearch:client', 'salt-call state.sls elasticsearch.client')
    // salt.enforceState(master, 'I@elasticsearch:client', 'elasticsearch.client', true)
    salt.cmdRun(master, 'I@kibana:client', 'salt-call state.sls kibana.client')
    // salt.enforceState(master, 'I@kibana:client', 'kibana.client', true)

    // Install collectd, heka and sensu services on the nodes, this will also
    // generate the metadata that goes into the grains and eventually into Salt Mine
    salt.enforceState(master, '*', 'collectd')
    salt.enforceState(master, '*', 'salt.minion')
    salt.enforceState(master, '*', 'heka')

    // Gather the Grafana metadata as grains
    salt.enforceState(master, 'I@grafana:collector', 'grafana.collector', true)

    // Update Salt Mine
    salt.enforceState(master, '*', 'salt.minion.grains')
    salt.runSaltProcessStep(master, '*', 'saltutil.refresh_modules')
    salt.runSaltProcessStep(master, '*', 'mine.update')

    sleep(5)

    // Update Heka
    salt.enforceState(master, 'I@heka:aggregator:enabled:True or I@heka:remote_collector:enabled:True', 'heka')

    // Update collectd
    salt.enforceState(master, 'I@collectd:remote_client:enabled:True', 'collectd')

    def alarming_service_pillar = salt.getPillar(master, 'mon*01*', '_param:alarming_service')
    def alarming_service = alarming_service_pillar['return'][0].values()[0]

    switch (alarming_service) {
        case 'sensu':
            // Update Sensu
            // TODO for stacklight team, should be fixed in model
            salt.enforceState(master, 'I@sensu:client', 'sensu')
        default:
            break
            // Default is nagios, and was enforced in installStacklightControl()
    }

    salt.cmdRun(master, 'I@grafana:client and *01*', 'salt-call state.sls grafana.client')
    // salt.enforceState(master, 'I@grafana:client and *01*', 'grafana.client', true)

    // Finalize the configuration of Grafana (add the dashboards...)
    salt.enforceState(master, 'I@grafana:client and *01*', 'grafana.client')
    salt.enforceState(master, 'I@grafana:client and *02*', 'grafana.client')
    salt.enforceState(master, 'I@grafana:client and *03*', 'grafana.client')
    // nw salt -C 'I@grafana:client' --async service.restart salt-minion; sleep 10

    // Get the StackLight monitoring VIP addres
    //vip=$(salt-call pillar.data _param:stacklight_monitor_address --out key|grep _param: |awk '{print $2}')
    //vip=${vip:=172.16.10.253}
    def pillar = salt.getPillar(master, 'ctl01*', '_param:stacklight_monitor_address')
    common.prettyPrint(pillar)
    def stacklight_vip = pillar['return'][0].values()[0]

    if (stacklight_vip) {
        // (re)Start manually the services that are bound to the monitoring VIP
        common.infoMsg("restart services on node with IP: ${stacklight_vip}")
        salt.runSaltProcessStep(master, "G@ipv4:${stacklight_vip}", 'service.restart', ['remote_collectd'])
        salt.runSaltProcessStep(master, "G@ipv4:${stacklight_vip}", 'service.restart', ['remote_collector'])
        salt.runSaltProcessStep(master, "G@ipv4:${stacklight_vip}", 'service.restart', ['aggregator'])
        salt.runSaltProcessStep(master, "G@ipv4:${stacklight_vip}", 'service.restart', ['nagios3'])
    } else {
        throw new Exception("Missing stacklight_vip")
    }
}

//
// backups
//

def installBackup(master, component='common') {
    def salt = new com.mirantis.mk.Salt()
    if (component == 'common') {
        // Install Backupninja
        if (salt.testTarget(master, 'I@backupninja:client')) {
            salt.enforceState(master, 'I@backupninja:client', 'salt.minion.grains')
            salt.runSaltProcessStep(master, 'I@backupninja:client', 'saltutil.sync_grains')
            salt.runSaltProcessStep(master, 'I@backupninja:client', 'mine.flush')
            salt.runSaltProcessStep(master, 'I@backupninja:client', 'mine.update')
            salt.enforceState(master, 'I@backupninja:client', 'backupninja')
        }
        if (salt.testTarget(master, 'I@backupninja:server')) {
            salt.enforceState(master, 'I@backupninja:server', 'salt.minion.grains')
            salt.enforceState(master, 'I@backupninja:server', 'backupninja')
        }
    } else if (component == 'mysql') {
        // Install Xtrabackup
        if (salt.testTarget(master, 'I@xtrabackup:client')) {
            salt.enforceState(master, 'I@xtrabackup:client', 'salt.minion.grains')
            salt.runSaltProcessStep(master, 'I@xtrabackup:client', 'saltutil.sync_grains')
            salt.runSaltProcessStep(master, 'I@xtrabackup:client', 'mine.flush')
            salt.runSaltProcessStep(master, 'I@xtrabackup:client', 'mine.update')
            salt.enforceState(master, 'I@xtrabackup:client', 'xtrabackup')
        }
        if (salt.testTarget(master, 'I@xtrabackup:server')) {
            salt.enforceState(master, 'I@xtrabackup:server', 'xtrabackup')
        }
    } else if (component == 'contrail') {

        // Install Cassandra backup
        if (salt.testTarget(master, 'I@cassandra:backup:client')) {
            salt.enforceState(master, 'I@cassandra:backup:client', 'salt.minion.grains')
            salt.runSaltProcessStep(master, 'I@cassandra:backup:client', 'saltutil.sync_grains')
            salt.runSaltProcessStep(master, 'I@cassandra:backup:client', 'mine.flush')
            salt.runSaltProcessStep(master, 'I@cassandra:backup:client', 'mine.update')
            salt.enforceState(master, 'I@cassandra:backup:client', 'cassandra.backup')
        }
        if (salt.testTarget(master, 'I@cassandra:backup:server')) {
            salt.enforceState(master, 'I@cassandra:backup:server', 'cassandra.backup')
        }
        // Install Zookeeper backup
        if (salt.testTarget(master, 'I@zookeeper:backup:client')) {
            salt.enforceState(master, 'I@zookeeper:backup:client', 'salt.minion.grains')
            salt.runSaltProcessStep(master, 'I@zookeeper:backup:client', 'saltutil.sync_grains')
            salt.runSaltProcessStep(master, 'I@zookeeper:backup:client', 'mine.flush')
            salt.runSaltProcessStep(master, 'I@zookeeper:backup:client', 'mine.update')
            salt.enforceState(master, 'I@zookeeper:backup:client', 'zookeeper.backup')
        }
        if (salt.testTarget(master, 'I@zookeeper:backup:server')) {
            salt.enforceState(master, 'I@zookeeper:backup:server', 'zookeeper.backup')
        }
    } else if (component == 'ceph') {
        // Install Ceph backup
        if (salt.testTarget(master, 'I@ceph:backup:client')) {
            salt.enforceState(master, 'I@ceph:backup:client', 'salt.minion.grains')
            salt.runSaltProcessStep(master, 'I@ceph:backup:client', 'saltutil.sync_grains')
            salt.runSaltProcessStep(master, 'I@ceph:backup:client', 'mine.flush')
            salt.runSaltProcessStep(master, 'I@ceph:backup:client', 'mine.update')
            salt.enforceState(master, 'I@ceph:backup:client', 'ceph.backup')
        }
        if (salt.testTarget(master, 'I@ceph:backup:server')) {
            salt.enforceState(master, 'I@ceph:backup:server', 'ceph.backup')
        }
    }

}

//
// Ceph
//

def installCephMon(master, target='I@ceph:mon') {
    def salt = new com.mirantis.mk.Salt()

    salt.enforceState(master, 'I@ceph:common', 'salt.minion.grains')

    // generate keyrings
    if (salt.testTarget(master, 'I@ceph:mon:keyring:mon or I@ceph:common:keyring:admin')) {
        salt.enforceState(master, 'I@ceph:mon:keyring:mon or I@ceph:common:keyring:admin', 'ceph.mon')
        salt.runSaltProcessStep(master, 'I@ceph:mon', 'saltutil.sync_grains')
        salt.runSaltProcessStep(master, 'I@ceph:mon:keyring:mon or I@ceph:common:keyring:admin', 'mine.update')
        sleep(5)
    }
    // install Ceph Mons
    salt.enforceState(master, target, 'ceph.mon')
    if (salt.testTarget(master, 'I@ceph:mgr')) {
        salt.enforceState(master, 'I@ceph:mgr', 'ceph.mgr')
    }
}

def installCephOsd(master, target='I@ceph:osd', setup=true) {
    def salt = new com.mirantis.mk.Salt()

    // install Ceph OSDs
    salt.enforceState(master, target, 'ceph.osd')
    salt.runSaltProcessStep(master, 'I@ceph:osd', 'saltutil.sync_grains')
    salt.enforceState(master, target, 'ceph.osd.custom')
    salt.runSaltProcessStep(master, 'I@ceph:osd', 'saltutil.sync_grains')
    salt.runSaltProcessStep(master, 'I@ceph:osd', 'mine.update')
    installBackup(master, 'ceph')

    // setup pools, keyrings and maybe crush
    if (salt.testTarget(master, 'I@ceph:setup') && setup) {
        sleep(5)
        salt.enforceState(master, 'I@ceph:setup', 'ceph.setup')
    }
}

def installCephClient(master) {
    def salt = new com.mirantis.mk.Salt()

    // install Ceph Radosgw
    if (salt.testTarget(master, 'I@ceph:radosgw')) {
        salt.runSaltProcessStep(master, 'I@ceph:radosgw', 'saltutil.sync_grains')
        salt.enforceState(master, 'I@ceph:radosgw', 'ceph.radosgw')
    }
    // setup Keystone service and endpoints for swift or / and S3
    if (salt.testTarget(master, 'I@keystone:client')) {
        salt.enforceState(master, 'I@keystone:client', 'keystone.client')
    }
}

def connectCeph(master) {
    def salt = new com.mirantis.mk.Salt()

    // connect Ceph to the env
    if (salt.testTarget(master, 'I@ceph:common and I@glance:server')) {
        salt.enforceState(master, 'I@ceph:common and I@glance:server', ['ceph.common', 'ceph.setup.keyring', 'glance'])
        salt.runSaltProcessStep(master, 'I@ceph:common and I@glance:server', 'service.restart', ['glance-api', 'glance-glare', 'glance-registry'])
    }
    if (salt.testTarget(master, 'I@ceph:common and I@cinder:controller')) {
        salt.enforceState(master, 'I@ceph:common and I@cinder:controller', ['ceph.common', 'ceph.setup.keyring', 'cinder'])
    }
    if (salt.testTarget(master, 'I@ceph:common and I@nova:compute')) {
        salt.enforceState(master, 'I@ceph:common and I@nova:compute', ['ceph.common', 'ceph.setup.keyring'])
        salt.runSaltProcessStep(master, 'I@ceph:common and I@nova:compute', 'saltutil.sync_grains')
        salt.enforceState(master, 'I@ceph:common and I@nova:compute', ['nova'])
    }
}

def installOssInfra(master) {
  def common = new com.mirantis.mk.Common()
  def salt = new com.mirantis.mk.Salt()

  if (!common.checkContains('STACK_INSTALL', 'k8s') || !common.checkContains('STACK_INSTALL', 'openstack')) {
    def orchestrate = new com.mirantis.mk.Orchestrate()
    orchestrate.installInfra(master)
  }

  if (salt.testTarget(master, 'I@devops_portal:config')) {
    salt.enforceState(master, 'I@devops_portal:config', 'devops_portal.config')
    salt.enforceState(master, 'I@rundeck:client', ['linux.system.user', 'openssh'])
    salt.enforceState(master, 'I@rundeck:server', 'rundeck.server')
  }
}

def installOss(master) {
  def common = new com.mirantis.mk.Common()
  def salt = new com.mirantis.mk.Salt()

  //Get oss VIP address
  def pillar = salt.getPillar(master, 'cfg01*', '_param:stacklight_monitor_address')
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
    salt.cmdRun(master, 'I@postgresql:client', 'while true; do if docker service logs postgresql_postgresql-db 2>&1 | grep "ready to accept"; then break; else sleep 5; fi; done')
  }
  // XXX: first run usually fails on some inserts, but we need to create databases at first
  salt.enforceState(master, 'I@postgresql:client', 'postgresql.client', true, false)

  // Setup postgres database with integration between
  // Pushkin notification service and Security Monkey security audit service
  timeout(10) {
    common.infoMsg("Waiting for Pushkin to come up..")
    salt.cmdRun(master, 'I@postgresql:client', "while true; do curl -sf ${oss_vip}:8887/apps >/dev/null && break; done")
  }
  salt.enforceState(master, 'I@postgresql:client', 'postgresql.client')

  // Rundeck
  timeout(10) {
    common.infoMsg("Waiting for Rundeck to come up..")
    salt.cmdRun(master, 'I@rundeck:client', "while true; do curl -sf ${oss_vip}:4440 >/dev/null && break; done")
  }
  salt.enforceState(master, 'I@rundeck:client', 'rundeck.client')

  // Elasticsearch
  pillar = salt.getPillar(master, 'I@elasticsearch:client', 'elasticsearch:client:server:host')
  def elasticsearch_vip
  if(!pillar['return'].isEmpty()) {
    elasticsearch_vip = pillar['return'][0].values()[0]
  } else {
    common.errorMsg('[ERROR] Elasticsearch VIP address could not be retrieved')
  }

  timeout(10) {
    common.infoMsg('Waiting for Elasticsearch to come up..')
    salt.cmdRun(master, 'I@elasticsearch:client', "while true; do curl -sf ${elasticsearch_vip}:9200 >/dev/null && break; done")
  }
  salt.enforceState(master, 'I@elasticsearch:client', 'elasticsearch.client')
}
