package ee.cone.c4vdom_impl

import ee.cone.c4vdom.VDomValue

import scala.annotation.tailrec

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
        @tailrec def iter(previous: List[VPair], current: List[VPair], res: List[VPair]): Option[MapVDomValue] =
          if(current.nonEmpty){
            val nPrevious =
              if(previous.isEmpty || !current.head.sameKey(previous.head))
                current.head.withValue(wasNoValue) :: previous
              else previous
            val d = diff(nPrevious.head.value, current.head.value)
            val nRes = if (d.nonEmpty) current.head.withValue(d.get) :: res else res
            iter(nPrevious.tail, current.tail, nRes)
          }
          else if(previous.nonEmpty) set(n)
          else if(res.nonEmpty) Some(createMapValue(res))
          else None
        iter(p.pairs, n.pairs, Nil)
      case n => set(currValue)
    }
    case p if p == currValue => None
    case p => set(currValue)
  }
}
