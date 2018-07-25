package ee.cone.c4actor.hashing

import com.google.common.base.Charsets
import com.google.common.hash
import com.google.common.hash._
import ee.cone.c4actor.hashing.HashingTestProtocol.{HashOrig, HashTestOrig}
import ee.cone.c4proto.{Id, Protocol, protocol}

import collection.JavaConverters._

@protocol object HashingTestProtocol extends Protocol {

  @Id(0x1) case class HashOrig(
    @Id(0x2) srcId: String,
    @Id(0x3) list: List[String],
    @Id(0x4) byteStr: Option[HashTestOrig]
  )

  @Id(0x5) case class HashTestOrig(
    @Id(0x6) srcId: String,
    @Id(0x7) number: Long
  )

}

object CommonHashingUtils {
  def combiner: List[HashCode] ⇒ HashCode = list ⇒ Hashing.combineOrdered(list.asJava)

  val hashFunction: HashFunction = Hashing.murmur3_128()

  def stringList(list: List[String]): HashCode = {
    val hasher = hashFunction.newHasher
    list.foldLeft((hasher, 0))((old, item) ⇒ (old._1.putInt(old._2).putString(item, Charsets.UTF_8), old._2 + 1))
      ._1
      .hash()
  }
}

trait PutList {

}

/*
object HashingTestProtocolHashers extends PutList {
  val HashOrigFunnel = new Funnel[HashOrig] {
    def funnel(from: HashOrig, into: PrimitiveSink): Unit = {
      putListString(from.list)(into
        .putInt(1)
        .putString(classOf[HashOrig].getName, Charsets.UTF_8)
        .putInt(2)
      )

    }
  }

  val HashTestOrigFunnel = new Funnel[HashTestOrig] {
    def funnel(from: HashTestOrig, into: PrimitiveSink): Unit = {
      into
        .putInt(5)
        .putString(classOf[HashTestOrig].getName, Charsets.UTF_8)
        .putInt(2)
        .putString(from.srcId, Charsets.UTF_8)
        .putInt(7)
        .putLong(from.number)

    }
  }
}*/

object HashTestOrigHasher extends Hasher[HashTestOrig] {
  def getHash(a: HashTestOrig): HashCode =
    CommonHashingUtils.hashFunction
      .newHasher()
      .putInt(5)
      .putString(classOf[HashTestOrig].getName, Charsets.UTF_8)
      .putInt(6)
      .putString(a.srcId, Charsets.UTF_8)
      .putInt(7)
      .putLong(a.number)
      .hash()
}

object HashOrigHasher extends Hasher[HashOrig] {
  def getHash(a: HashOrig): HashCode = {
    val prefix = CommonHashingUtils.hashFunction
      .newHasher()
      .putInt(1)
      .putString(classOf[HashOrig].getName, Charsets.UTF_8)
      .hash()
    val pre_srcId = CommonHashingUtils.hashFunction.newHasher().putInt(2)
      .putString(a.srcId, Charsets.UTF_8)
      .hash()

    val pre_pre_list = CommonHashingUtils.hashFunction.newHasher()
      .putInt(3)
      .hash()
    val pre_list = CommonHashingUtils.combiner(pre_pre_list :: CommonHashingUtils.stringList(a.list) :: Nil)

    val pre_pre_byteStr = CommonHashingUtils.hashFunction.newHasher()
      .putInt(4)
      .hash()
    val pre_byteStr = CommonHashingUtils.combiner(pre_pre_byteStr :: (
      if (a.byteStr.isDefined) HashTestOrigHasher.getHash(a.byteStr.get) else CommonHashingUtils.hashFunction.newHasher().putString("NONE", Charsets.UTF_8).hash()) ::
      Nil
    )

    CommonHashingUtils.combiner(prefix :: pre_srcId :: pre_list :: pre_byteStr :: Nil)
  }
}

trait Hasher[A] {
  def getHash(a: A): HashCode
}
