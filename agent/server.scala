
//package ee.cone.c4agent

import upickle.default.{ReadWriter => RW, macroRW}

object Load {
  def apply(path: os.Path): Option[String] =
    Option(path).filter(os.exists).map(os.read)
}

case class ContextConf(context: String, issuer: String, audience: String, authenticator: String)
object ContextConf {
  implicit val rw: RW[ContextConf] = macroRW
  def path = os.home / "c4contexts.json"
  def load(): List[ContextConf] =
    upickle.default.read[List[ContextConf]](os.read(path))
}

case class PublicState(devName: String, authTime: Long){
  def save(): Unit = os.write.over(PublicState.path,toJsonString)
  def toJsonString: String = upickle.default.write(this)
}
object PublicState { 
  implicit val rw: RW[PublicState] = macroRW
  def path: os.Path = os.home / "c4agent_public_state.json"
  def load(): Option[PublicState] =
      Load(path).map(text=>upickle.default.read[PublicState](text))
}

trait TokenVerifyApp {
  import java.net.URL
  import java.security.InvalidParameterException
  import java.security.interfaces.RSAPublicKey
  import com.auth0.jwt.JWT
  import com.auth0.jwt.algorithms.Algorithm
  import com.auth0.jwk.UrlJwkProvider

  def verifyToken(contexts: Seq[ContextConf], idToken: String): (ContextConf,String) = {
    val decodedToken = JWT.decode(idToken)
    val issuer = decodedToken.getIssuer
    val context = contexts.find(_.issuer == issuer)
      .getOrElse(throw new InvalidParameterException("issuer"))
    val jwksUri = s"$issuer/keys"
    val provider = new UrlJwkProvider(new URL(jwksUri))
    val publicKey = provider.get(decodedToken.getKeyId()).getPublicKey()
      .asInstanceOf[RSAPublicKey]
    val algorithm = Algorithm.RSA256(publicKey, null)
    val verifier = JWT.require(algorithm).withIssuer(issuer)
      .withAudience(context.audience).build()
    val verifiedToken = verifier.verify(idToken)
    (context, verifiedToken.getClaim("email").asString)
  }
}

trait SunServerApp {
  def handleIndexHTML(): String
  def handleForm(form: Map[String, String]): Unit

  import java.nio.charset.StandardCharsets.UTF_8
  import com.sun.net.httpserver.{HttpServer,HttpHandler,HttpExchange}
  import java.net.{InetSocketAddress,URLDecoder}
  import scala.util.control.NonFatal

  object Handler extends HttpHandler {
    def handle(exchange: HttpExchange): Unit = (
      exchange.getRequestMethod:String,
      exchange.getRequestURI.getPath:String,
      Option(exchange.getRequestHeaders.getFirst("Content-Type")).getOrElse(""):String
    ) match {
      case ("GET","/","") =>
        val data = handleIndexHTML().getBytes(UTF_8)
        exchange.getResponseHeaders.add("Content-Type", "text/html; charset=UTF-8")
        exchange.sendResponseHeaders(200, data.length)
        exchange.getResponseBody().write(data)
        exchange.close()
      case ("POST","/form","application/x-www-form-urlencoded") =>
        try {
          val content: String = new String(exchange.getRequestBody().readAllBytes(), UTF_8)
          val formMap: Map[String, String] = content.split("&").map(
            kvStr => kvStr.split("=").map(URLDecoder.decode(_, UTF_8)) match {
              case Array(k, v) => k -> v
            }
          ).toMap
          handleForm(formMap)
        } catch {
          case NonFatal(exception) =>
            println(exception.getMessage)
            exception.printStackTrace()
        }
        exchange.getResponseHeaders.add("Location", "/")
        exchange.sendResponseHeaders(301, -1)
        exchange.close()
      case t =>
        println(t)
    }
  }

  def startServer(): Unit = {
    val server = HttpServer.create(new InetSocketAddress(1979), 2)
    server.createContext("/", Handler)
    server.setExecutor(null)
    server.start()
  }
}

