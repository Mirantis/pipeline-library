package com.mirantis.mk

/**
 *
 * Virsh functions
 *
 */

/**
 * Ensures that the live snapshot exists
 *
 * @param nodeProvider      KVM node that hosts the VM
 * @param target            Unique identification of the VM being snapshoted without domain (for ex. ctl01)
 * @param snapshotName      Snapshot name
 * @param path              Path where snapshot image and dumpxml are being put
 * @param diskName          Disk name of the snapshot
 */
def liveSnapshotPresent(master, nodeProvider, target, snapshotName, path='/var/lib/libvirt/images', diskName='vda') {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def snapshotPresent = ""
    def domain = salt.getDomainName(master)
    try {
        snapshotPresent = salt.getReturnValues(salt.cmdRun(master, "${nodeProvider}*", "virsh snapshot-list ${target}.${domain} | grep ${snapshotName}")).split("\n")[0]
    } catch (Exception er) {
        common.infoMsg('snapshot not present')
    }
    if (!snapshotPresent.contains(snapshotName)) {
        def dumpxmlPresent = ''
        try {
            dumpxmlPresent = salt.getReturnValues(salt.cmdRun(master, "${nodeProvider}*", "ls -la ${path}/${target}.${domain}.xml")).split("\n")[0]
        } catch (Exception er) {
            common.infoMsg('dumpxml file not present')
        }
        if (!dumpxmlPresent?.trim()) {
            salt.cmdRun(master, "${nodeProvider}*", "virsh dumpxml ${target}.${domain} > ${path}/${target}.${domain}.xml")
        }
        salt.cmdRun(master, "${nodeProvider}*", "virsh snapshot-create-as --domain ${target}.${domain} ${snapshotName} --diskspec ${diskName},file=${path}/${target}.${domain}.${snapshotName}.qcow2 --disk-only --atomic")
    }
}

/**
 * Ensures that the live snapshot does not exist
 *
 * @param nodeProvider      KVM node that hosts the VM
 * @param target            Unique identification of the VM being snapshoted without domain (for ex. ctl01)
 * @param snapshotName      Snapshot name
 * @param path              Path where snapshot image and dumpxml are being put
 */
def liveSnapshotAbsent(master, nodeProvider, target, snapshotName, path='/var/lib/libvirt/images') {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def domain = salt.getDomainName(master)
    try {
        salt.cmdRun(master, "${nodeProvider}*", "virsh snapshot-delete ${target}.${domain} --metadata ${snapshotName}")
    } catch (Exception e) {
        common.warningMsg("Snapshot ${snapshotName} for ${target}.${domain} does not exist or failed to be removed")
    }
    try {
        salt.runSaltProcessStep(master, "${nodeProvider}*", 'file.remove', ["${path}/${target}.${domain}.${snapshotName}.qcow2"], null, true)
    } catch (Exception e) {
        common.warningMsg("Snapshot ${snapshotName} qcow2 file for ${target}.${domain} does not exist or failed to be removed")
    }
    try {
        salt.runSaltProcessStep(master, "${nodeProvider}*", 'file.remove', ["${path}/${target}.${domain}.xml"], null, true)
    } catch (Exception e) {
        common.warningMsg("Dumpxml file for ${target}.${domain} does not exist or failed to be removed")
    }
}

/**
 * Rollback
 *
 * @param nodeProvider      KVM node that hosts the VM
 * @param target            Unique identification of the VM being snapshoted without domain (for ex. ctl01)
 * @param snapshotName      Snapshot name
 * @param path              Path where snapshot image and dumpxml are being put
 */
def liveSnapshotRollback(master, nodeProvider, target, snapshotName, path='/var/lib/libvirt/images') {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def domain = salt.getDomainName(master)
    try {
        salt.getReturnValues(salt.cmdRun(master, "${nodeProvider}*", "ls -la ${path}/${target}.${domain}.xml"))
        salt.runSaltProcessStep(master, "${nodeProvider}*", 'virt.destroy', ["${target}.${domain}"], null, true)
        salt.cmdRun(master, "${nodeProvider}*", "virsh define ${path}/${target}.${domain}.xml")
        liveSnapshotAbsent(master, nodeProvider, target, snapshotName, path)
        salt.runSaltProcessStep(master, "${nodeProvider}*", 'virt.start', ["${target}.${domain}"], null, true)
    } catch (Exception er) {
        common.infoMsg("No rollback for ${target}.${domain} was executed. Dumpxml file not present.")
    }
}

