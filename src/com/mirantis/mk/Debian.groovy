package com.mirantis.mk

/**
 *
 * Debian functions
 *
 */

def cleanup(image="debian:sid") {
    def common = new com.mirantis.mk.Common()
    def img = docker.image(image)

    workspace = common.getWorkspace()
    sh("docker run -e DEBIAN_FRONTEND=noninteractive -v ${workspace}:${workspace} -w ${workspace} --rm=true --privileged ${image} /bin/bash -c 'rm -rf build-area || true'")
}

/*
 * Build binary Debian package from existing dsc
 *
 * @param file  dsc file to build
 * @param image Image name to use for build (default debian:sid)
 */
def buildBinary(file, image="debian:sid", extraRepoUrl=null, extraRepoKeyUrl=null) {
    def common = new com.mirantis.mk.Common()
    def jenkinsUID = common.getJenkinsUid()
    def jenkinsGID = common.getJenkinsGid()
    def pkg = file.split('/')[-1].split('_')[0]
    def dockerLib = new com.mirantis.mk.Docker()
    def imageArray = image.split(":")
    def os = imageArray[0]
    def dist = imageArray[1]
    def img = dockerLib.getImage("mirantis/debian-build-${os}-${dist}:latest", image)
    def workspace = common.getWorkspace()
    def debug = env.getEnvironment().containsKey("DEBUG") && env["DEBUG"].toBoolean() ? "true" : ""

    img.inside("-u root:root" ) {
        sh("""bash -x -c 'cd ${workspace} && (which eatmydata || (apt-get update && apt-get install -y eatmydata)) &&
            export DEBUG="${debug}" &&
            export LD_LIBRARY_PATH=\${LD_LIBRARY_PATH:+"\$LD_LIBRARY_PATH:"}/usr/lib/libeatmydata &&
            export LD_PRELOAD=\${LD_PRELOAD:+"\$LD_PRELOAD "}libeatmydata.so &&
            export DEB_BUILD_OPTIONS=nocheck &&
            [[ -z "${extraRepoUrl}" && "${extraRepoUrl}" != "null" ]] || echo "${extraRepoUrl}" | tr ";" "\\\\n" >/etc/apt/sources.list.d/extra.list &&
            [[ -z "${extraRepoKeyUrl}" && "${extraRepoKeyUrl}" != "null" ]] || (
                which curl || (apt-get update && apt-get install -y curl) &&
                EXTRAKEY=`echo "${extraRepoKeyUrl}" | tr ";" " "` &&
                for RepoKey in \${EXTRAKEY}; do curl --insecure -ss -f "\${RepoKey}" | apt-key add - ; done
            ) &&
            apt-get update && apt-get install -y build-essential devscripts equivs sudo &&
            groupadd -g ${jenkinsGID} jenkins &&
            useradd -s /bin/bash --uid ${jenkinsUID} --gid ${jenkinsGID} -m jenkins &&
            chown -R ${jenkinsUID}:${jenkinsGID} /home/jenkins &&
            [ ! -f pre_build_script.sh ] || bash ./pre_build_script.sh &&
            sudo -H -E -u jenkins dpkg-source -x ${file} build-area/${pkg} && cd build-area/${pkg} &&
            mk-build-deps -t "apt-get -o Debug::pkgProblemResolver=yes -y" -i debian/control &&
            sudo -H -E -u jenkins debuild --preserve-envvar DEBUG --no-lintian -uc -us -b'""")
    }

}

/*
 * Build source package from directory
 *
 * @param dir   Tree to build
 * @param image Image name to use for build (default debian:sid)
 * @param snapshot Generate snapshot version (default false)
 */
def buildSource(dir, image="debian:sid", snapshot=false, gitEmail='jenkins@dummy.org', gitName='Jenkins', revisionPostfix="", remote="origin/") {
    def isGit
    try {
        sh("test -d ${dir}/.git")
        isGit = true
    } catch (Exception e) {
        isGit = false
    }

    if (isGit == true) {
        buildSourceGbp(dir, image, snapshot, gitEmail, gitName, revisionPostfix, remote)
    } else {
        buildSourceUscan(dir, image)
    }
}

/*
 * Build source package, fetching upstream code using uscan
 *
 * @param dir   Tree to build
 * @param image Image name to use for build (default debian:sid)
 */
