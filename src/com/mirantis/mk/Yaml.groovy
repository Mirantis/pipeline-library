package com.mirantis.mk

@Grab(group='org.yaml', module='snakeyaml', version='1.17')
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.DumperOptions

/**
 * Helper class for YAML operations
 *
 */


/**
 * Convert YAML document to Map object
 * @param data YAML string
 */
@NonCPS
def loadYAML(String data) {
  def yaml = new Yaml()
  return yaml.load(data)
}


/**
 * Convert Map object to YAML string
 * @param map Map object
 */
@NonCPS
def dumpYAML(Map map) {
  def dumperOptions = new DumperOptions()
  dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK)
  def yaml = new Yaml(dumperOptions)
  return yaml.dump(map)
}
