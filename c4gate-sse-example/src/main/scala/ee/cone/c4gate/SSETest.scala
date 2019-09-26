package ee.cone.c4gate

import java.time.Instant

import com.typesafe.scalalogging.LazyLogging

import Function.chain
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, assemble, by}
import ee.cone.c4gate.AlienProtocol.U_FromAlienStatus
import ee.cone.c4ui.{AlienExchangeApp, FromAlienTaskAssemble}


class TestSSEApp extends ServerApp
  with EnvConfigApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp with TreeIndexValueMergerFactoryApp
  with BranchApp
  with AlienExchangeApp
  with NoAssembleProfilerApp
  with ManagementApp
  with FileRawSnapshotApp
  with BasicLoggingApp
{
  override def assembles: List[Assemble] =
    new FromAlienTaskAssemble("/sse.html") ::
    new TestSSEAssemble ::
    super.assembles
    //println(s"visit http://localhost:${config.get("C4HTTP_PORT")}/sse.html")
}

@assemble class TestSSEAssembleBase   {
  def joinView(
    key: SrcId,
    task: Each[BranchTask]
  ): Values[(SrcId,BranchHandler)] = {
    //println(s"joinView ${tasks}")
    List(WithPK(TestSSEHandler(task.branchKey, task)))
  }
}

case class TestSSEHandler(branchKey: SrcId, task: BranchTask) extends BranchHandler with LazyLogging {
  def exchange: BranchMessage => Context => Context = message => local => {
    val now = Instant.now
    val (keepTo,freshTo) = task.sending(local)
    val send = chain(List(keepTo,freshTo).flatten.map(_("show",s"${now.getEpochSecond}")))
    logger.info(s"TestSSEHandler $keepTo $freshTo")
    ByPK(classOf[U_FromAlienStatus]).of(local).values.foreach{ status =>
      logger.info(s"${status.isOnline} ... ${status.expirationSecond - now.getEpochSecond}")
    }
    SleepUntilKey.set(now.plusSeconds(1)).andThen(send)(local)
  }
  def seeds: Context => List[BranchProtocol.S_BranchResult] = _ => Nil
}