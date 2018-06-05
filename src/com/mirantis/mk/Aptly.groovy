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

/**
 * Cleanup snapshots
 *
 * @param server        Server host
 * @param opts          Options: debug, timeout, ...
 */
def cleanupSnapshots(server, opts='-d'){
    sh("aptly-publisher --url ${server} ${opts} cleanup")
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

def publish(server, config='/etc/aptly/publisher.yaml', recreate=false, only_latest=true, force_overwrite=true, opts='-d --timeout 3600') {
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
    if (findFiles(glob: "${prefix}*")) {
       archiveArtifacts artifacts: "${prefix}*"
    } else {
       def common = new com.mirantis.mk.Common()
       common.warningMsg("Aptly dump publishes for a prefix ${prefix}* failed. No dump files found! This can be OK in case of your creating new publish")
    }
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

/**
 * The function return name of the snapshot belongs to repo considering the prefix with storage by REST API.
 *
 * @param server        URI the server to connect to aptly API
 * @param destribution  Destiribution of the repo which have to be found
 * @param prefix        Prefix of the repo including storage eg. prefix or s3:aptcdn:prefix
 * @param component     Component of the repo
 *
 * @return snapshot name
 **/
def getSnapshotByAPI(server, distribution, prefix, component) {
    http = new com.mirantis.mk.Http()
    def list_published = http.restGet(server, '/api/publish')
    def storage
    for (items in list_published) {
        for (row in items) {
            if (prefix.tokenize(':')[1]) {
                storage = prefix.tokenize(':')[0] + ':' + prefix.tokenize(':')[1]
            } else {
                storage = ''
            }
            if (row.key == 'Distribution' && row.value == distribution && items['Prefix'] == prefix.tokenize(':').last() && items['Storage'] == storage) {
                for (source in items['Sources']){
                    if (source['Component'] == component) {
                        if(env.getEnvironment().containsKey('DEBUG') && env['DEBUG'] == "true"){
                            println ('Snapshot belongs to ' + distribution + '/' + prefix + ': ' + source['Name'])
                        }
                        return source['Name']
                    }
                }
            }
        }
    }
    return false
}

/**
 * Returns list of the packages from specified Aptly repo  by REST API
 *
 * @param server            URI of the server insluding port and protocol
 * @param repo              Local repo name
 **/
def listPackagesFromRepoByAPI(server, repo){
    http = new com.mirantis.mk.Http()
    def packageList = http.restGet(server, "/api/repos/${repo}/packages")
    return packageList
}

/**
 * Deletes packages from specified Aptly repo by REST API
 *
 * @param server            URI of the server insluding port and protocol
 * @param repo              Local repo name
 * @param packageRefs       Package list specified by packageRefs
 **/
def deletePackagesFromRepoByAPI(server, repo, packageRefs){
    http = new com.mirantis.mk.Http()
    def data  = [:]
    data['PackageRefs'] = packageRefs
    http.restDelete(server, "/api/repos/${repo}/packages", data)
}

/**
 * Returns list of the packages matched to pattern and
 * belonged to particular snapshot by REST API
 *
 * @param server            URI of the server insluding port and protocol
 * @param snapshotName      Snapshot to check
 * @param packagesList      Pattern of the components to be compared
 **/
def snapshotPackagesByAPI(server, snapshotName, packagesList) {
    http = new com.mirantis.mk.Http()
    def pkgs = http.restGet(server, "/api/snapshots/${snapshotName}/packages")
    def packages = []

    for (package_pattern in packagesList.tokenize(',')) {
        def pkg = pkgs.find { item -> item.contains(package_pattern) }
        packages.add(pkg)
    }

    return packages
}


/**
 * Creates snapshot of the repo or package refs by REST API
 * @param server               URI of the server insluding port and protocol
 * @param repo                 Local repo name
 * @param snapshotName         Snapshot name is going to be created
 * @param snapshotDescription  Snapshot description
 * @param packageRefs          List of the packages are going to be included into the snapshot
 **/
def snapshotCreateByAPI(server, repo, snapshotName, snapshotDescription = null, packageRefs = null) {
    http = new com.mirantis.mk.Http()
    def data  = [:]
    data['Name'] = snapshotName
    if (snapshotDescription) {
        data['Description'] = snapshotDescription
    } else {
        data['Description'] = "Snapshot of ${repo} repo"
    }
    if (packageRefs) {
        data['PackageRefs'] = packageRefs
        http.restPost(server, '/api/snapshots', data)
    } else {
        http.restPost(server, "/api/repos/${repo}/snapshots", data)
    }
}

/**
 * Publishes the snapshot accodgin to distribution, components and prefix by REST API
 * @param server        URI of the server insluding port and protocol
 * @param snapshotName  Snapshot is going to be published
 * @param distribution  Distribution for the published repo
 * @param components    Component for the published repo
 * @param prefix        Prefix for thepubslidhed repo including storage
 **/
def snapshotPublishByAPI(server, snapshotName, distribution, components, prefix) {
    http = new com.mirantis.mk.Http()
    def source = [:]
    source['Name'] = snapshotName
    source['Component'] = components
    def data = [:]
    data['SourceKind'] = 'snapshot'
    data['Sources'] = [source]
    data['Architectures'] = ['amd64']
    data['Distribution'] = distribution
    return http.restPost(server, "/api/publish/${prefix}", data)
}

/**
 * Unpublish Aptly repo by REST API
 *
 * @param aptlyServer Aptly connection object
 * @param aptlyPrefix Aptly prefix where need to delete a repo
 * @param aptlyRepo  Aptly repo name
 */
def unpublishByAPI(aptlyServer, aptlyPrefix, aptlyRepo){
    http = new com.mirantis.mk.Http()
    http.restDelete(aptlyServer, "/api/publish/${aptlyPrefix}/${aptlyRepo}")
}

/**
 * Delete Aptly repo by REST API
 *
 * @param aptlyServer Aptly connection object
 * @param aptlyRepo  Aptly repo name
 */
def deleteRepoByAPI(aptlyServer, aptlyRepo){
    http = new com.mirantis.mk.Http()
    http.restDelete(aptlyServer, "/api/repos/${aptlyRepo}")
}
