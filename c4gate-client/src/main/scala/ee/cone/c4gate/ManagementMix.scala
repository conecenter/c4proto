package ee.cone.c4gate

import ee.cone.c4actor.{ActorName, AssemblesApp, Config}
import ee.cone.c4assemble.Assemble

trait ManagementApp extends AssemblesApp {
  def config: Config

  private lazy val mainActorName = ActorName(config.get("C4STATE_TOPIC_PREFIX"))
  override def assembles: List[Assemble] =
    new ManagementPostAssemble(mainActorName) :: new PostConsumerAssemble(mainActorName) ::
      super.assembles
}

/*
*
* Usage:
* curl $gate_addr_port/manage/$app_name -XPOST -HX-r-world-key:$worldKey -HX-r-selection:(all|keys|:$key)
* curl 127.0.0.1:8067/manage/ee.cone.c4gate.TestPasswordApp -XPOST -HX-r-world-key:SrcId,TxTransform -HX-r-selection:all
*
* */