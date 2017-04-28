package com.mirantis.mk
/**
 * Orchestration functions
 *
*/

def validateFoundationInfra(master) {
    def salt = new com.mirantis.mk.Salt()

    salt.runSaltProcessStep(master, 'I@salt:master', 'cmd.run', ['salt-key'], null, true)
    salt.runSaltProcessStep(master, 'I@salt:minion', 'test.version', [], null, true)
    salt.runSaltProcessStep(master, 'I@salt:master', 'cmd.run', ['reclass-salt --top'], null, true)
    salt.runSaltProcessStep(master, 'I@reclass:storage', 'reclass.inventory', [], null, true)
    salt.runSaltProcessStep(master, 'I@salt:minion', 'state.show_top', [], null, true)
}


def installFoundationInfra(master) {
    def salt = new com.mirantis.mk.Salt()

    salt.enforceState(master, 'I@salt:master', ['salt.master', 'reclass'], true)
    salt.enforceState(master, '*', ['linux.system'], true)
    salt.enforceState(master, '*', ['salt.minion'], true)
    salt.runSaltProcessStep(master, 'I@linux:system', 'saltutil.refresh_pillar', [], null, true)
    salt.runSaltProcessStep(master, 'I@linux:system', 'saltutil.sync_all', [], null, true)
    salt.enforceState(master, 'I@linux:system', ['linux', 'openssh', 'salt.minion', 'ntp'], true)
}

def installInfraKvm(master) {
    def salt = new com.mirantis.mk.Salt()

    salt.runSaltProcessStep(master, 'I@linux:system', 'saltutil.refresh_pillar', [], null, true)
    salt.runSaltProcessStep(master, 'I@linux:system', 'saltutil.sync_all', [], null, true)

    salt.enforceState(master, 'I@salt:control', ['salt.minion', 'linux.system', 'linux.network', 'ntp'], true)
    salt.enforceState(master, 'I@salt:control', 'libvirt', true)
    salt.enforceState(master, 'I@salt:control', 'salt.control', true)

    sleep(600)

    salt.runSaltProcessStep(master, '* and not kvm*', 'saltutil.refresh_pillar', [], null, true)
    salt.runSaltProcessStep(master, '* and not kvm*', 'saltutil.sync_all', [], null, true)
}

def installOpenstackInfra(master) {
    def salt = new com.mirantis.mk.Salt()

    // Install glusterfs
    salt.enforceState(master, 'I@glusterfs:server', 'glusterfs.server.service', true)

    // Install keepaliveds
    //runSaltProcessStep(master, 'I@keepalived:cluster', 'state.sls', ['keepalived'], 1)
    salt.enforceState(master, 'I@keepalived:cluster and *01*', 'keepalived', true)
    salt.enforceState(master, 'I@keepalived:cluster', 'keepalived', true)

    // Check the keepalived VIPs
    salt.runSaltProcessStep(master, 'I@keepalived:cluster', 'cmd.run', ['ip a | grep 172.16.10.2'])

    salt.enforceState(master, 'I@glusterfs:server and *01*', 'glusterfs.server.setup', true)

    salt.runSaltProcessStep(master, 'I@glusterfs:server', 'cmd.run', ['gluster peer status'], null, true)
    salt.runSaltProcessStep(master, 'I@glusterfs:server', 'cmd.run', ['gluster volume status'], null, true)

    // Install rabbitmq
    withEnv(['ASK_ON_ERROR=false']){
        retry(2) {
            salt.enforceState(master, 'I@rabbitmq:server', 'rabbitmq', true)
        }
    }

    // Check the rabbitmq status
    salt.runSaltProcessStep(master, 'I@rabbitmq:server', 'cmd.run', ['rabbitmqctl cluster_status'])

    // Install galera
    withEnv(['ASK_ON_ERROR=false']){
        retry(2) {
            salt.enforceState(master, 'I@galera:master', 'galera', true)
        }
    }
    salt.enforceState(master, 'I@galera:slave', 'galera', true)

    // Check galera status
    salt.runSaltProcessStep(master, 'I@galera:master', 'mysql.status')
    salt.runSaltProcessStep(master, 'I@galera:slave', 'mysql.status')

    // // Setup mysql client
    // salt.enforceState(master, 'I@mysql:client', 'mysql.client', true)

    // Install haproxy
    salt.enforceState(master, 'I@haproxy:proxy', 'haproxy', true)
    salt.runSaltProcessStep(master, 'I@haproxy:proxy', 'service.status', ['haproxy'])
    salt.runSaltProcessStep(master, 'I@haproxy:proxy', 'service.restart', ['rsyslog'])

    // Install memcached
    salt.enforceState(master, 'I@memcached:server', 'memcached', true)
}


