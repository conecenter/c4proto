package ee.cone.c4actor

import okio._

object NoCompressorFactory extends CompressorFactory {
  def create():  Option[Compressor] = None
}

case class GzipCompressor() extends Compressor {
  def name: String = "gzip"
  def compress: ByteString ⇒ ByteString = body ⇒
    FinallyClose(new Buffer) { sink ⇒
      FinallyClose(new GzipSink(sink))(
        gzipSink ⇒
          gzipSink.write(new Buffer().write(body), body.size)
      )
      sink.readByteString()
    }
}

class GzipCompressorStream extends Compressor {
  def name: String = "gzip"
  private val sink = new Buffer()
  private val gzipSink = new GzipSink(sink)
  def compress: ByteString ⇒ ByteString = body ⇒ synchronized{    
    gzipSink.write(new Buffer().write(body), body.size)   
    gzipSink.flush()    
    sink.readByteString()
  }
 // def close():Unit = gzipSink.close()  
}

class GzipGzipCompressorStreamFactory extends CompressorFactory {
  def create():  Option[Compressor] = Option(new GzipCompressorStream)
}