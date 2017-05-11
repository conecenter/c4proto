
package ee.cone.c4actor

import ee.cone.c4assemble.Types.World
import ee.cone.c4assemble.WorldKey

trait ExternalDBFactory {
  def create(wrap: (()⇒java.sql.Connection)⇒RConnectionPool): RConnectionPool
}

case object WithJDBCKey extends WorldKey[(RConnection⇒World)⇒World](_⇒throw new Exception)

trait RConnectionPool {
  def doWith[T](f: RConnection⇒T): T
}

trait RDBBind {
  def in(value: String): RDBBind
  def in(value: Long): RDBBind
  def in(value: Boolean): RDBBind
  def outLong: RDBBind
  def outText: RDBBind
  def call(): List[Object]
}

trait RConnection {
  def procedure(name: String): RDBBind
  def execute(code: String): Unit
  def executeQuery(code: String, cols: List[String], bind: List[Object]): List[Map[String,Object]]
}

object HexStr { def apply(i: Long): String = "'0x%04x'".format(i) }