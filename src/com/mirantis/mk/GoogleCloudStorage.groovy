package com.mirantis.mk

/**
    Work with Google Cloud Storage
**/

/** Exists or not gcloud binary file
 *
 * @param gcloudBinDir Path to check
*/
def checkGcloudBinary(String gcloudBinDir) {
    def status = sh(script: "${gcloudBinDir}/google-cloud-sdk/bin/gcloud info > /dev/null", returnStatus: true)
    return "${status}" == "0"
}

/** Download gcloud archive with binarties
 *
 * @param gcloudBinDir Path to save binaries
 * @param url Specific URL to use to download
*/
def downloadGcloudUtil(String gcloudBinDir, String url="") {
    if (!url) {
        url="https://dl.google.com/dl/cloudsdk/channels/rapid/downloads/google-cloud-sdk-253.0.0-linux-x86_64.tar.gz"
    }
    dir(gcloudBinDir) {
        def archiveName='google-cloud-sdk'
        sh """
            wget -O ${archiveName}.tar.gz ${url}
            tar -xf ${archiveName}.tar.gz
        """
    }
}

/** Auth gcloud util with provided creds
 *
 * @param gcloudBinDir Path to save binaries
 * @param creds Creds name to use, saved as secret file
 * @param projectName Project name to use
*/
def authGcloud(String gcloudBinDir, String creds, String projectName) {
    ws {
        withCredentials([
            file(credentialsId: creds,
                variable: 'key_file')
        ]) {
            sh "${gcloudBinDir}/google-cloud-sdk/bin/gcloud auth activate-service-account --key-file $key_file --project ${projectName}"
        }
    }
}

/** Revoke gcloud auth account
 *
 * @param gcloudBinDir Path to save binaries
*/
def revokeGcloud(String gcloudBinDir) {
    sh "${gcloudBinDir}/google-cloud-sdk/bin/gcloud auth revoke"
}

/** Copy file to Google Storage Bucket
 *
 * @param gcloudBinDir Path to save binaries
 * @param src Source file
 * @param dst Destination path in storage
 * @param entireTree Copy entire directory tree
*/
def cpFile(String gcloudBinDir, String src, String dst, Boolean entireTree=false) {
    def fileURL = ''
    if (entireTree) {
        sh "${gcloudBinDir}/google-cloud-sdk/bin/gsutil cp -r ${src} ${dst}"
        return dst
    } else {
        def fileBaseName = sh(script:"basename ${src}", returnStdout: true).trim()
        sh "${gcloudBinDir}/google-cloud-sdk/bin/gsutil cp ${src} ${dst}/${fileBaseName}"
        return "${dst}/${fileBaseName}"
    }
}

/** Set ACL on files in bucket
 *
 * @param gcloudBinDir Path to save binaries
 * @param path Path to file in bucket
 * @param acls ACLs to be set for file
*/
def setAcl(String gcloudBinDir, String path, ArrayList acls) {
    for(String acl in acls) {
        sh "${gcloudBinDir}/google-cloud-sdk/bin/gsutil acl ch -u ${acl} ${path}"
    }
}

/** Upload files to Google Cloud Storage Bucket
 *
 * @param config LinkedHashMap with next parameters:
 *   @param gcloudBinDir Path to save binaries
 *   @param creds Creds name to use, saved as secret file
 *   @param projectName Project name to use
 *   @param sources List of file to upload
 *   @param dest Destination path in Google Storage, in format: gs://<path>
 *   @param acls ACLs for uploaded files
 *   @param entireTree Copy entire directory to bucket
 *
 * Returns URLs list of uploaded files
*/
def uploadArtifactToGoogleStorageBucket(Map config) {
    def gcloudDir = config.get('gcloudDir', '/tmp/gcloud')
    def creds = config.get('creds')
    def project = config.get('project')
    def acls = config.get('acls', ['AllUsers:R'])
    def sources = config.get('sources')
    def dest = config.get('dest')
    def entireTree = config.get('entireTree', false)
    def fileURLs = []
    if (!checkGcloudBinary(gcloudDir)) {
        downloadGcloudUtil(gcloudDir)
    }
    try {
        authGcloud(gcloudDir, creds, project)
        for(String src in sources) {
            def fileURL = cpFile(gcloudDir, src, dest, entireTree)
            setAcl(gcloudDir, fileURL, acls)
            fileURLs << fileURL
        }
    } finally {
        revokeGcloud(gcloudDir)
    }
    return fileURLs
}