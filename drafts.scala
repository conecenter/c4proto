







object Test {
/*
  class Change
  case class A(id: String, description: String)
  //case class B(id: String, description: String)

  case class World(aById: Map[String,A], aByDescription: Map[String,B])

  def keys(obj: A): Seq[(,)] =

  def reduce(world: World, next: A): World = {
    val prevOpt = world.aById.get(next.id)

  }
  ////
*/val test = {
    def f(a: ⇒Int): Int = 0
    lazy val a = f(b)
    lazy val b = 9
    0
  }
  {
    case class A[D](v: Option[D])
    case class B[+D](v: Option[D])
    def c[AM,BM](i: Map[AM,BM])(implicit a:A[AM], b: B[BM]=B(None)) = println(i,a,b)
    implicit val a = A[Int](Some(1))
    implicit val b = B[Int](Some(2))
    implicit val bs = B[String](Some("BS"))
    c(Map[Int,Long]())
    c(Map[Int,Int]())
  }

  {
    //case class A[B,C,D](b: D[B])

  }








  //val aggregator = new Aggregator



  //aggregator.join

}

import Types._

class MyReduction(implicit indexFactory: IndexFactory, eventSource: EventSource){
  import indexFactory._
  implicit lazy val void: Index[Types.SrcId,Void] = ???
  implicit lazy val newRawChildNode = createOriginalIndex[RawChildNode]
  implicit lazy val newRawParentNode = createOriginalIndex[RawParentNode]
  implicit lazy val newChildNodeByParentJoin = new ChildNodeByParentJoin
  implicit lazy val newParentNodeWithChildrenJoin = new ParentNodeWithChildrenJoin
  implicit lazy val newChildNodeByParent = createJoinMapIndex[RawChildNode,Void,ChildNodeByParent]
  implicit lazy val newParentNodeWithChildren = createJoinMapIndex[ChildNodeByParent,RawParentNode,ParentNodeWithChildren]
}

case class RawChildNode(srcId: SrcId, parentSrcId: SrcId, caption: String)
case class RawParentNode(srcId: SrcId, caption: String)
case class ChildNodeByParent(srcId: SrcId, child: RawChildNode)
case class ParentNodeWithChildren(srcId: SrcId, children: List[RawChildNode])
/*
case class World[S](implicit
  rawChildNode: Index[S,SrcId,RawChildNode],
  rawParentNode: Index[S,SrcId,RawParentNode],
  childNodeByParent: Index[S,SrcId,ChildNodeByParent],
  parentNodeWithChildren: Index[S,SrcId,ParentNodeWithChildren]
)*/
class ChildNodeByParentJoin extends Join[RawChildNode,Void,ChildNodeByParent] {
  def join(rawChildNode: Values[RawChildNode], void: Values[Void]): Values[ChildNodeByParent] =
    rawChildNode.map(child ⇒ ChildNodeByParent(srcId=child.parentSrcId,child=child))
}
class ParentNodeWithChildrenJoin extends Join[ChildNodeByParent,RawParentNode,ParentNodeWithChildren] {
  def join(childNodeByParent: Values[ChildNodeByParent], rawParentNode: Values[RawParentNode]): Values[ParentNodeWithChildren] = {
    rawParentNode.map(parent ⇒
      ParentNodeWithChildren(parent.srcId, childNodeByParent.map(_.child).toList)
    )
  }
}


////

trait Void
object Types {
  type SrcId = String
  type Values[V] = Seq[V]//Map[V,Int] //List[Counted[V]]
}
trait EventSource
/*

trait Reduction[W] {
  def next: W
}
trait Keys[V]
*/
// moment -> mod/index -> key/srcId -> value -> count
trait Lens[From,To] {
  def get(from: From): Option[To]
  def set(from: From, to: Option[To]): From
}
class World(val value: Map[Object,Object])
trait Index[K,V] {
  def need(prev: World, next: World): World
}
trait Join[T1,T2,R] {
  def join(a1: Values[T1], a2: Values[T2]): Values[R]
}
trait IndexFactory {
  def createJoinMapIndex[T1, T2, R](implicit
    rejoin: Join[T1, T2, R], index1: Index[SrcId, T1], index2: Index[SrcId, T2]
  ): Index[SrcId, R]
  def createOriginalIndex[R](implicit eventSource: EventSource): Index[SrcId, R]
}
  //def valuesToSeq[T](values: Values[T]): Seq[T]
  //def values[T](values: Seq[T]): Values[T]

////

class IndexFactoryImpl extends IndexFactory {
  def createJoinMapIndex[T1, T2, R](implicit
      rejoin: Join[T1, T2, R],
      index1: Index[SrcId, T1], index2: Index[SrcId, T2]
  ) = new JoinMapIndex
  override def createOriginalIndex[R](implicit eventSource: EventSource) =
    new OriginalIndex
}

class OriginalIndex[R] extends Index[SrcId, R] {
  def need(prev: World, next: World): World = {
    ???
  }
}

class JoinMapIndex[T1, T2, R](implicit
  rejoin: Join[T1, T2, R],
    index1: Index[SrcId, T1], index2: Index[SrcId, T2],
    worldLens: Lens[World, IndexMap[SrcId, R]] = new WorldLensImpl
) extends Index[SrcId, R] {
  def need(prev: World, next0: World): World = if(worldLens.get(next0).nonEmpty) next else {
    val next2 = index2.need(prev, index1.need(prev, next))
    val next3 = ???
    worldLens.set(world,next3)
  }
}

case class IndexMap[K,V](value: Map[K,Map[V,Int]], changed: Set[K])

class WorldLensImpl[To<:Object] extends Lens[World,To] {
  def get(from: World) = from.value.get(this).asInstanceOf[Option[To]]
  def set(from: World, to: Option[To]) =
    new World(if(to.isEmpty) from.value - this else from.value + (this→to.get))
}

////

trait WorldInnerExpression[T1, T2, R] {
  def calculate(prev: Option[R], a1: Option[T1], a2: Option[T2]): R
}

trait WorldOuterExpression[T1, T2, R] {
  def need(prev: World, next: World): World
}


class WorldOuterExpressionImpl[T1, T2, R](
    expr1: T1, expr2: T2
) {
  def need(prevWorld: World, nextWorld: World): World = {
    if(nextWorld.contains(this)){ return nextWorld }
    val nextWorld2 = expr2.need(prevWorld, expr1.need(prevWorld, nextWorld))

    prevWorld.get(this) nextWorld2

    val nextValue

     + (this→)
    val next3 = ???
    worldLens.set(world,next3)

  }
}