package ee.cone.c4actor.hashsearch.index.dynamic

import ee.cone.c4actor._

object TestHash extends HashSearchIdGeneration {
  def main(args: Array[String]): Unit = {
    println(commonPrefix(1, List("testLensStr")))
    println(commonPrefix(1, List("testLensInt")))
  }
}

trait HashSearchIdGeneration {
  private lazy val hashGen: HashGen = new MurMur3HashGen

  private lazy val commonPrefixUd = 0

  def commonPrefix(modelId: Int, lensName: List[String]): String =
    hash(commonPrefixUd, modelId, lensName)

  private lazy val leafId = 1

  def leafId(commonPrefix: String, by: Product): String = hash(leafId, commonPrefix, by)

  private lazy val heapId = 2

  def heapId(commonPrefix: String, by: Product): String = hash(heapId, commonPrefix, by)

  private lazy val nodeId = 3

  def indexNodeId(commonPrefix: String, byId: Long): String = hash(nodeId, commonPrefix, byId)

  def indexNodeId(modelId: Int, lensName: List[String], byId: Long): String = hash(nodeId, commonPrefix(modelId, lensName), byId)

  private lazy val modelId = 4

  def indexModelId(commonPrefix: String, modelSrcId: String): String = hash(modelId, commonPrefix, modelSrcId)

  lazy val parser: PreHashingMurMur3 = PreHashingMurMur3()

  private def hash(typeId: Int, prefix: String, obj: Product): String = {
    hashGen.generate(List(typeId, prefix, obj))
  }

  private def hash(typeId: Int, prefix: String, srcId: String): String = {
    hashGen.generate(List(typeId, prefix, srcId))
  }

  private def hash(typeId: Int, prefix: String, id: Long): String = {
    hashGen.generate(List(typeId, prefix, id))
  }

  private def hash(typeId: Int, id: Long, names: List[String]): String = {
    hashGen.generate(typeId :: id :: names)
  }
}

trait CreateRangerDirective extends HashSearchIdGeneration with ComponentProviderApp {
  def qAdapterRegistry: QAdapterRegistry

  private lazy val dynamicIndexModelsRegistry: DynamicIndexModelsRegistry = resolveSingle(classOf[DynamicIndexModelsRegistry])

  lazy val modelIdMap: Map[String, Int] = dynamicIndexModelsRegistry.models.map(p => p.modelCl.getName -> p.modelId).toMap
  lazy val nameToIdMap: Map[String, Long] = qAdapterRegistry.byName.transform((_, v) => if (v.hasId) v.id else -1)

  def apply[Model <: Product, By <: Product](modelCl: Class[Model], by: By, lensName: List[String]): RangerDirective[Model] = {
    val modelId = modelIdMap(modelCl.getName)
    val byId = nameToIdMap(by.getClass.getName)
    RangerDirective[Model](indexNodeId(modelId, lensName, byId), by)
  }
}
