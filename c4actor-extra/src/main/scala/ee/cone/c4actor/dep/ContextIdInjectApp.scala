package ee.cone.c4actor.dep

import ee.cone.c4actor.dep.ContextTypes.ContextId
import ee.cone.c4actor.dep.request.ContextIdRequestProtocol.ContextIdRequest

import scala.collection.immutable.Map

trait ContextIdInjectApp extends DepAskFactoryApp {
  private lazy val sessionIdAsk: DepAsk[ContextIdRequest, ContextId] = depAskFactory.forClasses(classOf[ContextIdRequest], classOf[ContextId])

  def inject[ReasonIn <: Product](reason: DepAsk[ReasonIn, _], handler: ReasonIn ⇒ ContextId): DepHandler =
    sessionIdAsk.add(reason, (rq: ReasonIn) ⇒ Map(ContextIdRequest() → handler(rq)))
}
