package com.mirantis.mk

/**
 *
 *  Functions to work with Helm
 *
 */

/**
 * Build index file for helm chart
 * @param extra_params   additional params, e.g. --url repository_URL
 * @param charts_dir     path to a directory
 */

def helmRepoIndex(extra_params='', charts_dir='.'){
    sh("helm repo index ${extra_params} ${charts_dir}")
}

/**
 * Rebuild index file for helm chart repo
 * @param absHelmRepoUrl if set to true, URLs to charts will be absolute
 * @param helmRepoUrl    repository with helm charts
 * @param md5Remote      md5 sum of index.yaml for check
 */

def helmMergeRepoIndex(helmRepoUrl, md5Remote='', absHelmRepoUrl=true) {
    def common      = new com.mirantis.mk.Common()

    def helmRepoDir    = '.'
    def helmExtraParams = ''
    if (absHelmRepoUrl) {
        helmExtraParams = "--url ${helmRepoUrl}"
    }

    def indexRes = common.shCmdStatus("wget -O index-upstream.yaml ${helmRepoUrl}/index.yaml")

    if (indexRes['status']){
        if (indexRes['status'] == 8 && indexRes['stderr'].contains('ERROR 404') && !md5Remote) {
            common.warningMsg("Index.yaml not found in ${helmRepoUrl} and will be fully regenerated")
        } else {
            error("Something went wrong during index.yaml download: ${indexRes['stderr']}")
        }
    } else {
        if (md5Remote) {
            def md5Local = sh(script: "md5sum index-upstream.yaml | cut -d ' ' -f 1", returnStdout: true).readLines()[0]
            if (md5Local != md5Remote) {
                  error 'Target repository already exist, but upstream index.yaml broken or not found'
            }
        }
        helmExtraParams += " --merge index-upstream.yaml"
    }
    helmRepoIndex(helmExtraParams, helmRepoDir)
}

/**
 * Generates version for helm chart based on information from git repository. Tries to search
 * first parent git tag using pattern '[0-9]*-{tagSuffix}', if found that tag will be used
 * in final version, if not found - version will be formed as '{defaultVersion}-{tagSuffix}'. Number
 * of commits since last tag or sha of current commit can be added to version.
 *
 * @param repoDir        string, path to a directory with git repository of helm charts
 * @param devVersion     Boolean, if set to true development version will be calculated e.g 0.1.0-mcp-{sha of current commit}
 * @param increment      Boolean, if set to true patch version will be incremented (e.g 0.1.0 -> 0.1.1)
 * @param defaultVersion string, value of version which will be used in case no tags found. should be semver2 compatible
 * @param tagSuffix      string, suffix which will be used for finding tags in git repository, also if tag not found, it
 *                               it will be added to {defaultVersion} e.g {defaultVersion}-{tagSuffix}
 */

def generateChartVersionFromGit(repoDir, devVersion = true, increment = false, defaultVersion = '0.1.0', tagSuffix = 'mcp') {
    def common = new com.mirantis.mk.Common()
    def git = new com.mirantis.mk.Git()
    String initialVersion = "${defaultVersion}"
    String countRange
    String versionData
    String tagPattern = "[0-9]*"
    if (tagSuffix) {
        tagPattern = "${tagPattern}-${tagSuffix}"
        initialVersion = "${initialVersion}-${tagSuffix}"
    }
    dir(repoDir){
        Map cmd = common.shCmdStatus("git describe --tags --first-parent --abbrev=0 --match ${tagPattern}")
        String lastTag = cmd['stdout'].trim()

        if (cmd['status'] != 0){
            if (cmd['stderr'].contains('fatal: No names found, cannot describe anything')){
                common.warningMsg("No parent git tag found, using initial version ${initialVersion}")
                versionData = initialVersion
                countRange = 'HEAD'
            } else {
                error("Something went wrong, cannot find git information ${cmd['stderr']}")
            }
        } else {
            versionData = lastTag
            countRange = "${lastTag}..HEAD"
        }
        List versionParts = versionData.tokenize('-')

        if (!common.isSemVer(versionData)){
            error "Version ${versionData} is not in semver2 format"
        }
        if (tagSuffix && versionParts.size() == 2 && versionParts[1] != tagSuffix){
            error "Tag suffix ${tagSuffix} was specified but not found in ${versionData}"
        }
        String commitsSinceTag = sh(script: "git rev-list --count ${countRange}", returnStdout: true).trim()
        String commitSha = sh(script: 'git rev-parse --short=7 HEAD', returnStdout: true).trim()

        if (commitsSinceTag == '0'){
            return versionData
        }

        if (devVersion){
            versionParts.add(commitSha)
        } else {
            versionParts.add(commitsSinceTag)
        }
        // Patch version will be incremented e.g. 0.1.0 -> 0.1.1
        if (increment) {
            versionParts[0] = git.incrementVersion(versionParts[0])
        }
        return versionParts.join('-')
    }
}

/**
 * Takes a list of dependencies and a version, and sets a version for each dependency in requirements.yaml. If dependency isn't
 * found in requirements.yaml or requirements.yaml does not exist - does nothing.
 *
 * @param chartPath      string, path to a directory with helm chart
 * @param dependencies   list of hashes with names and versions of dependencies in format:
 *                       [['name': 'chart-name1', 'version': '0.1.0-myversion'], ['name': 'chart-name2', 'version': '0.2.0-myversion']]
 */

def setChartDependenciesVersion(chartPath, List dependencies){
    def common = new com.mirantis.mk.Common()
    if (!dependencies){
        error 'No list of target dependencies is specified'
    }
    def reqsFilePath = "${chartPath}/requirements.yaml"
    def chartYaml = readYaml file: "${chartPath}/Chart.yaml"
    def reqsUpdateNeeded = false
    def reqsMap = [:]
    if (fileExists(reqsFilePath)){
        reqsMap = readYaml file: reqsFilePath
        for (i in dependencies) {
            for (item in reqsMap.get('dependencies', [])){
                if (item['name'] == i['name']){
                    common.infoMsg("Set version ${i['version']} for dependency ${i['name']} in chart ${chartYaml['name']}")
                    item['version'] = i['version']
                    reqsUpdateNeeded = true
                }
            }
        }
    }
    if (reqsUpdateNeeded){
        sh "rm ${reqsFilePath}"
        writeYaml file: reqsFilePath, data: reqsMap
    } else {
        common.warningMsg("requirements.yaml doesn't exist at path ${reqsFilePath} or chart doesn't contain ${dependencies}, nothing to set")
    }
}
