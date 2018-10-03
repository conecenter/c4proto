package ee.cone.c4actor

import ee.cone.c4actor.QProtocol.{Update, Updates}

import scala.collection.immutable.Map

class ContextFactory(richRawWorldFactory: RichRawWorldFactory) {
  def updated(updates: List[Update]): Context = {
    val world = richRawWorldFactory.create(Updates("0" * OffsetHexSize(), updates))
    new Context(world.injected, world.assembled, Map.empty)
  }
}

