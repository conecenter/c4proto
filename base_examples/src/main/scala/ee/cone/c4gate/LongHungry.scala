package ee.cone.c4gate

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.c4assemble
import ee.cone.c4di.c4multi
import ee.cone.c4gate.HttpProtocol.N_Header
import ee.cone.c4gate.{ByPathHttpPublication, Publisher}
import ee.cone.c4gate.LongHungryProto.D_Blob
import ee.cone.c4proto._
import okio.ByteString

import java.time.Instant

@protocol("LongHungryApp") object LongHungryProto {
  @Id(0x6a98) case class D_Blob(@Id(0x6a99) srcId: SrcId, @Id(0x6a9a) data: ByteString)
}

@c4("LongHungryApp") final case class LongHungryLongTx(srcId: SrcId = "LongHungryLongTx")
  extends SingleTxTr with LazyLogging
{
  def transform(local: Context): TxEvents = {
    for(i <- LazyList.from(0)){
      logger.info("more long")
      Thread.sleep(1000)
    }
    Nil
  }
}

@c4("LongHungryApp") final case class LongHungryHungryTx(srcId: SrcId = "LongHungryHungryTx")(
  sleep: Sleep,
) extends SingleTxTr with LazyLogging {
  def transform(local: Context): TxEvents = {
    logger.info("more hungry")
    LEvent.update(D_Blob("LongHungry", ToByteString(Instant.now.toString * 1000000))) ++ sleep.forSeconds(1)
  }
}
