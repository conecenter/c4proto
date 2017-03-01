package ee.cone.c4gate

import Function.chain

import ee.cone.c4actor.LEvent.{add, update}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Values, World}
import ee.cone.c4assemble.{Assemble, WorldKey, assemble}
import ee.cone.c4ui.{AlienExchangeApp, FromAlienTaskAssemble}


class TestSSEApp extends ServerApp
  with EnvConfigApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp
  with BranchApp
  with AlienExchangeApp
{
  override def assembles: List[Assemble] =
    new FromAlienTaskAssemble("localhost", "/sse.html") ::
    new TestSSEAssemble ::
    super.assembles
    //println(s"visit http://localhost:${config.get("C4HTTP_PORT")}/sse.html")
}

case object TestTimerKey extends WorldKey[java.lang.Long](0L)

@assemble class TestSSEAssemble extends Assemble {
  def joinView(
    key: SrcId,
    tasks: Values[BranchTask]
  ): Values[(SrcId,BranchHandler)] = {
    println(s"joinView ${tasks}")
    for(task ← tasks) yield task.branchKey → TestSSEHandler(task.branchKey, task)
  }
}

case class TestSSEHandler(branchKey: SrcId, task: BranchTask) extends BranchHandler {
  def exchange: BranchMessage ⇒ World ⇒ World = message ⇒ local ⇒ {
    val seconds = System.currentTimeMillis / 1000
    if(TestTimerKey.of(local) == seconds) local
    else {
      val sessionKeys = task.sessionKeys(local).toSeq
      val send = SendToAlienKey.of(local)(_:SrcId,"show",s"$seconds")
      println(s"TestSSEHandler $sessionKeys")
      TestTimerKey.set(seconds).andThen(chain(sessionKeys.map(send)))(local)
    }
  }
  def seeds: World ⇒ List[BranchProtocol.BranchResult] = _ ⇒ Nil
}