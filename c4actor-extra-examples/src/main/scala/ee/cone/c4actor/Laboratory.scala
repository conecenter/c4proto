package ee.cone.c4actor

import ee.cone.c4actor.utils.GeneralizedOrigFactory

object Laboratory {
  def main(args: Array[String]): Unit = {
    val a = A(2, "1")
    println(a.copy(b = 2))
    println(a.copy(2))
    //println(test2(a))
  }


  case class A(b: Int, z: String)

  case class B(srcId: String, value: Option[Int])

  def test[B](implicit copy: String ⇒ B) = {
    copy("")
  }

  def test2[E <: HasCopy[E]](a: E) = {
    a.copy("")
  }

  trait HasCopy[C] {
    def copy: String ⇒ C
  }

  case object GeneralizedB extends GeneralizedOrigFactory[B](classOf[B], str ⇒ _.copy(srcId = str))

}