def buildSourceUscan(dir, image="debian:sid") {
    def common = new com.mirantis.mk.Common()
    def dockerLib = new com.mirantis.mk.Docker()
    def imageArray = image.split(":")
    def os = imageArray[0]
    def dist = imageArray[1]
    def img = dockerLib.getImage("mirantis/debian-build-${os}-${dist}:latest", image)
    def workspace = common.getWorkspace()

    img.inside("-u root:root" ) {
        sh("""cd ${workspace} && apt-get update && apt-get install -y build-essential devscripts &&
        cd ${dir} && uscan --download-current-version &&
        dpkg-buildpackage -S -nc -uc -us""")
    }
}

/*
 * Build source package using git-buildpackage
 *
 * @param dir   Tree to build
 * @param image Image name to use for build (default debian:sid)
 * @param snapshot Generate snapshot version (default false)
 */
def buildSourceGbp(dir, image="debian:sid", snapshot=false, gitName='Jenkins', gitEmail='jenkins@dummy.org', revisionPostfix="", remote="origin/") {
    def common = new com.mirantis.mk.Common()
    def jenkinsUID = common.getJenkinsUid()
    def jenkinsGID = common.getJenkinsGid()

    if (! revisionPostfix) {
        revisionPostfix = ""
    }

    def workspace = common.getWorkspace()
    def dockerLib = new com.mirantis.mk.Docker()
    def imageArray = image.split(":")
    def os = imageArray[0]
    def dist = imageArray[1]
    def img = dockerLib.getImage("mirantis/debian-build-${os}-${dist}:latest", image)

    img.inside("-u root:root") {

        withEnv(["DEBIAN_FRONTEND=noninteractive", "DEBFULLNAME='${gitName}'", "DEBEMAIL='${gitEmail}'"]) {
            sh("""bash -x -c 'cd ${workspace} && (which eatmydata || (apt-get update && apt-get install -y eatmydata)) &&
            export LD_LIBRARY_PATH=\${LD_LIBRARY_PATH:+"\$LD_LIBRARY_PATH:"}/usr/lib/libeatmydata &&
            export LD_PRELOAD=\${LD_PRELOAD:+"\$LD_PRELOAD "}libeatmydata.so &&
            apt-get update && apt-get install -y build-essential git-buildpackage dpkg-dev sudo &&
            groupadd -g ${jenkinsGID} jenkins &&
            useradd -s /bin/bash --uid ${jenkinsUID} --gid ${jenkinsGID} -m jenkins &&
            chown -R ${jenkinsUID}:${jenkinsGID} /home/jenkins &&
            cd ${dir} &&
            sudo -H -E -u jenkins git config --global user.name "${gitName}" &&
            sudo -H -E -u jenkins git config --global user.email "${gitEmail}" &&
            [[ "${snapshot}" == "false" ]] || (
                VERSION=`dpkg-parsechangelog --count 1 --show-field Version` &&
                UPSTREAM_VERSION=`echo \$VERSION | cut -d "-" -f 1` &&
                REVISION=`echo \$VERSION | cut -d "-" -f 2` &&
                TIMESTAMP=`date +%Y%m%d%H%M` &&
                if [[ "`cat debian/source/format`" = *quilt* ]]; then
                    UPSTREAM_BRANCH=`(grep upstream-branch debian/gbp.conf || echo master) | cut -d = -f 2 | tr -d " "` &&
                    UPSTREAM_REV=`git rev-parse --short ${remote}\$UPSTREAM_BRANCH` &&
                    NEW_UPSTREAM_VERSION="\$UPSTREAM_VERSION+\$TIMESTAMP.\$UPSTREAM_REV" &&
                    NEW_UPSTREAM_VERSION_TAG=`echo \$NEW_UPSTREAM_VERSION | sed 's/.*://'` &&
                    NEW_VERSION=\$NEW_UPSTREAM_VERSION-\$REVISION$revisionPostfix &&
                    echo "Generating new upstream version \$NEW_UPSTREAM_VERSION_TAG" &&
                    sudo -H -E -u jenkins git tag \$NEW_UPSTREAM_VERSION_TAG ${remote}\$UPSTREAM_BRANCH &&
                    sudo -H -E -u jenkins git merge -X theirs \$NEW_UPSTREAM_VERSION_TAG
                else
                    NEW_VERSION=\$VERSION+\$TIMESTAMP.`git rev-parse --short HEAD`$revisionPostfix
                fi &&
                sudo -H -E -u jenkins gbp dch \
                    --auto \
                    --git-author \
                    --id-length=7 \
                    --git-log='--reverse' \
                    --ignore-branch \
                    --new-version=\$NEW_VERSION \
                    --distribution `lsb_release -c -s` \
                    --force-distribution &&
                sudo -H -E -u jenkins git add -u debian/changelog &&
                sudo -H -E -u jenkins git commit -m "New snapshot version \$NEW_VERSION"
            ) &&
            sudo -H -E -u jenkins gbp buildpackage -nc --git-force-create --git-notify=false --git-ignore-branch --git-ignore-new --git-verbose --git-export-dir=../build-area -sa -S -uc -us '""")
        }
    }
}

