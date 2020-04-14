package ee.cone.c4actor.tests

import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.{AssembledContext, TxTransform}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.time._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.c4assemble
import ee.cone.c4di.c4

@c4time(0x1337) case object TestTestTime extends CurrentTime(10L)

@c4assemble class TestTimeAssembleBase {
  def test(
    srcId: SrcId,
    fb: Each[S_Firstborn],
    @time(TestTestTime) time: Each[T_Time]
  ): Values[(SrcId, TxTransform)] = Nil
}

@c4 final class TestTime(timeGetters: TimeGetters) {
  val test: AssembledContext => Option[T_Time] = timeGetters(TestTestTime).ofA
}