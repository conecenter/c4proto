
package ee.cone.c4actor

import ee.cone.c4assemble.Assembled

trait ExternalDBOption

@c4component @listed abstract class ExternalDBOptionProvider {
  def externalDBOptions: List[ExternalDBOption]
}


trait RDBOptionFactory {
  def dbProtocol(value: Object): ExternalDBOption
  def fromDB[P<:Product](cl: Class[P]): ExternalDBOption
  def toDB[P<:Product](cl: Class[P], code: List[String]): ExternalDBOption
}

class ProtocolDBOption(val protocol: Object) extends ExternalDBOption
class ToDBOption(val className: String, val code: List[String], val assemble: Assembled) extends ExternalDBOption
class FromDBOption(val className: String) extends ExternalDBOption