package ee.cone.c4ui

import ee.cone.c4actor._
import ee.cone.c4assemble.Single
import ee.cone.c4di.c4
import ee.cone.c4vdom.{ChildPair, OfDiv}

@c4("AccessViewApp") final class InnerAccessViewRegistry(accessViews: List[GeneralAccessView])(
  val values: Map[String,GeneralAccessView] = CheckedMap(accessViews.map(v => v.valueClass.getName -> v))
)

@c4("AccessViewApp") final class AccessViewRegistryImpl(
  inner: DeferredSeq[InnerAccessViewRegistry]
) extends AccessViewRegistry {
  def view[P](access: Access[P]): Context=>List[ChildPair[OfDiv]] = local => {
    val general: GeneralAccessView =
      Single(inner.value).values(access.initialValue.getClass.getName)
    general.asInstanceOf[AccessView[P]].view(access)(local)
  }
}
