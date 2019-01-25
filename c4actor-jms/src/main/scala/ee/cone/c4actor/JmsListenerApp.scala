package ee.cone.c4actor

import ee.cone.c4actor.jms_processing.JmsProtocol
import ee.cone.c4actor.jms_processing.JmsProtocol.JmsIncomeMessage
import ee.cone.c4assemble.Single
import ee.cone.c4proto.Protocol
import javax.jms._
import org.apache.activemq.ActiveMQConnectionFactory
import org.apache.activemq.command.ActiveMQBytesMessage

import scala.annotation.tailrec
import scala.collection.immutable.Seq

case class JmsParameters(
  address: String,
  port: String,
  queue: String
)

class JmsQRecordImpl(val topic: TopicName, val value: Array[Byte], val headers: Seq[RawHeader]) extends QRecord

case class JmsListenerStart(
  execution: Execution, toUpdate: ToUpdate, rawQSender: RawQSender, jmsParameters: JmsParameters
) extends Executable {
  def run(): Unit = {
    println("Starting JMS Listener")
   // try {
      val connectionFactory = new ActiveMQConnectionFactory(
        s"tcp://${jmsParameters.address}:${jmsParameters.port}")
      //connectionFactory.setUserName("admin")
     // connectionFactory.setPassword("admin")
      val connection = connectionFactory.createConnection()
      val session = connection.createSession(false,
        Session.CLIENT_ACKNOWLEDGE )
      val queue = session.createQueue(jmsParameters.queue)
      val consumer = session.createConsumer(queue)
      connection.start()

      def writeToKafka(msg: TextMessage): Boolean = {
        val msgId = Option(msg.getJMSMessageID)
        val jmsType = Option(msg.getJMSType).getOrElse("")
        val msgBody = Option(msg.getText).getOrElse("")
        if (msgId.isDefined) {
          val orig = JmsIncomeMessage(msgId.get, jmsType, msgBody)
          val update = toUpdate.toUpdate(Single(LEvent.update(orig)))
          val (bytes, headers) = toUpdate.toBytes(List(update))
          val qRecord = new JmsQRecordImpl(InboxTopicName(), bytes, headers)
          val offset = rawQSender.send(List(qRecord))
          println("Written. Offset: " + offset)
          true
        } else false
      }

      @tailrec
      def execute(): Unit = {
        val message = consumer.receive()
        message match {
          case msg: TextMessage =>
            val writeToKafkaErrorOccurred = !writeToKafka(msg)
            if (writeToKafkaErrorOccurred) {
              println("WRITE KAFKA FAILED! STOPPING!")
              session.recover()
              Thread.sleep(1000)
              System.exit(0)
            } else {
              println("Received message: " + msg.getText)
              msg.acknowledge()
            }
          case msg: ActiveMQBytesMessage => {
            var bytes = new Array[Byte](msg.getBodyLength.toInt)
            msg.readBytes(bytes)
            println(new String(bytes))
          }
        }
        execute()
      }

      execute()

  /*  } finally {
      broker.stop()
    }*/
    println("Stopping JMS Listener")
    execution.complete()
  }
}


//  JMS_ADDRESS='localhost' JMS_PORT='61616' JMS_QUEUE='customerQueue' C4BOOTSTRAP_SERVERS=127.0.0.1:8092 C4INBOX_TOPIC_PREFIX='' C4MAX_REQUEST_SIZE=25000000  C4STATE_TOPIC_PREFIX=ee.cone.c4actor.JmsListenerApp sbt ~'c4actor-jms/runMain ee.cone.c4actor.ServerMain'
class JmsListenerApp extends ToStartApp
  with VMExecutionApp
  with ExecutableApp
  with RichDataApp
  with SimpleAssembleProfilerApp
  with KafkaProducerApp
  with EnvConfigApp
  with ProtocolsApp
{
  lazy val jmsParameters = JmsParameters(config.get("JMS_ADDRESS"), config.get("JMS_PORT"), config.get("JMS_QUEUE"))
  override def toStart: List[Executable] = JmsListenerStart(execution, toUpdate, rawQSender, jmsParameters) :: super.toStart
  override def protocols: List[Protocol] = JmsProtocol :: super.protocols
}