/*
 * Run lintian checks
 *
 * @param changes   Changes file to test against
 * @param profile   Lintian profile to use (default debian)
 * @param image     Image name to use for build (default debian:sid)
 */
def runLintian(changes, profile="debian", image="debian:sid") {
    def common = new com.mirantis.mk.Common()
    def workspace = common.getWorkspace()
    def dockerLib = new com.mirantis.mk.Docker()
    def imageArray = image.split(":")
    def os = imageArray[0]
    def dist = imageArray[1]
    def img = dockerLib.getImage("mirantis/debian-build-${os}-${dist}:latest", image)
    img.inside("-u root:root") {
        sh("""cd ${workspace} && apt-get update && apt-get install -y lintian &&
            lintian -Ii -E --pedantic --profile=${profile} ${changes}""")
    }
}

/*
 * Import gpg key
 *
 * @param privateKeyCredId   Public key jenkins credential id
 */
def importGpgKey(privateKeyCredId)
{
    def common = new com.mirantis.mk.Common()
    def workspace = common.getWorkspace()
    def privKey = common.getCredentials(privateKeyCredId, "key")
    def private_key = privKey.privateKeySource.privateKey
    def gpg_key_id = common.getCredentials(privateKeyCredId, "key").username
    def retval = sh(script: "export GNUPGHOME=${workspace}/.gnupg; gpg --list-secret-keys | grep ${gpg_key_id}", returnStatus: true)
    if (retval) {
        writeFile file:"${workspace}/private.key", text: private_key
        sh(script: "gpg --no-tty --allow-secret-key-import --homedir ${workspace}/.gnupg --import ./private.key")
    }
}

/*
 * upload source package to launchpad
 *
 * @param ppaRepo   ppa repository on launchpad
 * @param dirPath repository containing the source packages
 */

def uploadPpa(ppaRepo, dirPath, privateKeyCredId) {

   def common = new com.mirantis.mk.Common()
   def workspace = common.getWorkspace()
   def gpg_key_id = common.getCredentials(privateKeyCredId, "key").username

   dir(dirPath)
   {
       def images = findFiles(glob: "*.orig*.tar.gz")
       for (int i = 0; i < images.size(); ++i) {
          def name = images[i].getName()
          def orig_sha1 = common.cutOrDie("sha1sum ${name}", 0)
          def orig_sha256 = common.cutOrDie("sha256sum ${name}", 0)
          def orig_md5 = common.cutOrDie("md5sum ${name}", 0)
          def orig_size = common.cutOrDie("ls -l ${name}", 4)

          def retval = sh(script: "wget --quiet -O orig-tmp https://launchpad.net/ubuntu/+archive/primary/+files/${name}", returnStatus: true)
          if (retval == 0) {
             sh("mv orig-tmp ${name}")
             def new_sha1 = common.cutOrDie("sha1sum ${name}", 0)
             def new_sha256 = common.cutOrDie("sha256sum ${name}", 0)
             def new_md5 = common.cutOrDie("md5sum ${name}", 0)
             def new_size = common.cutOrDie("ls -l ${name}", 4)

             sh("sed -i -e s,$orig_sha1,$new_sha1,g -e s,$orig_sha256,$new_sha256,g -e s,$orig_size,$new_size,g -e s,$orig_md5,$new_md5,g *.dsc")
             sh("sed -i -e s,$orig_sha1,$new_sha1,g -e s,$orig_sha256,$new_sha256,g -e s,$orig_size,$new_size,g -e s,$orig_md5,$new_md5,g *_source.changes")
          }
        }
        sh("export GNUPGHOME=${workspace}/.gnupg; debsign --re-sign -k ${gpg_key_id} *_source.changes")
        sh("export GNUPGHOME=${workspace}/.gnupg; dput -f \"ppa:${ppaRepo}\" *_source.changes")
    }
}

