package ee.cone.c4proto

import ee.cone.c4proto.Types._

case class RawChildNode(srcId: SrcId, parentSrcId: SrcId, caption: String)
case class RawParentNode(srcId: SrcId, caption: String)
case object ChildNodeByParent extends IndexWorldKey[SrcId,RawChildNode]
case class ParentNodeWithChildren(caption: String, children: List[RawChildNode])

class ChildNodeByParentJoin extends Join2(
  BySrcId(classOf[RawChildNode]), VoidBy[SrcId](), ChildNodeByParent
) {
  def join(rawChildNode: Values[RawChildNode], void: Values[Unit]): Values[(SrcId,RawChildNode)] =
    rawChildNode.map(child ⇒ child.parentSrcId → child)
  def sort(nodes: Iterable[RawChildNode]): List[RawChildNode] =
    nodes.toList.sortBy(_.srcId)
}
class ParentNodeWithChildrenJoin extends Join2(
  ChildNodeByParent, BySrcId(classOf[RawParentNode]), BySrcId(classOf[ParentNodeWithChildren])
) {
  def join(
    childNodeByParent: Values[RawChildNode],
    rawParentNode: Values[RawParentNode]
  ): Values[(SrcId,ParentNodeWithChildren)] = {
    rawParentNode.map(parent ⇒
      parent.srcId → ParentNodeWithChildren(parent.caption, childNodeByParent)
    )
  }
  def sort(nodes: Iterable[ParentNodeWithChildren]): List[ParentNodeWithChildren] =
    if(nodes.size <= 1) nodes.toList else throw new Exception("PK")
}



  //lazy val findAdapter = new FindAdapter()
  //lazy val qRecords = new QRecords(findAdapter)


object AssemblerTestApp extends App {
  val indexFactory = new IndexFactoryImpl
  import indexFactory._
  val handlerLists = new CoHandlerListsImpl(()⇒
    createJoinMapIndex(new ChildNodeByParentJoin) ::
    createJoinMapIndex(new ParentNodeWithChildrenJoin) ::
    Nil
  )
  val reducer = new ReducerImpl(handlerLists)()()




}
