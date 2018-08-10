# Creating a Server

The library includes helper syntax for creating an `fs2.Stream` to manage
the lifecycle of a `grpc-java` server. This makes the library easier to
use when you are trying to write your program using FS2 and cats-effect.

## ServerBuilder syntax

You can either import all syntax, or use the à la carte option:

```scala tab="All syntax"
import org.lyranthe.fs2_grpc.java_runtime.syntax.all._
```

```scala tab="Only ServerBuilder syntax"
import org.lyranthe.fs2_grpc.java_runtime.syntax.serverBuilder._
```

## Using