/**
 * Merge snapshot while instance is running
 *
 * @param nodeProvider      KVM node that hosts the VM
 * @param target            Unique identification of the VM being snapshoted without domain (for ex. ctl01)
 * @param snapshotName      Snapshot name
 * @param path              Path where snapshot image and dumpxml are being put
 * @param diskName          Disk name of the snapshot
 */
def liveSnapshotMerge(master, nodeProvider, target, snapshotName, path='/var/lib/libvirt/images', diskName='vda') {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def domain = salt.getDomainName(master)
    try {
        salt.cmdRun(master, "${nodeProvider}*", "virsh blockcommit ${target}.${domain} ${diskName} --active --verbose --pivot")
        try {
            salt.cmdRun(master, "${nodeProvider}*", "virsh snapshot-delete ${target}.${domain} --metadata ${snapshotName}")
        } catch (Exception e) {
            common.warningMsg("Snapshot ${snapshotName} for ${target}.${domain} does not exist or failed to be removed")
        }
        try {
            salt.runSaltProcessStep(master, "${nodeProvider}*", 'file.remove', ["${path}/${target}.${domain}.${snapshotName}.qcow2"], null, true)
        } catch (Exception e) {
            common.warningMsg("Snapshot ${snapshotName} qcow2 file for ${target}.${domain} does not exist or failed to be removed")
        }
        try {
            salt.runSaltProcessStep(master, "${nodeProvider}*", 'file.remove', ["${path}/${target}.${domain}.xml"], null, true)
        } catch (Exception e) {
            common.warningMsg("Dumpxml file for ${target}.${domain} does not exist or failed to be removed")
        }
    } catch (Exception e) {
        common.errorMsg("The live snapshoted VM ${target}.${domain} failed to be merged, trying to fix it")
        checkLiveSnapshotMerge(master, nodeProvider, target, snapshotName, path, diskName)
    }
}


/**
 * Check live snapshot merge failure due to known qemu issue not receiving message about merge completion
 *
 * @param nodeProvider      KVM node that hosts the VM
 * @param target            Unique identification of the VM being snapshoted without domain (for ex. ctl01)
 * @param snapshotName      Snapshot name
 * @param path              Path where snapshot image and dumpxml are being put
 * @param diskName          Disk name of the snapshot
 */
def checkLiveSnapshotMerge(master, nodeProvider, target, snapshotName, path='/var/lib/libvirt/images', diskName='vda') {
    def salt = new com.mirantis.mk.Salt()
    def domain = salt.getDomainName(master)
    def out =  salt.getReturnValues(salt.cmdRun(master, "${nodeProvider}*", "virsh blockjob ${target}.${domain} ${diskName} --info"))
    if (out.contains('Block Commit')) {
        def blockJobs = salt.getReturnValues(salt.cmdRun(master, "{nodeProvider}*", "virsh qemu-monitor-command ${target}.${domain} --pretty -- '{ \"execute\": \"query-block-jobs\" }'"))
        if (blockJobs.contains('offset')) {
            // if Block Commit hangs on 100 and check offset - len = 0, then it is safe to merge the image
            input message: "Please check if offset - len = 0, If so run: virsh qemu-monitor-command ${target}.${domain} --pretty -- '{ \"execute\": \"block-job-complete\", \"arguments\": { \"device\": \"drive-virtio-disk0\" } }', then virsh define ${path}/${target}.${domain}.xml, then virsh snapshot-delete ${target}.${domain} --metadata ${snapshotName} and remove ${path}/${target}.${domain}.${snapshotName}.qcow2 file. When you resolve this issue click on PROCEED."
        }
    }
}

