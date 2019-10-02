package ee.cone.c4actor

trait BaseExamplesTestApp extends TestVMRichDataCompApp
  with SimpleAssembleProfilerApp with VMExecutionApp with ExecutableApp

class ConnTestAppBase extends BaseExamplesTestApp

//C4STATE_TOPIC_PREFIX=ee.cone.c4actor.ConnTestApp sbt ~'c4actor-base-examples/run-main ee.cone.c4actor.ServerMain'

class EachTestAppBase extends BaseExamplesTestApp

// C4STATE_TOPIC_PREFIX=ee.cone.c4actor.EachTestApp sbt ~'c4actor-base-examples/run-main ee.cone.c4actor.ServerMain'

class JoinAllTestAppBase extends BaseExamplesTestApp

// C4STATE_TOPIC_PREFIX=ee.cone.c4actor.JoinAllTestApp sbt ~'c4actor-base-examples/run-main ee.cone.c4actor.ServerMain'
trait JustJoinTestAppBase
trait RevRelFactoryImplAppBase
class RRTest1AppBase extends BaseExamplesTestApp with RevRelFactoryImplApp with JustJoinTestApp
class RRTest2AppBase extends BaseExamplesTestApp with RevRelFactoryImplApp with JustJoinTestApp
class RRTest3AppBase extends BaseExamplesTestApp with RevRelFactoryImplApp with JustJoinTestApp

// C4STATE_TOPIC_PREFIX=ee.cone.c4actor.RRTest1App sbt ~'c4actor-base-examples/run-main ee.cone.c4actor.ServerMain'

trait AssemblerTestAppBase
class SimpleAssemblerTestAppBase extends AssemblerTestApp with BaseExamplesTestApp
class NotEffectiveAssemblerTestAppBase extends AssemblerTestApp with BaseExamplesTestApp

class HashSearchTestAppBase extends BaseExamplesTestApp

class ProtoAdapterTestAppBase extends ExecutableApp with VMExecutionApp
  with BaseApp with ProtoApp with BigDecimalApp with GzipRawCompressorApp

