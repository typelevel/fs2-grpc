package io.fs2.grpc

import java.io.File
import scala.io.Source
import buildinfo.BuildInfo.sourceManaged

class Fs2CodeGeneratorSpec extends munit.FunSuite {

  val sourcesGenerated = new File(sourceManaged.getAbsolutePath, "/io/fs2/grpc/")

  test("code generator outputs correct service file") {

    val testFileName = "TestServiceFs2Grpc.scala"
    val reference = Source.fromResource(s"${testFileName}.txt").getLines().mkString("\n")
    val generated = Source.fromFile(new File(sourcesGenerated, testFileName)).getLines().mkString("\n")

    assertEquals(generated, reference)

  }

}
