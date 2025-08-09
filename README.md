# Gears+

Additional utilities for the [Gears](https://github.com/lampepfl/gears) async library, providing higher-level concurrency abstractions built on top of Gears' lightweight async runtime.

## Features

- **Actors**: Type-safe, ask-only actors for managing mutable state
- **DST (Deterministic Simulation Testing)**: Virtual time control for testing async code

## Installation

```scala
//> using dep "ch.epfl.lamp::gears:0.2.0"
//> using file "src/Actor.scala"
//> using file "src/dst/*.scala"  // For DST support
```

## Quick Start

```scala
import gears.async.*
import gears.async.actors.*
import gears.async.default.given

// Define your mutable state
class Counter:
  private var count = 0
  
  def increment(n: Int): Int =
    count += n
    count
  
  def getValue(): Int = count

// Use it with an actor
@main def example(): Unit =
  Async.blocking:
    val counter = Counter()
    val actor = Actor.create(counter, name = "counter")
    
    val result = actor.ask(_.increment(5))
    println(s"Result: $result") // Result: 5
```

## Actor API

```scala
Actor.create[T](
  logic: T,                       // The mutable state to protect
  name: String = "unnamed",       // Actor name for debugging
  bufferSize: Option[Int] = None, // Optional buffer size for backpressure
  close: Option[T => Unit] = None // Optional cleanup function
)
```

The actor provides an ask-only pattern (no fire-and-forget tell):

```scala
val result = actor.ask(_.someMethod(args))
```

All operations are executed serially, ensuring thread-safe access to mutable state.

## Key Concepts

**Structured Concurrency** - Actors are tied to their creation scope and automatically terminate when the scope ends:

```scala
Async.blocking:
  val result = Async.group:
    val actor = Actor.create(Counter())
    actor.ask(_.increment(10))
    // Actor terminates when this scope ends
```

**Error Handling** - Non-fatal exceptions are propagated to the caller without killing the actor:

```scala
try
  actor.ask(_.riskyOperation())
catch
  case e: Exception => 
    println(s"Operation failed: ${e.getMessage}")
    // Actor is still alive
```

**Backpressure** - Control memory usage with buffered channels:

```scala
val actor = Actor.create(
  state,
  bufferSize = Some(100) // Max 100 pending requests
)
```

**Resource Cleanup** - Automatic cleanup when actors terminate:

```scala
val actor = Actor.create(
  resource,
  close = Some(_.cleanup())
)
```

## Examples

### Concurrent Operations

```scala
Async.blocking:
  val actor = Actor.create(Counter())
  
  val futures = (1 to 10).map: i =>
    Future:
      actor.ask(_.increment(i))
  
  val results = Future.awaitAll(futures)
```

### Bank Account

```scala
class BankAccount(initialBalance: Double):
  private var balance = initialBalance
  
  def deposit(amount: Double): Double =
    require(amount > 0)
    balance += amount
    balance
  
  def withdraw(amount: Double): Double =
    if balance >= amount then
      balance -= amount
      balance
    else
      throw new IllegalStateException("Insufficient funds")

Async.blocking:
  val account = BankAccount(1000.0)
  val actor = Actor.create(account)
  
  actor.ask(_.deposit(500.0))   // 1500.0
  actor.ask(_.withdraw(200.0))  // 1300.0
```

All examples are tested in [test/ActorExamplesTest.scala](test/ActorExamplesTest.scala).

## Deterministic Simulation Testing (DST)

DST provides virtual time control for testing time-dependent async code deterministically. Time only advances when explicitly instructed, making tests fast and reproducible.

### Basic Usage

```scala
import gears.async.*
import gears.async.dst.DST
import gears.async.dst.DST.given
import scala.concurrent.duration.*

DST.withVirtualTime:
  var completed = false
  
  Future:
    AsyncOperations.sleep(100.millis)
    completed = true
  
  assert(!completed)       // Future hasn't completed yet
  DST.advance(100.millis)  // Advance virtual time
  assert(completed)        // Now it's done instantly
```

### DST API

- `DST.withVirtualTime[T](body: Async.Spawn ?=> T): T` - Creates a test context with virtual time
- `DST.advance(duration: FiniteDuration)` - Advances virtual time by the specified duration
- `DST.nowMillis: Long` - Returns the current virtual time in milliseconds

### Testing Time-Dependent Code

```scala
DST.withVirtualTime:
  val timer = Timer(1.second)
  var ticks = 0
  
  Future:
    timer.run()
  
  timer.src.onComplete: _ =>
    ticks += 1
  
  DST.advance(500.millis)
  assert(ticks == 0)  // Not enough time passed
  
  DST.advance(500.millis)
  assert(ticks == 1)  // Timer fired after 1 second
  
  DST.advance(2.seconds)
  assert(ticks == 3)  // Timer fired 2 more times
```

### Testing Actors with DST

```scala
DST.withVirtualTime:
  val actor = Actor.create(RateLimitedService())
  
  // Schedule multiple operations
  val f1 = Future(actor.ask(_.process("request1")))
  val f2 = Future.delay(100.millis)(actor.ask(_.process("request2")))
  
  // Nothing happens until time advances
  assert(!f1.poll().isDefined)
  
  DST.advance(50.millis)
  assert(f1.poll().isDefined)   // First request completes
  assert(!f2.poll().isDefined)  // Second still waiting
  
  DST.advance(50.millis)
  assert(f2.poll().isDefined)   // Second request completes
```

### Key Benefits

- **Deterministic**: Same time advancements always produce same results
- **Fast**: No actual waiting - tests complete instantly
- **Precise**: Control time with millisecond precision
- **Isolated**: Each test starts with time at 0

## Design Philosophy

- **Ask-only pattern**: No tell pattern to maintain type safety and simplicity
- **Function-based API**: Direct function application (`T => U`) instead of message ADTs
- **Structured concurrency first**: Actors tied to async scopes for automatic resource management
- **Fail-fast semantics**: Clear error propagation and immediate failure of pending operations

## Requirements

- Scala 3.3+
- Gears 0.2.0+
- Java 21+

## License

Apache 2.0 - See [LICENSE](LICENSE) for details.