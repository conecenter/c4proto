package ee.cone.c4http

object ServerApp extends App {
  new PostGateway(8067,"localhost:9092","test-http-requests").start()
}
