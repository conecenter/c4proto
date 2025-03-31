package ee.cone.c4actor

import ee.cone.c4di.c4
import ee.cone.c4proto.ToByteString
import ee.cone.c4actor.QProtocol._

@c4("TestVMRichDataCompApp") final class ContextFactoryImpl(
  reducer: RichRawWorldReducer,
  toUpdate: ToUpdate,
  updateMapUtil: UpdateMapUtil,
) extends ContextFactory {
  def updated(updates: List[N_Update]): Context = {
    val (bytes, headers) = toUpdate.toBytes(updates.map(updateMapUtil.insert))
    val firstUpdate = SimpleRawEvent("0" * OffsetHexSize(), ToByteString(bytes), headers)
    val world = reducer.createContext(Option(firstUpdate))
    new Context(world.assembled, Map.empty)
  }
}

