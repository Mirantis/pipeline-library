package com.mirantis.mk

/**
 * Install and configure ceph clients
 *
 * @param master        Salt connection object
 * @param extra_tgt     Extra targets for compound
 */
def installClient(master, extra_tgt='') {
    def salt = new Salt()

    // install Ceph Radosgw
    installRgw(master, "I@ceph:radosgw", extra_tgt)

    // setup keyring for Openstack services
    salt.enforceStateWithTest([saltId: master, target: "I@ceph:common and I@glance:server $extra_tgt", state: ['ceph.common', 'ceph.setup.keyring']])
    salt.enforceStateWithTest([saltId: master, target: "I@ceph:common and I@cinder:controller $extra_tgt", state: ['ceph.common', 'ceph.setup.keyring']])
    salt.enforceStateWithTest([saltId: master, target: "I@ceph:common and I@nova:compute $extra_tgt", state: ['ceph.common', 'ceph.setup.keyring']])
    salt.enforceStateWithTest([saltId: master, target: "I@ceph:common and I@gnocchi:server $extra_tgt", state: ['ceph.common', 'ceph.setup.keyring']])
}

/**
 * Install and configure ceph monitor on target
 *
 * @param master        Salt connection object
 * @param target        Target specification, compliance to compound matcher in salt
 * @param extra_tgt     Extra targets for compound
 */
def installMon(master, target="I@ceph:mon", extra_tgt='') {
    def salt = new Salt()

    salt.enforceState([saltId: master, target: "$target $extra_tgt", state: 'salt.minion.grains'])

    // TODO: can we re-add cmn01 with proper keyrings?
    // generate keyrings
    if(salt.testTarget(master, "( I@ceph:mon:keyring:mon or I@ceph:common:keyring:admin ) $extra_tgt")) {
        salt.enforceState([saltId: master, target: "( I@ceph:mon:keyring:mon or I@ceph:common:keyring:admin ) $extra_tgt", state: 'ceph.mon'])
        salt.runSaltProcessStep(master, "I@ceph:mon $extra_tgt", 'saltutil.sync_grains')
        salt.runSaltProcessStep(master, "( I@ceph:mon:keyring:mon or I@ceph:common:keyring:admin ) $extra_tgt", 'mine.update')

        // on target nodes mine is used to get pillar from 'ceph:common:keyring:admin' via grain.items
        // we need to refresh all pillar/grains to make data sharing work correctly
        salt.fullRefresh(master, "( I@ceph:mon:keyring:mon or I@ceph:common:keyring:admin ) $extra_tgt")

        sleep(5)
    }
    // install Ceph Mons
    salt.enforceState([saltId: master, target: "I@ceph:mon $extra_tgt", state: 'ceph.mon'])
    salt.enforceStateWithTest([saltId: master, target: "I@ceph:mgr $extra_tgt", state: 'ceph.mgr'])

    // update config
    salt.enforceState([saltId: master, target: "I@ceph:common $extra_tgt", state: 'ceph.common'])
}

/**
 * Install and configure osd daemons on target
 *
 * @param master        Salt connection object
 * @param target        Target specification, compliance to compound matcher in salt
 * @param extra_tgt     Extra targets for compound
 */
def installOsd(master, target="I@ceph:osd", setup=true, extra_tgt='') {
    def salt = new Salt()
    def orchestrate = new Orchestrate()

    // install Ceph OSDs
    salt.enforceState([saltId: master, target: target, state: ['linux.storage','ceph.osd']])
    salt.runSaltProcessStep(master, "I@ceph:osd $extra_tgt", 'saltutil.sync_grains')
    salt.enforceState([saltId: master, target: target, state: 'ceph.osd.custom'])
    salt.runSaltProcessStep(master, "I@ceph:osd $extra_tgt", 'saltutil.sync_grains')
    salt.runSaltProcessStep(master, "I@ceph:osd $extra_tgt", 'mine.update')

    // setup pools, keyrings and maybe crush
    if(salt.testTarget(master, "I@ceph:setup $extra_tgt") && setup) {
        orchestrate.installBackup(master, 'ceph')
        salt.enforceState([saltId: master, target: "I@ceph:setup $extra_tgt", state: 'ceph.setup'])
    }
}

/**
 * Install and configure rgw service on target
 *
 * @param master        Salt connection object
 * @param target        Target specification, compliance to compound matcher in salt
 * @param extra_tgt     Extra targets for compound
 */
