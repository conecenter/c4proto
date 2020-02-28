package ee.cone.c4actor

import ee.cone.c4actor.Killing.KillerId
import ee.cone.c4actor.LifeTypes.Alive
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, assemble, by, c4assemble, c4multiAssemble, distinct}
import ee.cone.c4di.c4

@c4("MortalFactoryCompApp") case class MortalFactoryImpl(factory: MortalAssembleFactory) extends MortalFactory {
  def apply[P <: Product](cl: Class[P]): Assemble = factory.create(cl)
}

case class Killing(hash: SrcId, ev: LEvent[Product])
object Killing {
  type KillerId = SrcId
}

@c4multiAssemble class MortalAssembleBase[D_Node<:Product](classOfMortal: Class[D_Node])(
  anUUIDUtil: IdGenUtil
)   {
  def createKilling(
    key: SrcId,
    mortal: Each[D_Node],
    @distinct @by[Alive] keepAlive: Values[D_Node]
  ): Values[(KillerId,Killing)] = if(keepAlive.nonEmpty) Nil else for {
    ev <- LEvent.delete(mortal)
    killing <- Seq(Killing(anUUIDUtil.srcIdFromSrcIds(ev.srcId,ev.className/*it's just string*/),ev))
  } yield s"killing" -> killing //scaling: killing.hash.substring(0,1)
}

@c4assemble("MortalFactoryCompApp") class MortalFatalityAssembleBase   {
  def aggregateKilling(
    key: SrcId,
    @by[KillerId] killings: Values[Killing]
  ): Values[(SrcId, TxTransform)] =
    WithPK(SimpleTxTransform(s"killer/$key", killings.map(_.ev))) :: Nil
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
)   {
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
abstract class GiveLifeRule[Giver,Mortal](to: Giver => List[Mortal]) extends Product


case class GiveLifeRulesImpl() extends GiveLifeRules {
  def add[Giver, Mortal](rule: GiveLifeRule[Giver, Mortal]): GiveLifeRules = ???
}


object ToPrimaryKey {
  def apply(p: Product): SrcId = p.product Element(0) match{ case s: String => s }
}
@assemble class GiveLifeAssemble[Giver<:Product,Mortal<:Product](
  classOfGiver: Class[Giver],
  classOfMortal: Class[Mortal],
  f: Giver => List[Mortal]
)   {
  import LifeTypes.MortalSrcId
  def join(
    key: SrcId,
    givers: Values[Giver]
  ): Values[(Alive,Mortal)] =
    for(giver <- givers; mortal <- f(giver)) yield ToPrimaryKey(mortal) -> mortal
}
*/


