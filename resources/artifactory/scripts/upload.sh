#!/bin/bash

#
#   :mod: `upload.sh` -- Artifactory upload helper
#   =========================================================================
#
#   .. module:: upload.sh
#       :platform: Unix
#       :synopsys: this script uploads files to JFrog Artifactory
#                  instance
#
#   .. envvar::
#       :var ARTIFACTORY_USERNAME:  Access username
#       :var ARTIFACTORY_PASSWORD:  Access password
#       :var ARTIFACTORY_URL:       Artifactory api url
#       :var ARTIFACTORY_TARGET:    Path at the repository for uploading
#                                   including repository name
#       :var ARTIFACTORY_PROPS:     Optional artifact properties
#       :var FILE_TO_UPLOAD:        Path to local file to upload
#
#   .. requirements::
#       * ``awk``
#       * ``cat``
#       * ``curl``
#       * ``echo``
#       * ``grep``
#       * ``mktemp``
#       * ``sed``
#       * ``sha1sum``
#       * ``touch``
#

cleanup_tmpfiles() {
    trap EXIT
    [ -d "${TMP_DIR}" ] && rm -rf "${TMP_DIR}"
    exit 0
}

TMP_DIR=$(mktemp -d)
trap cleanup_tmpfiles EXIT

STDOUT_FILE=${TMP_DIR}/stdout
STDERR_FILE=${TMP_DIR}/stderr
HEADERS_FILE=${TMP_DIR}/headers
touch "${STDOUT_FILE}" "${STDERR_FILE}" "${HEADERS_FILE}"

exec 2>${STDERR_FILE}

UPLOAD_PREFIX=${FILE_TO_UPLOAD%/*}
FILE_NAME=${FILE_TO_UPLOAD##*/}

EFFECTIVE_URL="${ARTIFACTORY_URL}/${ARTIFACTORY_TARGET}/${UPLOAD_PREFIX}/${FILE_NAME};${ARTIFACTORY_PROPS}"

FILE_SHA1_CHECKSUM=$(sha1sum "${FILE_TO_UPLOAD}" | awk '{print $1}')

curl \
    --silent \
    --show-error \
    --location \
    --globoff \
    --dump-header "${HEADERS_FILE}" \
    --output "${STDOUT_FILE}" \
    --user "${ARTIFACTORY_USERNAME}:${ARTIFACTORY_PASSWORD}" \
    --request PUT \
    --header "X-Checksum-Sha1:${FILE_SHA1_CHECKSUM}" \
    --upload-file "${FILE_TO_UPLOAD}" \
    --url "${EFFECTIVE_URL}"

EXIT_CODE=$?
HTTP_RESPONSE_CODE=$(cat "${HEADERS_FILE}" | grep '^HTTP' | awk '{print $2}')

if [ "${HTTP_RESPONSE_CODE:0:1}" != "2" ]; then
    >&2 echo "Failed at ${EFFECTIVE_URL}"
fi

for outfile in "${STDOUT_FILE}" "${STDERR_FILE}"; do
    sed -z -i \
        -e 's|\n|\\n|g' \
        -e 's|"|\\"|g' \
        -e 's|\r||g' \
        "${outfile}"
done

cat << EOF
{
    "stdout": "$(cat ${STDOUT_FILE})",
    "stderr": "$(cat ${STDERR_FILE})",
    "exit_code": ${EXIT_CODE},
    "response_code": ${HTTP_RESPONSE_CODE:-null},
}
EOF