/**
* Upgrade packages on given target.
*
* @param env    Salt Connection object or env  Salt command map
* @param target Salt target to upgrade packages on.
*/
def osUpgrade(env, target){
  def common = new com.mirantis.mk.Common()
  def salt = new com.mirantis.mk.Salt()

  common.infoMsg("Running upgrade on ${target}")

  salt.runSaltProcessStep(env, target, 'pkg.refresh_db', [], null, true)
  def cmd = 'export DEBIAN_FRONTEND=noninteractive; apt-get -y -q --allow-downgrades -o Dpkg::Options::=\"--force-confdef\" -o Dpkg::Options::=\"--force-confold\" upgrade;'
  salt.runSaltProcessStep(env, target, 'cmd.run', [cmd])
}

/**
* Running dist-upgrade on given target.
*
* @param env    Salt Connection object or env  Salt command map
* @param target Salt target to upgrade packages on.
*/
def osDistUpgrade(env, target){
  def salt = new com.mirantis.mk.Salt()
  def common = new com.mirantis.mk.Common()

  common.infoMsg("Running dist-upgrade on ${target}")
  salt.runSaltProcessStep(env, target, 'pkg.refresh_db', [], null, true)
  def cmd = 'export DEBIAN_FRONTEND=noninteractive; apt-get -y -q --allow-downgrades -o Dpkg::Options::=\"--force-confdef\" -o Dpkg::Options::=\"--force-confold\" dist-upgrade;'
  salt.runSaltProcessStep(env, target, 'cmd.run', [cmd])
}

/**
* Reboot specified target, and wait when minion is UP.
*
* @param env       Salt Connection object or env  Salt command map
* @param target    Salt target to upgrade packages on.
* @param timeout   Sleep timeout when doing retries.
* @param attempts  Number of attemps to wait for.
*/
def osReboot(env, target, timeout=30, attempts=10){
  def salt = new com.mirantis.mk.Salt()
  def common = new com.mirantis.mk.Common()

  salt.runSaltProcessStep(env, target, 'cmd.run', ["touch /tmp/rebooting"])
  salt.runSaltProcessStep(env, target, 'system.reboot', [], null, true, 5)

  common.retry(timeout, attempts){
    if (salt.runSaltProcessStep(env, target, 'file.file_exists', ['/tmp/rebooting'], null, true, 5)['return'][0].values()[0].toBoolean()){
      error("The system is still rebooting...")
    }
  }
}

/**
* Upgrade OS on given node, wait when minion become reachable.
*
* @param env             Salt Connection object or env  Salt command map
* @param target          Salt target to upgrade packages on.
* @param mode            'upgrade' or 'dist-upgrade'
* @param postponeReboot  Boolean flag to specify if reboot have to be postponed.
* @param timeout   Sleep timeout when doing retries.
* @param attempts  Number of attemps to wait for.
*/
def osUpgradeNode(env, target, mode, postponeReboot=false, timeout=30, attempts=10){
    def common = new com.mirantis.mk.Common()
    def salt = new com.mirantis.mk.Salt()

    def rebootRequired = false
    if (mode == 'dist-upgrade'){
      osDistUpgrade(env, target)
    } else if (mode == 'upgrade'){
      osUpgrade(env, target)
    }
    rebootRequired = salt.runSaltProcessStep(env, target, 'file.file_exists', ['/var/run/reboot-required'], null, true, 5)['return'][0].values()[0].toBoolean()
    if (rebootRequired) {
      if (!postponeReboot){
        common.infoMsg("Reboot is required after upgrade on ${target} Rebooting...")
        osReboot(env, target, timeout, attempts)
      } else {
        common.infoMsg("Postponing reboot on node ${target}")
      }
    }
}
