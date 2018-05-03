package ee.cone.c4actor

import ee.cone.c4actor.utils.GeneralizedOrigFactory

object Laboratory {
  def main(args: Array[String]): Unit = {
    val test1 = (2,Test(2))
    val hash = test1._2.hashCode()
    val test2 = (1,test1._2.copy())
    val hash2 = test2._2.hashCode
  }

  case class Test(a: Int){
    lazy val hashLazy = {
      println("Here")
      runtime.ScalaRunTime._hashCode(this)}

    override def hashCode: Int = hashLazy

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

