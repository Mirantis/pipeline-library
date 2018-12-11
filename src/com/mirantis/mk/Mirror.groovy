package com.mirantis.mk

/**
 *
 * Functions to work with mirror.mirantis.com
 *
 */

/**
 * Returns latest meta for latest available snapshot
 *
 * @param host          mirror host
 * @param version       version of components (nightly|testing|stable|release-x)
 * @param component     component name (salt-formulas)
 * @param distribution  ubuntu distribution to get repo for (xenial by default)
 */
def getLatestSnapshotMeta(host, version, component, distribution='xenial') {
  common = new com.mirantis.mk.Common()

  def repoUrl = "http://${host}/${version}/${component}/${distribution}.target.txt"
  def res
  def snapshotName
  def meta = [:]

  res = common.shCmdStatus("curl ${repoUrl}")
  // Return multiple lines where first one is the latest snapshot
  // ../../.snapshots/nightly-salt-formulas-xenial-2018-12-10-204157
  snapshotName = res['stdout'].split("\n")[0].tokenize('/').last().trim()

  meta['repoUrl'] = "http://${host}/.snapshots/${snapshotName}"
  meta['repoName'] = snapshotName

  return meta
}
