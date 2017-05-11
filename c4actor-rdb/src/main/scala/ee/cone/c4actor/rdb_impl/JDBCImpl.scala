package ee.cone.c4actor.rdb_impl

import java.lang.Math.toIntExact
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

class RDBBindImpl(
  connection: java.sql.Connection,
  prevOpt: Option[RDBBindImpl], index: Int,
  val code: String⇒String,
  val execute: java.sql.CallableStatement⇒List[Object]
) extends RDBBind {
  private def next(execNext: java.sql.CallableStatement⇒List[Object]): RDBBind =
    new RDBBindImpl(
      connection,
      Option(this), index+1,
      c ⇒ prev.code(if(c.isEmpty) "?" else s"?,$c"),
      execNext
    )
  private def prev = prevOpt.get
  private def inObject(value: Object) = next{ stmt ⇒
    stmt.setObject(index,value)
    prev.execute(stmt)
  }
  def in(value: Long): RDBBind = inObject(value:java.lang.Long)
  def in(value: Boolean): RDBBind = inObject(value:java.lang.Boolean)
  def in(value: String): RDBBind = if(value.length < 1000) inObject(value)
  else next{ stmt ⇒
    FinallyClose[java.sql.Clob,List[Object]](connection.createClob(), _.free()){ clob ⇒
      clob.setString(1,value)
      stmt.setClob(index,clob)
      prev.execute(stmt)
    }
  }

  def outLong: RDBBind = next{ stmt ⇒
    stmt.registerOutParameter(index,java.sql.Types.BIGINT)
    val res = prev.execute(stmt)
    stmt.getObject(index) :: res
  }
  def outText: RDBBind = next{ stmt ⇒
    stmt.registerOutParameter(index,java.sql.Types.CLOB)
    val res = prev.execute(stmt)
    FinallyClose[java.sql.Clob,List[Object]](stmt.getClob(index), _.free()){ clob ⇒
      clob.getSubString(1,toIntExact(clob.length())) :: res
    }
  }
  def call(): List[Object] =
    FinallyClose(connection.prepareCall(code("")))(execute).reverse
}

//def prepare(stmt: java.sql.CallableStatement, )

class RConnectionImpl(conn: java.sql.Connection) extends RConnection {

  private def bindObjects(stmt: java.sql.PreparedStatement, bind: List[Object]) =
    bind.zipWithIndex.foreach{ case (v,i) ⇒ stmt.setObject(i+1,v) }

  def procedure(name: String): RDBBind = new RDBBindImpl(conn, None, 1, { c ⇒
    s"begin ${if(c.isEmpty) name else s"$name($c)"}; end;"
  }, { stmt ⇒
    stmt.execute()
    Nil
  })

  def execute(code: String): Unit =
    FinallyClose(conn.prepareStatement(code)){ stmt ⇒
      //println(code,bind)
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
