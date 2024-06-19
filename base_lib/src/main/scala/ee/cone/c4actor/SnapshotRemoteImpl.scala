package ee.cone.c4actor

import java.net.{URLDecoder, URLEncoder}
import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets.UTF_8
import ee.cone.c4di.c4

@c4("ConfigSimpleSignerApp") final class SimpleSignerImpl(
  config: Config, idGenUtil : IdGenUtil
)(
  fileName: String = config.get("C4AUTH_KEY_FILE")
)(
  val salt: String = new String(Files.readAllBytes(Paths.get(fileName)),UTF_8)
) extends SimpleSigner {
  def sign(data: List[String], until: Long): String = {
    val uData = until.toString :: data
    val hash = idGenUtil.srcIdFromStrings(salt :: uData:_*)
    (hash :: uData).map(URLEncoder.encode(_,"UTF-8")).mkString("=")
  }

  def retrieve(check: Boolean): Option[String]=>Option[List[String]] = _.flatMap{ signed =>
    val hash :: untilStr :: data = signed.split("=", -1).map(URLDecoder.decode(_,"UTF-8")).toList
    val until = untilStr.toLong
    if(!check) Option(data)
    else if(until < System.currentTimeMillis) None
    else if(sign(data,until) == signed) Option(data)
    else None
  }
}

@c4("TaskSignerApp") final class SnapshotTaskSignerImpl(inner: SimpleSigner)(
  val url: String = "/need-snapshot"
) extends SnapshotTaskSigner {
  def sign(task: SnapshotTask, until: Long): String = inner.sign(List(url,task.name) ++ task.offsetOpt, until)
  def retrieve(check: Boolean): Option[String]=>Option[SnapshotTask] =
    signed => inner.retrieve(check)(signed) match {
      case Some(Seq(`url`,"next")) => Option(NextSnapshotTask(None))
      case Some(Seq(`url`,"next", offset)) => Option(NextSnapshotTask(Option(offset)))
      case Some(Seq(`url`,"debug", offset)) => Option(DebugSnapshotTask(offset))
      case _ => None
    }
}
