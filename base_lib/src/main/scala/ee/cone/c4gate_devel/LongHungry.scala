package ee.cone.c4gate_devel

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.c4assemble
import ee.cone.c4di.c4multi
import ee.cone.c4gate.HttpProtocol.N_Header
import ee.cone.c4gate.{ByPathHttpPublication, Publisher}
import ee.cone.c4gate_devel.LongHungryProto.D_Blob
import ee.cone.c4proto._
import okio.ByteString

import java.time.Instant

trait LongHungryAppBase

@protocol("LongHungryApp") object LongHungryProto {
  @Id(0x6a98) case class D_Blob(@Id(0x6a99) srcId: SrcId, @Id(0x6a9a) data: ByteString)
}

@c4assemble("LongHungryApp") class LongHungryAssembleBase(
  factory: LongHungryTxFactory
){
  def join(
    key: SrcId,
    firstborn: Each[S_Firstborn]
  ): Values[(SrcId, TxTransform)] =
    Seq(WithPK(factory.create("Long-LongHungry")), WithPK(factory.create("Hungry-LongHungry")))
}

@c4multi("LongHungryApp") final case class LongHungryTx(srcId: SrcId)(
  txAdd: LTxAdd
) extends TxTransform with LazyLogging {
  def transform(local: Context): Context = srcId match {
    case "Long-LongHungry" =>
      for(i <- LazyList.from(0)){
        logger.info("more long")
        Thread.sleep(1000)
      }
      local
    case "Hungry-LongHungry" =>
      logger.info("more hungry")
      val events = LEvent.update(D_Blob("LongHungry", ToByteString(Instant.now.toString * 1000000)))
      txAdd.add(events).andThen(SleepUntilKey.set(Instant.now.plusSeconds(1)))(local)
  }
}
