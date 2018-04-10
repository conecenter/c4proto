package ee.cone.c4actor.dep

case class DepOuterResponse(request: DepOuterRequest, value: Option[_])

case class DepInnerResponse(request: DepInnerRequest, value: Option[_])
