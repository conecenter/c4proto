package ee.cone.c4ui

import java.time.Instant

import com.typesafe.scalalogging.LazyLogging

import Function.chain
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor_branch._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, CallerAssemble, c4assemble}
import ee.cone.c4gate.AlienProtocol.U_FromAlienStatus
import ee.cone.c4di.{c4, provide}

//println(s"visit http://localhost:${config.get("C4HTTP_PORT")}/sse.html")
@c4("TestSSEApp")  class SSEFromAlienTaskAssembleBase {
  @provide def subAssembles: Seq[Assemble] =
    new FromAlienTaskAssemble("/sse.html") :: Nil
}

@c4assemble("TestSSEApp") class TestSSEAssembleBase(
  getU_FromAlienStatus: GetByPK[U_FromAlienStatus],
) extends LazyLogging {
  def joinView(
    key: SrcId,
    task: Each[BranchTask]
  ): Values[(SrcId,BranchHandler)] = {
    logger.info(s"joinView ${task}")
    List(WithPK(TestSSEHandler(task.branchKey, task)(getU_FromAlienStatus)))
  }
}

case class TestSSEHandler(branchKey: SrcId, task: BranchTask)(
  getU_FromAlienStatus: GetByPK[U_FromAlienStatus],
) extends BranchHandler with LazyLogging {
  def exchange: BranchMessage => Context => Context = message => local => {
    val now = Instant.now
    val (keepTo,freshTo) = task.sending(local)
    val send = chain(List(keepTo,freshTo).flatten.map(_("show",s"${now.getEpochSecond}")))
    logger.info(s"TestSSEHandler $keepTo $freshTo")
    getU_FromAlienStatus.ofA(local).values.foreach{ status =>
      logger.info(s"${status.isOnline} ... ${status.expirationSecond - now.getEpochSecond}")
    }
    SleepUntilKey.set(now.plusSeconds(1)).andThen(send)(local)
  }
  def seeds: Context => List[BranchProtocol.S_BranchResult] = _ => Nil
}