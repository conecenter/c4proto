package ee.cone.c4gate

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file._
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Single, c4assemble}
import ee.cone.c4gate.HttpProtocol.{N_Header, S_HttpPublication}
import ee.cone.c4proto.{ToByteString, c4}
import okio.ByteString

import scala.collection.immutable.Seq

//todo un-publish

import scala.jdk.CollectionConverters.IterableHasAsScala

object DirInfo {
  def sortedList(dir: Path): List[Path] =
    FinallyClose(Files.newDirectoryStream(dir))(_.asScala.toList).sorted
  def deepFiles(path: Path): List[Path] = {
    if(!Files.exists(path)) Nil
    else if(Files.isDirectory(path)) sortedList(path).flatMap(deepFiles)
    else List(path) //Files.isRegularFile(path)
  }
}

sealed trait LastPublishState extends Product
case object LastPublishStateKey extends TransientLens[LastPublishState](NotCheckedLastPublishState)
case object NotCheckedLastPublishState extends LastPublishState
case object NoFileLastPublishState extends LastPublishState
case class FileLastPublishState(content: ByteString) extends LastPublishState

@c4assemble("PublishingCompApp") class PublishingAssembleBase(publishing: Publishing){
  def join(
    srcId: SrcId,
    firstborn: Each[S_Firstborn]
  ): Values[(SrcId,TxTransform)] =
    List(WithPK(PublishingTx("PublishingTx")(publishing)))
}

case class PublishingTx(srcId: SrcId)(publishing: Publishing) extends TxTransform {
  def transform(local: Context): Context = publishing.transform(local)
}

@c4("PublishingCompApp") class Publishing(
  idGenUtil: IdGenUtil,
  publishFromStringsProviders: List[PublishFromStringsProvider],
  mimeTypesProviders: List[PublishMimeTypesProvider],
  publishFullCompressor: PublishFullCompressor,
)(
  mimeTypes: String=>Option[String] = mimeTypesProviders.flatMap(_.get).toMap.get,
  compressor: Compressor = publishFullCompressor.value
) extends LazyLogging {
  def transform(local: Context): Context = { //Seq[Observer[RichContext]]
    val fromPath = Paths.get("htdocs")
    val publishState =
      Option(fromPath.resolve("publish_time")).filter(Files.exists(_))
      .map(path=>FileLastPublishState(ToByteString(Files.readAllBytes(path))))
      .getOrElse(NoFileLastPublishState)
    if(LastPublishStateKey.of(local) == publishState)
      SleepUntilKey.set(Instant.ofEpochMilli(System.currentTimeMillis+1000))(local)
    else {
      logger.debug("publish started")
      val events =
        publishFromStringsProviders.flatMap(_.get).flatMap{ case(path,body) => publish(path,body.getBytes(UTF_8))(local)} ++
          DirInfo.deepFiles(fromPath).flatMap(file=>publish(s"/${fromPath.relativize(file)}",Files.readAllBytes(file))(local))
      logger.debug("publish finishing")
      TxAdd(events).andThen(LastPublishStateKey.set(publishState))(local)
    }
  }
  def publish(path: String, body: Array[Byte]): Context=>Seq[LEvent[Product]] = local => {
    val pointPos = path.lastIndexOf(".")
    val ext = if(pointPos<0) "" else path.substring(pointPos+1)
    val byteString = compressor.compress(ToByteString(body))
    val mimeType = mimeTypes(ext)
    val eTag = "v1-" +
      idGenUtil.srcIdFromSerialized(0,byteString) +
      idGenUtil.srcIdFromSerialized(0,ToByteString(s"${mimeType.getOrElse("")}:"))
    val headers =
      N_Header("etag", s""""$eTag"""") ::
      N_Header("content-encoding", compressor.name) ::
      mimeType.map(N_Header("content-type",_)).toList
    val publication = S_HttpPublication(path,headers,byteString,None)
    val existingPublications = ByPK(classOf[S_HttpPublication]).of(local)
    //println(s"${existingPublications.getOrElse(path,Nil).size}")
    if(existingPublications.get(path).contains(publication)) {
      logger.debug(s"$path (${byteString.size}) exists")
      Nil
    } else {
      logger.debug(s"$path (${byteString.size}) published")
      LEvent.update(publication)
    }
  }
}