def installOpenstackControl(master) {
    def salt = new com.mirantis.mk.Salt()

    // Install horizon dashboard
    salt.enforceState(master, 'I@horizon:server', 'horizon', true)
    salt.enforceState(master, 'I@nginx:server', 'nginx', true)

    // setup keystone service
    //runSaltProcessStep(master, 'I@keystone:server', 'state.sls', ['keystone.server'], 1)
    salt.enforceState(master, 'I@keystone:server and *01*', 'keystone.server', true)
    salt.enforceState(master, 'I@keystone:server', 'keystone.server', true)
    // populate keystone services/tenants/roles/users

    // keystone:client must be called locally
    //salt.runSaltProcessStep(master, 'I@keystone:client', 'cmd.run', ['salt-call state.sls keystone.client'], null, true)
    salt.runSaltProcessStep(master, 'I@keystone:server', 'service.restart', ['apache2'])
    sleep(30)
    salt.enforceState(master, 'I@keystone:client', 'keystone.client', true)
    salt.enforceState(master, 'I@keystone:client', 'keystone.client', true)
    salt.runSaltProcessStep(master, 'I@keystone:server', 'cmd.run', ['. /root/keystonercv3; openstack service list'], null, true)

    // Install glance and ensure glusterfs clusters
    //runSaltProcessStep(master, 'I@glance:server', 'state.sls', ['glance.server'], 1)
    salt.enforceState(master, 'I@glance:server and *01*', 'glance.server', true)
    salt.enforceState(master, 'I@glance:server', 'glance.server', true)
    salt.enforceState(master, 'I@glance:server', 'glusterfs.client', true)

    // Update fernet tokens before doing request on keystone server
    salt.enforceState(master, 'I@keystone:server', 'keystone.server', true)

    // Check glance service
    salt.runSaltProcessStep(master, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; glance image-list'], null, true)

    // Install and check nova service
    //runSaltProcessStep(master, 'I@nova:controller', 'state.sls', ['nova'], 1)
    salt.enforceState(master, 'I@nova:controller and *01*', 'nova.controller', true)
    salt.enforceState(master, 'I@nova:controller', 'nova.controller', true)
    salt.runSaltProcessStep(master, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; nova service-list'], null, true)

    // Install and check cinder service
    //runSaltProcessStep(master, 'I@cinder:controller', 'state.sls', ['cinder'], 1)
    salt.enforceState(master, 'I@cinder:controller and *01*', 'cinder', true)
    salt.enforceState(master, 'I@cinder:controller', 'cinder', true)
    salt.runSaltProcessStep(master, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; cinder list'], null, true)

    // Install neutron service
    //runSaltProcessStep(master, 'I@neutron:server', 'state.sls', ['neutron'], 1)

    salt.enforceState(master, 'I@neutron:server and *01*', 'neutron.server', true)
    salt.enforceState(master, 'I@neutron:server', 'neutron.server', true)
    salt.runSaltProcessStep(master, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; neutron agent-list'], null, true)

    // Install heat service
    //runSaltProcessStep(master, 'I@heat:server', 'state.sls', ['heat'], 1)
    salt.enforceState(master, 'I@heat:server and *01*', 'heat', true)
    salt.enforceState(master, 'I@heat:server', 'heat', true)
    salt.runSaltProcessStep(master, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; heat resource-type-list'], null, true)

    // Restart nova api
    salt.runSaltProcessStep(master, 'I@nova:controller', 'service.restart', ['nova-api'])
}


def installOpenstackNetwork(master, physical = "false") {
    def salt = new com.mirantis.mk.Salt()

    salt.runSaltProcessStep(master, 'I@neutron:gateway', 'state.apply', [], null, true)
}


def installOpenstackCompute(master) {
    def salt = new com.mirantis.mk.Salt()

    // Configure compute nodes
    retry(2) {
        salt.runSaltProcessStep(master, 'I@nova:compute', 'state.apply', [], null, true)
    }
}


def installContrailNetwork(master) {
    def salt = new com.mirantis.mk.Salt()


    // Install opencontrail database services
    //runSaltProcessStep(master, 'I@opencontrail:database', 'state.sls', ['opencontrail.database'], 1)
    try {
        salt.enforceState(master, 'I@opencontrail:database and *01*', 'opencontrail.database', true)
    } catch (Exception e) {
        common.warningMsg('Exception in state opencontrail.database on I@opencontrail:database and *01*')
    }

    try {
        salt.enforceState(master, 'I@opencontrail:database', 'opencontrail.database', true)
    } catch (Exception e) {
        common.warningMsg('Exception in state opencontrail.database on I@opencontrail:database')
    }

    // Install opencontrail control services
    //runSaltProcessStep(master, 'I@opencontrail:control', 'state.sls', ['opencontrail'], 1)
    salt.runSaltProcessStep(master, 'I@opencontrail:control and *01*', 'state.sls', ['opencontrail', 'exclude=opencontrail.client'])
    salt.runSaltProcessStep(master, 'I@opencontrail:control', 'state.sls', ['opencontrail', 'exclude=opencontrail.client'])
    salt.runSaltProcessStep(master, 'I@opencontrail:collector', 'state.sls', ['opencontrail', 'exclude=opencontrail.client'])

    // Test opencontrail
    salt.runSaltProcessStep(master, 'I@opencontrail:control', 'cmd.run', ['contrail-status'], null, true)
}


def installContrailCompute(master) {
     def salt = new com.mirantis.mk.Salt()
    // Configure compute nodes
    // Provision opencontrail control services
    salt.enforceState(master, 'I@opencontrail:database:id:1', 'opencontrail.client', true)
    // Provision opencontrail virtual routers
    salt.enforceState(master, 'I@opencontrail:compute', 'opencontrail.client', true)

    salt.runSaltProcessStep(master, 'I@nova:compute', 'cmd.run', ['exec 0>&-; exec 1>&-; exec 2>&-; nohup bash -c "ip link | grep vhost && echo no_reboot || sleep 5 && reboot & "'], null, true)
    sleep(300)
}


def installKubernetesInfra(master) {
     def salt = new com.mirantis.mk.Salt()

    // Install glusterfs
    salt.runSaltProcessStep(master, 'I@glusterfs:server', 'state.sls', ['glusterfs.server.service'])

    // Install keepalived
    salt.runSaltProcessStep(master, 'I@keepalived:cluster and *01*', 'state.sls', ['keepalived'])
    salt.runSaltProcessStep(master, 'I@keepalived:cluster', 'state.sls', ['keepalived'])

    // Check the keepalived VIPs
    salt.runSaltProcessStep(master, 'I@keepalived:cluster', 'cmd.run', ['ip a | grep 172.16.10.2'])

    // Setup glusterfs
    salt.runSaltProcessStep(master, 'I@glusterfs:server and *01*', 'state.sls', ['glusterfs.server.setup'])
    salt.runSaltProcessStep(master, 'I@glusterfs:server', 'cmd.run', ['gluster peer status'])
    salt.runSaltProcessStep(master, 'I@glusterfs:server', 'cmd.run', ['gluster volume status'])

    // Install haproxy
    salt.runSaltProcessStep(master, 'I@haproxy:proxy', 'state.sls', ['haproxy'])
    salt.runSaltProcessStep(master, 'I@haproxy:proxy', 'service.status', ['haproxy'])

    // Install docker
    salt.runSaltProcessStep(master, 'I@docker:host', 'state.sls', ['docker.host'])
    salt.runSaltProcessStep(master, 'I@docker:host', 'cmd.run', ['docker ps'])

    // Install etcd
    salt.runSaltProcessStep(master, 'I@etcd:server', 'state.sls', ['etcd.server.service'])
    salt.runSaltProcessStep(master, 'I@etcd:server', 'cmd.run', ['./var/lib/etcd/configenv && etcdctl cluster-health'])

}


def installKubernetesControl(master) {
    def salt = new com.mirantis.mk.Salt()
    // Install Kubernetes pool and Calico
    salt.runSaltProcessStep(master, 'I@kubernetes:master', 'state.sls', ['kubernetes.master.kube-addons'])
    salt.runSaltProcessStep(master, 'I@kubernetes:pool', 'state.sls', ['kubernetes.pool'])

    // Setup etcd server
    salt.runSaltProcessStep(master, 'I@kubernetes:master and *01*', 'state.sls', ['etcd.server.setup'])

    // Run k8s without master.setup
    salt.runSaltProcessStep(master, 'I@kubernetes:master', 'state.sls', ['kubernetes', 'exclude=kubernetes.master.setup'])

    // Run k8s master setup
    salt.runSaltProcessStep(master, 'I@kubernetes:master and *01*', 'state.sls', ['kubernetes.master.setup'])

    // Restart kubelet
    salt.runSaltProcessStep(master, 'I@kubernetes:pool', 'service.restart', ['kubelet'])
}


def installKubernetesCompute(master) {
    def salt = new com.mirantis.mk.Salt();
    // Install opencontrail
    salt.runSaltProcessStep(master, 'I@opencontrail:compute', 'state.sls', ['opencontrail'])
    // Reboot compute nodes
    salt.runSaltProcessStep(master, 'I@opencontrail:compute', 'system.reboot')
}


def installStacklightControl(master) {
    def salt = new com.mirantis.mk.Salt();

    // infra install
    // Install the StackLight backends
    salt.enforceState(master, '*01* and  I@elasticsearch:server', 'elasticsearch.server', true)
    salt.enforceState(master, 'I@elasticsearch:server', 'elasticsearch.server', true)

    salt.enforceState(master, '*01* and I@influxdb:server', 'influxdb', true)
    salt.enforceState(master, 'I@influxdb:server', 'influxdb', true)

    salt.enforceState(master, '*01* and I@kibana:server', 'kibana.server', true)
    salt.enforceState(master, 'I@kibana:server', 'kibana.server', true)

    salt.enforceState(master, '*01* and I@grafana:server','grafana.server', true)
    salt.enforceState(master, 'I@grafana:server','grafana.server', true)

    salt.enforceState(master, 'I@nagios:server', 'nagios.server', true)
    salt.enforceState(master, 'I@elasticsearch:client', 'elasticsearch.client.service', true)
    salt.enforceState(master, 'I@kibana:client', 'kibana.client.service', true)

    sleep(10)
}

def installStacklightClient(master) {
    def salt = new com.mirantis.mk.Salt();

    salt.runSaltProcessStep(master, 'I@elasticsearch.client', 'cmd.run', ['salt-call state.sls elasticsearch.client'], null, true)
    // salt.enforceState(master, 'I@elasticsearch:client', 'elasticsearch.client', true)
    salt.runSaltProcessStep(master, 'I@kibana.client', 'cmd.run', ['salt-call state.sls kibana.client'], null, true)
    // salt.enforceState(master, 'I@kibana:client', 'kibana.client', true)

    // Install collectd, heka and sensu services on the nodes, this will also
    // generate the metadata that goes into the grains and eventually into Salt Mine
    salt.enforceState(master, '*', 'collectd', true)
    salt.enforceState(master, '*', 'salt.minion', true)
    salt.enforceState(master, '*', 'heka', true)

    // Gather the Grafana metadata as grains
    salt.enforceState(master, 'I@grafana:collector', 'grafana.collector', true)

    // Update Salt Mine
    salt.enforceState(master, '*', 'salt.minion.grains', true)
    salt.runSaltProcessStep(master, '*', 'saltutil.refresh_modules', [], null, true)
    salt.runSaltProcessStep(master, '*', 'mine.update', [], null, true)

    sleep(5)

    // Update Heka
    salt.enforceState(master, 'I@heka:aggregator:enabled:True or I@heka:remote_collector:enabled:True', 'heka', true)

    // Update collectd
    salt.enforceState(master, 'I@collectd:remote_client:enabled:True', 'collectd', true)

    // Update Nagios
    salt.enforceState(master, 'I@nagios:server', 'nagios', true)
    // Stop the Nagios service because the package starts it by default and it will
    // started later only on the node holding the VIP address
    salt.runSaltProcessStep(master, 'I@nagios:server', 'service.stop', ['nagios3'], null, true)

    // Update Sensu
    // TODO for stacklight team, should be fixed in model
    //salt.enforceState(master, 'I@sensu:server', 'sensu', true)

    // Finalize the configuration of Grafana (add the dashboards...)
    salt.enforceState(master, 'I@grafana:client', 'grafana.client.service', true)
    // nw salt -C 'I@grafana:client' --async service.restart salt-minion; sleep 10

    salt.runSaltProcessStep(master, 'I@grafana.client and *01*', 'cmd.run', ['salt-call state.sls grafana.client'], null, true)
    // salt.enforceState(master, 'I@grafana:client and *01*', 'grafana.client', true)

    // Get the StackLight monitoring VIP addres
    //vip=$(salt-call pillar.data _param:stacklight_monitor_address --out key|grep _param: |awk '{print $2}')
    //vip=${vip:=172.16.10.253}
    def pillar = salt.getPillar(master, 'ctl01*', '_param:stacklight_monitor_address')
    print(common.prettyPrint(pillar))
    def stacklight_vip = pillar['return'][0].values()[0]

    if (stacklight_vip) {
        // (re)Start manually the services that are bound to the monitoring VIP
        common.infoMsg("restart services on node with IP: ${stacklight_vip}")
        salt.runSaltProcessStep(master, "G@ipv4:${stacklight_vip}", 'service.restart', ['remote_collectd'], null, true)
        salt.runSaltProcessStep(master, "G@ipv4:${stacklight_vip}", 'service.restart', ['remote_collector'], null, true)
        salt.runSaltProcessStep(master, "G@ipv4:${stacklight_vip}", 'service.restart', ['aggregator'], null, true)
        salt.runSaltProcessStep(master, "G@ipv4:${stacklight_vip}", 'service.restart', ['nagios3'], null, true)
    } else {
        throw new Exception("Missing stacklight_vip")
    }
}
