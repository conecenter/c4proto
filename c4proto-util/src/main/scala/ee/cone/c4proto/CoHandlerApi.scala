package ee.cone.c4proto

// common interfaces to communicate between decoupled components;
trait EventKey[Item]
trait BaseCoHandler
case class CoHandler[Item](on: EventKey[Item])(val handle: Item)
  extends BaseCoHandler
trait CoHandlerLists {
  def list[Item](ev: EventKey[Item]): List[Item]
}
