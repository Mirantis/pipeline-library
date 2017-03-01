package com.mirantis.mk
/**
 * Orchestration functions
 *
*/

def validateFoundationInfra(master) {
    def salt = new com.mirantis.mk.Salt()
    salt.runSaltProcessStep(master, 'I@salt:master', 'cmd.run', ['salt-key'])
    salt.runSaltProcessStep(master, 'I@salt:minion', 'test.version')
    salt.runSaltProcessStep(master, 'I@salt:master', 'cmd.run', ['reclass-salt --top'])
    salt.runSaltProcessStep(master, 'I@reclass:storage', 'reclass.inventory')
    salt.runSaltProcessStep(master, 'I@salt:minion', 'state.show_top')
}


def installFoundationInfra(master) {
    def salt = new com.mirantis.mk.Salt()
    salt.runSaltProcessStep(master, 'I@salt:master', 'state.sls', ['salt.master,reclass'])
    salt.runSaltProcessStep(master, 'I@linux:system', 'saltutil.refresh_pillar')
    salt.runSaltProcessStep(master, 'I@linux:system', 'saltutil.sync_all')
    salt.runSaltProcessStep(master, 'I@linux:system', 'state.sls', ['linux,openssh,salt.minion,ntp'])
}

def installInfraKvm(master) {
    def salt = new com.mirantis.mk.Salt()
    salt.runSaltProcessStep(master, 'I@linux:system', 'saltutil.refresh_pillar')
    salt.runSaltProcessStep(master, 'I@linux:system', 'saltutil.sync_all')

    salt.runSaltProcessStep(master, 'I@salt:control', 'state.sls', ['salt.minion,linux.system,linux.network,ntp'])
    salt.enforceState(master, 'I@salt:control', 'libvirt', true)
    salt.enforceState(master, 'I@salt:control', 'salt.control', true)

    sleep(300)

    salt.runSaltProcessStep(master, '* and not kvm*', 'saltutil.refresh_pillar')
    salt.runSaltProcessStep(master, '* and not kvm*', 'saltutil.sync_all')
    salt.runSaltProcessStep(master, '* and not kvm*', 'state.sls', ['linux,openssh,salt.minion,ntp'])

}

def installOpenstackMkInfra(master, physical = "false") {
    def salt = new com.mirantis.mk.Salt()
    // Install keepaliveds
    //runSaltProcessStep(master, 'I@keepalived:cluster', 'state.sls', ['keepalived'], 1)
    salt.enforceState(master, 'ctl01*', 'keepalived', true)
    salt.enforceState(master, 'I@keepalived:cluster', 'keepalived', true)
    // Check the keepalived VIPs
    salt.runSaltProcessStep(master, 'I@keepalived:cluster', 'cmd.run', ['ip a | grep 172.16.10.2'])
    // Install glusterfs
    salt.enforceState(master, 'I@glusterfs:server', 'glusterfs.server.service', true)

    //runSaltProcessStep(master, 'I@glusterfs:server', 'state.sls', ['glusterfs.server.setup'], 1)
    if (physical.equals("false")) {
        salt.enforceState(master, 'ctl01*', 'glusterfs.server.setup', true)
        salt.enforceState(master, 'ctl02*', 'glusterfs.server.setup', true)
        salt.enforceState(master, 'ctl03*', 'glusterfs.server.setup', true)
    } else {
        salt.enforceState(master, 'kvm01*', 'glusterfs.server.setup', true)
        salt.enforceState(master, 'kvm02*', 'glusterfs.server.setup', true)
        salt.enforceState(master, 'kvm03*', 'glusterfs.server.setup', true)
    }
    salt.runSaltProcessStep(master, 'I@glusterfs:server', 'cmd.run', ['gluster peer status'])
    salt.runSaltProcessStep(master, 'I@glusterfs:server', 'cmd.run', ['gluster volume status'])

    // Install rabbitmq
    salt.enforceState(master, 'I@rabbitmq:server', 'rabbitmq', true)
    // Check the rabbitmq status
    salt.runSaltProcessStep(master, 'I@rabbitmq:server', 'cmd.run', ['rabbitmqctl cluster_status'])
    // Install galera
    salt.enforceState(master, 'I@galera:master', 'galera', true)
    salt.enforceState(master, 'I@galera:slave', 'galera', true)
    // Check galera status
    salt.runSaltProcessStep(master, 'I@galera:master', 'mysql.status')
    salt.runSaltProcessStep(master, 'I@galera:slave', 'mysql.status')
    // Install haproxy
    salt.enforceState(master, 'I@haproxy:proxy', 'haproxy', true)
    salt.runSaltProcessStep(master, 'I@haproxy:proxy', 'service.status', ['haproxy'])
    salt.runSaltProcessStep(master, 'I@haproxy:proxy', 'service.restart', ['rsyslog'])
    // Install memcached
    salt.enforceState(master, 'I@memcached:server', 'memcached', true)
}