def installRgw(master, target="I@ceph:radosgw", extra_tgt='') {
    def salt = new Salt()

    if(salt.testTarget(master, "I@ceph:radosgw $extra_tgt")) {
        salt.fullRefresh(master, "I@ceph:radosgw $extra_tgt")
        salt.enforceState([saltId: master, target: "I@ceph:radosgw $extra_tgt", state: ['keepalived', 'haproxy', 'ceph.radosgw']])
    }
}

/**
 * Remove rgw daemons from target
 *
 * @param master        Salt connection object
 * @param target        Target specification, compliance to compound matcher in salt
 * @param extra_tgt     Extra targets for compound
 */
def removeRgw(master, target, extra_tgt='') {
    def salt = new Salt()

    // TODO needs to be reviewed
    salt.fullRefresh(master, "I@ceph:radosgw $extra_tgt")
    salt.enforceState([saltId: master, target: "I@ceph:radosgw $extra_tgt", state: ['keepalived', 'haproxy', 'ceph.radosgw']])
}

/**
 * Remove osd daemons from target
 *
 * @param master        Salt connection object
 * @param target        Target specification, compliance to compound matcher in salt
 * @param osds          List of osd to remove
 * @param safeRemove    Wait for data rebalance before remove drive
 * @param target        Target specification, compliance to compound matcher in salt
 */
def removeOsd(master, target, osds, flags, safeRemove=true, wipeDisks=false) {
    def common = new Common()
    def salt = new Salt()

    // systemctl stop ceph-osd@0 && ceph osd purge 0 --yes-i-really-mean-it && umount /dev/vdc1; test -b /dev/vdc1 && dd if=/dev/zero of=/dev/vdc1 bs=1M; test -b /dev/vdc2 && dd if=/dev/zero of=/dev/vdc2 bs=1M count=100; sgdisk -d1 -d2 /dev/vdc; partprobe
    if(osds.isEmpty()) {
        common.warningMsg('List of OSDs was empty. No OSD is removed from cluster')
        return
    }

    // `ceph osd out <id> <id>`
    cmdRun(master, 'ceph osd out ' + osds.join(' '), true, true)

    if(safeRemove) {
        waitForHealthy(master, flags)
    }

    for(osd in osds) {
        salt.runSaltProcessStep(master, target, 'service.stop', "ceph-osd@$osd", null, true)
        cmdRun(master, "ceph osd purge $osd --yes-i-really-mean-it", true, true)
    }

    for(osd in osds) {
        def lvm_enabled = getPillar(master, target, "ceph:osd:lvm_enabled")
        if(lvm_enabled) {
            // ceph-volume lvm zap --osd-id 1 --osd-fsid 55BD4219-16A7-4037-BC20-0F158EFCC83D --destroy
            def output = cmdRunOnTarget(master, target, "ceph-volume lvm zap --osd-id $osd --destroy >/dev/null && echo 'zaped'", false)
            if(output == 'zaped') { continue }
        }

        common.infoMsg("Removing legacy osd.")
        def journal_partition = ""
        def block_db_partition = ""
        def block_wal_partition = ""
        def block_partition = ""
        def data_partition = ""
        def dataDir = "/var/lib/ceph/osd/ceph-$osd"
        journal_partition = cmdRunOnTarget(master, target,
            "test -f $dataDir/journal_uuid && readlink -f /dev/disk/by-partuuid/`cat $dataDir/journal_uuid`", false)
        block_db_partition = cmdRunOnTarget(master, target,
            "test -f $dataDir/block.db_uuid && readlink -f /dev/disk/by-partuuid/`cat $dataDir/block.db_uuid`", false)
        block_wal_partition = cmdRunOnTarget(master, target,
            "test -f $dataDir/block.wal_uuid && readlink -f /dev/disk/by-partuuid/`cat $dataDir/block.wal_uuid`", false)
        block_partition = cmdRunOnTarget(master, target,
            "test -f $dataDir/block_uuid && readlink -f /dev/disk/by-partuuid/`cat $dataDir/block_uuid`", false)
        data_partition = cmdRunOnTarget(master, target,
            "test -f $dataDir/fsid && readlink -f /dev/disk/by-partuuid/`cat $dataDir/fsid`", false)

        try {
            if(journal_partition.trim()) { removePartition(master, target, journal_partition) }
            if(block_db_partition.trim()) { removePartition(master, target, block_db_partition) }
            if(block_wal_partition.trim()) { removePartition(master, target, block_wal_partition) }
            if(block_partition.trim()) { removePartition(master, target, block_partition, 'block', wipeDisks) }
            if(data_partition.trim()) { removePartition(master, target, data_partition, 'data', wipeDisks) }
            else { common.warningMsg("Can't find data partition for osd.$osd") }
        }
        catch(Exception e) {
            // report but continue as problem on one osd could be sorted out after
            common.errorMsg("Found some issue during cleaning partition for osd.$osd on $target")
            common.errorMsg(e)
            currentBuild.result = 'FAILURE'
        }

        cmdRunOnTarget(master, target, "partprobe", false)
    }
}

