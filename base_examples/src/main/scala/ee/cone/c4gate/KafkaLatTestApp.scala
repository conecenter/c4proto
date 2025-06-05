package ee.cone.c4gate

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor._
import ee.cone.c4assemble.Single
import ee.cone.c4di.c4

import scala.annotation.tailrec
import scala.collection.immutable.Seq


class TestQRecordImpl(val topic: TxLogName, val value: Array[Byte], val headers: Seq[RawHeader]) extends QRecord
@c4("KafkaLatTestApp") final class TestRootProducerImpl(
  qMessages: QMessages,
) extends Executable with LazyLogging {
  def run(): Unit = {
    iteration()
  }
  @tailrec private def iteration(): Unit = {
    val offset = qMessages.doSend(Nil)
    logger.info(s"pushed $offset")
    Thread.sleep(1000)
    iteration()
  }
}


@c4("KafkaLatTestApp") final class TestRootConsumerImpl(consuming: Consuming) extends Executable with LazyLogging {
  def run(): Unit = {
    consuming.process("0" * OffsetHexSize(), consumer => iteration(consumer))
  }
  @tailrec private def iteration(consumer: Consumer): Unit = {
    val events = consumer.poll()
    logger.info(s"poll-ed ${events.size}")
    iteration(consumer)
  }
}