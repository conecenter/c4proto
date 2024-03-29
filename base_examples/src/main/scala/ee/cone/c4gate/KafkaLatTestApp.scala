package ee.cone.c4gate

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.{RawQSender, _}
import ee.cone.c4assemble.Single
import ee.cone.c4di.c4

import scala.annotation.tailrec
import scala.collection.immutable.Seq


class TestQRecordImpl(val topic: TxLogName, val value: Array[Byte], val headers: Seq[RawHeader]) extends QRecord
@c4("KafkaLatTestApp") final class TestRootProducerImpl(
  rawQSender: RawQSender, toUpdate: ToUpdate,
  currentTxLogName: CurrentTxLogName,
) extends Executable with LazyLogging {
  def run(): Unit = {
    iteration()
  }
  @tailrec private def iteration(): Unit = {
    val updates = Nil //LEvent.update(S_Firstborn(actorName,offset)).toList.map(toUpdate.toUpdate)
    val (bytes, headers) = toUpdate.toBytes(updates)
    val offset = rawQSender.send(new TestQRecordImpl(currentTxLogName,bytes,headers))
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