/**
 * Update montoring for target hosts
 *
 * @param master        Salt connection object
 * @param target        Target specification, compliance to compound matcher in salt
 * @param extra_tgt     Extra targets for compound
 */
def updateMonitoring(master, target="I@ceph:common", extra_tgt='') {
    def common = new Common()
    def salt = new Salt()

    def prometheusNodes = salt.getMinions(master, "I@prometheus:server $extra_tgt")
    if(!prometheusNodes.isEmpty()) {
        //Collect Grains
        salt.enforceState([saltId: master, target: "$target $extra_tgt", state: 'salt.minion.grains'])
        salt.runSaltProcessStep(master, "$target $extra_tgt", 'saltutil.refresh_modules')
        salt.runSaltProcessStep(master, "$target $extra_tgt", 'mine.update')
        sleep(5)
        salt.enforceState([saltId: master, target: "$target $extra_tgt", state: ['fluentd', 'telegraf', 'prometheus']])
        salt.enforceState([saltId: master, target: "I@prometheus:server $extra_tgt", state: 'prometheus'])
    }
    else {
        common.infoMsg('No Prometheus nodes in cluster. Nothing to do.')
    }
}

def connectCeph(master, extra_tgt='') {
    new Common().infoMsg("This method was renamed. Use method connectOS insead.")
    connectOS(master, extra_tgt)
}

/**
 * Enforce configuration and connect OpenStack clients
 *
 * @param master        Salt connection object
 * @param extra_tgt     Extra targets for compound
 */
def connectOS(master, extra_tgt='') {
    def salt = new Salt()

    // setup Keystone service and endpoints for swift or / and S3
    salt.enforceStateWithTest([saltId: master, target: "I@keystone:client $extra_tgt", state: 'keystone.client'])

    // connect Ceph to the env
    if(salt.testTarget(master, "I@ceph:common and I@glance:server $extra_tgt")) {
        salt.enforceState([saltId: master, target: "I@ceph:common and I@glance:server $extra_tgt", state: ['glance']])
        salt.runSaltProcessStep(master, "I@ceph:common and I@glance:server $extra_tgt", 'service.restart', ['glance-api'])
    }
    if(salt.testTarget(master, "I@ceph:common and I@cinder:controller $extra_tgt")) {
        salt.enforceState([saltId: master, target: "I@ceph:common and I@cinder:controller $extra_tgt", state: ['cinder']])
        salt.runSaltProcessStep(master, "I@ceph:common and I@cinder:controller $extra_tgt", 'service.restart', ['cinder-volume'])
    }
    if(salt.testTarget(master, "I@ceph:common and I@nova:compute $extra_tgt")) {
        salt.enforceState([saltId: master, target: "I@ceph:common and I@nova:compute $extra_tgt", state: ['nova']])
        salt.runSaltProcessStep(master, "I@ceph:common and I@nova:compute $extra_tgt", 'service.restart', ['nova-compute'])
    }
    if(salt.testTarget(master, "I@ceph:common and I@gnocchi:server $extra_tgt")) {
        salt.enforceState([saltId: master, target: "I@ceph:common and I@gnocchi:server:role:primary $extra_tgt", state: 'gnocchi.server'])
        salt.enforceState([saltId: master, target: "I@ceph:common and I@gnocchi:server $extra_tgt", state: 'gnocchi.server'])
    }
}

/**
 * Remove vm from VCP
 *
 * @param master        Salt connection object
 * @param target        Target specification, compliance to compound matcher in salt
 */
def removeVm(master, target) {
    def common = new Common()
    def salt = new Salt()

    def fqdn = getGrain(master, target, 'id')
    def hostname = salt.stripDomainName(fqdn)
    def hypervisor = getPillar(master, "I@salt:control", "salt:control:cluster:internal:node:$hostname:provider")

    removeSalt(master, target)

    if(hypervisor?.trim()) {
        cmdRunOnTarget(master, hypervisor, "virsh destroy $fqdn")
        cmdRunOnTarget(master, hypervisor, "virsh undefine $fqdn")
    }
    else {
        common.errorMsg("There is no provider in pillar for $hostname")
    }
}

/**
 * Stop target salt minion, remove its key on master and definition in reclass
 *
 * @param master        Salt connection object
 * @param target        Target specification, compliance to compound matcher in salt
 */
