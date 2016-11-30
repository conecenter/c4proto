
package ee.cone.c4proto
import Types._

abstract class Join1[T1, R,TK,RK](
  t1: WorldKey[Index[TK,T1]], 
  val outputWorldKey: WorldKey[Index[RK,R]]
) extends Join[R,TK,RK] {
  def join(a1: Values[T1]): Values[(RK,R)]
  //
  def joins(in: Seq[Values[Object]]): Iterable[(RK,R)] = in match {
    case Seq(a1) ⇒
      join(a1.asInstanceOf[Values[T1]])
  }
  def inputWorldKeys: Seq[WorldKey[Index[TK,Object]]] =
    Seq(t1).asInstanceOf[Seq[WorldKey[Index[TK,Object]]]]
}

abstract class Join2[T1, T2, R,TK,RK](
  t1: WorldKey[Index[TK,T1]], t2: WorldKey[Index[TK,T2]], 
  val outputWorldKey: WorldKey[Index[RK,R]]
) extends Join[R,TK,RK] {
  def join(a1: Values[T1], a2: Values[T2]): Values[(RK,R)]
  //
  def joins(in: Seq[Values[Object]]): Iterable[(RK,R)] = in match {
    case Seq(a1, a2) ⇒
      join(a1.asInstanceOf[Values[T1]], a2.asInstanceOf[Values[T2]])
  }
  def inputWorldKeys: Seq[WorldKey[Index[TK,Object]]] =
    Seq(t1, t2).asInstanceOf[Seq[WorldKey[Index[TK,Object]]]]
}

abstract class Join3[T1, T2, T3, R,TK,RK](
  t1: WorldKey[Index[TK,T1]], t2: WorldKey[Index[TK,T2]], t3: WorldKey[Index[TK,T3]], 
  val outputWorldKey: WorldKey[Index[RK,R]]
) extends Join[R,TK,RK] {
  def join(a1: Values[T1], a2: Values[T2], a3: Values[T3]): Values[(RK,R)]
  //
  def joins(in: Seq[Values[Object]]): Iterable[(RK,R)] = in match {
    case Seq(a1, a2, a3) ⇒
      join(a1.asInstanceOf[Values[T1]], a2.asInstanceOf[Values[T2]], a3.asInstanceOf[Values[T3]])
  }
  def inputWorldKeys: Seq[WorldKey[Index[TK,Object]]] =
    Seq(t1, t2, t3).asInstanceOf[Seq[WorldKey[Index[TK,Object]]]]
}
