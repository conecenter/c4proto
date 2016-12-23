package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4gate.InternetProtocol.TcpWrite
import ee.cone.c4gate.TestClockProtocol.ClockData
import ee.cone.c4proto.{Id, Protocol, protocol}

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
  def sseAllowOrigin: Option[String] = Option("*")
  //"sse-test"

  private lazy val testSSETxTransform = {
    println(s"visit http://localhost:${config.get("C4HTTP_PORT")}/sse.html")
    new TestSSETxTransform(sseMessages)
  }
  override def txTransforms: List[TxTransform] =
    testSSETxTransform :: super.txTransforms
  override def protocols: List[Protocol] =
    TestClockProtocol :: InternetProtocol :: super.protocols
}

@protocol object TestClockProtocol extends Protocol {
  @Id(0x0001) case class ClockData(@Id(0x0002) key: String, @Id(0x0003) seconds: Long)
}

class TestSSETxTransform(sseMessages: SSEMessages) extends TxTransform {
  def transform(tx: WorldTx): WorldTx = {
    val seconds = System.currentTimeMillis / 1000
    if(By.srcId(classOf[ClockData]).of(tx.world).getOrElse("",Nil).exists(_.seconds==seconds)) return tx
    val events =
      LEvent.update(ClockData("",seconds)) ::
      By.srcId(classOf[SSEConnection]).of(tx.world).values.flatten.filter(_.state.nonEmpty).map { conn ⇒
        LEvent.update(sseMessages.message(conn.connectionKey,"show",seconds.toString,seconds))
      }.toList
    tx.add(events:_*)
  }
}
