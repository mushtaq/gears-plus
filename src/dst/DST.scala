package gears.async.dst

import gears.async.*

import scala.concurrent.duration.FiniteDuration

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
  /** Advances virtual time by the specified duration.
    * This is a convenience method for use within withVirtualTime context.
    */
  def advance(duration: FiniteDuration)(using scheduler: DSTScheduler): Unit =
    scheduler.advance(duration)
  
  /** Returns the current virtual time in milliseconds.
    * This is a convenience method for use within withVirtualTime context.
    */
  def nowMillis(using scheduler: DSTScheduler): Long =
    scheduler.nowMillis
  
  /** Creates a test context with virtual time control.
    * 
    * @param body The test code to execute with virtual time control.
    * @return The result of the test body.
    */
  def withVirtualTime[T](body: (Async.Spawn, AsyncOperations, DSTScheduler) ?=> T): T =
    val scheduler: DSTScheduler = new DSTScheduler
    scheduler.drainAll()
    scheduler.reset()
    given AsyncOperations = DSTAsyncOperations
    given DSTSupport.type = DSTSupport
    given DSTSupport.Scheduler = scheduler
    Async.blocking:
      body
