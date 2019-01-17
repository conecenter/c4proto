package ee.cone.c4actor

import javax.jms._
import org.apache.activemq.ActiveMQConnectionFactory


case class ConsumerMessageSender(consumerName: String) extends MessageListener {
  def onMessage(message: Message): Unit = {
    message match {
      case msg: TextMessage => try{
        println(s"$consumerName ${msg.getText}")
      } catch {
        case e: JMSException => e.printStackTrace()
      }
    }
  }
}

case class JmsSenderStart(
  execution: Execution, toUpdate: ToUpdate, jmsParameters: JmsParameters
) extends Executable {
  def run(): Unit = {
    System.err.println("!!!!! TEST USE ONLY !!!!!!!")
    println("Starting JMS Sender")
    /*val broker = BrokerFactory.createBroker(new URI(
      s"broker:(tcp://${JmsRuntimeParams.ADDRESS}:${JmsRuntimeParams.PORT})"))
    if(!broker.isStarted) broker.start()*/
    try {
      val connectionFactory = new ActiveMQConnectionFactory(
        s"tcp://${jmsParameters.address}:${jmsParameters.port}")
      val connection = connectionFactory.createConnection()
      val session = connection.createSession(false,
        Session.AUTO_ACKNOWLEDGE)
      val queue = session.createQueue(jmsParameters.queue)
      val payload = "Important Task "+System.currentTimeMillis()
      val msg = session.createTextMessage(payload)
      val producer = session.createProducer(queue)
      println("Sending text '" + payload + "'")
      producer.send(msg)
      var i = 5990
      while(true) {
        i = i + 1
        val msg = session.createTextMessage(""+i)
        producer.send(msg)
        println("Sent: "+msg.getText)
        Thread.sleep(1000)
      }
    }
    execution.complete()
  }
}


//  JMS_ADDRESS='localhost' JMS_PORT='61616' JMS_QUEUE='customerQueue' C4STATE_TOPIC_PREFIX=ee.cone.c4actor.JmsSenderApp sbt ~'c4actor-jms/runMain ee.cone.c4actor.ServerMain'
class JmsSenderApp extends ToStartApp
  with VMExecutionApp
  with ExecutableApp
  with RichDataApp
  with SimpleAssembleProfilerApp
  with EnvConfigApp
{
  lazy val jmsParameters = JmsParameters(config.get("JMS_ADDRESS"), config.get("JMS_PORT"), config.get("JMS_QUEUE"), 0)
  override def toStart: List[Executable] = JmsSenderStart(execution, toUpdate, jmsParameters) :: super.toStart
}

