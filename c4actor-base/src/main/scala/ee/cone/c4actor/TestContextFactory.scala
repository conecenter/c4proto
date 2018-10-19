package ee.cone.c4actor

import ee.cone.c4actor.QProtocol.Update
import ee.cone.c4proto.ToByteString

import scala.collection.immutable.Map

class ContextFactory(richRawWorldFactory: RichRawWorldFactory, toUpdate: ToUpdate) {
  def updated(updates: List[Update]): Context = {
    val eWorld = richRawWorldFactory.create()
    val firstUpdate = RawEvent(eWorld.offset, ToByteString(toUpdate.toBytes(updates)))
    val world = eWorld.reduce(List(firstUpdate))
    new Context(world.injected, world.assembled, Map.empty)
  }
}

object NoRawSnapshotLoader extends RawSnapshotLoader {
  def list: List[RawSnapshot] = Nil
}
