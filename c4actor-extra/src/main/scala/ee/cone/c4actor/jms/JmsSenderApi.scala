package ee.cone.c4actor.jms

import ee.cone.c4actor.jms.JmsProtocol.JmsOutcomeMessage

trait JmsSenderFactory {
  def create: JmsSender
}

trait JmsSender {
  def sendMessage(message: JmsOutcomeMessage): Boolean
}
