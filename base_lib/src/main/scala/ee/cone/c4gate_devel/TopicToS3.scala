package ee.cone.c4gate_devel

import com.typesafe.scalalogging.LazyLogging
import okio.ByteString

import scala.annotation.tailrec
import scala.concurrent.{Await, Future, Promise}
import ee.cone.c4actor._
import ee.cone.c4assemble.{C4UUID, Single}
import ee.cone.c4di.{c4, provide}
import ee.cone.c4gate_devel.TxGroupProtocol._
import ee.cone.c4proto.{Id, ProtoAdapter, protocol}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.collection.immutable.Queue
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters.IterableHasAsScala

@protocol("TxGroupApp") object TxGroupProtocol {
  case class N_TxGroup(
    @Id(0x0011) srcId: String, // dummy
    @Id(0x0080) txs: List[N_Tx]
  )
  case class N_Tx(
    @Id(0x0081) resource: String,
    @Id(0x0082) data: ByteString,
  )
}

object TxGroup {
  def bucketPostfix: String = "txg"
  def splitter: String = "-"
}

@c4("TopicToS3App") final class TopicToS3(
  execution: Execution, snapshotUtil: SnapshotUtil,
  s3: S3Manager, s3L: S3Lister, currentTxLogName: CurrentTxLogName, consuming: Consuming,
  adapter: ProtoAdapter[N_TxGroup],
  // consumerBeginningOffset: ConsumerBeginningOffset -- does not work, LOBroker fails later
) extends LazyLogging {
  import TxGroup._
  private def getSaved: List[(String,String)] =
    execution.aWait(s3L.list(currentTxLogName, bucketPostfix)(_)).toList.flatten
  private def targetGroupVolume: Long = 1_000_000_000L //1_000_000L
  @tailrec private def iteration(consumer: Consumer, wasTaken: Queue[RawEvent], wasSize: Long, time: Long): Unit = {
    val events = consumer.poll()
    val taken = wasTaken.enqueueAll(events)
    val size = wasSize + events.map(_.data.size).sum
    //logger.warn(s"here ${taken.size} $size ${System.nanoTime - time}")
    if(taken.isEmpty) iteration(consumer, taken, size, System.nanoTime)
    else if(size > targetGroupVolume || System.nanoTime - time > 1_000_000_000L * 60L){
      val rangeName = s"$bucketPostfix/${taken.head.srcId}$splitter${taken.last.srcId}"
      s3.put(currentTxLogName, rangeName, adapter.encode(N_TxGroup("", taken.toList.map(ev =>
        N_Tx(snapshotUtil.getName("snapshot_txs", ev.srcId, ev.data.toByteArray, ev.headers), ev.data)
      ))))
      logger.info(s"saved $rangeName")
      iteration(consumer, Queue.empty, 0, System.nanoTime)
    } else iteration(consumer, taken, size, time)
  }
  private object Saver extends Executable {
    def run(): Unit = {
      val nextOffsets = getSaved.map { case (nm, _) => nm.split(splitter)(1) } // java.lang.Long.parseLong(???,16)
      val offset = if (nextOffsets.isEmpty) consuming.process("0" * OffsetHexSize(), _.endOffset) else nextOffsets.max //OffsetHex(???)
      // ? what will happen if old txg exists, but old inbox records are purged ?
      consuming.process(offset, consumer => iteration(consumer, Queue.empty, 0, System.nanoTime))
    }
  }
  @tailrec private def purgeIteration(): Unit = {
    val keepPeriod = 1000 * 3600 * 24 * 7
    val items = getSaved.map{ case (nm,tmStr) => (nm, s3L.parseTime(tmStr)) }
    val maxTm = items.map{ case (_,tm) => tm }.maxOption
    val toDel = items.collect{ case (nm,tm) if maxTm.get-tm > keepPeriod => s"$bucketPostfix/$nm" }
    logger.info(s"purger: items ${items.size}, maxTm $maxTm, toDel ${toDel.size}")
    execution.fatal(implicit ec => Future.sequence(toDel.map(s3.delete(currentTxLogName, _))))
    Thread.sleep(1000 * 60)
    purgeIteration()
  }
  private object Purger extends Executable {
    def run(): Unit = purgeIteration()
  }
  @provide def executables: Seq[Executable] = Seq(Saver, Purger)
}