def removeSalt(master, target) {
    def common = new Common()

    def fqdn = getGrain(master, target, 'id')
    try {
        cmdRunOnTarget(master, 'I@salt:master', "salt-key --include-accepted -r $fqdn -y")
    }
    catch(Exception e) {
        common.warningMsg(e)
    }
}

def deleteKeyrings(master, target, extra_tgt='') {
    def host = getGrain(master, target, 'host')
    def keys = cmdRun(master, "ceph auth list 2>/dev/null | grep $host", false).tokenize('\n')
    if(keys.isEmpty()) {
        new Common().warningMsg("Nothing to do. There is no keyring for $host")
    }
    for(key in keys) {
        cmdRun(master, "ceph auth del $key")
    }
}

def generateMapping(pgmap,map) {
    def pg_new
    def pg_old
    for(pg in pgmap) {
        pg_new = pg["up"].minus(pg["acting"])
        pg_old = pg["acting"].minus(pg["up"])
        for(int i = 0; i < pg_new.size(); i++) {
            // def string = "ceph osd pg-upmap-items " + pg["pgid"].toString() + " " + pg_new[i] + " " + pg_old[i] + ";"
            def string = "ceph osd pg-upmap-items ${pg["pgid"]} ${pg_new[i]} ${pg_old[i]}"
            map.add(string)
        }
    }
}

/**
 * Run command on the first of avaliable ceph monitors
 *
 * @param master        Salt connection object
 * @param cmd           Command to run
 * @param checkResponse Check response of command. (optional, default true)
 * @param output        Print output (optional, default false)
 */
def cmdRun(master, cmd, checkResponse=true, output=false) {
    def salt = new Salt()
    def cmn01 = salt.getFirstMinion(master, "I@ceph:mon")
    return salt.cmdRun(master, cmn01, cmd, checkResponse, null, output)['return'][0][cmn01]
}

/**
 * Run command on target host
 *
 * @param master        Salt connection object
 * @param target        Target specification, compliance to compound matcher in salt
 * @param cmd           Command to run
 * @param checkResponse Check response of command. (optional, default true)
 * @param output        Print output (optional, default false)
 */
def cmdRunOnTarget(master, target, cmd, checkResponse=true, output=false) {
    def salt = new Salt()
    return salt.cmdRun(master, target, cmd, checkResponse, null, output)['return'][0].values()[0]
}

/**
 * Ceph refresh pillars and get one for first host
 *
 * @param master        Salt connection object
 * @param target        Target specification, compliance to compound matcher in salt
 * @param pillar        Pillar to obtain
 */
def getPillar(master, target, pillar) {
    def common = new Common()
    def salt = new Salt()
    try {
        return salt.getPillar(master, target, pillar)['return'][0].values()[0]
    }
    catch(Exception e) {
        common.warningMsg('There was no pillar for the target.')
    }
}

/**
 * Ceph refresh grains and get one for first host
 *
 * @param master        Salt connection object
 * @param target        Target specification, compliance to compound matcher in salt
 * @param grain         Grain to obtain
 */
def getGrain(master, target, grain) {
    def common = new Common()
    def salt = new Salt()
    try {
        return salt.getGrain(master, target, grain)['return'][0].values()[0].values()[0]
    }
    catch(Exception e) {
        common.warningMsg('There was no grain for the target.')
    }
}

/**
 * Set flags
 *
 * @param master        Salt connection object
 * @param flags         Collection of flags to set
 */
def setFlags(master, flags) {
    if(flags instanceof String) { flags = [flags] }
    for(flag in flags) {
        cmdRun(master, 'ceph osd set ' + flag)
    }
}

/**
 * Unset flags
 *
 * @param master        Salt connection object
 * @param flags         Collection of flags to unset (optional)
 */
def unsetFlags(master, flags=[]) {
    if(flags instanceof String) { flags = [flags] }
    for(flag in flags) {
        cmdRun(master, 'ceph osd unset ' + flag)
    }
}

/**
 * Wait for healthy cluster while ignoring flags which have been set
 *
 * @param master        Salt connection object
 * @param attempts      Attempts before it pause execution (optional, default 300)
 */
