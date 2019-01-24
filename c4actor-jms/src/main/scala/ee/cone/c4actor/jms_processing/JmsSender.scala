package ee.cone.c4actor.jms_processing

import ee.cone.c4actor.jms_processing.JmsProtocol.JmsOutcomeMessage
import javax.jms.{Connection, MessageProducer, Session}
import org.apache.activemq.ActiveMQConnectionFactory

import scala.util.Try

trait JmsSender {
  def storedFactory: ActiveMQConnectionFactory
  def storedConnection: Connection
  def storedSession: Session

  def sendMessage(message: JmsOutcomeMessage): Boolean

  def address: String
  def port: String
  def username: String
  def password: String
  def queueName: String
}

case class JmsSenderImpl(address: String, port: String, queueName: String, username: String, password: String) extends JmsSender {
  lazy val storedFactory: ActiveMQConnectionFactory = new ActiveMQConnectionFactory(
      s"tcp://$address:$port")

  lazy val storedConnection: Connection = storedFactory.createConnection(username, password)

  lazy val storedSession: Session = storedConnection.createSession(false, Session.AUTO_ACKNOWLEDGE)

  lazy val storedProducer: MessageProducer = {
    val queue = storedSession.createQueue(queueName)
    storedSession.createProducer(queue)
  }

  def sendMessage(message: JmsOutcomeMessage): Boolean = {
    val msg = storedSession.createTextMessage(message.messageBody)
    Try(storedProducer.send(msg)).toOption.isDefined
  }
}
