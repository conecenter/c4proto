package ee.cone.c4actor

import ee.cone.c4actor.QProtocol.N_Update
import ee.cone.c4proto.{ToByteString}
import okio.ByteString

import scala.collection.immutable.Map

class ContextFactory(reducer: RichRawWorldReducer, toUpdate: ToUpdate) {
  def updated(updates: List[N_Update]): Context = {
    val (bytes, headers) = toUpdate.toBytes(updates)
    val firstUpdate = SimpleRawEvent("0" * OffsetHexSize(), ToByteString(bytes), headers)
    val world = reducer.reduce(None,List(firstUpdate))
    new Context(world.injected, world.assembled, world.executionContext, Map.empty)
  }
}

/*
object NoSnapshotMaker extends SnapshotMaker {
  def make(task: SnapshotTask): () => List[RawSnapshot] = throw new Exception
}

object NoRawSnapshotLoader extends RawSnapshotLoader {
  def list(subDirStr: String): List[RawSnapshot] = Nil
  def load(snapshot: RawSnapshot): ByteString = throw new Exception
}*/