def waitForHealthy(master, flags, attempts=300) {
    def common = new Common()

    def count = 0
    def isHealthy = false
    def health = ''

    // wait for current ops will be reflected in status
    sleep(5)

    while(count++ < attempts) {
        health = cmdRun(master, 'ceph health', false)
        if(health == 'HEALTH_OK') { return }
        else {
            // HEALTH_WARN noout,norebalance flag(s) set
            def unexpectedFlags = health.tokenize(' ').getAt(1)?.tokenize(',')
            unexpectedFlags.removeAll(flags)
            if(health.contains('HEALTH_WARN') && unexpectedFlags.isEmpty()) { return }
        }
        common.warningMsg("Ceph cluster is still unhealthy: $health")
        sleep(10)
    }
    // TODO: MissingMethodException
    input message: "After ${count} attempts cluster is still unhealthy."
    //throw new RuntimeException("After ${count} attempts cluster is still unhealthy. Can't proceed")
}
def waitForHealthy(master, String host, flags, attempts=300) {
    new Common().warningMsg('This method will be deprecated.')
    waitForHealthy(master, flags, attempts)
}

/**
 * Remove unused orphan partition after some osds
 *
 * @param master        Salt connection object
 * @param target        Target specification, compliance to compound matcher in salt
 * @param wipePartitions     Wipe each found partitions completely (optional, defaul false)
 */
def removeOrphans(master, target, wipePartitions=false) {
    def common = new Common()
    def salt = new Salt()

    def orphans = []
    // TODO: ceph-disk is avaliable only in luminous
    def disks = cmdRunOnTarget(master, target, "ceph-disk list --format json 2>/dev/null",false)
    disks = "{\"disks\":$disks}" // common.parseJSON() can't parse a list of maps
    disks = common.parseJSON(disks)['disks']
    for(disk in disks) {
        for(partition in disk.get('partitions')) {
            def orphan = false
            if(partition.get('type') == 'block.db' && !partition.containsKey('block.db_for')) { orphan = true }
            else if(partition.get('type') == 'block' && !partition.containsKey('block_for')) { orphan = true }
            else if(partition.get('type') == 'data' && !partition.get('state') == 'active') { orphan = true }
            // TODO: test for the rest of types

            if(orphan) {
                if(partition.get('path')) {
                    removePartition(master, target, partition['path'], partition['type'], wipePartitions)
                }
                else {
                    common.warningMsg("Found orphan partition on $target but failed to remove it.")
                }
            }
        }
    }
    cmdRunOnTarget(master, target, "partprobe", false)
}

/**
 * Ceph remove partition
 *
 * @param master        Salt connection object
 * @param target        Target specification, compliance to compound matcher in salt
 * @param partition     Partition to remove on target host
 * @param type          Type of partition. Some partition need additional steps (optional, default empty string)
 * @param fullWipe      Fill the entire partition with zeros (optional, default false)
 */
def removePartition(master, target, partition, type='', fullWipe=false) {
    def common = new Common()
    def salt = new Salt()

    def dev = ''
    def part_id = ''
    def partitionID = ''
    def disk = ''
    def wipeCmd = ''
    def lvm_enabled = getPillar(master, target, "ceph:osd:lvm_enabled")

    if(!partition?.trim()) {
        throw new Exception("Can't proceed without defined partition.")
    }
    cmdRunOnTarget(master, target, "test -b $partition")

    if(fullWipe) { wipeCmd = "dd if=/dev/zero of=$partition bs=1M 2>/dev/null" }
    else { wipeCmd = "dd if=/dev/zero of=$partition bs=1M count=100 2>/dev/null" }

    common.infoMsg("Removing from the cluster $type partition $partition on $target.")
    if(type == 'lockbox') {
        try {
            partition = cmdRunOnTarget(master, target, "lsblk -rp | grep -v mapper | grep $partition", false)
            cmdRunOnTarget(master, target, "umount $partition")
        }
        catch (Exception e) {
            common.warningMsg(e)
        }
    }
    else if(type == 'data') {
        cmdRunOnTarget(master, target, "umount $partition 2>/dev/null", false)
        cmdRunOnTarget(master, target, wipeCmd, false)
    }
    else if(type == 'block' || fullWipe) {
        cmdRunOnTarget(master, target, wipeCmd, false)
    }
    try {
        partitionID = cmdRunOnTarget(master, target, "cat /sys/dev/block/`lsblk $partition -no MAJ:MIN | xargs`/partition", false)
        disk = cmdRunOnTarget(master, target, "lsblk $partition -no pkname", false)
    }
    catch (Exception e) {
        common.errorMsg("Couldn't get disk name or partition number for $partition")
        common.warningMsg(e)
    }
    try {
        cmdRunOnTarget(master, target, "sgdisk -d$partitionID /dev/$disk", true, true)
    }
    catch (Exception e) {
        common.warningMsg("Did not found any device to be wiped.")
        common.warningMsg(e)
    }
    // try to remove partition table if disk have no partitions left - required by ceph-volume
    cmdRunOnTarget(master, target, "partprobe -d -s /dev/$disk | grep partitions\$ && sgdisk -Z /dev/$disk", false, true)
}
