package ee.cone.c4vdom_impl

import ee.cone.c4vdom.VDomValue

case class DoSetPair(value: VDomValue) extends VPair {
  def jsonKey = "$set"
  def sameKey(other: VPair) = Never()
  def withValue(value: VDomValue) = Never()
}

class DiffImpl(createMapValue: List[VPair]=>MapVDomValue, wasNoValue: WasNoVDomValue) extends Diff {
  private def set(value: VDomValue) = Some(createMapValue(DoSetPair(value)::Nil))
  def diff(prevValue: VDomValue, currValue: VDomValue): Option[MapVDomValue] = prevValue match {
    case p: MapVDomValue => currValue match {
      case n: MapVDomValue =>
        var previous = p.pairs
        var current  = n.pairs
        var res: List[VPair] = Nil
        while(current.nonEmpty){
          if(previous.isEmpty || !current.head.sameKey(previous.head))
            previous = current.head.withValue(wasNoValue) :: previous
          val d = diff(previous.head.value, current.head.value)
          if (d.nonEmpty) res = current.head.withValue(d.get) :: res
          previous = previous.tail
          current = current.tail
        }
        if(previous.nonEmpty) set(n)
        else if(res.nonEmpty) Some(createMapValue(res))
        else None
      case n => set(currValue)
    }
    case p if p == currValue => None
    case p => set(currValue)
  }
}
