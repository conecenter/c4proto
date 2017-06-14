
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

trait RDBBind[R] {
  def in(value: String): RDBBind[R]
  def in(value: Long): RDBBind[R]
  def in(value: Boolean): RDBBind[R]
  def call(): R
}

trait RConnection {
  def outUnit(name: String): RDBBind[Unit]
  def outLongOption(name: String): RDBBind[Option[Long]]
  def outText(name: String): RDBBind[String]
  def execute(code: String): Unit
  def executeQuery(code: String, cols: List[String], bind: List[Object]): List[Map[String,Object]]
}

