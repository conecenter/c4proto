package ee.cone.c4gate

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.{RawQSender, _}

import scala.annotation.tailrec
import scala.collection.immutable.Seq

class KafkaLatTestApp extends RichDataApp with ExecutableApp with EnvConfigApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp with NoAssembleProfilerApp
{
  override def toStart: List[Executable] =
    new TestRootProducerImpl(rawQSender,toUpdate) ::
    new TestRootConsumerImpl(consuming) ::
    super.toStart
}

class TestQRecordImpl(val topic: TopicName, val value: Array[Byte], val headers: Seq[RawHeader]) extends QRecord
class TestRootProducerImpl(rawQSender: RawQSender, toUpdate: ToUpdate) extends Executable with LazyLogging {
  def run(): Unit = {
    iteration()
  }
  @tailrec private def iteration(): Unit = {
    val updates = Nil //LEvent.update(S_Firstborn(actorName,offset)).toList.map(toUpdate.toUpdate)
    val (bytes, headers) = toUpdate.toBytes(updates)
    rawQSender.send(List(new TestQRecordImpl(InboxTopicName(),bytes,headers)))
    logger.info(s"pushed")
    Thread.sleep(1000)
    iteration()
  }
}


class TestRootConsumerImpl(consuming: Consuming) extends Executable with LazyLogging {
  def run(): Unit = {
    consuming.process("0" * OffsetHexSize(), consumer ⇒ iteration(consumer))
  }
  @tailrec private def iteration(consumer: Consumer): Unit = {
    val events = consumer.poll()
    logger.info(s"poll-ed ${events.size}")
    iteration(consumer)
  }
}