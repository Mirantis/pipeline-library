package com.mirantis.mk

/**
 *
 * Ceph functions
 *
 */

/**
 * Ceph health check
 *
 */
def waitForHealthy(master, target, flags=[], count=0, attempts=300) {
    def common = new com.mirantis.mk.Common()
    def salt = new com.mirantis.mk.Salt()
    // wait for healthy cluster
    while (count < attempts) {
        def health = salt.cmdRun(master, target, 'ceph health')['return'][0].values()[0]
        if (health.contains('HEALTH_OK')) {
            common.infoMsg('Cluster is healthy')
            break
        } else {
            for (flag in flags) {
                if (health.contains(flag + ' flag(s) set') && !(health.contains('down'))) {
                    common.infoMsg('Cluster is healthy')
                    return
                }
            }
        }
        common.infoMsg("Ceph health status: ${health}")
        count++
        sleep(10)
    }
}

/**
 * Ceph remove partition
 *
 */
def removePartition(master, target, partition_uuid, type='', id=-1) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def partition = ""
    if (type == 'lockbox') {
        try {
            // umount - partition = /dev/sdi2
            partition = salt.cmdRun(master, target, "lsblk -rp | grep -v mapper | grep ${partition_uuid} ")['return'][0].values()[0].split()[0]
            salt.cmdRun(master, target, "umount ${partition}")
        } catch (Exception e) {
            common.warningMsg(e)
        }
    } else if (type == 'data') {
        try {
            // umount - partition = /dev/sdi2
            partition = salt.cmdRun(master, target, "df | grep /var/lib/ceph/osd/ceph-${id}")['return'][0].values()[0].split()[0]
            salt.cmdRun(master, target, "umount ${partition}")
        } catch (Exception e) {
            common.warningMsg(e)
        }
        try {
            // partition = /dev/sdi2
            partition = salt.cmdRun(master, target, "blkid | grep ${partition_uuid} ")['return'][0].values()[0].split(":")[0]
        } catch (Exception e) {
            common.warningMsg(e)
        }
    } else {
        try {
            // partition = /dev/sdi2
            partition = salt.cmdRun(master, target, "blkid | grep ${partition_uuid} ")['return'][0].values()[0].split(":")[0]
        } catch (Exception e) {
            common.warningMsg(e)
        }
    }
    if (partition?.trim()) {
        if (partition.contains("nvme")) {
            // partition = /dev/nvme1n1p2
            // dev = /dev/nvme1n1
            def dev = partition.replaceAll('p\\d+$', "")
            // part_id = 2
            def part_id = partition.substring(partition.lastIndexOf("p") + 1).replaceAll("[^0-9]+", "")

        } else {
            // partition = /dev/sdi2
            // dev = /dev/sdi
            def dev = partition.replaceAll('\\d+$', "")
            // part_id = 2
            def part_id = partition.substring(partition.lastIndexOf("/") + 1).replaceAll("[^0-9]+", "")
        }
        salt.cmdRun(master, target, "Ignore | parted ${dev} rm ${part_id}")
    }
    return
}
