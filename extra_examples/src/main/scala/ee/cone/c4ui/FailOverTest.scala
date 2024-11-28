package ee.cone.c4ui

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor._
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.c4assemble
import ee.cone.c4assemble.Types._
import ee.cone.c4di._
import scala.annotation.tailrec

/*
@c4("TestTodoApp") final class FailOverTest(
  config: Config,
  actorName: ActorName,
  execution: Execution,
) extends Executable with LazyLogging {
  def run(): Unit = concurrent.blocking {
    val remove = execution.onShutdown("test-fail-over",()=>{
      iter(true)
    })
    iter(false)
  }
  @tailrec def iter(isFinal: Boolean): Unit = {
    logger.debug(s"normal ${actorName.value} ${config.get("C4ELECTOR_SERVERS")}")
    Thread.sleep(100)
    iter(isFinal)
  }
}
*/

/*
@c4("TestTodoApp") final class FailOverTestEnable
  extends EnableSimpleScaling(classOf[FailOverTestTx])

@c4("TestTodoApp") final class FailOverTest extends Executable with LazyLogging {
  def run(): Unit = iter()
  @tailrec def iter(): Unit = {
    logger.debug(s"Executable up")
    Thread.sleep(100)
    iter()
  }
}

@c4assemble("TestTodoApp") class FailOverTestAssembleBase(
  failOverTestTxFactory: FailOverTestTxFactory
){
  def toTx(
    srcId: SrcId,
    firstborn: Each[S_Firstborn],
  ): Values[(SrcId, TxTransform)] =
    List(
      WithPK(failOverTestTxFactory.create("FailOverTest-0")),
      WithPK(failOverTestTxFactory.create("FailOverTest-1")),
      WithPK(failOverTestTxFactory.create("FailOverTest-2")),
    )
}

@c4multi("TestTodoApp") final case class FailOverTestTx(
  srcId: SrcId
) extends TxTransform with LazyLogging {
  def transform(local: Context): Context = {
    logger.debug(s"Tx up $srcId")
    local
  }
}*/