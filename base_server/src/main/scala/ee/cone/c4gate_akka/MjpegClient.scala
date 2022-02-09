
package ee.cone.c4gate_akka

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._

import akka.NotUsed
import akka.util.ByteString
import akka.stream.{OverflowStrategy,SharedKillSwitch}
import akka.stream.scaladsl.{Flow, Keep, MergeHub, Sink, Source, BroadcastHub, RestartSource}

import akka.http.scaladsl.HttpExt
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, ResponseEntity}

import akka.http.scaladsl.model.headers.{Authorization,GenericHttpCredentials,HttpChallenge,`WWW-Authenticate`}

import ee.cone.c4di._
import ee.cone.c4actor.Execution

import com.typesafe.scalalogging.LazyLogging

case class MjpegCamConf(
  host: String, uri: String, username: String, password: String
)

@c4("AkkaGatewayApp") final class MjpegClient(
  execution: Execution,
  akkaMat: AkkaMat,
  akkaHttp: AkkaHttp,
) extends RoomFactory with LazyLogging {
  def pathPrefix: String = "/mjpeg/"

  def md5(v: String): String = okio.ByteString.encodeUtf8(v).md5().hex()

  def getAuthReq(resp: HttpResponse, uri: String, username: String, password: String): HttpRequest = {
    val Seq(HttpChallenge("Digest",realm,challengeArgs)) =
      resp.headers[`WWW-Authenticate`].flatMap(_.challenges)
    val nonce = challengeArgs("nonce")
    val ha1 = md5(s"$username:$realm:$password")
    val ha2 = md5(s"GET:$uri")
    val response = md5(s"$ha1:$nonce:$ha2")
    val cred = GenericHttpCredentials("Digest","",Map(
      "username" -> username,
      "realm" -> realm,
      "nonce" -> nonce,
      "uri" -> uri,
      "response" -> response,
    ))
    val headers = Seq(Authorization(cred))
    HttpRequest(uri=uri,headers=headers)
  }

  // custom step takes less cpu than Framing.delimiter, but works only if there are more in-frames, than out
  def jpegSource(entity: ResponseEntity): Source[ByteString,_] = {
    //println(s"AAA: ${entity.contentType}")
    val boundary = entity.contentType.mediaType.params("boundary")
    val headEnd = ByteString("\r\n\r\n")
    val boundaryOuter = ByteString(s"$boundary\r\n")
    logger.info(s"B[$boundary]")
    entity.dataBytes.scan((ByteString.empty,ByteString.empty)){(st, part) =>
      val (_,keep) = st
      val acc = keep ++ part
      val boundaryPos = acc.indexOfSlice(boundaryOuter)
      if(boundaryPos < 0) (ByteString.empty,acc)
      else (acc.take(boundaryPos), acc.drop(boundaryPos+boundaryOuter.size))
    }.collect{ case (out,_) if out.nonEmpty =>
      val headEndPos = out.indexOfSlice(headEnd)
      out.drop(headEndPos+headEnd.size)
    }
  }

  def createRoom(will: String, killSwitch: SharedKillSwitch): Flow[ByteString,ByteString,NotUsed] = {
    val commonSourcePromise = Promise[Source[ByteString,NotUsed]]
    execution.fatal{ implicit ec =>
      for{
        mat <- akkaMat.get
        http <- akkaHttp.get
        ignoredOK <- {
          val ConfLineValues = """\s*(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s*""".r
          val ConfLineValues(hostPort,uri,username,password) = will
          val ConfHostPort = """(\S+):(\S+)""".r
          val (host,port): (String,Int) = hostPort match {
            case ConfHostPort(host,portStr) => (host,portStr.toInt)
            case h => (h,80)
          }
          val connFlow = http.outgoingConnection(host,port)
          val initReqSource = Source.single(HttpRequest(uri=uri))
          val authReqSource = if(username=="-" && password=="-") initReqSource
            else initReqSource.via(connFlow).mapAsync(1){ firstResp =>
              firstResp.entity.discardBytes()(mat).future()
                .map(done=>getAuthReq(firstResp, uri, username, password))(ec)
            }
          val canFailSource = authReqSource.via(connFlow)
            .flatMapConcat{ secondResp => jpegSource(secondResp.entity) }
            .idleTimeout(4.seconds)
          val commonSource = RestartSource.withBackoff(
            minBackoff = 3.seconds, maxBackoff = 30.seconds, randomFactor = 0.2,
          )(()=>canFailSource)
            .via(killSwitch.flow)
            .toMat(BroadcastHub.sink[ByteString])(Keep.right)
            .run()(mat) //check?
          execution.success(commonSourcePromise,commonSource) // need to be before ignoredOK resolution
          commonSource.toMat(Sink.ignore)(Keep.right).run()(mat)
        }
      } yield ignoredOK
    }
    val source = Source.fromFutureSource(commonSourcePromise.future)
      .buffer(size = 2, overflowStrategy = OverflowStrategy.dropHead)
      .keepAlive(1.seconds,()=>ByteString.empty)
    val sink = Flow[ByteString].idleTimeout(5.seconds).to(Sink.ignore) // ignore stream -- so cam will work w/o client
    Flow.fromSinkAndSourceCoupled(sink,source)
  }
}
