package ee.cone.c4actor.dep.request

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.{AssemblesApp, WithPK}
import ee.cone.c4actor.dep.{DepInnerRequest, DepOuterRequest}
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, Single, assemble, by}

trait DepOuterDamToDepInnerRequestApp extends AssemblesApp {
  override def assembles: List[Assemble] = new DepInnerRequestProducer :: super.assembles
}

@assemble class DepInnerRequestProducer extends Assemble{
  type OuterRqByInnerSrcId = SrcId

  def DepOuterDamToDepInnerRequest
  (
    outerRqId: SrcId,
    @by[OuterRqByInnerSrcId] outers: Values[DepOuterRequest] //TODO move it to main DepAssemble
  ): Values[(SrcId, DepInnerRequest)] = {
    val inner: DepInnerRequest = Single(outers.map(_.innerRequest).distinct)
    WithPK(inner) :: Nil
  }
}
