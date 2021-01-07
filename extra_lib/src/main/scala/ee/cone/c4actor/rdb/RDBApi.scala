
package ee.cone.c4actor.rdb

import ee.cone.c4assemble.Assemble

trait ExternalActive

trait ExternalDBOption
trait CommonDBOption extends ExternalDBOption{
  def cl:Class[_]
  lazy val className: String = cl.getName
}

trait RDBOptionFactory {
  def fromDB[P<:Product](cl: Class[P]): ExternalDBOption
  def toDB[P<:Product](cl: Class[P], code: List[String]): ExternalDBOption
}

class ToDBOption(val cl:Class[_], val code: List[String], val assemble: Assemble) extends CommonDBOption
class FromDBOption(val cl:Class[_]) extends CommonDBOption