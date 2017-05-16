package ee.cone.c4actor.rdb_impl

import java.lang.Math.toIntExact
import java.sql.{CallableStatement, Connection}
import java.util.concurrent.CompletableFuture

import ee.cone.c4actor._
import ee.cone.c4assemble.Types.World

class ExternalDBSyncClient(
  dbFactory: ExternalDBFactory,
  db: CompletableFuture[RConnectionPool] = new CompletableFuture() //dataSource: javax.sql.DataSource
) extends InitLocal with Executable {
  def initLocal: World ⇒ World = WithJDBCKey.set(db.get.doWith)
  def run(ctx: ExecutionContext): Unit = db.complete(dbFactory.create(
    createConnection ⇒ new RConnectionPool {
      def doWith[T](f: RConnection⇒T): T = {
        FinallyClose(createConnection()) { sqlConn ⇒
          val conn = new RConnectionImpl(sqlConn)
          f(conn)
        }
      }
    }
  ))
}

object FinallyClose {
  def apply[A<:AutoCloseable,T](o: A)(f: A⇒T): T = try f(o) finally o.close()
  def apply[A,T](o: A, close: A⇒Unit)(f: A⇒T): T = try f(o) finally close(o)
}

abstract class RDBBindImpl extends RDBBind {
  def connection: java.sql.Connection
  def index: Int
  def code(wasCode: String): String
  def execute(stmt: java.sql.CallableStatement): List[Object]
  //
  private def inObject(value: Object) = new InObjectRDBBind(this, value)
  def in(value: Long): RDBBind = inObject(value:java.lang.Long)
  def in(value: Boolean): RDBBind = inObject(value:java.lang.Boolean)
  def in(value: String): RDBBind =
    if(value.length < 1000) inObject(value) else new InTextRDBBind(this, value)
  def outLong: RDBBind = new OutLongRDBBind(this)
  def outText: RDBBind = new OutTextRDBBind(this)
  def call(): List[Object] = {
    val theCode = code("")
    println(theCode)
    FinallyClose(connection.prepareCall(theCode))(execute).reverse
  }
}

class InObjectRDBBind(val prev: RDBBindImpl, value: Object) extends ArgRDBBind {
  def execute(stmt: CallableStatement): List[Object] = {
    stmt.setObject(index,value)
    prev.execute(stmt)
  }
}

class InTextRDBBind(val prev: RDBBindImpl, value: String) extends ArgRDBBind {
  def execute(stmt: CallableStatement): List[Object] = {
    FinallyClose[java.sql.Clob,List[Object]](connection.createClob(), _.free()){ clob ⇒
      clob.setString(1,value)
      stmt.setClob(index,clob)
      prev.execute(stmt)
    }
  }
}

class OutLongRDBBind(val prev: RDBBindImpl) extends ArgRDBBind {
  def execute(stmt: CallableStatement): List[Object] = {
    stmt.registerOutParameter(index,java.sql.Types.BIGINT)
    val res = prev.execute(stmt)
    stmt.getObject(index) :: res
  }
}

class OutTextRDBBind(val prev: RDBBindImpl) extends ArgRDBBind {
  def execute(stmt: CallableStatement): List[Object] = {
    stmt.registerOutParameter(index,java.sql.Types.CLOB)
    val res = prev.execute(stmt)
    FinallyClose[java.sql.Clob,List[Object]](stmt.getClob(index), _.free()){ clob ⇒
      clob.getSubString(1,toIntExact(clob.length())) :: res
    }
  }
}

abstract class ArgRDBBind extends RDBBindImpl {
  def prev: RDBBindImpl
  def connection: Connection = prev.connection
  def index: Int = prev.index + 1
  def code(wasCode: String): String =
    prev.code(if(wasCode.isEmpty) "?" else s"?,$wasCode")
}

class MainRDBBind(
  val connection: java.sql.Connection, name: String
) extends RDBBindImpl {
  def index = 0
  def code(wasCode: String): String =
    s"begin ${if(wasCode.isEmpty) name else s"$name($wasCode)"}; end;"
  def execute(stmt: CallableStatement): List[Object] = {
    stmt.execute()
    Nil
  }
}

//def prepare(stmt: java.sql.CallableStatement, )

class RConnectionImpl(conn: java.sql.Connection) extends RConnection {

  private def bindObjects(stmt: java.sql.PreparedStatement, bind: List[Object]) =
    bind.zipWithIndex.foreach{ case (v,i) ⇒ stmt.setObject(i+1,v) }

  def procedure(name: String): RDBBind = new MainRDBBind(conn, name)

  def execute(code: String): Unit =
    FinallyClose(conn.prepareStatement(code)){ stmt ⇒
      println(code)
      stmt.execute()
      //println(stmt.getWarnings)
    }

  def executeQuery(code: String, cols: List[String], bind: List[Object]): List[Map[String,Object]] = {
    //println(s"code:: [$code]")
    //conn.prepareCall(code).re
    FinallyClose(conn.prepareStatement(code)) { stmt ⇒
      bindObjects(stmt, bind)
      FinallyClose(stmt.executeQuery()) { rs ⇒
        var res: List[Map[String, Object]] = Nil
        while(rs.next()) res = cols.map(cn ⇒ cn → rs.getObject(cn)).toMap :: res
        res.reverse
      }
    }
  }
}
