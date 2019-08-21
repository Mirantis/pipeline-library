package com.mirantis.mk

/**
 *
 *  Functions to work with Helm
 *
 */

/**
 * Build index file for helm chart
 */

def helmIndex(){
    sh("helm index")
}