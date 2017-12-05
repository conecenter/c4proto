package ee.cone.c4actor

class ProtoAdapterTestApp extends CompoundExecutableApp
  with `The VMExecution`
  with `The MyProtocol`
  with `The QProtocol`
  with `The QAdapterRegistryImpl`
  with `The ToUpdateImpl`
  with `The ProtoAdapterTest`

abstract class RichTestApp extends RichDataApp
  with CompoundExecutableApp
  with `The VMExecution`
  with TreeIndexValueMergerFactoryApp
  with `The SimpleAssembleProfiler`

class AssemblerTestApp extends RichTestApp
  with `The PCProtocol`
  with `The TestAssemble`
  with `The AssemblerTestStart`

class NotEffectiveAssemblerTestApp extends RichTestApp
  with `The PCProtocol`
  with `The TestAssemble`
  with `The NotEffectiveAssemblerTest`

class ConnTestApp extends RichTestApp
  with `The ConnProtocol`
  with `The ConnAssemble`
  with `The ConnStart`

class HashSearchTestApp extends RichTestApp
  with `The SomeModelAccessImpl`
  with `The HashSearchTestIndexAssemble`
  with `The HashSearchTestImpl`
  with `The HashSearchTestAssemble`
  with `The HashSearchTestProtocol`
