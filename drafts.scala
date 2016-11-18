







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

class MyReduction(world: World[Old])(implicit aggregator: Aggregator, events: Events)
  extends Reduction[World[New]]
{
  import aggregator._
  import world._
  implicit lazy val void: Index[New,Types.SrcId,Void] = ???
  implicit private lazy val newRawChildNode = reduceOriginal[RawChildNode]
  implicit private lazy val newRawParentNode = reduceOriginal[RawParentNode]
  implicit private lazy val newChildNodeByParent = joinMap(new ChildNodeByParentJoin)
  implicit private lazy val newParentNodeWithChildren = joinMap(new ParentNodeWithChildrenJoin)
  def next: World[New] = World[New]()
}

case class RawChildNode(srcId: SrcId, parentSrcId: SrcId, caption: String)
case class RawParentNode(srcId: SrcId, caption: String)
case class ChildNodeByParent(srcId: SrcId, child: RawChildNode)
case class ParentNodeWithChildren(srcId: SrcId, children: List[RawChildNode])
case class World[S](implicit
  rawChildNode: Index[S,SrcId,RawChildNode],
  rawParentNode: Index[S,SrcId,RawParentNode],
  childNodeByParent: Index[S,SrcId,ChildNodeByParent],
  parentNodeWithChildren: Index[S,SrcId,ParentNodeWithChildren]
)
class ChildNodeByParentJoin(implicit aggregator: Aggregator) extends Join[RawChildNode,Void,ChildNodeByParent] {
  import aggregator._
  def join(rawChildNode: Values[RawChildNode], void: Values[Void]): Values[ChildNodeByParent] =
    rawChildNode.map(child ⇒ ChildNodeByParent(srcId=child.parentSrcId,child=child))
}
class ParentNodeWithChildrenJoin(implicit aggregator: Aggregator) extends Join[ChildNodeByParent,RawParentNode,ParentNodeWithChildren] {
  import aggregator._
  def join(childNodeByParent: Values[ChildNodeByParent], rawParentNode: Values[RawParentNode]): Values[ParentNodeWithChildren] = {
    rawParentNode.map(parent ⇒
      ParentNodeWithChildren(parent.srcId, childNodeByParent.map(_.child).toList)
    )
  }
}


////

case class Counted[V](value: V, count: Int)
trait Void
object Types {
  type SrcId = String
  type Values[V] = Seq[V]//Map[V,Int] //List[Counted[V]]
}
trait Old
trait New
trait Events
trait Reduction[W] {
  def next: W
}
trait Index[S,K,V]
trait Keys[V]
trait Join[T1,T2,R] {
  def join(a1: Values[T1], a2: Values[T2]): Values[R]
}
trait Aggregator {
  def joinMap[T1,T2,R](rejoin: Join[T1,T2,R])(implicit
    index1: Index[New,SrcId,T1],
    index2: Index[New,SrcId,T2],
    indexPrev: Index[Old,SrcId,R]
  ): Index[New,SrcId,R]
  def reduceOriginal[R](implicit indexPrev: Index[Old,SrcId,R], events: Events): Index[New,SrcId,R]
  //def valuesToSeq[T](values: Values[T]): Seq[T]
  //def values[T](values: Seq[T]): Values[T]
}