object Main extends SunServerApp with TokenVerifyApp with WebApp with BackgroundApp {
  def protoDir = os.Path(Option(System.getenv("C4CI_PROTO_DIR")).get)

  def main(args: Array[String]): Unit = {
    startServer()
    keepRunning(initialPeriodicSeq)
  }
}

trait WebApp {
  def protoDir: os.Path
  def verifyToken(contexts: Seq[ContextConf], idToken: String): (ContextConf,String)

  def handleForm(form: Map[String, String]): Unit = form("op") match {
    case "set_token" => setToken(form("id_token"))
  }

  def kubeConfDir: os.Path = os.home / ".kube"
  def kubeTokenDir: os.Path = kubeConfDir / "tokens"
  def regenerateKubeConf(): Unit = {
    os.copy.over(kubeConfDir / "config-template", kubeConfDir / "config")
    for (path <- os.list(kubeTokenDir))
      os.proc("kubectl", "config", "set-credentials", s"${path.last}", "--token", os.read(path)).call()
  }
  def setToken(idToken: String): Unit = {
    val contextsPath = os.home / "c4contexts.json"
    val contexts = ContextConf.load()
    val ChkMail = """(\w+)@.+""".r
    val (context, ChkMail(devName)) = verifyToken(contexts, idToken)
    os.write.over(kubeTokenDir / context.context, idToken, createFolders = true)
    regenerateKubeConf()
    PublicState(devName, System.currentTimeMillis()).save()
  }

  def handleIndexHTML(): String = {
    val publicState = PublicState.load()
    val script = Seq(
      s"""const publicState = [${publicState.map(_.toJsonString).mkString}]""",
      s"""const contexts = ${os.read(ContextConf.path)}""",
      os.read(protoDir / "agent" / "client.js"),
    ).mkString("\n")
    s"""<!DOCTYPE html><html lang="en"><head><meta charset="UTF-8"><title>c4agent</title></head><body><script type="module">$script</script></body></html>"""
  }
}

trait BackgroundApp {
  def protoDir: os.Path

  private def checkVer() = {
    val path = protoDir / "agent" / "server.scala"
    val was = os.read(path)
    new Periodic(()=>{
      if (was != os.read(path)) System.exit(1)
      None
    })
  }

  private def setup = new NonRunningProcess(()=>
    for (st <- PublicState.load()) yield {
      val path = protoDir / "sync.pl"
      Cmd(s"${st.authTime}", Seq("perl", s"$path", "setup_rsh", st.devName))
    }
  )

  private def forward = new NonRunningProcess(()=>
    for(st <- PublicState.load(); pod <- Load(os.Path("/tmp/c4pod")))
      yield Cmd(s"${st.authTime}", Seq("kubectl","--context","dev","port-forward","--address","0.0.0.0",pod,"4005"))
  )

  def initialPeriodicSeq: Seq[Periodic] = Seq(checkVer(), setup, forward)

  import scala.annotation.tailrec
  @tailrec final def keepRunning(was: Seq[Periodic]): Unit = {
    val will = was.map(w=>w.activate().getOrElse(w))
    Thread.sleep(1000)
    keepRunning(will)
  }

}

class Periodic(val activate: ()=>Option[Periodic])

case class Cmd(ver: String, value: Seq[String])

class RunningProcess(getCommand: ()=>Option[Cmd], wasCommand: Cmd, process: os.SubProcess) extends Periodic(()=>
  if (!process.isAlive) Option(new NonRunningProcess(getCommand))
  else {
    if (!getCommand().contains(wasCommand)) {
      println("will stop",wasCommand)
      process.destroy()
    }
    None
  }
)

class NonRunningProcess(val getCommand: ()=>Option[Cmd]) extends Periodic(()=>
  getCommand().map{ cmd =>
    println("will start",cmd)
    new RunningProcess(getCommand,cmd,os.proc(cmd.value).spawn(stdin=os.Inherit,stdout=os.Inherit))
  }
)
