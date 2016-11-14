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

/*
object Test {

  class Change
  case class A(id: String, description: String)
  //case class B(id: String, description: String)

  case class World(aById: Map[String,A], aByDescription: Map[String,B])

  def keys(obj: A): Seq[(,)] =

  def reduce(world: World, next: A): World = {
    val prevOpt = world.aById.get(next.id)

  }

}
*/