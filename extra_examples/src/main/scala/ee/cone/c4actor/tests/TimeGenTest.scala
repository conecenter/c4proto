package ee.cone.c4actor.tests

import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.TxTransform
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.time._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.c4assemble

@c4time(0x1337) case object TestTestTime extends CurrentTime(10L)

@c4assemble class TestTimeAssembleBase {
  def test(
    srcId: SrcId,
    fb: Each[S_Firstborn],
    @time(TestTestTime) time: Each[T_Time]
  ): Values[(SrcId, TxTransform)] = Nil
}