package ee.cone.c4actor

import ee.cone.c4actor.LifeTypes.Alive
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble, by}

case class MortalFactoryImpl(anUUIDUtil: UUIDUtil) extends MortalFactory {
  def apply[P <: Product](cl: Class[P]): Assemble = new MortalAssemble(cl,anUUIDUtil)
}

case class Killing(hash: SrcId, ev: LEvent[Product])

@assemble class MortalAssemble[Node<:Product](
  classOfMortal: Class[Node],
  anUUIDUtil: UUIDUtil
) extends Assemble {
  type KillingId = SrcId

  def createKilling(
    key: SrcId,
    mortals: Values[Node],
    @by[Alive] keepAlive: Values[Node]
  ): Values[(KillingId,Killing)] = for {
    mortal ← mortals if keepAlive.isEmpty
    ev ← LEvent.delete(mortal)
    killing ← Seq(Killing(anUUIDUtil.srcIdFromSrcIds(ev.srcId,ev.className/*it's just string*/),ev))
  } yield killing.hash.substring(0,1) → killing

  def aggregateKilling(
    key: SrcId,
    @by[KillingId] killings: Values[Killing]
  ): Values[(SrcId,TxTransform)] = for {
    killing ← killings
  } yield WithPK(SimpleTxTransform(s"kill/$key", killings.map(_.ev)))
}

case class SimpleTxTransform[P<:Product](srcId: SrcId, todo: Values[LEvent[P]]) extends TxTransform {
  def transform(local: Context): Context = TxAdd(todo)(local)
}

/*
object LifeTypes {
  type MortalSrcId = SrcId
}

@assemble class LifeAssemble[Parent,Child](
  classOfParent: Class[Parent],
  classOfChild: Class[Child]
) extends Assemble {
  import LifeTypes.ParentSrcId
  def join(
    key: SrcId,
    parents: Values[Parent],
    @by[ParentSrcId] children: Values[Child]
  ): Values[(SrcId,TxTransform)] = ???
}
*/

/*
trait GiveLifeRulesApp {
  def emptyGiveLifeRules: GiveLifeRules
  def giveLifeRules: GiveLifeRules = emptyGiveLifeRules
}
trait GiveLifeRules {
  def add[Giver,Mortal](rule: GiveLifeRule[Giver,Mortal]): GiveLifeRules
}
abstract class GiveLifeRule[Giver,Mortal](to: Giver ⇒ List[Mortal]) extends Product


case class GiveLifeRulesImpl() extends GiveLifeRules {
  def add[Giver, Mortal](rule: GiveLifeRule[Giver, Mortal]): GiveLifeRules = ???
}


object ToPrimaryKey {
  def apply(p: Product): SrcId = p.product Element(0) match{ case s: String ⇒ s }
}
@assemble class GiveLifeAssemble[Giver<:Product,Mortal<:Product](
  classOfGiver: Class[Giver],
  classOfMortal: Class[Mortal],
  f: Giver ⇒ List[Mortal]
) extends Assemble {
  import LifeTypes.MortalSrcId
  def join(
    key: SrcId,
    givers: Values[Giver]
  ): Values[(Alive,Mortal)] =
    for(giver ← givers; mortal ← f(giver)) yield ToPrimaryKey(mortal) → mortal
}
*/


