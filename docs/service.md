# Defining a Service

## Creating the service definition

Every service is defined by a `service` section inside a Protocol Buffer format description.

!!! code "`src/main/protobuf/service.proto`"

    ```proto
    syntax = "proto3";
    
    package my.srv;
    
    service MyService {
        // Define service here
    }
    ```
    
This will generate a class `my.srv.service.MyServiceFs2` (a bit of a mouthful).
Let's describe where each component comes from:

```my.srv```
: This comes from the `package` stanza in the definition.

```service```
: This comes from the name of the file (`service.proto`). You can remove this
feature of ScalaPB by setting `CodeGeneratorOption.FlatPackage`.

```MyServiceFs2```
: This comes from the name of the service, with`Fs2` appended.

The service will have this look:

!!! code
    ```scala
    package my.srv.service
    
    trait MyServiceFs2[F[_]] {
       // Methods here
    } 
    ```
    
Note that the service is defined in so-called "tagless-final" style, to make
the trait flexible to use, and easy to test.

??? hint "Finally Tagless"
    Finally Tagless is an approach to writing interfaces which leaves the effect
    type undefined until a later implementation, but allows the effect to be
    circumscribed by requiring a typeclass instance. 

## API call types

### 1 request, 1 response

This is the traditional RPC model, with one request giving rise to one response.

```proto tab="service.proto"
service MyService {
    rpc SayHello (RequestType) returns (ResponseType) {}
}
```

```scala tab="Generated trait"
trait MyServiceFs2[F[_]] {
   def sayHello(request: RequestType): F[ResponseType]
}
```

??? note "Client Implementation Summary"
    The client sends the request, and then waits for the response.

??? note "Server Implementation Summary"
    When the server is supplied the request, it computes the response using the
    request as input.

### 1 request, 0+ responses

This is a streaming response model, where the information used to generate the
responses is generally parameterised by the request provided.

```proto tab="service.proto"
service MyService {
    rpc SayHello (RequestType) returns (stream ResponseType) {}
}
```

```scala tab="Generated trait"
trait MyServiceFs2[F[_]] {
   def sayHello(request: RequestType): fs2.Stream[F, ResponseType]
}
```

??? note "Client Implementation Summary"
    The client sends the request, and then waits for zero or more responses.

??? note "Server Implementation Summary"
    When the server is supplied the request, it computes a stream of responses
    using the request as input.

### 0+ requests, 1 response

This is a streaming request model, generally the response represents some kind
of summary or result after all requests are sent.

```proto tab="service.proto"
service MyService {
    rpc SayHello (stream RequestType) returns (ResponseType) {}
}
```

```scala tab="Generated trait"
trait MyServiceFs2[F[_]] {
   def sayHello(request: fs2.Stream[F, RequestType]): F[ResponseType]
}
```

??? note "Client Implementation Summary"
    The client sends a stream of requests, and then waits for the response.

??? note "Server Implementation Summary"
    When the server is supplied a stream of requests, it computes the response
    after the stream is terminated, using the stream as input.

### 0+ requests, 0+ responses

This provides bidirectional streaming; both client and server can send requests
or responses whenever they want.

```proto tab="service.proto"
service MyService {
    rpc SayHello (stream RequestType) returns (stream ResponseType) {}
}
```

```scala tab="Generated trait"
trait MyServiceFs2[F[_]] {
   def sayHello(request: fs2.Stream[F, RequestType]): fs2.Stream[F, ResponseType]
}
```

??? note "Client Implementation Summary"
    The client sends a stream of requests, and asynchronously waits for a
    stream of responses. Either side can terminate the stream 
 
??? note "Server Implementation Summary"
    Blah blah

## Messages

