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
