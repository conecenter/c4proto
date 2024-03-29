package ee.cone.c4gate

import java.nio.file._
import java.time.Instant

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4gate.HttpProtocol.{N_Header, S_Manifest}
import ee.cone.c4di.c4
import ee.cone.c4proto.ToByteString
import okio.ByteString

import scala.jdk.CollectionConverters.IterableHasAsScala
import scala.jdk.CollectionConverters.IteratorHasAsScala

//todo un-publish

import scala.jdk.CollectionConverters.IterableHasAsScala
import java.nio.charset.StandardCharsets.UTF_8

@c4assemble("PublishingCompApp") class PublishingAssembleBase(publishing: Publishing){
  def join(
    srcId: SrcId,
    firstborn: Each[S_Firstborn]
  ): Values[(SrcId,TxTransform)] =
    List(
      WithPK(PublishingFromFilesTx()(publishing)),
      WithPK(PublishingFromStringsTx()(publishing)),
    )
}

case class PublishingFromFilesTx(srcId: SrcId="PublishingFromFilesTx")(publishing: Publishing) extends TxTransform {
  def transform(local: Context): Context = publishing.checkPublishFromFiles(local)
}
case class PublishingFromStringsTx(srcId: SrcId="PublishingFromStringsTx")(publishing: Publishing) extends TxTransform {
  def transform(local: Context): Context = publishing.checkPublishFromStrings(local)
}

trait PublicDirProvider {
  def get: List[(String,Path)]
}

@c4("PublishingCompApp") final class PublicPaths(config: ListConfig)(
  val value: List[Path] =
    config.get("C4PUBLIC_PATH")
      .flatMap(paths=>"""([^\s:]+)""".r.findAllIn(paths)).map(Paths.get(_))
)

@c4("PublishingCompApp") final class ModPublicDirProvider(
  config: ListConfig, publicPaths: PublicPaths,
) extends PublicDirProvider with LazyLogging {
  def get: List[(String,Path)] = {
    val hasMod = (for {
      modulesStr <- config.get("C4MODULES")
      m <- modulesStr.split(":")
    } yield m).toSet
    logger.debug(s"hasMod: $hasMod")
    val Line = """(\S+)\s+(\S+)\s+(\S+)""".r
    for {
      mainPublicPath <- publicPaths.value
      fName <- Option(mainPublicPath.resolve("c4gen.ht.links")).toList if Files.exists(fName)
      Line(mod,url,pf) <- Files.readAllLines(fName).asScala.toList if hasMod(mod)
    } yield (url,mainPublicPath.resolve(pf))
  }
}

// we need it, because there's no good place to found there's new *.svg in src/
case object InitialPublishDone extends TransientLens[Boolean](false)
case object PublishFromStringsCache extends TransientLens[Option[List[ByPathHttpPublication]]](None)

@c4("PublishingCompApp") final class Publishing(
  idGenUtil: IdGenUtil,
  publishFromStringsProviders: List[PublishFromStringsProvider],
  mimeTypesProviders: List[PublishMimeTypesProvider],
  publicDirProviders: List[PublicDirProvider],
  publishFullCompressor: PublishFullCompressor,
  publisher: Publisher,
  txAdd: LTxAdd,
  publicPaths: PublicPaths,
)(
  mimeTypes: String=>Option[String] = mimeTypesProviders.flatMap(_.get).toMap.get,
  compressor: RawCompressor = publishFullCompressor.value
) extends LazyLogging {
  private def sleepABit: Context=>Context = SleepUntilKey.set(Instant.ofEpochMilli(System.currentTimeMillis+1000))
  def checkPublishFromStrings(local: Context): Context = {
    val timer = NanoTimer()
    val prepared = PublishFromStringsCache.of(local).getOrElse(for {
      publishFromStringsProvider <- publishFromStringsProviders
      (path,body) <- publishFromStringsProvider.get
    } yield prepare(path,body.getBytes(UTF_8)))
    val strEvents = publisher.publish("FromStrings", prepared)(local)
    val end = timer.ms
    logger.debug(s"checkPublishFromStrings: ${prepared.size} prepared, ${strEvents.size} events, $end ms")
    Function.chain(Seq(txAdd.add(strEvents), PublishFromStringsCache.set(Option(prepared)), sleepABit))(local) // we can not sleep forever due to snapshot put
  }
  def checkPublishFromFiles(local: Context): Context = {
    val timeToPublish =
      publicPaths.value.map(_.resolve("publish_time")).filter(Files.exists(_))
        .flatMap(path=>publisher.publish("FromFilesTime",List(prepare("/publish_time",Files.readAllBytes(path))))(local))
    if(timeToPublish.isEmpty && InitialPublishDone.of(local))
      sleepABit(local)
    else {
      val filesToPublish = publisher.publish("FromFiles", for {
        publicDirProvider <- publicDirProviders
        (url, file) <- publicDirProvider.get if url != "/publish_time"
      } yield prepare(url, Files.readAllBytes(file)))(local)
      txAdd.add(filesToPublish ++ timeToPublish).andThen(InitialPublishDone.set(true))(local)
    }
  }
  def prepare(path: String, body: Array[Byte]): ByPathHttpPublication = {
    val pointPos = path.lastIndexOf(".")
    val ext = if(pointPos<0) "" else path.substring(pointPos+1)
    val byteString = new ByteString(compressor.compress(body))
    val mimeType = mimeTypes(ext)
    val eTag = "v1-" +
      idGenUtil.srcIdFromSerialized(0,byteString) +
      idGenUtil.srcIdFromSerialized(0,ToByteString(s"${mimeType.getOrElse("")}:"))
    val headers =
      N_Header("etag", s""""$eTag"""") ::
        N_Header("content-encoding", compressor.name) ::
        mimeType.map(N_Header("content-type",_)).toList
    ByPathHttpPublication(path,headers,byteString)
  }
}
