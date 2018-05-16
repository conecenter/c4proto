package ee.cone.c4actor.hashsearch.index.dynamic

import ee.cone.c4actor.QAdapterRegistryApp
import ee.cone.c4actor.hashsearch.base.HashSearchAssembleSharedKeys
import ee.cone.c4actor.hashsearch.rangers.HashSearchRangerRegistryApi
import ee.cone.c4assemble.{Assemble, assemble}
import ee.cone.c4actor.DefaultModelRegistryApp
import ee.cone.c4actor.DefaultModelRegistry

trait HashSearchDynamicIndexApp extends DynamicIndexAssemble with QAdapterRegistryApp with DefaultModelRegistryApp {

}

@assemble class HashSearchDynamicIndexAssemble[Model <: Product](
  modelCl: Class[Model],
  modelId: Int,
  rangerRegistry: HashSearchRangerRegistryApi,
  defaultModelRegistry: DefaultModelRegistry
) extends Assemble with HashSearchAssembleSharedKeys {
}
