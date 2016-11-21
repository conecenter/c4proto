package ee.cone.c4proto

// common interfaces to communicate between decoupled components;
trait EventKey[Item]
trait BaseCoHandler
case class CoHandler[Item](on: EventKey[Item])(val handle: Item)
  extends BaseCoHandler

trait CoHandlerLists {
  def list[Item](ev: EventKey[Item]): List[Item]
  def single[Item](ev: EventKey[Item], fail: ()⇒Item): Item
}
/*
* include public lazy val with provider into mix,
* then provider will be found (using reflection) and its handlers used
* */
trait CoHandlerProvider {
  def handlers: List[BaseCoHandler]
}
trait CoMixBase {
  def handlerLists: CoHandlerLists
}
