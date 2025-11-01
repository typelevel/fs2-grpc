/*
 * Copyright (c) 2018 Gary Coady / Fs2 Grpc Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2.grpc.e2e

import java.io.File

import fs2.grpc.GeneratedCompanion
import fs2.grpc.e2e.buildinfo.BuildInfo.{sourceManaged, scalaVersion}
import hello.world._

import scala.io.Source

class Fs2CodeGeneratorRenderContextAsImplicitSpec extends munit.FunSuite {

  val sourcesGenerated = new File(sourceManaged.getAbsolutePath, "render-context-as-implicit/hello/world")
  val suffix = if (scalaVersion.startsWith("3")) "Scala3" else ""

  test("code generator outputs correct service file") {
    val fileName = "TestServiceFs2GrpcRenderContextAsImplicit"
    val testFileName = s"$fileName.scala"
    val referenceName = s"$fileName$suffix.scala"
    val reference = Source.fromResource(s"$referenceName.txt").getLines().mkString("\n")
    val generated = Source.fromFile(new File(sourcesGenerated, testFileName)).getLines().mkString("\n")

    assertEquals(generated, reference)
  }

  test("code generator outputs correct service file for trailers") {
    val fileName = s"TestServiceFs2GrpcRenderContextAsImplicitTrailers"
    val testFileName = s"$fileName.scala"
    val referenceName = s"$fileName$suffix.scala"
    val reference = Source.fromResource(s"$referenceName.txt").getLines().mkString("\n")
    val generated = Source.fromFile(new File(sourcesGenerated, testFileName)).getLines().mkString("\n")

    assertEquals(generated, reference)
  }

  test("implicit of companion resolves") {
    implicitly[GeneratedCompanion[TestServiceFs2GrpcRenderContextAsImplicit]]
  }

  test("implicit of companion resolves trailers") {
    implicitly[GeneratedCompanion[TestServiceFs2GrpcRenderContextAsImplicitTrailers]]
  }

}