def installOpenstackMkControl(master) {
    def salt = new com.mirantis.mk.Salt()
    // setup keystone service
    //runSaltProcessStep(master, 'I@keystone:server', 'state.sls', ['keystone.server'], 1)
    salt.enforceState(master, 'ctl01*', 'keystone.server', true)
    salt.enforceState(master, 'I@keystone:server', 'keystone.server', true)
    // populate keystone services/tenants/roles/users
    salt.enforceState(master, 'I@keystone:client', 'keystone.client', true)
    salt.runSaltProcessStep(master, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; keystone service-list'])
    // Install glance and ensure glusterfs clusters
    //runSaltProcessStep(master, 'I@glance:server', 'state.sls', ['glance.server'], 1)
    salt.enforceState(master, 'ctl01*', 'glance.server', true)
    salt.enforceState(master, 'I@glance:server', 'glance.server', true)
    salt.enforceState(master, 'I@glance:server', 'glusterfs.client', true)
    // Update fernet tokens before doing request on keystone server
    salt.enforceState(master, 'I@keystone:server', 'keystone.server', true)
    // Check glance service
    salt.runSaltProcessStep(master, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; glance image-list'])
    // Install and check nova service
    //runSaltProcessStep(master, 'I@nova:controller', 'state.sls', ['nova'], 1)
    salt.enforceState(master, 'ctl01*', 'nova', true)
    salt.enforceState(master, 'I@nova:controller', 'nova', true)
    salt.runSaltProcessStep(master, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; nova service-list'])
    // Install and check cinder service
    //runSaltProcessStep(master, 'I@cinder:controller', 'state.sls', ['cinder'], 1)
    salt.enforceState(master, 'ctl01*', 'cinder', true)
    salt.enforceState(master, 'I@cinder:controller', 'cinder', true)
    salt.runSaltProcessStep(master, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; cinder list'])
    // Install neutron service
    //runSaltProcessStep(master, 'I@neutron:server', 'state.sls', ['neutron'], 1)
    salt.enforceState(master, 'ctl01*', 'neutron', true)
    salt.enforceState(master, 'I@neutron:server', 'neutron', true)
    salt.runSaltProcessStep(master, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; neutron agent-list'])
    // Install heat service
    //runSaltProcessStep(master, 'I@heat:server', 'state.sls', ['heat'], 1)
    salt.enforceState(master, 'ctl01*', 'heat', true)
    salt.enforceState(master, 'I@heat:server', 'heat', true)
    salt.runSaltProcessStep(master, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; heat resource-type-list'])
    // Install horizon dashboard
    salt.enforceState(master, 'I@horizon:server', 'horizon', true)
    salt.enforceState(master, 'I@nginx:server', 'nginx', true)
}


def installOpenstackMkNetwork(master, physical = "false") {
    def salt = new com.mirantis.mk.Salt()
    // Install opencontrail database services
    //runSaltProcessStep(master, 'I@opencontrail:database', 'state.sls', ['opencontrail.database'], 1)
    salt.enforceState(master, 'ntw01*', 'opencontrail.database', true)
    salt.enforceState(master, 'I@opencontrail:database', 'opencontrail.database', true)
    // Install opencontrail control services
    //runSaltProcessStep(master, 'I@opencontrail:control', 'state.sls', ['opencontrail'], 1)
    salt.enforceState(master, 'ntw01*', 'opencontrail', true)
    salt.enforceState(master, 'I@opencontrail:control', 'opencontrail', true)

    // Provision opencontrail control services
    if (physical.equals("false")) {
        salt.runSaltProcessStep(master, 'I@opencontrail:control:id:1', 'cmd.run', ['/usr/share/contrail-utils/provision_control.py --api_server_ip 172.16.10.254 --api_server_port 8082 --host_name ctl01 --host_ip 172.16.10.101 --router_asn 64512 --admin_password workshop --admin_user admin --admin_tenant_name admin --oper add'])
        salt.runSaltProcessStep(master, 'I@opencontrail:control:id:1', 'cmd.run', ['/usr/share/contrail-utils/provision_control.py --api_server_ip 172.16.10.254 --api_server_port 8082 --host_name ctl02 --host_ip 172.16.10.102 --router_asn 64512 --admin_password workshop --admin_user admin --admin_tenant_name admin --oper add'])
        salt.runSaltProcessStep(master, 'I@opencontrail:control:id:1', 'cmd.run', ['/usr/share/contrail-utils/provision_control.py --api_server_ip 172.16.10.254 --api_server_port 8082 --host_name ctl03 --host_ip 172.16.10.103 --router_asn 64512 --admin_password workshop --admin_user admin --admin_tenant_name admin --oper add'])
    }

    // Test opencontrail
    salt.runSaltProcessStep(master, 'I@opencontrail:control', 'cmd.run', ['contrail-status'])
    salt.runSaltProcessStep(master, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; neutron net-list'])
    salt.runSaltProcessStep(master, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; nova net-list'])
}


def installOpenstackMkCompute(master, physical = "false") {
     def salt = new com.mirantis.mk.Salt()
    // Configure compute nodes
    salt.runSaltProcessStep(master, 'I@nova:compute', 'state.apply')
    salt.runSaltProcessStep(master, 'I@nova:compute', 'state.apply')

    // Provision opencontrail virtual routers
    if (physical.equals("false")) {
        salt.runSaltProcessStep(master, 'I@opencontrail:control:id:1', 'cmd.run', ['/usr/share/contrail-utils/provision_vrouter.py --host_name cmp01 --host_ip 172.16.10.105 --api_server_ip 172.16.10.254 --oper add --admin_user admin --admin_password workshop --admin_tenant_name admin'])
    }

    salt.runSaltProcessStep(master, 'I@nova:compute', 'system.reboot')
}


def installOpenstackMcpInfra(master) {
     def salt = new com.mirantis.mk.Salt()
    // Comment nameserver
    salt.runSaltProcessStep(master, 'I@kubernetes:master', 'cmd.run', ["sed -i 's/nameserver 10.254.0.10/#nameserver 10.254.0.10/g' /etc/resolv.conf"])
    // Install glusterfs
    salt.runSaltProcessStep(master, 'I@glusterfs:server', 'state.sls', ['glusterfs.server.service'])
    // Install keepalived
    salt.runSaltProcessStep(master, 'ctl01*', 'state.sls', ['keepalived'])
    salt.runSaltProcessStep(master, 'I@keepalived:cluster', 'state.sls', ['keepalived'])
    // Check the keepalived VIPs
    salt.runSaltProcessStep(master, 'I@keepalived:cluster', 'cmd.run', ['ip a | grep 172.16.10.2'])
    // Setup glusterfs
    salt.runSaltProcessStep(master, 'ctl01*', 'state.sls', ['glusterfs.server.setup'])
    salt.runSaltProcessStep(master, 'ctl02*', 'state.sls', ['glusterfs.server.setup'])
    salt.runSaltProcessStep(master, 'ctl03*', 'state.sls', ['glusterfs.server.setup'])
    salt.runSaltProcessStep(master, 'I@glusterfs:server', 'cmd.run', ['gluster peer status'])
    salt.runSaltProcessStep(master, 'I@glusterfs:server', 'cmd.run', ['gluster volume status'])
    // Install haproxy
    salt.runSaltProcessStep(master, 'I@haproxy:proxy', 'state.sls', ['haproxy'])
    salt.runSaltProcessStep(master, 'I@haproxy:proxy', 'service.status', ['haproxy'])
    // Install docker
    salt.runSaltProcessStep(master, 'I@docker:host', 'state.sls', ['docker.host'])
    salt.runSaltProcessStep(master, 'I@docker:host', 'cmd.run', ['docker ps'])
    // Install bird
    salt.runSaltProcessStep(master, 'I@bird:server', 'state.sls', ['bird'])
    // Install etcd
    salt.runSaltProcessStep(master, 'I@etcd:server', 'state.sls', ['etcd.server.service'])
    salt.runSaltProcessStep(master, 'I@etcd:server', 'cmd.run', ['etcdctl cluster-health'])
}


def installOpenstackMcpControl(master) {
    def salt = new com.mirantis.mk.Salt()
    // Install Kubernetes pool and Calico
    salt.runSaltProcessStep(master, 'I@kubernetes:pool', 'state.sls', ['kubernetes.pool'])
    salt.runSaltProcessStep(master, 'I@kubernetes:pool', 'cmd.run', ['calicoctl node status'])

    // Setup etcd server
    salt.runSaltProcessStep(master, 'I@kubernetes:master', 'state.sls', ['etcd.server.setup'])

    // Run k8s without master.setup
    salt.runSaltProcessStep(master, 'I@kubernetes:master', 'state.sls', ['kubernetes', 'exclude=kubernetes.master.setup'])

    // Run k8s master setup
    salt.runSaltProcessStep(master, 'ctl01*', 'state.sls', ['kubernetes.master.setup'])

    // Revert comment nameserver
    salt.runSaltProcessStep(master, 'I@kubernetes:master', 'cmd.run', ["sed -i 's/nameserver 10.254.0.10/#nameserver 10.254.0.10/g' /etc/resolv.conf"])

    // Set route
    salt.runSaltProcessStep(master, 'I@kubernetes:pool', 'cmd.run', ['ip r a 10.254.0.0/16 dev ens4'])

    // Restart kubelet
    salt.runSaltProcessStep(master, 'I@kubernetes:pool', 'service.restart', ['kubelet'])
}


def installOpenstackMcpCompute(master) {
    def salt = new com.mirantis.mk.Salt();
    // Install opencontrail
    salt.runSaltProcessStep(master, 'I@opencontrail:compute', 'state.sls', ['opencontrail'])
    // Reboot compute nodes
    salt.runSaltProcessStep(master, 'I@opencontrail:compute', 'system.reboot')
}


def installStacklightControl(master) {
    def salt = new com.mirantis.mk.Salt();
    salt.runSaltProcessStep(master, 'I@elasticsearch:server', 'state.sls', ['elasticsearch.server'])
    salt.runSaltProcessStep(master, 'I@influxdb:server', 'state.sls', ['influxdb'])
    salt.runSaltProcessStep(master, 'I@kibana:server', 'state.sls', ['kibana.server'])
    salt.runSaltProcessStep(master, 'I@grafana:server', 'state.sls', ['grafana'])
    salt.runSaltProcessStep(master, 'I@nagios:server', 'state.sls', ['nagios'])
    salt.runSaltProcessStep(master, 'I@elasticsearch:client', 'state.sls', ['elasticsearch.client'])
    salt.runSaltProcessStep(master, 'I@kibana:client', 'state.sls', ['kibana.client'])
}
