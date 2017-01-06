package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4assemble.Types.World
import ee.cone.c4assemble.WorldKey

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

class TestSSEui extends SSEui with InitLocal {
  def allowOriginOption: Option[String] = Some("*")
  def postURL: String = "/connection"
  def fromAlien: (String ⇒ Option[String]) ⇒ World ⇒ World = _ ⇒ identity
  def toAlien: World ⇒ (World, List[(String, String)]) = local ⇒ {
    val seconds = System.currentTimeMillis / 1000
    if(TestTimerKey.of(local) == seconds) (local,Nil)
    else (TestTimerKey.modify(_⇒seconds)(local), List("show"→seconds.toString))
  }
}
