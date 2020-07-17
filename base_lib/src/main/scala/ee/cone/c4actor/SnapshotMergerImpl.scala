package ee.cone.c4actor

import ee.cone.c4di.c4
import ee.cone.c4proto.ToByteString
import ee.cone.c4actor.QProtocol._

/*snapshot cleanup:
docker exec ... ls -la c4/db4/snapshots
docker logs ..._snapshot_maker_1
docker exec ... bash -c 'echo "30" > db4/snapshots/.ignore'
docker restart ..._snapshot_maker_1
*/

class SnapshotMergerImpl(
  toUpdate: ToUpdate,
  differ: SnapshotDiffer,
  remoteSnapshotUtil: RemoteSnapshotUtil,
  rawSnapshotLoaderFactory: RawSnapshotLoaderFactory,
  snapshotLoaderFactory: SnapshotLoaderFactory,
  reducer: RichRawWorldReducer,
  signer: SnapshotTaskSigner
) extends SnapshotMerger {
  def merge(baseURL: String, signed: String): Context=>Context = local => {
    val task = signer.retrieve(check = false)(Option(signed)).get
    val parentProcess = remoteSnapshotUtil.request(baseURL,signed)
    val parentSnapshotLoader = snapshotLoaderFactory.create(rawSnapshotLoaderFactory.create(baseURL))
    val currentFullUpdates = differ.needCurrentUpdates(local)
    val targetRawSnapshot :: txs = parentProcess()
    val Some(targetFullSnapshot) = parentSnapshotLoader.load(targetRawSnapshot)
    val targetFullUpdates = toUpdate.toUpdates(List(targetFullSnapshot))
    val diffUpdates = differ.diff(currentFullUpdates,targetFullUpdates)
    task match {
      case t:NextSnapshotTask =>
        assert(t.offsetOpt.isEmpty || txs.isEmpty)
        WriteModelKey.modify(_.enqueueAll(diffUpdates))(local)
      case t:DebugSnapshotTask =>
        val (bytes, headers) = toUpdate.toBytes(diffUpdates)
        val diffRawEvent = SimpleRawEvent(targetFullSnapshot.srcId,ToByteString(bytes), headers)
        val preTargetWorld = reducer.reduce(Option(local),List(diffRawEvent))
        val Seq(Some(targetTxSnapshot)) = txs.map(parentSnapshotLoader.load)
        DebugStateKey.set(Option((preTargetWorld,targetTxSnapshot)))(local)
    }
  }
}

@c4("ServerCompApp") final class SnapshotDifferImpl(
  toUpdate: ToUpdate,
  getOffset: GetOffset,
  snapshotMaker: SnapshotMaker,
  snapshotLoader: SnapshotLoader
) extends SnapshotDiffer {
  def diff(currentUpdates: List[N_Update], targetUpdates: List[N_Update]): List[N_Update] = {
    val state = currentUpdates.map(up=>toUpdate.toKey(up)->up).toMap
    val updates = targetUpdates.filterNot{ up => state.get(toUpdate.toKey(up)).contains(up) }
    val deletes = state.keySet -- targetUpdates.map(toUpdate.toKey)
    (deletes.toList ::: updates).sortBy(toUpdate.by)
  }
  def needCurrentUpdates: Context=>List[N_Update] = local => {
    val rawSnapshot = snapshotMaker.make(NextSnapshotTask(Option(getOffset.of(local))))
    val Seq(Some(currentFullSnapshot)) = rawSnapshot.map(snapshotLoader.load)
    toUpdate.toUpdates(List(currentFullSnapshot))
  }
}