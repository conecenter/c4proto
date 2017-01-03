
package ee.cone.c4actor
import Types._

abstract class Join1[T1, R,TK,RK](
  t1: WorldKey[Index[TK,T1]], 
  val outputWorldKey: WorldKey[Index[RK,R]]
) extends Join[R,TK,RK] {
  def join(a1: Values[T1]): Values[(RK,R)]
  //
  def joins(key: TK, in: Seq[Values[Object]]): Iterable[(RK,R)] = in match {
    case Seq(Nil) => Nil
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
  def joins(key: TK, in: Seq[Values[Object]]): Iterable[(RK,R)] = in match {
    case Seq(Nil, Nil) => Nil
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
  def joins(key: TK, in: Seq[Values[Object]]): Iterable[(RK,R)] = in match {
    case Seq(Nil, Nil, Nil) => Nil
    case Seq(a1, a2, a3) ⇒
      join(a1.asInstanceOf[Values[T1]], a2.asInstanceOf[Values[T2]], a3.asInstanceOf[Values[T3]])
  }
  def inputWorldKeys: Seq[WorldKey[Index[TK,Object]]] =
    Seq(t1, t2, t3).asInstanceOf[Seq[WorldKey[Index[TK,Object]]]]
}

abstract class Join4[T1, T2, T3, T4, R,TK,RK](
  t1: WorldKey[Index[TK,T1]], t2: WorldKey[Index[TK,T2]], t3: WorldKey[Index[TK,T3]], t4: WorldKey[Index[TK,T4]], 
  val outputWorldKey: WorldKey[Index[RK,R]]
) extends Join[R,TK,RK] {
  def join(a1: Values[T1], a2: Values[T2], a3: Values[T3], a4: Values[T4]): Values[(RK,R)]
  //
  def joins(key: TK, in: Seq[Values[Object]]): Iterable[(RK,R)] = in match {
    case Seq(Nil, Nil, Nil, Nil) => Nil
    case Seq(a1, a2, a3, a4) ⇒
      join(a1.asInstanceOf[Values[T1]], a2.asInstanceOf[Values[T2]], a3.asInstanceOf[Values[T3]], a4.asInstanceOf[Values[T4]])
  }
  def inputWorldKeys: Seq[WorldKey[Index[TK,Object]]] =
    Seq(t1, t2, t3, t4).asInstanceOf[Seq[WorldKey[Index[TK,Object]]]]
}

abstract class Join5[T1, T2, T3, T4, T5, R,TK,RK](
  t1: WorldKey[Index[TK,T1]], t2: WorldKey[Index[TK,T2]], t3: WorldKey[Index[TK,T3]], t4: WorldKey[Index[TK,T4]], t5: WorldKey[Index[TK,T5]], 
  val outputWorldKey: WorldKey[Index[RK,R]]
) extends Join[R,TK,RK] {
  def join(a1: Values[T1], a2: Values[T2], a3: Values[T3], a4: Values[T4], a5: Values[T5]): Values[(RK,R)]
  //
  def joins(key: TK, in: Seq[Values[Object]]): Iterable[(RK,R)] = in match {
    case Seq(Nil, Nil, Nil, Nil, Nil) => Nil
    case Seq(a1, a2, a3, a4, a5) ⇒
      join(a1.asInstanceOf[Values[T1]], a2.asInstanceOf[Values[T2]], a3.asInstanceOf[Values[T3]], a4.asInstanceOf[Values[T4]], a5.asInstanceOf[Values[T5]])
  }
  def inputWorldKeys: Seq[WorldKey[Index[TK,Object]]] =
    Seq(t1, t2, t3, t4, t5).asInstanceOf[Seq[WorldKey[Index[TK,Object]]]]
}
