
package ee.cone.c4actor_branch

import ee.cone.c4actor.Types.{LEvents, SrcId}
import ee.cone.c4actor._
import ee.cone.c4actor_branch.BranchProtocol._
import ee.cone.c4actor_branch.BranchTypes.BranchKey
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4di.c4
import ee.cone.c4proto.ToByteString

@c4("BranchApp") final class BranchOperationsImpl(
  registry: QAdapterRegistry, idGenUtil: IdGenUtil, getS_BranchResults: GetByPK[S_BranchResults]
) extends BranchOperations {
  def toSeed(value: Product): N_BranchResult = {
    val valueAdapter = registry.byName(value.getClass.getName)
    val bytes = ToByteString(valueAdapter.encode(value))
    val id = idGenUtil.srcIdFromSerialized(valueAdapter.id, bytes)
    N_BranchResult(id, valueAdapter.id, bytes)
  }
  def collect[T<:Product](seeds: Seq[N_BranchResult], cl: Class[T]): Seq[T] = {
    val adapter = registry.byName(cl.getName)
    seeds.collect{ case u if u.valueTypeId == adapter.id => adapter.decode(u.value).asInstanceOf[T] }
  }
  def saveChanges(local: Context, branchKey: String, seeds: List[N_BranchResult]): LEvents = {
    val res = S_BranchResults(branchKey,seeds)
    if(getS_BranchResults.ofA(local).get(branchKey).contains(res)) Nil else LEvent.update(res)
  }
  def purge(local: Context, branchKey: String): LEvents =
    getS_BranchResults.ofA(local).get(branchKey).toSeq.flatMap(LEvent.delete)
}

case class BranchTaskImpl(branchKey: String, product: Product) extends BranchTask {
  def relocate(to: SrcId): Context => Context = throw new Exception
}

@c4assemble("BranchApp") class BranchAssembleBase(registry: QAdapterRegistry){
  def mapBranchSeedsByChild(
    key: SrcId,
    branchResult: Each[S_BranchResults]
  ): Values[(BranchKey, N_BranchResult)] = for (child <- branchResult.children) yield WithPK(child)

  def joinBranchTask(
    key: SrcId,
    @distinct @by[BranchKey] seeds: Values[N_BranchResult]
  ): Values[(SrcId, BranchTask)] =
    for (seed <- seeds.take(1); adapter <- registry.byId.get(seed.valueTypeId))
      yield WithPK(BranchTaskImpl(key, adapter.decode(seed.value.toByteArray)))
}
