package com.mirantis.tcp_qa

/**
 * Activate virtual environment and update requirements if needed
 */
def prepareEnv() {
    sh '''
        if [ ! -r "${VENV_PATH}/bin/activate" ]; then
            echo 'Python virtual environment not found! Set correct VENV_PATH!'
            exit 1
        fi
    '''

    sh '''
        . ${VENV_PATH}/bin/activate
        pip install --upgrade --upgrade-strategy=only-if-needed -r tcp_tests/requirements.txt
    '''
}


/**
 * Download an image for tests
 */
def prepareImage() {
    sh '''
      if [ -n "${IMAGE_LINK}" ]; then
          IMAGE_FILE="$(basename "${IMAGE_LINK}")"
          IMAGES_DIR="$(dirname "${IMAGE_PATH}")"
          mkdir -p "${IMAGES_DIR}"
          cd "${IMAGES_DIR}" && wget -N "${IMAGE_LINK}"
          ln -sf "${IMAGES_DIR}/${IMAGE_FILE}" "${IMAGE_PATH}" || test -f "${IMAGE_PATH}"
      fi

      if [ ! -r "${IMAGE_PATH}" ]; then
          echo "Image not found: ${IMAGE_PATH}"
          exit 1
      fi

    '''
}

/**
 * Check that bridge traffic is not filtered on the host
 */
def checkBridgeNetfilterDisabled () {
    def res = sh(script: 'file /proc/sys/net/bridge/bridge-nf-call-iptables 2>/dev/null || echo "Not found"',
                 returnStdout: true).trim()
    if ( ! res.equals('Not found') ) {
        res = sh(script: 'cat /proc/sys/net/bridge/bridge-nf-call-iptables',
                     returnStdout: true).trim()
        if ( ! res.equals('0') ) {
             error("Kernel parameter 'net.bridge.bridge-nf-call-iptables' should be disabled to run the tests!")
        }
    } else {
        echo("WARNING: it isn't possible to check net.bridge.bridge-nf-call-iptables value. Please make sure it is set to '0'!")
    }
}


/**
 * Destroy running environment
 */
def destroyEnv() {
    if ( !(env.KEEP_BEFORE.equals('yes') || env.KEEP_BEFORE.equals('true')) ) {
        sh '''
            . ${VENV_PATH}/bin/activate
            dos.py destroy ${ENV_NAME} || true
        '''
    }
}

/**
 * Erase running environment
 */
def eraseEnv() {
        sh '''
            . ${VENV_PATH}/bin/activate
            dos.py erase ${ENV_NAME} || true
        '''
}

