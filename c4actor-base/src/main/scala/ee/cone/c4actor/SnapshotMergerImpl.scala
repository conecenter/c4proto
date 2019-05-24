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
  remoteSnapshotUtil: RemoteSnapshotUtil,
  rawSnapshotLoaderFactory: RawSnapshotLoaderFactory,
  snapshotLoaderFactory: SnapshotLoaderFactory,
  reducer: RichRawWorldReducer,
  signer: Signer[SnapshotTask]
) extends SnapshotMerger {
  private def diff(snapshot: RawEvent, targetSnapshot: RawEvent): List[Update] = {
    val currentUpdates = toUpdate.toUpdates(List(snapshot))
    val targetUpdates = toUpdate.toUpdates(List(targetSnapshot))
    val state = currentUpdates.map(up⇒toUpdate.toKey(up)→up).toMap
    val updates = targetUpdates.filterNot{ up ⇒ state.get(toUpdate.toKey(up)).contains(up) }
    val deletes = (state.keySet -- targetUpdates.map(toUpdate.toKey)).map(state)
    (deletes.toList ::: updates).sortBy(toUpdate.toKey)
  }
  def merge(baseURL: String, signed: String): Context⇒Context = local ⇒ {
    val task = signer.retrieve(check = false)(Option(signed)).get
    val parentProcess = remoteSnapshotUtil.request(baseURL,signed)
    val rawSnapshot = snapshotMaker.make(NextSnapshotTask(Option(reducer.reduce(Option(local),Nil).offset)))
    val parentSnapshotLoader = snapshotLoaderFactory.create(rawSnapshotLoaderFactory.create(baseURL))
    val Seq(Some(currentFullSnapshot)) = rawSnapshot.map(snapshotLoader.load)
    val targetRawSnapshot :: txs = parentProcess()
    val Some(targetFullSnapshot) = parentSnapshotLoader.load(targetRawSnapshot)
    val diffUpdates = diff(currentFullSnapshot,targetFullSnapshot)
    task match {
      case t:NextSnapshotTask ⇒
        assert(t.offsetOpt.isEmpty || txs.isEmpty)
        WriteModelKey.modify(_.enqueue(diffUpdates))(local)
      case t:DebugSnapshotTask ⇒
        val (bytes, headers) = toUpdate.toBytes(diffUpdates)
        val diffRawEvent = SimpleRawEvent(targetFullSnapshot.srcId,ToByteString(bytes), headers)
        val preTargetWorld = reducer.reduce(Option(local),List(diffRawEvent))
        val Seq(Some(targetTxSnapshot)) = txs.map(parentSnapshotLoader.load)
        DebugStateKey.set(Option((preTargetWorld,targetTxSnapshot)))(local)
    }
  }
}
