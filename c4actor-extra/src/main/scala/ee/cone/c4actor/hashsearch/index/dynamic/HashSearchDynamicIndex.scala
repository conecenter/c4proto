package ee.cone.c4actor.hashsearch.index.dynamic

import ee.cone.c4actor.QAdapterRegistryApp
import ee.cone.c4actor.hashsearch.base.HashSearchAssembleSharedKeys
import ee.cone.c4actor.hashsearch.rangers.HashSearchRangerRegistryApi
import ee.cone.c4assemble.{Assemble, assemble}

trait HashSearchDynamicIndexApp extends DynamicIndexAssemble with QAdapterRegistryApp {

}

@assemble class HashSearchDynamicIndexAssemble[Model <: Product](
  modelCl: Class[Model],
  modelId: Int,
  rangerRegistry: HashSearchRangerRegistryApi
) extends Assemble with HashSearchAssembleSharedKeys {
}
