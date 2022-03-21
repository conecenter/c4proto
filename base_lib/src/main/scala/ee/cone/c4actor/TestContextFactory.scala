package ee.cone.c4actor

import ee.cone.c4di.c4
import ee.cone.c4proto.ToByteString
import ee.cone.c4actor.QProtocol._
import ee.cone.c4assemble.Types.emptyReadModel

import scala.collection.immutable.Map

@c4("TestVMRichDataCompApp") final class ContextFactoryImpl(
  reducer: RichRawWorldReducer,
  toUpdate: ToUpdate,
) extends ContextFactory {
  def updated(updates: List[N_Update]): Context = {
    val updateFromSeq = updates.map(toUpdate.toUpdateFrom(_,Nil))
    val (bytes, headers) = toUpdate.toBytes(updateFromSeq)
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
