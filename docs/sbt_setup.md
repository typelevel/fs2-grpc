# SBT Setup

## Essential SBT configuration

To enable the fs2-grpc plugin:

!!! code "`project/plugins.sbt`"
    ```scala
    addSbtPlugin("org.lyranthe.fs2-grpc" % "sbt-java-gen" % "0.3.0")
    ```

Add the following to the project that contains the service definitions:

!!! code "`build.sbt`"
    ```scala
    enablePlugins(Fs2Grpc)
    ```

## Advanced SBT settings

!!! summary "`grpcJavaVersion`"
    Version of `grpc-java` used (defaults to same as ScalaPB). You might change this if you wanted to use some
    features of a newer `grpc-java` library than the one supported by ScalaPB. There are potential issues with
    binary compatibility, so care is required if changing this value. 

!!! summary "`scalapbCodeGeneratorOptions`"
    Code generator options passed to ScalaPB

     - `CodeGeneratorOption.FlatPackage`
     - `CodeGeneratorOption.JavaConversions`
     - `CodeGeneratorOption.Grpc`
     - `CodeGeneratorOption.Fs2Grpc`
     - `CodeGeneratorOption.SingleLineToProtoString`
     - `CodeGeneratorOption.AsciiFormatToString`

!!! summary "`scalapbProtobufDirectory`"
    Directory which contains generated code after the service definitions are processed. The default directory
    is under the `sourceManaged` directory, which is automatically included in the set of source files used
    for compilation. It should not need to be modified, except until special circumstances.

!!! summary "`scalapbCodeGenerators`"
    Code generators used by ScalaPB. This is usually managed by this plugin, based on the values from
    `scalapbCodeGeneratorOptions`, you should not need to modify it unless you have to manually add a
    generator other than ScalaPB or this plugin.

