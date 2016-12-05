package ee.cone.c4proto

import java.util.concurrent.ExecutorService

import ee.cone.c4proto.Types.{Index, SrcId, World}

@protocol object QProtocol extends Protocol {
  @Id(0x0010) case class TopicKey(@Id(0x0011) srcId: String, @Id(0x0012) valueTypeId: Long)
}

trait QRecord {
  def streamKey: StreamKey
  def key: Array[Byte]
  def value: Array[Byte]
}

case class StreamKey(from: String, to: String)

trait RawQSender {
  def send(rec: QRecord): Unit
}

abstract class MessageMapper[M](val mClass: Class[M]) {
  def streamKey: StreamKey
  def mapMessage(message: M): Seq[Product]
}

trait MessageMappersApp {
  def messageMappers: List[MessageMapper[_]] = Nil
}

trait QMessageMapper {
  def streamKeys: List[StreamKey]
  def mapMessage(streamKey: StreamKey, rec: QRecord): Seq[QRecord]
}

trait QMessages {
  def toRecord(streamKey: StreamKey, message: Product): QRecord
  def toTree(records: Iterable[QRecord]): Map[WorldKey[_],Index[Object,Object]]
}

////

trait ToStartApp {
  def toStart: List[CanStart] = Nil
}

trait ShouldStartEarly {
  def isReady: Boolean
}

trait CanStart {
  def start(pool: ExecutorService): Unit
  def early: Option[ShouldStartEarly]
}

trait CanFail {
  def isDone: Boolean
}

trait ServerFactory {
  def toServer(runnable: Runnable): CanStart
}

trait WorldProvider {
  def world: World
}

////

object OnShutdown {
  def apply(f: ()⇒Unit): Unit = Runtime.getRuntime.addShutdownHook(new Thread(){
    override def run(): Unit = f()
  })
}