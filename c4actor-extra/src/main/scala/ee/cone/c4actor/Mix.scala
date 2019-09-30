package ee.cone.c4actor

import ee.cone.c4proto.{Component, ComponentsApp, Protocol}

trait ProtocolsApp extends ComponentsApp {
  override def components: List[Component] =
    protocols.distinct.flatMap(_.components) :::
      super.components

  def protocols: List[Protocol] = Nil
}
