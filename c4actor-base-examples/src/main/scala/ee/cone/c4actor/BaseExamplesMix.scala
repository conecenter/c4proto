package ee.cone.c4actor

import ee.cone.c4di.c4app

trait BaseExamplesTestApp extends TestVMRichDataCompApp
  with SimpleAssembleProfilerCompApp with VMExecutionApp with ExecutableApp

@c4app class ConnTestAppBase extends BaseExamplesTestApp

//C4STATE_TOPIC_PREFIX=ee.cone.c4actor.ConnTestApp sbt ~'c4actor-base-examples/run-main ee.cone.c4actor.ServerMain'

@c4app class EachTestAppBase extends BaseExamplesTestApp

// C4STATE_TOPIC_PREFIX=ee.cone.c4actor.EachTestApp sbt ~'c4actor-base-examples/run-main ee.cone.c4actor.ServerMain'

@c4app class JoinAllTestAppBase extends BaseExamplesTestApp

// C4STATE_TOPIC_PREFIX=ee.cone.c4actor.JoinAllTestApp sbt ~'c4actor-base-examples/run-main ee.cone.c4actor.ServerMain'
trait JustJoinTestAppBase
trait RevRelFactoryImplAppBase
@c4app class RRTest1AppBase extends BaseExamplesTestApp with RevRelFactoryImplApp with JustJoinTestApp
@c4app class RRTest2AppBase extends BaseExamplesTestApp with RevRelFactoryImplApp with JustJoinTestApp
@c4app class RRTest3AppBase extends BaseExamplesTestApp with RevRelFactoryImplApp with JustJoinTestApp

// C4STATE_TOPIC_PREFIX=ee.cone.c4actor.RRTest1App sbt ~'c4actor-base-examples/run-main ee.cone.c4actor.ServerMain'

trait AssemblerTestAppBase
@c4app class SimpleAssemblerTestAppBase extends AssemblerTestApp with BaseExamplesTestApp
@c4app class NotEffectiveAssemblerTestAppBase extends AssemblerTestApp with BaseExamplesTestApp

@c4app class HashSearchTestAppBase extends BaseExamplesTestApp

@c4app class ProtoAdapterTestAppBase extends ExecutableApp with VMExecutionApp
  with BaseApp with ProtoApp with BigDecimalApp with GzipRawCompressorApp

