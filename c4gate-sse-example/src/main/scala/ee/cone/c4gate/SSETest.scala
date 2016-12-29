package ee.cone.c4gate

import ee.cone.c4actor.Types.World
import ee.cone.c4actor._
import ee.cone.c4vdom.VDomState
import ee.cone.c4vdom
import ee.cone.c4vdom_mix.VDomApp

object TestSSE extends Main((new TestSSEApp).execution.run)

class TestSSEApp extends ServerApp
  with EnvConfigApp
  with QMessagesApp
  with TreeAssemblerApp
  with QReducerApp
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
  def fromAlien(post: HttpPostByConnection)(local: World): World = local
  def toAlien(local: World): (World, List[(String, String)]) = {
    val seconds = System.currentTimeMillis / 1000
    if(TestTimerKey.of(local) == seconds) (local,Nil)
    else (TestTimerKey.transform(_⇒seconds)(local), List("show"→seconds.toString))
  }
}

////

object TestVDom extends Main((new TestVDomApp).execution.run)

class TestVDomApp extends ServerApp
  with EnvConfigApp
  with QMessagesApp
  with TreeAssemblerApp
  with QReducerApp
  with KafkaProducerApp with KafkaConsumerApp
  with SerialObserversApp
  with SSEApp
  with VDomApp
{
  type VDomStateContainer = World
  lazy val vDomStateKey: c4vdom.Lens[World,Option[VDomState]] = VDomStateKey
  lazy val sseUI: SSEui = {
    println(s"visit http://localhost:${config.get("C4HTTP_PORT")}/???.html")
    new TestVDomUI
  }
}

case object VDomStateKey extends WorldKey[Option[VDomState]](None)
  with c4vdom.Lens[World, Option[VDomState]]

class TestVDomUI extends SSEui {
  def allowOriginOption: Option[String] = Some("*")
  def fromAlien(post: HttpPostByConnection)(local: World): World = ???
  def toAlien(local: World): (World, List[(String, String)]) = {
    /*val seconds = System.currentTimeMillis / 1000
    if(TestTimerKey.of(local) == seconds) (local,Nil)
    else (TestTimerKey.transform(_⇒seconds)(local), List("show"→seconds.toString))*/
  }
}