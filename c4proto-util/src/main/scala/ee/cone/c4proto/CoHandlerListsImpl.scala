package ee.cone.c4proto

class CoHandlerListsImpl(getMix: ()=>Object) extends CoHandlerLists {
  def list[Item](ev: EventKey[Item]): List[Item] = {
    val res = value.getOrElse(ev,Nil).asInstanceOf[List[Item]]
    //println(ev, res.size)
    res
  }
  private lazy val value = {
    val cl = classOf[CoHandlerProvider]
    val mix = getMix()
    mix.getClass.getMethods.filter(m => cl.isAssignableFrom(m.getReturnType))
      .sortBy(_.getName).map(_.invoke(mix).asInstanceOf[CoHandlerProvider]).distinct
      .flatMap(_.handlers).toList
      .map{ case h: CoHandler[_] ⇒ h }
      .groupBy(_.on).mapValues(_.map(_.handle))
  }
  def single[Item](ev: EventKey[Item], fail: ()⇒Item): Item = list(ev) match {
    case Nil ⇒
      //println(s"single failed $ev")
      fail()
    case h :: Nil =>
      //println(s"single found  $ev")
      h
    case l => throw new Exception(s"${l.size} handlers for $ev")
  }
}

trait BaseConnectionMix extends CoMixBase {
  lazy val handlerLists = new CoHandlerListsImpl(()⇒this)
}