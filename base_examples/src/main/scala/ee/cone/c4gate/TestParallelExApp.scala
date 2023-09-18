
package ee.cone.c4gate

import java.util.UUID
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor._
import ee.cone.c4actor.Types._
import ee.cone.c4proto._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.c4assemble
import ee.cone.c4di.c4multi
import ee.cone.c4gate.TestParallelExProto._

@protocol("TestParallelExApp") object TestParallelExProto {
  @Id(0x6a99) case class D_TestOrig(@Id(0x6a99) srcId: SrcId, @Id(0x6a98) data: Int)
}

case object TestParallelExKey extends TransientLens[Int](0)

@c4assemble("TestParallelExApp") class TestParallelExBase(
  factory: TestParallelExTxFactory,
){
  def join(
    key: SrcId,
    firstborn: Each[S_Firstborn],
  ): Values[(SrcId, TxTransform)] =
    List("TestParallelEx-0","TestParallelEx-1","TestParallelEx-2").map(pk=>WithPK(factory.create(pk)))

  def joinD(
    key: SrcId,
    testOrig: Each[D_TestOrig],
  ): Values[(SrcId, TxTransform)] =
    List(WithPK(factory.create(s"$key-a")))
}


@c4multi("TestParallelExApp") final case class TestParallelExTx(srcId: SrcId)(
  txAdd: LTxAdd,
  getD_TestOrig: GetByPK[D_TestOrig],
) extends TxTransform with LazyLogging {
  /* transient increment, orig increment*/
  def transform_0(local: Context): Context = srcId match { case "TestParallelEx-0" =>
    val wasTestOrig = getD_TestOrig.ofA(local).getOrElse(srcId, D_TestOrig(srcId, 0))
    logger.info(s"handling ${TestParallelExKey.of(local)} ${wasTestOrig.data}")
    txAdd.add(LEvent.update(wasTestOrig.copy(data=wasTestOrig.data+1))).andThen(TestParallelExKey.modify(_+1))(local)
  }
  /* 2 producers with different processing times, 1 observer; no parallel for same id*/
  def transform_1(local: Context): Context = srcId match {
    case "TestParallelEx-0" =>
      val data = getD_TestOrig.ofA(local).toList.sortBy(_._1).map(_._2.data).mkString(" ")
      logger.info(s"$srcId OK $data")
      local
    case "TestParallelEx-1" | "TestParallelEx-2" =>
      val wasData = getD_TestOrig.ofA(local).get(srcId).fold(0)(_.data)
      val data = UUID.randomUUID().hashCode
      logger.info(s"$srcId starts $wasData -> $data")
      Thread.sleep(if (srcId == "TestParallelEx-1") 500 else 600)
      logger.info(s"$srcId ends $wasData -> $data")
      txAdd.add(LEvent.update(D_TestOrig(srcId, data)))(local)
    case _ => local
  }
  /* instant rerun with delivered (while in progress) world */
  def transform_2(local: Context): Context = srcId match {
    case "TestParallelEx-0" =>
      val data = getD_TestOrig.ofA(local).get("TestParallelEx-1").fold(0)(_.data)
      logger.info(s"$srcId starts seeing $data")
      Thread.sleep(300)
      logger.info(s"$srcId ends seeing $data")
      local
    case "TestParallelEx-1" =>
      val wasData = getD_TestOrig.ofA(local).get(srcId).fold(0)(_.data)
      val data = UUID.randomUUID().hashCode
      logger.info(s"$srcId upd-starts $wasData -> $data")
      Thread.sleep(70)
      logger.info(s"$srcId upd-ends $wasData -> $data")
      txAdd.add(LEvent.update(D_TestOrig(srcId, data)))(local)
    case "TestParallelEx-2" =>
      val data = getD_TestOrig.ofA(local).get("TestParallelEx-1").fold(0)(_.data)
      logger.info(s"$srcId just seeing $data")
      local
    case _ => local
  }
  /* transients dies is txTr leaves world */
  def transform_3(local: Context): Context = srcId match {
    case "TestParallelEx-0" =>
      Thread.sleep(5000)
      val events = getD_TestOrig.ofA(local).get(srcId).fold(LEvent.update(D_TestOrig(srcId, 1)))(LEvent.delete)
      logger.info(s"$srcId upd: ${events.nonEmpty}")
      txAdd.add(events)(local)
    case "TestParallelEx-0-a" =>
      logger.info(s"$srcId transient was: ${TestParallelExKey.of(local)}")
      TestParallelExKey.modify(_ + 1)(local)
    case _ => local
  }

  def transform(local: Context): Context = ??? //choose

}



