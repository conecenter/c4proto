package ee.cone.c4http

import ee.cone.c4proto.{Finished, Starting}

object ServerApp extends App {
  try {
    val server = new HttpGateway(8067,"localhost:9092","test-http-posts","test-http-gets")
    server.start()
    while(server.state != Finished) {
      if(server.state == Starting) println("Starting...")
      Thread.sleep(1000)
    }
  } finally System.exit(0)
}


object Test {
/*
  class Change
  case class A(id: String, description: String)
  //case class B(id: String, description: String)

  case class World(aById: Map[String,A], aByDescription: Map[String,B])

  def keys(obj: A): Seq[(,)] =

  def reduce(world: World, next: A): World = {
    val prevOpt = world.aById.get(next.id)

  }
  ////
*/{
    def f(a: ⇒Int): Int = 0
    lazy val a = f(b)
    lazy val b = 9
  }
  {
    case class A[D](v: Option[D])
    case class B[+D](v: Option[D])
    def c[AM,BM](i: Map[AM,BM])(implicit a:A[AM], b: B[BM]=B(None)) = println(i,a,b)
    implicit val a = A[Int](Some(1))
    implicit val b = B[Int](Some(2))
    implicit val bs = B[String](Some("BS"))
    c(Map[Int,Long]())
    c(Map[Int,Int]())
  }

  {
    //case class A[B,C,D](b: D[B])

  }


  trait Index[S,V]
  trait Keys[V]
  trait Values[V]

  case class MyNode()
  case class World[S](implicit a: Index[S,MyNode])

  trait Aggregator[SP,SN] {
    def join[T1,T2,R](
        rejoin: Function2[Values[T1],Values[T2],Values[R]],
        remap: R⇒Keys[R]
    )(implicit
        indexing1: Index[SN,T1],
        indexing2: Index[SN,T2],
        indexedR: Index[SP,R]
    ): Index[SN,R]
  }

  //val aggregator = new Aggregator



  //aggregator.join

}
