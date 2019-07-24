/*package ee.cone.c4actor

import java.nio.charset.StandardCharsets.UTF_8

import ee.cone.c4actor.QProtocol.S_Updates
import ee.cone.c4actor.Types.{NextOffset, SharedComponentMap}
import ee.cone.c4proto.ToByteString
*/
/*
object DebugInit extends ToInject {
  def toInject: List[Injectable] = DebugKey.set(None)
}*/

/*
class DebugRichRawWorldFactory(rawSnapshot: RawSnapshot, options: RawDebugOptions, inner: RawWorldFactory) extends RawWorldFactory{
  def create(): RawWorld = rawSnapshot.loadRecent(inner.create(), None) match {
    case world: RichContext ⇒
      val data = options.load("request-event")
      if(data.isEmpty) world else {
        val event = RawEvent(world.offset, ToByteString(data))
        val injectMore = DebugKey.set(Option((world.assembled,event)))
        new DebugRichRawWorld(world, world.injected ++ injectMore.map(_.pair))
      }
  }
}
class DebugRichRawWorld(
  inner: RawWorld with RichContext,
  val injected: SharedComponentMap
) extends RawWorld with RichContext {
  def assembled: ReadModel = inner.assembled
  def offset: NextOffset = inner.offset
  def reduce(events: List[RawEvent]): RawWorld =
    new DebugRichRawWorld(inner.reduce(events) match{ case w: RawWorld with RichContext ⇒ w }, injected)
  def hasErrors: Boolean = inner.hasErrors
  def save(): String = inner.save()
}
*/