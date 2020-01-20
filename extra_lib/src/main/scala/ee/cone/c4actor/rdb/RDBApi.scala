
package ee.cone.c4actor.rdb

import ee.cone.c4assemble.Assemble

trait ExternalDBOption

trait RDBOptionFactory {
  def fromDB[P<:Product](cl: Class[P]): ExternalDBOption
  def toDB[P<:Product](cl: Class[P], code: List[String]): ExternalDBOption
}

class ToDBOption(val className: String, val code: List[String], val assemble: Assemble) extends ExternalDBOption
class FromDBOption(val className: String) extends ExternalDBOption