@c4("ExtractTxApp") final class ExtractTx(
  config: Config, listConfig: ListConfig, adapter: ProtoAdapter[N_TxGroup], snapshotUtil: SnapshotUtil,
  tmpRes: Promise[Path] = Promise(),
) extends Executable with Early with FileConsumerDir with LazyLogging {
  import TxGroup._
  private def runGetBytes(cmd: Seq[String]): Array[Byte] = {
    logger.info(s"running $cmd")
    val proc =  new ProcessBuilder(cmd: _*).start()
    //val res =
    proc.getInputStream.readAllBytes()
    //logger.info(s"exit code: ${proc.waitFor()} ; result bytes: ${res.length}")
    //res
  }

  private def runGetLines(cmd: Seq[String]): Array[String] = new String(runGetBytes(cmd), UTF_8).split("\n")
  private def toBytes(lines: Iterable[String]): Array[Byte] = lines.mkString("\n").getBytes(UTF_8)
  private def write(path: Path, data: Array[Byte]): Unit = {
    Files.createDirectories(path.getParent)
    Files.write(path, data)
  }
  @tailrec private def retry[T](f: ()=>Option[T]): T = f() match {
    case Some(r) => r
    case None =>
      Thread.sleep(1000)
      logger.warn(s"retry")
      retry(f)
  }
  private def extract(): Path = {
    val kubeContext: String = config.get("C4REPLAY_KUBE_CONTEXT")
    val topicPrefix: String = config.get("C4REPLAY_TOPIC_PREFIX")
    val snapshot: String = config.get("C4REPLAY_SNAPSHOT")
    val replayUntil: String = config.get("C4REPLAY_UNTIL")
    val uuid = C4UUID.nameUUIDFromBytes(toBytes(Seq(kubeContext,topicPrefix,snapshot,replayUntil)))
    val tmp = Paths.get(s"/tmp/$uuid")
    logger.info(s"extracting $tmp")
    val txListPath =  tmp.resolve("snapshot_tx_list")
    if(Files.notExists(txListPath)){
      val kc = Seq("kubectl", "--context", kubeContext)
      val s3pod :: _ = runGetLines(kc ++ Seq("get", "pods", "-o", "name", "-l", "c4s3client")).toList
      val mc = kc ++ Seq("exec", s3pod, "--", "/tools/mc")
      //
      val snapshotPf = s"snapshots/$snapshot"
      write(tmp.resolve("snapshot_name"), snapshotPf.getBytes(UTF_8))
      write(tmp.resolve(snapshotPf), runGetBytes(mc ++ Seq("cat",s"def/$topicPrefix.$snapshotPf")))
      //
      val snapshotN = snapshot.split(splitter).head
      val txQ = new java.util.ArrayList[String]
      val groups =  runGetLines(mc ++ Seq("ls","--json",s"def/$topicPrefix.$bucketPostfix")).toList.map{ line =>
        val obj = ujson.read(line)
        (obj("key").str, obj("size").num.toInt)
      }.sorted
      for((group,size) <- groups){
        val Array(fromN,toN) = group.split(splitter)
        if(snapshotN < toN && fromN < replayUntil) {
          val bytes =  retry(()=>Option(runGetBytes(
            mc ++ Seq("cat",s"def/$topicPrefix.$bucketPostfix/$group")
          )).filter(_.length==size))
          for(tx <- adapter.decode(bytes).txs) {
            write(tmp.resolve(tx.resource), tx.data.toByteArray)
            val offset = snapshotUtil.hashFromName(RawSnapshot(tx.resource)).get.offset
            if(snapshotN < offset && offset < replayUntil) txQ.add(tx.resource)
          }
        }
      }
      write(txListPath, toBytes(txQ.asScala))
    }
    logger.info(s"extracted $tmp")
    tmp
  }
  def run(): Unit = tmpRes.success(Single.option(listConfig.get("C4REPLAY_DIR")).fold(extract())(Paths.get(_)))
  def resolve(p: String): Path = Await.result(tmpRes.future, Duration.Inf).resolve(p)
}
