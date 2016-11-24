package ee.cone.c4proto

// common interfaces to communicate between decoupled components;
trait EventKey[Item]
trait BaseCoHandler
case class CoHandler[Item](on: EventKey[Item])(val handle: Item)
  extends BaseCoHandler
trait CoHandlerLists {
  def list[Item](ev: EventKey[Item]): List[Item]
}

object CoHandlerLists {
  def apply(handlers: List[BaseCoHandler]): CoHandlerLists = {
    val value = handlers.map{ case h: CoHandler[_] ⇒ h }
      .groupBy(_.on).mapValues(_.map(_.handle))
    new CoHandlerListsImpl(value)
  }
}

class CoHandlerListsImpl(value: Map[EventKey[_],List[_]]) extends CoHandlerLists {
  def list[Item](ev: EventKey[Item]): List[Item] =
    value.getOrElse(ev,Nil).asInstanceOf[List[Item]]
}