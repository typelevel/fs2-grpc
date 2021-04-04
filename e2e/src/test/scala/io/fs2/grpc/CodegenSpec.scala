/*
 * Copyright (c) 2018 Gary Coady
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
