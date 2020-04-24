package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.N_CompressedUpdates
import ee.cone.c4di.{c4, c4multi, provide}
import ee.cone.c4proto.{ProtoAdapter, ToByteString}
import okio._

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}

object NoStreamCompressorFactory extends StreamCompressorFactory {
  def create(): Option[Compressor] = None
}

@c4("ProtoApp") final case class DeCompressorRegistryImpl(
  compressors: List[DeCompressor],
  innerMultiDeCompressorFactory: InnerMultiDeCompressorFactory
)(
  val byNameMap: Map[String, DeCompressor] = CheckedMap(compressors.map(c => c.name -> c))
)(
  val multiByNameMap: Map[String, MultiDeCompressor] = CheckedMap((
    innerMultiDeCompressorFactory.create(byNameMap) ::
    compressors.map(new WrapMultiDeCompressor(_))
  ).map(c => c.name -> c))
) extends DeCompressorRegistry {
  def byName: String => MultiDeCompressor = multiByNameMap.apply
}

class WrapMultiDeCompressor(compressor: DeCompressor) extends MultiDeCompressor {
  def name: String = compressor.name
  def deCompress(data: ByteString)(implicit executionContext: ExecutionContext): List[Future[ByteString]] =
    List(Future(compressor.deCompress(data)))
}

@c4multi("ProtoApp") final class InnerMultiDeCompressor(
  byName: Map[String, DeCompressor]
)(
  compressedUpdatesAdapter: ProtoAdapter[N_CompressedUpdates],
) extends MultiDeCompressor {
  def name = "inner"
  def deCompress(data: ByteString)(implicit executionContext: ExecutionContext): List[Future[ByteString]] = {
    val compressedUpdates = compressedUpdatesAdapter.decode(data)
    val compressor = byName(compressedUpdates.compressorName)
    compressedUpdates.values.map(v=>Future(compressor.deCompress(v)))
  }
}

@c4("ProtoApp") final class InnerMultiRawCompressorProvider(
  compressor: Option[RawCompressor],
  innerMultiRawCompressorFactory: InnerMultiRawCompressorFactory
) {
  @provide def get: Seq[MultiRawCompressor] =
    compressor.map(innerMultiRawCompressorFactory.create).toSeq
}

@c4multi("ProtoApp") final class InnerMultiRawCompressor(
  compressor: RawCompressor
)(
  compressedUpdatesAdapter: ProtoAdapter[N_CompressedUpdates]
) extends MultiRawCompressor with LazyLogging {
  def name: String = "inner"
  def compress(data: Future[Array[Byte]]*)(implicit executionContext: ExecutionContext): Future[Array[Byte]] =
    for {
      values <- Future.sequence(data.map(f=>f.map(d=>ToByteString(compressor.compress(d)))).toList)
    } yield {
      logger.debug(s"Encoding compressed ${values.size} parts...")
      val res = compressedUpdatesAdapter.encode(N_CompressedUpdates(compressor.name,values))
      logger.debug(s"Encoded compressed")
      res
    }
}





////

@c4("ServerCompApp") final 
case class GzipFullDeCompressor() extends DeCompressor {
  def name: String = "gzip"

  @tailrec
  private def readAgain(source: Source, sink: Buffer): Unit =
    if (source.read(sink, 10000000) >= 0)
      readAgain(source, sink)

  def deCompress(body: ByteString): ByteString =
    FinallyClose(new Buffer) { sink =>
      FinallyClose(new GzipSource(new Buffer().write(body)))(
        gzipSource =>
          readAgain(gzipSource, sink)
      )
      sink.readByteString()
    }
}

case class GzipFullCompressor() extends Compressor {
  def name: String = "gzip"
  def compress(body: ByteString): ByteString =
    FinallyClose(new Buffer) { sink =>
      FinallyClose(new GzipSink(sink))(
        gzipSink =>
          gzipSink.write(new Buffer().write(body), body.size)
      )
      sink.readByteString()
    }
}

@c4("GzipRawCompressorApp") final 
case class GzipFullRawCompressor() extends RawCompressor {
  def name: String = "gzip"
  def compress(body: Array[Byte]): Array[Byte] =
    FinallyClose(new Buffer) { sink =>
      FinallyClose(new GzipSink(sink))(
        gzipSink =>
          gzipSink.write(new Buffer().write(body), body.length)
      )
      sink.readByteArray()
    }
}


class GzipStreamCompressor(
  readSink: Buffer = new Buffer()
)(
  gzipSink: GzipSink = new GzipSink(readSink)
) extends Compressor {
  def name: String = "gzip"

  def compress(body: ByteString): ByteString = synchronized {
    gzipSink.write(new Buffer().write(body), body.size)
    gzipSink.flush()
    readSink.readByteString()
  }

  // need? def close():Unit = gzipSink.close()
}

class GzipStreamCompressorFactory extends StreamCompressorFactory {
  def create(): Option[Compressor] = Option(new GzipStreamCompressor()())
}