package com.mirantis.mk

/**
 *
 * Aptly functions
 *
 */

/**
 * Upload package into local repo
 *
 * @param file          File path
 * @param server        Server host
 * @param repo          Repository name
 */
def uploadPackage(file, server, repo, skipExists=false) {
    def pkg = file.split('/')[-1].split('_')[0]
    def jobName = currentBuild.build().environment.JOB_NAME

    sh("curl -v -f -F file=@${file} ${server}/api/files/${pkg}")
    sh("curl -v -o curl_out_${pkg}.log -f -X POST ${server}/api/repos/${repo}/file/${pkg}")

    try {
        sh("cat curl_out_${pkg}.log | json_pp | grep 'Unable to add package to repo' && exit 1 || exit 0")
    } catch (err) {
        sh("curl -s -f -X DELETE ${server}/api/files/${pkg}")
        if (skipExists == true) {
            println "[WARN] Package ${pkg} already exists in repo so skipping it's upload as requested"
        } else {
            error("Package ${pkg} already exists in repo, did you forget to add changelog entry and raise version?")
        }
    }
}

/**
 * Build step to upload package. For use with eg. parallel
 *
 * @param file          File path
 * @param server        Server host
 * @param repo          Repository name
 */
def uploadPackageStep(file, server, repo, skipExists=false) {
    return {
        uploadPackage(
            file,
            server,
            repo,
            skipExists
        )
    }
}

def snapshotRepo(server, repo, timestamp = null) {
    // XXX: timestamp parameter is obsoleted, time of snapshot creation is
    // what we should always use, not what calling pipeline provides
    def now = new Date();
    def ts = now.format("yyyyMMddHHmmss", TimeZone.getTimeZone('UTC'));

    def snapshot = "${repo}-${ts}"
    sh("curl -f -X POST -H 'Content-Type: application/json' --data '{\"Name\":\"$snapshot\"}' ${server}/api/repos/${repo}/snapshots")
}

def cleanupSnapshots(server, config='/etc/aptly-publisher.yaml', opts='-d --timeout 600') {
    sh("aptly-publisher -c ${config} ${opts} --url ${server} cleanup")
}

def diffPublish(server, source, target, components=null, opts='--timeout 600') {
    if (components) {
        def componentsStr = components.join(' ')
        opts = "${opts} --components ${componentsStr}"
    }
    sh("aptly-publisher --dry --url ${server} promote --source ${source} --target ${target} --diff ${opts}")
}

def promotePublish(server, source, target, recreate=false, components=null, packages=null, diff=false, opts='-d --timeout 600', dump_publish=false, storage="") {
    if (components && components != "all" && components != "") {
        def componentsStr = components.replaceAll(",", " ")
        opts = "${opts} --components ${componentsStr}"
    }
    if (packages && packages != "all" && packages != "") {
        def packagesStr = packages.replaceAll(",", " ")
        opts = "${opts} --packages ${packagesStr}"
    }
    if (recreate.toBoolean() == true) {
        opts = "${opts} --recreate"
    }
    if (diff.toBoolean() == true) {
        opts = "${opts} --dry --diff"
    }

    if (storage && storage != "") {
        opts = "${opts} --storage ${storage}"
    }

    if (dump_publish) {
        def now = new Date();
        dumpTarget = target
        def timestamp = now.format("yyyyMMddHHmmss", TimeZone.getTimeZone('UTC'));

        if (source.contains(')') || source.contains('*')) {
            sourceTarget = source.split('/')
            dumpTarget = target.split('/')[-1]
            sourceTarget[-1] = dumpTarget
            dumpTarget = sourceTarget.join('/')
        }

        dumpPublishes(server, timestamp, dumpTarget)
    }

    sh("aptly-publisher --url ${server} promote --acquire-by-hash --source '${source}' --target '${target}' --force-overwrite ${opts}")

}

def publish(server, config='/etc/aptly-publisher.yaml', recreate=false, only_latest=true, force_overwrite=true, opts='-d --timeout 3600') {
    if (recreate == true) {
        opts = "${opts} --recreate"
    }
    if (only_latest == true) {
        opts = "${opts} --only-latest"
    }
    if (force_overwrite == true) {
        opts = "${opts} --force-overwrite"
    }
    sh("aptly-publisher --url ${server} -c ${config} ${opts} --acquire-by-hash publish")
}

/**
 * Dump publishes
 *
 * @param server        Server host
 * @param save-dir      Directory where publishes are to be serialized
 * @param publishes     Publishes to be serialized
 * @param prefix        Prefix of dump files
 * @param opts          Options: debug, timeout, ...
 */
def dumpPublishes(server, prefix, publishes='all', opts='-d --timeout 600') {
    sh("aptly-publisher dump --url ${server} --save-dir . --prefix ${prefix} -p '${publishes}' ${opts}")
    archiveArtifacts artifacts: "${prefix}*"
}

/**
 * Restore publish from YAML file
 *
 * @param server        Server host
 * @param recreate      Recreate publishes
 * @param publish       Serialized YAML of Publish
 * @param components    Components to restore
 */
def restorePublish(server, recreate, publish, components='all') {

    opts = ""
    if (recreate) {
        opts << " --recreate"
    }

    sh("rm tmpFile || true")
    writeFile(file: "tmpFile", text: publish)
    sh("aptly-publisher restore --url ${server} --restore-file tmpFile --components ${components} ${opts}")
}
