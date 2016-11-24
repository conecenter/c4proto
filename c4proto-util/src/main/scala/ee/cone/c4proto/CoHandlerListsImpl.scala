package ee.cone.c4proto

class CoHandlerListsImpl(handlers: ()=>List[BaseCoHandler]) extends CoHandlerLists {
  def list[Item](ev: EventKey[Item]): List[Item] =
    value.getOrElse(ev,Nil).asInstanceOf[List[Item]]
  private lazy val value = handlers().map{ case h: CoHandler[_] ⇒ h }
      .groupBy(_.on).mapValues(_.map(_.handle))
}
