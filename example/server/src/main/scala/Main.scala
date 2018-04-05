import cats.effect.IO
import cats.implicits._
import com.example.v1alpha.hello._
import fs2._
import io.grpc._
import io.grpc.protobuf.services.ProtoReflectionService
import io.opencensus.contrib.grpc.metrics.RpcViews
import io.opencensus.contrib.zpages.ZPageHandlers
import io.opencensus.exporter.trace.stackdriver.{
  StackdriverTraceConfiguration,
  StackdriverTraceExporter
}
import io.opencensus.trace.config.TraceParams
import io.opencensus.trace.samplers.Samplers
import io.opencensus.trace.Tracing

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.JavaConverters._

class ExampleTracingImplementation(client: GreeterFs2Grpc[IO])
    extends GreeterFs2Grpc[Trace[IO, ?]] {
  override def sayHello(request: HelloRequest,
                        clientHeaders: Metadata): Trace[IO, HelloReply] = {
    if (request.retry > 0)
      Trace.liftF(
        client.sayHello(request.copy(retry = request.retry - 1), clientHeaders))
    else
      (Trace.addAnnotation[IO]("Sleeping for 2 seconds") *>
        Trace.liftF(IO.sleep(2.seconds)))
        .withSpan("sleeping around")
        .as(HelloReply("Name is: " + request.name))
  }

  override def sayHelloStream(
      request: Stream[Trace[IO, ?], HelloRequest],
      clientHeaders: Metadata): Stream[Trace[IO, ?], HelloReply] = {
    request.evalMap(sayHello(_, clientHeaders))
  }
}

object Main {

  val helloClient = {
    val channel =
      ManagedChannelBuilder.forAddress("localhost", 9999).usePlaintext().build()
    GreeterFs2Grpc.stub[IO](channel)
  }

  def main(args: Array[String]): Unit = {
    StackdriverTraceExporter.createAndRegister(
      StackdriverTraceConfiguration
        .builder()
        .setProjectId("mytest")
        .build())

    Tracing.getTraceConfig.updateActiveTraceParams(
      TraceParams.DEFAULT.toBuilder.setSampler(Samplers.alwaysSample).build)

    /*
      These two stanzas should be removed after https://github.com/scalapb/ScalaPB/issues/425
     */
    Tracing.getExportComponent.getSampledSpanStore
      .registerSpanNamesForCollection(
        GreeterGrpc.javaDescriptor.getMethods.asScala
          .map(m => "Recv." + m.getFullName)
          .asJava)

    Tracing.getExportComponent.getSampledSpanStore
      .registerSpanNamesForCollection(
        GreeterGrpc.javaDescriptor.getMethods.asScala
          .map(m => "Sent." + m.getFullName)
          .asJava)

    val server: Server =
      ServerBuilder
        .forPort(9999)
        .addService(GreeterFs2Grpc.bindService(
          new ExampleTracingImplementation(helloClient)))
        .addService(ProtoReflectionService.newInstance())
        .build()

    ZPageHandlers.startHttpServerAndRegisterAll(8080)
    RpcViews.registerAllViews()

    server.start()
    server.awaitTermination()
  }
}
