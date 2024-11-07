package ee.cone.c4gate_server

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.{Context, Early, Executable, Execution, GetByPK, LEvent}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4di.c4
import ee.cone.c4gate.AuthProtocol._
import ee.cone.c4gate.HttpProtocol.S_HttpRequest
import ee.cone.c4gate.{AuthOperations, SessionUtil}
import ee.cone.c4gate_server.RHttpTypes.RHttpHandlerCreate
import okio.ByteString

import java.util.concurrent.LinkedBlockingQueue
import scala.annotation.tailrec
import scala.concurrent.{Await, Promise}
import scala.concurrent.duration.Duration

@c4("AbstractHttpGatewayApp") final class AuthChecker(
  authOperations: AuthOperations, execution: Execution,
) extends Executable with Early {
  private val queue = new LinkedBlockingQueue[(String, Option[N_SecureHash], Promise[Int])]
  def check(password: String, hashOpt: Option[N_SecureHash]): Int = {
    val resp = Promise[Int]()
    queue.put((password, hashOpt, resp))
    Await.result(resp.future, Duration.Inf)
  }
  def run(): Unit = iteration(0L)
  @tailrec private def iteration(disabledUntil: Long): Unit = {
    val (password, hashOpt, resp) = queue.take()
    val now = System.currentTimeMillis()
    val (status, disableUntil) = if(now < disabledUntil) (429, disabledUntil)
    else if(hashOpt.exists(hash => authOperations.verify(password, hash))) (200, disabledUntil) else (400, now + 1000)
    execution.success(resp, status)
    iteration(disableUntil)
  }
}

@c4("AbstractHttpGatewayApp") final class AuthHttpHandler(
  httpResponseFactory: RHttpResponseFactory,
  getC_PasswordRequirements: GetByPK[C_PasswordRequirements],
  getC_PasswordHashOfUser: GetByPK[C_PasswordHashOfUser],
  getU_AuthenticatedSession: GetByPK[U_AuthenticatedSession],
  sessionUtil: SessionUtil,
  authOperations: AuthOperations, authChecker: AuthChecker,
) extends LazyLogging {
  import httpResponseFactory._
  def wire: RHttpHandlerCreate = next => (request,local) => if(request.method == "POST") request.path match {
    case "/auth/change" => withSession(request, local, _ => {
      val re = getC_PasswordRequirements.ofA(local).get("gate-password-requirements").map(_.regex)
      val (userName, password, prevHashOpt) = bodySplitUserPass(request, local)
      if (re.forall(regex => regex.isEmpty || password.matches(regex))) {
        val hash = authOperations.createHash(password, prevHashOpt)
        val lEvents = LEvent.update(S_PasswordChangeRequest(request.srcId, userName, Option(hash)))
        deferredResponse(request, _.copy(body=rawJson("requestId", request.srcId)), lEvents)
      } else directResponse(request, _.copy(status = 400))
    })
    case "/auth/check" =>
      val (userName, password, hashOpt) = bodySplitUserPass(request, local)
      val endTime = System.currentTimeMillis() + 1000
      val status = authChecker.check(password, hashOpt)
      Thread.sleep(Math.max(0, endTime - System.currentTimeMillis()))
      if(status == 200){
        val (sessionKey, events) = sessionUtil.create(userName, request.headers)
        deferredResponse(request, _.copy(body=rawJson("sessionKey", sessionKey)), events.toList)
      }
      else directResponse(request, _.copy(status = status))
    case "/auth/branch" => withSession(request, local, session => {
      directResponse(request, _.copy(body=rawJson("branchKey", session.logKey)))
    })
    case _ => next(request, local)
  } else next(request, local)
  // limit == 2; when limit is not provided or limit is 0 it discards trailing empty strings
  private def bodySplitUserPass(request: S_HttpRequest, local: Context): (SrcId, SrcId, Option[N_SecureHash]) = {
    val Array(u,p) = request.body.utf8().split("\n", 2)
    (u, p, getC_PasswordHashOfUser.ofA(local).get(u).flatMap(_.hash))
  }
  private def rawJson(k: String, v: String): ByteString = ByteString.encodeUtf8(s"""{"$k":"$v"}""")
  private def withSession(
    request: S_HttpRequest, local: Context, f: U_AuthenticatedSession=>RHttpResponse
  ): RHttpResponse = (for(
    sessionKey <- header(request, "x-r-session"); session <- getU_AuthenticatedSession.ofA(local).get(sessionKey)
  ) yield session).fold(directResponse(request, _.copy(status = 400, body=rawJson("error","missing"))))(f)
  def header(request: S_HttpRequest, key: String): Option[String] =
    request.headers.find(_.key == key).map(_.value)
}
