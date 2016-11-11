package com.mirantis.mcp

/**
 * Add LABEL to the end of the Dockerfile
 * User can also add some custom properties
 *
 * @param dockerfilePath is the path to Dockerfile, the default is ./Dockerfile
 * @param customProperties a Array of Strings that should be added to mandatory props
 *        in format ["prop1=value1", "prop2=value2"]
 * */
def setDockerfileLabels(String dockerfilePath = "./Dockerfile", ArrayList customProperties = null) {

    if (!fileExists(dockerfilePath)) {
        throw new RuntimeException("Unable to add LABEL to Dockerfile, ${dockerfilePath} doesn't exists")
    }
    echo "Updating ${dockerfilePath}"

    def namespace = "com.mirantis.image-specs."
    def properties = [
            "gerritProject=${env.GERRIT_PROJECT}",
            "gerritChangeNumber=${env.GERRIT_CHANGE_NUMBER}",
            "gerritPatchsetNumber=${env.GERRIT_PATCHSET_NUMBER}",
            "gerritChangeId=${env.GERRIT_CHANGE_ID}",
            "gerritPatchsetRevision=${env.GERRIT_PATCHSET_REVISION}"
    ]

    if (customProperties != null) {
        properties.addAll(customProperties)
    }
    def common = new com.mirantis.mcp.Common()
    def metadata = common.constructString(properties, namespace, " ")
    sh """
      cat <<EOF>> ${dockerfilePath}
      # Apply additional build metadata
      LABEL ${metadata}
    """
    return metadata
}
