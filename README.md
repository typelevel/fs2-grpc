# fs2-grpc - gRPC implementation for FS2/cats-effect

[![Gitter](https://badges.gitter.im/typelevel/fs2-grpc.svg)](https://gitter.im/typelevel/fs2-grpc?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge) [![CI](https://github.com/typelevel/fs2-grpc/actions/workflows/ci.yml/badge.svg)](https://github.com/typelevel/fs2-grpc/actions/workflows/ci.yml) [![Latest version](https://index.scala-lang.org/typelevel/fs2-grpc/sbt-fs2-grpc/latest.svg?color=orange&v=1)](https://index.scala-lang.org/typelevel/fs2-grpc/sbt-fs2-grpc)
![Code of Consuct](https://img.shields.io/badge/Code%20of%20Conduct-Scala-blue.svg) [![Scala Steward badge](https://img.shields.io/badge/Scala_Steward-helping-blue.svg?style=flat&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAAQCAMAAAARSr4IAAAAVFBMVEUAAACHjojlOy5NWlrKzcYRKjGFjIbp293YycuLa3pYY2LSqql4f3pCUFTgSjNodYRmcXUsPD/NTTbjRS+2jomhgnzNc223cGvZS0HaSD0XLjbaSjElhIr+AAAAAXRSTlMAQObYZgAAAHlJREFUCNdNyosOwyAIhWHAQS1Vt7a77/3fcxxdmv0xwmckutAR1nkm4ggbyEcg/wWmlGLDAA3oL50xi6fk5ffZ3E2E3QfZDCcCN2YtbEWZt+Drc6u6rlqv7Uk0LdKqqr5rk2UCRXOk0vmQKGfc94nOJyQjouF9H/wCc9gECEYfONoAAAAASUVORK5CYII=)](https://scala-steward.org)

## SBT configuration

`project/plugins.sbt`:
```scala
addSbtPlugin("org.typelevel" % "sbt-fs2-grpc" % "<latest-version>")
```

`build.sbt`:
```scala
enablePlugins(Fs2Grpc)
```

## Protocol buffer files

The protobuf files should be stored in the directory `<project_root>/src/main/protobuf`.

## Multiple projects

If the generated code is used by multiple projects, you may build the client/server code in a common project which other projects depend on. For example:

```scala
lazy val protobuf =
  project
    .in(file("protobuf"))
    .enablePlugins(Fs2Grpc)

lazy val client =
  project
    .in(file("client"))
    .dependsOn(protobuf)

lazy val server =
  project
    .in(file("server"))
    .dependsOn(protobuf)
```

## Creating a client

A `ManagedChannel` is the type used by `grpc-java` to manage a connection to a particular server. This library provides syntax for `ManagedChannelBuilder` which creates a `Resource` which can manage the shutdown of the channel, by calling `.resource[F]` where `F` has an instance of the `Sync` typeclass. This implementation will do a drain of the channel, and attempt to shut down the channel, forcefully closing after 30 seconds. An example of the syntax is:

```scala
import fs2.grpc.syntax.all._

val managedChannelResource: Resource[IO, ManagedChannel] =
  ManagedChannelBuilder
    .forAddress("127.0.0.1", 9999)
    .resource[IO]
```

The syntax also offers the method `resourceWithShutdown` which takes a function `ManagedChannel => F[Unit]` which is used to manage the shutdown. This may be used where requirements before shutdown do not match the default behaviour.

The generated code provides a method `stubResource[F]`, for any `F` which has a `Async` instance, and it takes a parameter of type `Channel` and `ClientOptions`. It returns a `Resource` with an implementation of the service (in a trait), which can be used to make calls.

```scala
import fs2.grpc.client.ClientOptions

def runProgram(stub: MyFs2Grpc[IO]): IO[Unit] = ???

val run: IO[Unit] = managedChannelResource
  .flatMap(ch => MyFs2Grpc.stubResource[IO](ch, ClientOptions.default))
  .use(runProgram)
```

If a `ManagedChannelProvider` isn't found on your classpath you may receive an error similar to 
```
io.grpc.ManagedChannelProvider$ProviderNotFoundException: No functional channel service provider found. Try adding a dependency on the grpc-okhttp or grpc-netty artifact
```
This can be fixed by adding a dependency to the [netty provider](https://github.com/grpc/grpc-java#transport) e.g.
```
libraryDependencies += "io.grpc" % "grpc-netty-shaded" % scalapb.compiler.Version.grpcJavaVersion
```

## Creating a server

The generated code provides a method `bindServiceResource[F]`, for any `F` which has a `Async` instance, and it takes an implementation of the service (in a trait) and `ServerOptions`, which is used to serve responses to RPC calls. It returns a `Resource[F, ServerServiceDefinition]` which is given to the server builder when setting up the service.

A `Server` is the type used by `grpc-java` to manage the server connections and lifecycle. This library provides syntax for `ServerBuilder`, which mirrors the pattern for the client. An example is:

```scala
import fs2.grpc.syntax.all._
import fs2.grpc.server.ServerOptions

val helloService: Resource[IO, ServerServiceDefinition] = 
  MyFs2Grpc.bindServiceResource[IO](new MyImpl(), ServerOptions.default)

def run(service: ServerServiceDefinition) = ServerBuilder
  .forPort(9999)
  .addService(service)
  .resource[IO]
  .evalMap(server => IO(server.start()))
  .useForever

helloService.use(run)
```

## Code generation options

To alter code generation, you can set some flags with `scalapbCodeGeneratorOptions`, e.g.:

```scala
scalapbCodeGeneratorOptions += CodeGeneratorOption.FlatPackage
```

The full set of options available are:

 - `CodeGeneratorOption.FlatPackage` - If true, the compiler does not append the proto base file name
 - `CodeGeneratorOption.JavaConversions` - Enable Java conversions for protobuf
 - `CodeGeneratorOption.Grpc` (included by default) - generate grpc bindings based on Observables
 - `CodeGeneratorOption.Fs2Grpc` (included by default) - generate grpc bindings for FS2/cats
 - `CodeGeneratorOption.SingleLineToProtoString` - `toProtoString` generates single line
 - `CodeGeneratorOption.AsciiFormatToString` - `toString` uses `toProtoString` functionality

## Pass additional protoc options

```scala
PB.protocOptions in Compile := Seq("-xyz")
```

### Tool Sponsorship

<img width="185px" height="44px" align="right" src="https://www.yourkit.com/images/yklogo.png"/>Development of fs2-grpc is generously supported in part by [YourKit](https://www.yourkit.com) through the use of their excellent Java profiler.


