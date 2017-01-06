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
  with InitLocalsApp
{
  override def initLocals: List[InitLocal] = {
    println(s"visit http://localhost:${config.get("C4HTTP_PORT")}/sse.html")
    NoProxySSEConfig :: TestSSEui :: super.initLocals
  }
}

case object TestTimerKey extends WorldKey[java.lang.Long](0L)

object TestSSEui extends InitLocal {
  def initLocal: World ⇒ World = ToAlienKey.set(local ⇒ {
    val seconds = System.currentTimeMillis / 1000
    if(TestTimerKey.of(local) == seconds) (local,Nil)
    else (TestTimerKey.set(seconds)(local), List("show"→seconds.toString))
  })
}
