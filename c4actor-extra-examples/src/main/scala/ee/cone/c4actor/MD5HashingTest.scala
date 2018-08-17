package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.MD5HashingProtocol.{TestOrigEasy, TestOrigHard}
import ee.cone.c4actor.PerformanceProtocol.{NodeInstruction, PerformanceNode}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4proto.{Id, Protocol, protocol}

import scala.collection.immutable
import scala.util.Random

//  C4STATE_TOPIC_PREFIX=ee.cone.c4actor.MD5HashingTestApp sbt ~'c4actor-extra-examples/runMain ee.cone.c4actor.ServerMain'

case class HashedRich[T](srcId: SrcId, preHashed: PreHashed[T])

case class HashedRichFixed(srcId: SrcId, preHashed: PreHashed[List[(HashedRich[TestOrigEasy], HashedRich[TestOrigHard])]])

case class NonHashedRich[T](srcId: SrcId, preHashed: T)

case class NonHashedRichFixed(srcId: SrcId, preHashed: List[(NonHashedRich[TestOrigEasy], NonHashedRich[TestOrigHard])])

@protocol object MD5HashingProtocol extends Protocol {

  @Id(0x239) case class TestOrigHard(
    @Id(0x240) srcId: String,
    @Id(0x441) int: Int,
    @Id(0x442) long: Long,
    @Id(0x443) opt: Option[String],
    @Id(0x444) list: List[Long]
  )

  @Id(0x445) case class TestOrigEasy(
    @Id(0x446) srcId: String,
    @Id(0x447) value: Int
  )

}

@assemble class MD5HashingAssemble(preHashing: PreHashing) extends Assemble {
  type HashedId = SrcId

  def HashMD5Easy(
    srcId: SrcId,
    easy: Each[TestOrigEasy]
  ): Values[(SrcId, HashedRich[TestOrigEasy])] =
    WithPK(HashedRich(easy.srcId, preHashing.wrap(easy))) :: Nil

  def HashMD5Hard(
    srcId: SrcId,
    easy: Each[TestOrigHard]
  ): Values[(SrcId, HashedRich[TestOrigHard])] =
    WithPK(HashedRich(easy.srcId, preHashing.wrap(easy))) :: Nil

  def EasyAndHard(
    srcId: SrcId,
    easy: Each[HashedRich[TestOrigEasy]],
    hard: Each[HashedRich[TestOrigHard]]
  ): Values[(SrcId, HashedRichFixed)] =
    WithPK(HashedRichFixed(easy.srcId + hard.srcId, preHashing.wrap((easy, hard) :: (easy, hard) :: (easy, hard) :: (easy, hard) :: Nil))) :: Nil

  def nonHashMD5Easy(
    srcId: SrcId,
    easy: Each[TestOrigEasy]
  ): Values[(SrcId, NonHashedRich[TestOrigEasy])] =
    WithPK(NonHashedRich(easy.srcId, easy)) :: Nil

  def nonHashMD5Hard(
    srcId: SrcId,
    easy: Each[TestOrigHard]
  ): Values[(SrcId, NonHashedRich[TestOrigHard])] =
    WithPK(NonHashedRich(easy.srcId, easy)) :: Nil

  def nonEasyAndHard(
    srcId: SrcId,
    easy: Each[NonHashedRich[TestOrigEasy]],
    hard: Each[NonHashedRich[TestOrigHard]]
  ): Values[(SrcId, NonHashedRichFixed)] =
    WithPK(NonHashedRichFixed(easy.srcId + hard.srcId, (easy, hard) :: (easy, hard) :: (easy, hard) :: (easy, hard) :: Nil)) :: Nil


}

class MD5HashingTest(
  execution: Execution, toUpdate: ToUpdate, contextFactory: ContextFactory
) extends Executable with LazyLogging {
  def run(): Unit = {
    import LEvent.update
    val worldSize = 50000
    val world: immutable.Seq[Product] =
      (for {
        i ← 1 to worldSize
      } yield generateRandomEasy(i.toString) :: generateHard(i.toString) :: Nil).flatten
    val worldUpdate: immutable.Seq[LEvent[Product]] = world.flatMap(update)
    val updates: List[QProtocol.Update] = worldUpdate.map(rec ⇒ toUpdate.toUpdate(rec)).toList
    val context: Context = contextFactory.create()
    val nGlobal: Context = ReadModelAddKey.of(context)(updates)(context)

    //logger.info(s"${nGlobal.assembled}")

    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
    println("World Ready")
    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
    println("Change index")
    val world2: immutable.Seq[Product] =
      (for {
        i ← 1 to worldSize / 2
      } yield generateRandomEasy(i.toString) :: generateHard(i.toString) :: Nil).flatten
    val worldUpdate2: immutable.Seq[LEvent[Product]] = world2.flatMap(update)
    val firstGlobal = TxAdd(worldUpdate2)(nGlobal)
    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
    println("Change index")

    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
    println("Change index")
    println(GlobalCounter.times, GlobalCounter.time)
    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
    execution.complete()
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

class MD5HashingTestApp extends RichDataApp
  with ExecutableApp
  with VMExecutionApp
  with TreeIndexValueMergerFactoryApp
  with ToStartApp {
  override def toStart: List[Executable] = new MD5HashingTest(execution, toUpdate, contextFactory) :: super.toStart

  override def protocols: List[Protocol] = MD5HashingProtocol :: super.protocols

  override def assembles: List[Assemble] = new MD5HashingAssemble(PreHashingMD5()) :: super.assembles

  lazy val assembleProfiler = ValueAssembleProfiler
}

