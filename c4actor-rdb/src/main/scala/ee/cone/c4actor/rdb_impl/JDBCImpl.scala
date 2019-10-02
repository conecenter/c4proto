package ee.cone.c4actor.rdb_impl

import java.lang.Math.toIntExact
import java.sql.{CallableStatement, Connection, ResultSet}
import java.util.concurrent.CompletableFuture

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor._
import ee.cone.c4proto.c4component

import scala.annotation.tailrec

@c4component("RDBSyncApp") class ExternalDBSyncClient(
  dbFactory: ExternalDBFactory,
  db: CompletableFuture[RConnectionPool] = new CompletableFuture() //dataSource: javax.sql.DataSource
) extends ToInject with Executable with ExternalDBClient {
  def toInject: List[Injectable] = WithJDBCKey.set(getConnectionPool.doWith)
  def run(): Unit = concurrent.blocking{ db.complete(dbFactory.create(
    createConnection => new RConnectionPool {
      def doWith[T](f: RConnection=>T): T = {
        FinallyClose(createConnection()) { sqlConn =>
          val conn = new RConnectionImpl(sqlConn)
          f(conn)
        }
      }
    }
  ))}
  def getConnectionPool: RConnectionPool = concurrent.blocking(db.get)
}



object FinallyFree {
  def apply[A,T](o: A, close: A=>Unit)(f: A=>T): T = try f(o) finally close(o)
}

abstract class RDBBindImpl[R] extends RDBBind[R] with LazyLogging {
  def connection: java.sql.Connection
  def index: Int
  def code(wasCode: String): String
  def execute(stmt: java.sql.CallableStatement): R
  //
  private def inObject(value: Object) = {
    //println(Thread.currentThread.getName,"bind",value)
    new InObjectRDBBind[R](this, value)
  }
  def in(value: Long): RDBBind[R] = inObject(value:java.lang.Long)
  def in(value: Boolean): RDBBind[R] = inObject(value:java.lang.Boolean)
  def in(value: String): RDBBind[R] =
    if(value.length < 1000) inObject(value) else new InTextRDBBind(this, value)
  def call(): R = concurrent.blocking {
    val theCode = code("")
    logger.debug(s"${Thread.currentThread.getName} code $theCode")
    FinallyClose(connection.prepareCall(theCode))(execute)
  }
}

class InObjectRDBBind[R](val prev: RDBBindImpl[R], value: Object) extends ArgRDBBind[R] {
  def execute(stmt: CallableStatement): R = {
    stmt.setObject(index,value)
    prev.execute(stmt)
  }
}

class InTextRDBBind[R](val prev: RDBBindImpl[R], value: String) extends ArgRDBBind[R] {
  def execute(stmt: CallableStatement): R = {
    FinallyClose[java.sql.Clob,R](_.free())(connection.createClob()){ clob =>
      clob.setString(1,value)
      stmt.setClob(index,clob)
      prev.execute(stmt)
    }
  }
}

abstract class ArgRDBBind[R] extends RDBBindImpl[R] {
  def prev: RDBBindImpl[R]
  def connection: Connection = prev.connection
  def index: Int = prev.index + 1
  def code(wasCode: String): String =
    prev.code(if(wasCode.isEmpty) "?" else s"?,$wasCode")
}

class OutUnitRDBBind(
  val connection: java.sql.Connection, name: String
) extends RDBBindImpl[Unit] {
  def index = 0
  def code(wasCode: String): String = s"{call $name ($wasCode)}"
  def execute(stmt: CallableStatement): Unit = stmt.execute()
}

class OutLongRDBBind(
  val connection: java.sql.Connection, name: String
) extends RDBBindImpl[Option[Long]] {
  def index = 1
  def code(wasCode: String): String = s"{? = call $name ($wasCode)}"
  def execute(stmt: CallableStatement): Option[Long] = {
    stmt.registerOutParameter(index,java.sql.Types.BIGINT)
    stmt.execute()
    Option(stmt.getLong(index))
  }
}

class OutTextRDBBind(
  val connection: java.sql.Connection, name: String
) extends RDBBindImpl[String] {
  def index = 1
  def code(wasCode: String): String = s"{? = call $name ($wasCode)}"
  def execute(stmt: CallableStatement): String = {
    stmt.registerOutParameter(index,java.sql.Types.CLOB)
    stmt.execute()
    FinallyClose[Option[java.sql.Clob],String](_.foreach(_.free()))(
      Option(stmt.getClob(index))
    ){ clob =>
      clob.map(c=>c.getSubString(1,toIntExact(c.length()))).getOrElse("")
    }
  }
}

class RConnectionImpl(conn: java.sql.Connection) extends RConnection with LazyLogging {

  private def bindObjects(stmt: java.sql.PreparedStatement, bindList: List[Object]) =
    bindList.zipWithIndex.foreach{ case (v,i) => stmt.setObject(i+1,v) }

  def outUnit(name: String): RDBBind[Unit] = new OutUnitRDBBind(conn, name)
  def outLongOption(name: String): RDBBind[Option[Long]] = new OutLongRDBBind(conn, name)
  def outText(name: String): RDBBind[String] = new OutTextRDBBind(conn, name)

  def execute(code: String): Unit = concurrent.blocking {
    FinallyClose(conn.prepareStatement(code)) { stmt =>
      logger.debug(code)
      stmt.execute()
      //println(stmt.getWarnings)
    }
  }

  def executeQuery(
    code: String, cols: List[String], bindList: List[Object]
  ): List[Map[String,Object]] = concurrent.blocking {
    //println(s"code:: [$code]")
    //conn.prepareCall(code).re
    FinallyClose(conn.prepareStatement(code)) { stmt =>
      bindObjects(stmt, bindList)

      FinallyClose(stmt.executeQuery()) { rs: ResultSet =>
        type Res = List[Map[String, Object]]
        @tailrec def iter(res: Res): Res =
          if(rs.next()) iter(cols.map(cn => cn -> rs.getObject(cn)).toMap :: res)
          else res.reverse
        iter(Nil)
      }
    }
  }
}
