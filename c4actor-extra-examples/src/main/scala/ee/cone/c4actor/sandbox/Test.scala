package ee.cone.c4actor.sandbox

import ee.cone.c4actor.MD5HashingProtocol.{TestOrigEasy, TestOrigHard}
import ee.cone.c4actor.{PreHashed, PreHashingImpl, PreHashingMD5, TimeColored}
import ee.cone.c4actor.Types.SrcId

import scala.collection.immutable
import scala.util.Random

object Test {
  def main(args: Array[String]): Unit = {
    val worldSize = 50000
    val world: immutable.Seq[Product] =
      (for {
        i ← 1 to worldSize
      } yield generateRandomEasy(i.toString) :: generateHard(i.toString) :: Nil).flatten
    Thread.sleep(5000)
    /*
        import java.security.MessageDigest
        val md = MessageDigest.getInstance("MD5")
        val rnArray = new Array[Byte](10000000)
        val rn10: immutable.Seq[Array[Byte]] = (for {i ← 0 until 1000000} yield new Array[Byte](10)).map(list ⇒ {
          Random.nextBytes(list);
          list
        }
        )
        val rn100 = (for {i ← 0 until 100000} yield new Array[Byte](100)).map(list ⇒ {
          Random.nextBytes(list);
          list
        }
        )
        val rn1000: immutable.Seq[Array[Byte]] = (for {i ← 0 until 10000} yield new Array[Byte](1000)).map(list ⇒ {
          Random.nextBytes(list);
          list
        }
        )
        Random.nextBytes(rnArray)

        for {j <- 1 to 10} {
          TimeColored("r", "1", lowerBound = -1L)({
            for {i ← rnArray} md.update(i);
            md.digest()
          }
          )
          md.reset()
          TimeColored("r", "10", lowerBound = -1L)({
            for {i ← rn10} md.update(i);
            md.digest()
          }
          )
          md.reset()
          TimeColored("r", "100", lowerBound = -1L)({
            for {i ← rn100} md.update(i);
            md.digest()
          }
          )
          md.reset()
          TimeColored("r", "1000", lowerBound = -1L)({
            for {i ← rn1000} md.update(i);
            md.digest()
          }
          )
          md.reset()
          TimeColored("r", "10000", lowerBound = -1L)({
            md.update(rnArray);
            md.digest()
          }
          )
          md.reset()
        }
    */

    /*TimeColored("g", "simple")({
      val riches = world.map(item ⇒ NonHashedRich("12321321", item)).toList
      val riches2 = NonHashedRichFixed("aasd", riches)

      val riches3 = world.map(item ⇒ NonHashedRich("12321321", item)).toList
      val riches4 = NonHashedRichFixed("aasd", riches3)
      println(riches == riches3 && riches2 == riches4)
    }
    )*/
    TimeColored("g", "murmur")({
      val hashing = PreHashingMD5()
      val riches = world.map(item ⇒ HashedRich("12321321", hashing.wrap(item))).toList
      val riches2 = HashedRichFixed("aasd", hashing.wrap(riches))

      val riches3 = world.map(item ⇒ HashedRich("12321321", hashing.wrap(item))).toList
      val riches4 = HashedRichFixed("aasd", hashing.wrap(riches3))
      println(riches == riches3 && riches2 == riches4)
    }
    )

    Thread.sleep(1000)
  }

  def generateRandomEasy: SrcId ⇒ TestOrigEasy = srcId ⇒
    TestOrigEasy(srcId, Random.nextInt(100000000))

  def generateHard: SrcId ⇒ TestOrigHard = srcId ⇒
    TestOrigHard(srcId,
      Random.nextInt(100000000),
      Random.nextLong(),
      Some(Random.nextInt(100000000).toString),
      (for {i ← 1 to 100} yield Random.nextLong()).toList
    )


}

case class HashedRich[T](srcId: SrcId, preHashed: PreHashed[T])

case class HashedRichFixed(srcId: SrcId, preHashed: PreHashed[List[HashedRich[_]]])

case class NonHashedRich[T](srcId: SrcId, preHashed: T)

case class NonHashedRichFixed(srcId: SrcId, preHashed: List[NonHashedRich[_]])