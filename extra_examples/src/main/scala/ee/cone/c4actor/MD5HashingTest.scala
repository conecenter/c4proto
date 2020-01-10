package ee.cone.c4actor

import java.lang.management.ManagementFactory

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.MD5HashingProtocol.{D_TestOrigEasy, D_TestOrigHard}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4proto.{Id, protocol}

import scala.collection.immutable
import scala.util.Random

//  C4STATE_TOPIC_PREFIX=ee.cone.c4actor.MD5HashingTestApp sbt ~'c4actor-extra-examples/runMain ee.cone.c4actor.ServerMain'

case class HashedRich[T](srcId: SrcId, preHashed: PreHashed[T])

case class HashedRichFixed(srcId: SrcId, preHashed: PreHashed[List[(HashedRich[D_TestOrigEasy], HashedRich[D_TestOrigHard])]])

case class NonHashedRich[T](srcId: SrcId, preHashed: T)

case class NonHashedRichFixed(srcId: SrcId, preHashed: List[(NonHashedRich[D_TestOrigEasy], NonHashedRich[D_TestOrigHard])])

trait MD5HashingProtocolAppBase

@protocol("MD5HashingProtocolApp") object MD5HashingProtocolBase {

  @Id(0x239) case class D_TestOrigHard(
    @Id(0x240) srcId: String,
    @Id(0x441) int: Int,
    @Id(0x442) long: Long,
    @Id(0x443) opt: Option[String],
    @Id(0x444) list: List[Long]
  )

  @Id(0x445) case class D_TestOrigEasy(
    @Id(0x446) srcId: String,
    @Id(0x447) value: Int
  )

}

@assemble class MD5HashingAssembleBase(preHashing: PreHashing) {
  type HashedId = SrcId

  def nonHashMD5Easy(
    srcId: SrcId,
    easy: Each[D_TestOrigEasy]
  ): Values[(SrcId, NonHashedRich[D_TestOrigEasy])] =
    WithPK(NonHashedRich(easy.srcId, easy)) :: Nil

  def nonHashMD5Hard(
    srcId: SrcId,
    easy: Each[D_TestOrigHard]
  ): Values[(SrcId, NonHashedRich[D_TestOrigHard])] =
    WithPK(NonHashedRich(easy.srcId, easy)) :: Nil

  def nonEasyAndHard(
    srcId: SrcId,
    easy: Each[NonHashedRich[D_TestOrigEasy]],
    hard: Each[NonHashedRich[D_TestOrigHard]]
  ): Values[(SrcId, NonHashedRichFixed)] =
    WithPK(NonHashedRichFixed(easy.srcId + hard.srcId, (easy, hard) :: (easy, hard) :: (easy, hard) :: (easy, hard) :: Nil)) :: Nil

  def HashMD5Easy(
    srcId: SrcId,
    easy: Each[D_TestOrigEasy]
  ): Values[(SrcId, HashedRich[D_TestOrigEasy])] =
    WithPK(HashedRich(easy.srcId, preHashing.wrap(easy))) :: Nil

  def HashMD5Hard(
    srcId: SrcId,
    easy: Each[D_TestOrigHard]
  ): Values[(SrcId, HashedRich[D_TestOrigHard])] =
    WithPK(HashedRich(easy.srcId, preHashing.wrap(easy))) :: Nil

  def EasyAndHard(
    srcId: SrcId,
    easy: Each[HashedRich[D_TestOrigEasy]],
    hard: Each[HashedRich[D_TestOrigHard]]
  ): Values[(SrcId, HashedRichFixed)] =
    WithPK(HashedRichFixed(easy.srcId + hard.srcId, preHashing.wrap((easy, hard) :: (easy, hard) :: (easy, hard) :: (easy, hard) :: Nil))) :: Nil


}

class MD5HashingTest(
  execution: Execution, toUpdate: ToUpdate, contextFactory: ContextFactory
) extends Executable with LazyLogging {
  def run(): Unit = {
    println(ManagementFactory.getRuntimeMXBean.getName)
    Thread.sleep(5000)
    import LEvent.update
    val worldSize = 50000
    val world: immutable.Seq[Product] =
      (for {
        i <- 1 to worldSize
      } yield generateRandomEasy(i.toString) :: generateHard(i.toString) :: Nil).flatten
    val worldUpdate: immutable.Seq[LEvent[Product]] = world.flatMap(update)
    val updates: List[QProtocol.N_Update] = worldUpdate.map(rec => toUpdate.toUpdate(rec)).toList
    val nGlobal = contextFactory.updated(updates)

    //logger.info(s"${nGlobal.assembled}")

    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
    println("World Ready")
    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
    println("Change index")
    val world2: immutable.Seq[Product] =
      (for {
        i <- 1 to worldSize / 2
      } yield generateRandomEasy(i.toString) :: generateHard(i.toString) :: Nil).flatten
    val worldUpdate2: immutable.Seq[LEvent[Product]] = world2.flatMap(update)
    val firstGlobal = TxAdd(worldUpdate2)(nGlobal)
    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
    println("Change index")

    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
    println("Change index")
    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
    execution.complete()
  }


  def generateRandomEasy: SrcId => D_TestOrigEasy = srcId =>
    D_TestOrigEasy(srcId, Random.nextInt(100000000))

  def generateHard: SrcId => D_TestOrigHard = srcId =>
    D_TestOrigHard(srcId,
      Random.nextInt(100000000),
      Random.nextLong(),
      Some(Random.nextInt(100000000).toString),
      (for {i <- 1 to 100} yield Random.nextLong()).toList
    )
}

class MD5HashingTestApp extends TestVMRichDataApp
  with ExecutableApp
  with VMExecutionApp
  with ToStartApp
  with MD5HashingProtocolApp {
  override def toStart: List[Executable] = new MD5HashingTest(execution, toUpdate, contextFactory) :: super.toStart

  override def assembles: List[Assemble] = new MD5HashingAssemble(PreHashingMurMur3()) :: super.assembles

  lazy val assembleProfiler = NoAssembleProfiler //ValueAssembleProfiler
}

