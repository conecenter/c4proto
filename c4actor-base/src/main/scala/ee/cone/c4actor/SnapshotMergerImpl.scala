package ee.cone.c4actor

import ee.cone.c4actor.QProtocol.Update
import ee.cone.c4proto.ToByteString

/*snapshot cleanup:
docker exec ... ls -la c4/db4/snapshots
docker logs ..._snapshot_maker_1
docker exec ... bash -c 'echo "30" > db4/snapshots/.ignore'
docker restart ..._snapshot_maker_1
*/

class SnapshotMergerImpl(
  toUpdate: ToUpdate,
  snapshotMaker: SnapshotMaker,
  snapshotLoader: SnapshotLoader,
  snapshotMakerFactory: SnapshotMakerFactory,
  rawSnapshotLoaderFactory: RawSnapshotLoaderFactory,
  snapshotLoaderFactory: SnapshotLoaderFactory,
  rawWorldFactory: RichRawWorldFactory,
  reducer: RichRawWorldReducer
) extends SnapshotMerger {
  private def diff(snapshot: RawEvent, targetSnapshot: RawEvent): List[Update] = {
    val currentUpdates = toUpdate.toUpdates(List(snapshot))
    val targetUpdates = toUpdate.toUpdates(List(targetSnapshot))
    val state = currentUpdates.map(up⇒toUpdate.toKey(up)→up).toMap
    val updates = targetUpdates.filterNot{ up ⇒ state.get(toUpdate.toKey(up)).contains(up) }
    val deletes = state.keySet -- targetUpdates.map(toUpdate.toKey)
    (deletes.toList ::: updates).sortBy(toUpdate.by)
  }
  def merge(source: String, task: SnapshotTask): Context⇒Context = local ⇒ {
    val Array(_, _, timeHex) = source.split("#")
    val process = snapshotMaker.make(NextSnapshotTask(Option(ReadModelOffsetKey.of(local))), timeHex)
    val parentSnapshotMaker = snapshotMakerFactory.create(source)
    val parentSnapshotLoader = snapshotLoaderFactory.create(rawSnapshotLoaderFactory.create(source))
    val parentProcess = parentSnapshotMaker.make(task, timeHex)
    val Seq(Some(currentFullSnapshot)) = process().map(snapshotLoader.load)
    val Some(targetFullSnapshot) :: txs = parentProcess().map(parentSnapshotLoader.load)
    val diffUpdates = diff(currentFullSnapshot,targetFullSnapshot)
    (task,txs) match {
      case (t:NextSnapshotTask,Seq()) ⇒
        WriteModelKey.modify(_.enqueue(diffUpdates))(local)
      case (t:DebugSnapshotTask,Seq(Some(targetTxSnapshot))) ⇒
        val (bytes, headers) = toUpdate.toBytes(diffUpdates)
        val diffRawEvent = SimpleRawEvent(targetFullSnapshot.srcId,ToByteString(bytes), headers)
        val preTargetWorld = reducer.reduce(List(diffRawEvent))(local)
        DebugStateKey.set(Option((preTargetWorld,targetTxSnapshot)))(local)
    }
  }
}
