
//package ee.cone.c4agent

import upickle.default.{ReadWriter => RW, macroRW}

import scala.annotation.tailrec

object Load {
  def apply(path: os.Path): Option[String] =
    Option(path).filter(os.exists).map(os.read)
}

case class ContextConf(context: String, issuer: String, audience: String)
object ContextConf {
  implicit val rw: RW[ContextConf] = macroRW
  def path = os.home / "c4contexts.json"
  def load(): List[ContextConf] =
    upickle.default.read[List[ContextConf]](os.read(path))
}

case class PublicState(devName: String){
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

trait UndertowApp {
  def handleIndexHTML(): String
  def handleForm(form: Map[String,String]): Unit

  import scala.jdk.CollectionConverters.IteratorHasAsScala
  import io.undertow.server.{HttpHandler,HttpServerExchange}
  import io.undertow.util.Headers

  object Handler extends HttpHandler {
    def handleRequest(exchange: HttpServerExchange): Unit = {
      //exchange.dispatch(executor,runnable)
      val method = exchange.getRequestMethod.toString
      val path = exchange.getRequestPath
      (method,path) match {
        case ("GET","/") =>
          val content = handleIndexHTML()
          exchange.getResponseHeaders.put(Headers.CONTENT_TYPE, "text/html; charset=UTF-8")
          exchange.getResponseSender.send(content)
        case ("POST","/form") =>
          val formData = io.undertow.server.handlers.form.FormParserFactory.builder.build().createParser(exchange).parseBlocking()
          val formMap = formData.iterator().asScala.map(k => k -> formData.get(k).getFirst.getValue).toMap
          handleForm(formMap)
          exchange.getResponseHeaders.put(Headers.LOCATION, "/")
          exchange.setStatusCode(301)
          exchange.endExchange()
      }
    }
  }
  def startServer(): Unit = io.undertow.Undertow.builder
    .addHttpListener(1979, "0.0.0.0")
    .setHandler(Handler)
    .build.start()
}


object Main extends UndertowApp with TokenVerifyApp {

  def protoDir = os.Path(Option(System.getenv("C4CI_PROTO_DIR")).get)

  def handleForm(form: Map[String,String]): Unit = form("op") match {
    case "set_token" => setToken(form("id_token"))
  }

  def setToken(idToken: String): Unit = {
    val contextsPath = os.home / "c4contexts.json"
    val contexts = ContextConf.load()
    val ChkMail = """(\w+)@.+""".r
    val (context,ChkMail(devName)) = verifyToken(contexts,idToken)
    os.proc("kubectl","config","set-credentials",context.context,"--token",idToken).call()
    PublicState(devName).save()
  }

  def handleIndexHTML(): String = {
    val publicState = PublicState.load()
    val jsContent = os.read(protoDir / "agent" / "client.js")
    val script = s"""const publicState = [${publicState.map(_.toJsonString).mkString}]\n${jsContent}"""
    s"""<!DOCTYPE html><html lang="en"><head><meta charset="UTF-8"><title>c4agent</title></head><body><script type="module">$script</script></body></html>"""
  }

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
      os.Shellable(Seq("perl", s"$path", "setup_rsh", st.devName))
    }
  )

  private def forward = new NonRunningProcess(()=>
    for(pod <- Load(os.Path("/tmp/c4pod")))
      yield os.Shellable(Seq("kubectl","--context","dev","port-forward","--address","0.0.0.0",pod,"4005"))
  )

  def main(args: Array[String]): Unit = {
    startServer()
    keepRunning(Seq(checkVer(),setup,forward))
  }

  @tailrec def keepRunning(was: Seq[Periodic]): Unit = {
    val will = was.map(w=>w.activate().getOrElse(w))
    Thread.sleep(1000)
    keepRunning(will)
  }

}

class Periodic(val activate: ()=>Option[Periodic])

class RunningProcess(getCommand: ()=>Option[os.Shellable], wasCommand: os.Shellable, process: os.SubProcess) extends Periodic(()=>
  if (!process.isAlive) Option(new NonRunningProcess(getCommand))
  else {
    if (!getCommand().contains(wasCommand)) process.destroy()
    None
  }
)

class NonRunningProcess(val getCommand: ()=>Option[os.Shellable]) extends Periodic(()=>
  getCommand().map{ cmd =>
    println(cmd)
    new RunningProcess(getCommand,cmd,os.proc(cmd).spawn())
  }
)
