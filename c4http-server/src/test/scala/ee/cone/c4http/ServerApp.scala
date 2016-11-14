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
