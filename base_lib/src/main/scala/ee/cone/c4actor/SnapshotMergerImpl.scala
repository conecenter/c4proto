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
) extends SnapshotMerger { // not needed
  def merge(baseURL: String, signed: String): Context=>Context = local => {
    val task = signer.retrieve(check = false)(Option(signed)).get
    val parentProcess = remoteSnapshotUtil.request(baseURL,signed)
    val parentSnapshotLoader = snapshotLoaderFactory.create(rawSnapshotLoaderFactory.create(baseURL))
    val currentFullSnapshot = differ.needCurrentSnapshot(local)
    val targetRawSnapshot :: txs = parentProcess()
    val Some(targetFullSnapshot) = parentSnapshotLoader.load(targetRawSnapshot)
    val diffUpdates = differ.diff(currentFullSnapshot, targetFullSnapshot, Set.empty)
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
  snapshotLoader: SnapshotLoader,
  updateMapUtil: UpdateMapUtil,
  snapshotPatchIgnoreRegistry: SnapshotPatchIgnoreRegistry,
) extends SnapshotDiffer {
  def diff(currentSnapshot: RawEvent, targetFullSnapshot: RawEvent, addIgnore: Set[Long]): List[N_UpdateFrom] =
    diff(currentSnapshot, toUpdate.toUpdates(List(targetFullSnapshot),"diff-to"), addIgnore)
  def diff(currentSnapshot: RawEvent, target: List[N_UpdateFrom], addIgnore: Set[Long]): List[N_UpdateFrom] =
    updateMapUtil.diff(toUpdate.toUpdates(List(currentSnapshot),"diff-from"), target, snapshotPatchIgnoreRegistry.ignore ++ addIgnore)
  def needCurrentSnapshot: Context=>RawEvent = local => {
    val rawSnapshot = snapshotMaker.make(NextSnapshotTask(Option(getOffset.of(local))))
    val Seq(Some(currentFullSnapshot)) = rawSnapshot.map(snapshotLoader.load)
    currentFullSnapshot
  }
}
