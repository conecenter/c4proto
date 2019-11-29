
package ee.cone.c4gate_akka_s3

import ee.cone.c4actor_s3_minio.MinioS3App
import ee.cone.c4di.c4app
import ee.cone.c4gate_akka.AkkaGatewayApp

@c4app class AkkaMinioGatewayAppBase extends AkkaGatewayApp with MinioS3App
