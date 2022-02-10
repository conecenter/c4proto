package ee.cone.c4actor

import ee.cone.c4di.c4

import java.net.http.HttpClient
import scala.concurrent.{Future, Promise}

@c4("HttpClientApp") final class HttpClientProviderImpl(execution: Execution)(
  clientPromise: Promise[HttpClient] = Promise(),
) extends HttpClientProvider with Executable with Early {
  def run(): Unit = execution.success(clientPromise, HttpClient.newHttpClient)
  def get: Future[HttpClient] = clientPromise.future
}
