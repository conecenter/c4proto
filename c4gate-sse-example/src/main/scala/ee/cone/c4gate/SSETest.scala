package ee.cone.c4gate

import java.util.UUID

import ee.cone.c4actor.LEvent.{add, update}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Values, World}
import ee.cone.c4assemble.{Assemble, WorldKey, assemble, by}
import ee.cone.c4gate.AlienProtocol.ToAlienWrite
import ee.cone.c4proto.Protocol

object TestSSE extends Main((new TestSSEApp).execution.run)

class TestSSEApp extends ServerApp
  with EnvConfigApp
  with KafkaProducerApp with KafkaConsumerApp
  with SerialObserversApp
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
  def exchange: (String ⇒ String) ⇒ World ⇒ World = message ⇒ local ⇒ {
    val seconds = System.currentTimeMillis / 1000
    if(TestTimerKey.of(local) == seconds) local
    else {
      val messages = task.sessionKeys(local).toSeq.map { sessionKey ⇒
        ToAlienWrite(s"${UUID.randomUUID}",sessionKey,"show",s"$seconds",0)
      }
      println(s"TestSSEHandler ${task.sessionKeys(local)}")
      TestTimerKey.set(seconds).andThen(add(messages.flatMap(update)))(local)
    }
  }
  def seeds: World ⇒ List[BranchProtocol.BranchResult] = _ ⇒ Nil
}