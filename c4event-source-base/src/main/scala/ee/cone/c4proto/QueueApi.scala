package ee.cone.c4proto

import java.util.concurrent.ExecutorService

import ee.cone.c4proto.Types.{Index, SrcId, World}

@protocol object QProtocol extends Protocol {
  @Id(0x0010) case class TopicKey(@Id(0x0011) srcId: String, @Id(0x0012) valueTypeId: Long)
}

case class ActorName(value: String)

sealed trait TopicName
case class InboxTopicName(actorName: ActorName) extends TopicName
case class StateTopicName(actorName: ActorName) extends TopicName

trait QRecord {
  def topic: TopicName
  def key: Array[Byte]
  def value: Array[Byte]
}

trait RawQSender {
  def send(rec: QRecord): Unit
}

sealed trait MessageMapResult
case class Update[M<:Product](srcId: SrcId, value: M) extends MessageMapResult
case class Delete[M<:Product](srcId: SrcId, value: Class[M]) extends MessageMapResult
case class Send[M<:Product](actorName: ActorName, value: M) extends MessageMapResult

abstract class MessageMapper[M](val mClass: Class[M]) {
  def actorName: ActorName
  def mapMessage(world: World, message: M): Seq[MessageMapResult]
}

trait MessageMappersApp {
  def messageMappers: List[MessageMapper[_]] = Nil
}

trait QMessageMapper {
  def mapMessage(world: World, rec: QRecord): Seq[QRecord]
}

trait QMessages {
  def toRecord(actorName: Option[ActorName], message: MessageMapResult): QRecord
  def toTree(records: Iterable[QRecord]): Map[WorldKey[_],Index[Object,Object]]
  def send[M](message: Send[M]): Unit
}

////

trait ToStartApp {
  def toStart: List[CanStart] = Nil
}

trait ShouldStartEarly {
  def isReady: Boolean
}

trait CanStart {
  def start(ctx: ExecutionContext): Unit
  def early: Option[ShouldStartEarly]
}

trait CanFail {
  def isDone: Boolean
}

trait ServerFactory {
  def toServer(runnable: Executable): CanStart
}

trait WorldProvider {
  def world: World
}

trait Executable {
  def run(ctx: ExecutionContext): Unit
}

class ExecutionContext(val executors: ExecutorService, val onShutdown: (()⇒Unit)⇒Unit)

////

object Trace { //m. b. to util
  def apply[T](f: =>T): T = try { f } catch {
    case e: Throwable => e.printStackTrace(); throw e
  }
}