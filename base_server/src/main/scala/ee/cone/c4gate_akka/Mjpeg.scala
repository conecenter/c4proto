
package ee.cone.c4gate_akka

import ee.cone.c4actor.Execution

case class MjpegCamConf(
  host: String, uri: String, username: String, password: String
)

class MjpegClient(
  execution: Execution,
  akkaMat: AkkaMat,
) extends RoomFactory {
  def getAuthReq(resp: HttpResponse, uri: String, username: String, password: String): HttpRequest = {
    val Seq(HttpChallenge("Digest",realm,challengeArgs)) =
      resp.headers[`WWW-Authenticate`].flatMap(_.challenges)
    val nonce = challengeArgs("nonce")
    val ha1 = MD5(s"$username:$realm:$password")
    val ha2 = MD5(s"GET:$uri")
    val response = MD5(s"$ha1:$nonce:$ha2")
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
  def jpegSource(entity: ResponseEntity): Source[String,_] = {
    val boundary = entity.contentType.mediaType.params("boundary")
    val headEnd = ByteString("\r\n\r\n")
    val boundaryOuter = ByteString(s"--$boundary\r\n")
    entity.dataBytes.scan((ByteString.empty,ByteString.empty)){(st, part) =>
      val (_,keep) = st
      val acc = keep ++ part
      val boundaryPos = acc.indexOfSlice(boundaryOuter)
      if(boundaryPos < 0) (ByteString.empty,acc)
      else (acc.take(boundaryPos), acc.drop(boundaryPos+boundaryOuter.size))
    }.collect{ case (out,_) if out.nonEmpty =>
      val headEndPos = out.indexOfSlice(headEnd)
      out.drop(headEndPos+headEnd.size).encodeBase64.utf8String
    }
  }

  def createRoom(will: RoomConf, killSwitch: SharedKillSwitch): Flow[String,String,NotUsed] = {
    val commonSourcePromise = Promise[Source[String,NotUsed]]
    execution.fatal{ implicit ec =>
      for{
        mat <- akkaMat.get
        commonSource = {
          import will._
          val connFlow = http.connectionTo(host).http()
          val canFailSource = Source.single(HttpRequest(uri=uri))
            .via(connFlow)
            .mapAsync(1){ firstResp =>
              firstResp.entity.discardBytes()(mat).future()
                .map(done=>getAuthReq(firstResp, uri, username, password))(ec)
            }
            .via(connFlow)
            .flatMapConcat{ secondResp => jpegSource(secondResp.entity) }
            .idleTimeout(4.seconds)
          val restartSettings = RestartSettings(
            minBackoff = 3.seconds, maxBackoff = 30.seconds, randomFactor = 0.2,
          )
          RestartSource.withBackoff(restartSettings)(()=>canFailSource)
            .via(killSwitch.flow)
            .toMat(BroadcastHub.sink[String])(Keep.right)
            .run()(mat) //check?
        }
        ignoredOK <- commonSource.toMat(Sink.ignore)(Keep.right).run()(mat)
      } yield {
        commonSourcePromise.success(commonSource)
        ignoredOK
      }
    }
    val source = Source.futureSource(commonSourcePromise.future)
      .buffer(size = 2, overflowStrategy = OverflowStrategy.dropHead)
      .keepAlive(1.seconds,()=>"")
    val sink = Flow[String].idleTimeout(5.seconds).to(Sink.ignore) // ignore stream -- so cam will work w/o client
    Flow.fromSinkAndSourceCoupled(sink,source)
  }
}