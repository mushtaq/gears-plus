package gears.async.dst

import gears.async.*
import scala.concurrent.duration.*

/** Main API for Deterministic Simulation Testing with Gears.
  * 
  * Provides virtual time control for testing async code deterministically.
  * Time only advances when explicitly instructed via `advance()`.
  * 
  * Example usage:
  * ```scala
  * DST.withVirtualTime:
  *   val f = Future:
  *     AsyncOperations.sleep(100.millis)
  *     doSomething()
  *   
  *   assert(!f.poll().isDefined)  // Future hasn't completed
  *   DST.advance(100.millis)       // Advance virtual time
  *   assert(f.poll().isDefined)    // Future completed instantly
  * ```
  */
object DST:
  export DSTScheduler.{advance, nowMillis}
  /** Creates a test context with virtual time control.
    * Automatically resets the scheduler for test isolation.
    */
  def withVirtualTime[T](body: (Async.Spawn, AsyncOperations) ?=> T): T =
    DSTScheduler.reset()
    given AsyncOperations = DSTAsyncOperations
    given DSTSupport.type = DSTSupport
    given DSTSupport.Scheduler = DSTScheduler
    Async.blocking:
      body
