package ee.cone.c4gate

import java.util.UUID

import ee.cone.c4actor.Types.{SrcId, World}
import ee.cone.c4actor._
import ee.cone.c4gate.TestTodoProtocol.Task
import ee.cone.c4proto.{Id, Protocol, protocol}
import ee.cone.c4actor.LEvent._
import ee.cone.c4vdom.{ChildPair, CurrentVDom, RootView, VDomState}
import ee.cone.c4vdom
import ee.cone.c4vdom_mix.VDomApp

object TestSSE extends Main((new TestSSEApp).execution.run)

class TestSSEApp extends ServerApp
  with EnvConfigApp
  with KafkaProducerApp with KafkaConsumerApp
  with SerialObserversApp
  with SSEApp
{
  lazy val sseUI: SSEui = {
    println(s"visit http://localhost:${config.get("C4HTTP_PORT")}/sse.html")
    new TestSSEui
  }
}

case object TestTimerKey extends WorldKey[java.lang.Long](0L)

class TestSSEui extends SSEui {
  def allowOriginOption: Option[String] = Some("*")
  def fromAlien: (String ⇒ Option[String]) ⇒ World ⇒ World = _ ⇒ identity
  def toAlien: World ⇒ (World, List[(String, String)]) = local ⇒ {
    val seconds = System.currentTimeMillis / 1000
    if(TestTimerKey.of(local) == seconds) (local,Nil)
    else (TestTimerKey.transform(_⇒seconds)(local), List("show"→seconds.toString))
  }
}
