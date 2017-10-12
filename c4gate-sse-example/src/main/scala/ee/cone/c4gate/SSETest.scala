package ee.cone.c4gate

import java.time.Instant

import com.typesafe.scalalogging.LazyLogging

import Function.chain
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble}
import ee.cone.c4ui.{AlienExchangeApp, FromAlienTaskAssemble}


class TestSSEApp extends ServerApp
  with EnvConfigApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp
  with BranchApp
  with AlienExchangeApp
  with UMLClientsApp with NoAssembleProfilerApp
  with ManagementApp
  with FileRawSnapshotApp
{
  override def assembles: List[Assemble] =
    new FromAlienTaskAssemble("/sse.html") ::
    new TestSSEAssemble ::
    super.assembles
    //println(s"visit http://localhost:${config.get("C4HTTP_PORT")}/sse.html")
}

@assemble class TestSSEAssemble extends Assemble {
  def joinView(
    key: SrcId,
    tasks: Values[BranchTask]
  ): Values[(SrcId,BranchHandler)] = {
    //println(s"joinView ${tasks}")
    for(task ← tasks) yield task.branchKey → TestSSEHandler(task.branchKey, task)
  }
}

case class TestSSEHandler(branchKey: SrcId, task: BranchTask) extends BranchHandler with LazyLogging {
  def exchange: BranchMessage ⇒ Context ⇒ Context = message ⇒ local ⇒ {
    val now = Instant.now
    val (keepTo,freshTo) = task.sending(local)
    val send = chain(List(keepTo,freshTo).flatten.map(_("show",s"${now.getEpochSecond}")))
    logger.info(s"TestSSEHandler $keepTo $freshTo")
    SleepUntilKey.set(now.plusSeconds(1)).andThen(send)(local)
  }
  def seeds: Context ⇒ List[BranchProtocol.BranchResult] = _ ⇒ Nil
}