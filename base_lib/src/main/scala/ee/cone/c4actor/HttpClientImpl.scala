package ee.cone.c4actor

import ee.cone.c4di.c4

import java.net.http.HttpClient
import java.net.http.HttpClient.Version.HTTP_1_1
import scala.concurrent.{Future, Promise}

@c4("HttpClientApp") final class HttpClientProviderImpl(execution: Execution, config: ListConfig)(
  clientPromise: Promise[HttpClient] = Promise(),
) extends HttpClientProvider with Executable with Early {
  def run(): Unit = {
    val downgrade = config.get("C4HTTP_DOWNGRADE").exists(_.nonEmpty)
    val client = if(downgrade) HttpClient.newBuilder().version(HTTP_1_1).build() else HttpClient.newHttpClient
    execution.success(clientPromise, client)
  }
  def get: Future[HttpClient] = clientPromise.future
}
