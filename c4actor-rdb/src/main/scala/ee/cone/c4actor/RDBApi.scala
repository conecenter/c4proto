
package ee.cone.c4actor

import ee.cone.c4proto.Protocol

trait ExternalDBOption

trait ExternalDBOptionFactory {
  def dbProtocol(value: Protocol): ExternalDBOption
  def fromDB[P<:Product](cl: Class[P]): ExternalDBOption
  def toDB[P<:Product](cl: Class[P], code: String): ExternalDBOption
  def createOrReplace(key: String, args: String, code: String): ExternalDBOption
}
