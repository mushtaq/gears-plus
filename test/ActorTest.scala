package gears.async.actors

import gears.async.*
import gears.async.default.given
import utest.*

// Test state class
class Counter:
  private var count = 0
  
  def increment(n: Int): Int =
    count += n
    count
  
  def getValue(): Int = count
  
  def decrement(n: Int): Int =
    count -= n
    count
  
  def throwError(): Int =
    throw new RuntimeException("Test error")

object ActorTest extends TestSuite:
  val tests = Tests:
    test("Basic ask pattern"):
      Async.blocking:
        val counter = Counter()
        val actor = Actor.create(counter)
        
        val result1 = actor.ask(_.increment(5))
        assert(result1 == 5)
        
        val result2 = actor.ask(_.increment(3))
        assert(result2 == 8)
        
        val result3 = actor.ask(_.getValue())
        assert(result3 == 8)

    test("Concurrent ask calls"):
      Async.blocking:
        val counter = Counter()
        val actor = Actor.create(counter)
        
        // Launch multiple concurrent operations
        val futures = (1 to 10).map: _ =>
          Future:
            actor.ask(_.increment(1))
        
        // Wait for all to complete
        val results = Future.awaitAll(futures)
        
        // Check final value
        val finalValue = actor.ask(_.getValue())
        assert(finalValue == 10)
        
        // Check that all increments happened (results should be 1 through 10)
        val sortedResults = results.sorted
        assert(sortedResults == (1 to 10).toList)

    test("Error handling in ask"):
      Async.blocking:
        val counter = Counter()
        val actor = Actor.create(counter)
        
        // First operation succeeds
        val result1 = actor.ask(_.increment(5))
        assert(result1 == 5)
        
        // Second operation throws exception
        try
          actor.ask(_.throwError())
          assert(false)
        catch
          case e: RuntimeException if e.getMessage == "Test error" =>
            // Expected
        
        // Actor should still be alive and processing
        val result3 = actor.ask(_.increment(2))
        assert(result3 == 7)

    test("Actor cancellation"):
      Async.blocking:
        val counter = Counter()
        val actor = Actor.create(counter)
        
        val result1 = actor.ask(_.increment(5))
        assert(result1 == 5)
        
        // Cancel the actor
        actor.cancel()
        
        // Further asks should fail
        try
          actor.ask(_.increment(3))
          assert(false)
        catch
          case _: ActorTerminatedException =>
            // Expected - actor was cancelled

    test("Actor names in error messages"):
      Async.blocking:
        val counter = Counter()
        val actor = Actor.create(counter, "named-actor")
        
        // Cancel the actor
        actor.cancel()
        
        // Try to use cancelled actor
        try
          actor.ask(_.increment(1))
          assert(false)
        catch
          case e: ActorTerminatedException =>
            // Error message should include actor name
            assert(e.getMessage.contains("named-actor"))
            assert(e.getMessage.contains("channel is closed"))

    test("Structured concurrency"):
      var actorRef: Option[ActorRef[Counter]] = None
      
      Async.blocking:
        Async.group:
          val counter = Counter()
          val actor = Actor.create(counter)
          actorRef = Some(actor)
          
          val result = actor.ask(_.increment(10))
          assert(result == 10)
        // Scope ends here, actor should be cancelled automatically
      
      // Try to use actor outside its scope
      Async.blocking:
        try
          actorRef.get.ask(_.increment(5))
          assert(false)
        catch
          case _: ActorTerminatedException =>
            // Expected - actor was terminated when scope ended
          case _: ChannelClosedException =>
            // Also acceptable - channel closed
    
    test("Pending promises cleanup on cancellation"):
      Async.blocking:
        val counter = Counter()
        val actor = Actor.create(counter, "cleanup-test")
        
        // Start a slow operation in a separate future
        val pendingFuture = Future:
          try
            actor.ask: c =>
              Thread.sleep(100) // Intentionally blocking to ensure operation is in-progress when cancelled
              c.increment(10)
            assert(false) // Should not reach here
          catch
            case e: ActorTerminatedException =>
              // Expected - actor was cancelled while operation was pending
              assert(e.getMessage.contains("cleanup-test"))
              assert(e.getMessage.contains("cancelled"))
        
        // Give the ask some time to be sent to the actor
        // Using Thread.sleep here because we need to ensure timing outside async context
        Thread.sleep(20)
        
        // Cancel the actor while the operation is in progress
        actor.cancel()
        
        // The pending future should fail with ActorTerminatedException
        pendingFuture.await
    
    test("Cleanup callback on termination"):
      Async.blocking:
        var cleanupCalled = false
        val counter = Counter()
        
        Async.group:
          val actor = Actor.create(
            counter,
            name = "cleanup-actor",
            close = Some(_ => cleanupCalled = true)
          )
          
          actor.ask(_.increment(5))
        // Scope ends, actor should be terminated and cleanup called
        
        assert(cleanupCalled)
    
    test("Buffered channel backpressure"):
      Async.blocking:
        val counter = Counter()
        // Create actor with small buffer
        val actor = Actor.create(counter, bufferSize = Some(2))
        
        // Send multiple operations concurrently
        val futures = (1 to 5).map: i =>
          Future:
            actor.ask(_.increment(i))
        
        // All should complete successfully despite buffer size
        Future.awaitAll(futures)
        // Results are cumulative: 1, 1+2=3, 3+3=6, 6+4=10, 10+5=15
        // But order may vary due to concurrency, so just check final value
        val finalValue = actor.ask(_.getValue())
        assert(finalValue == 15) // 1+2+3+4+5
    
    test("Actor serializes operations"):
      Async.blocking:
        var executionStartTimes = List.empty[Long]
        var executionEndTimes = List.empty[Long]
        
        class OrderTracker:
          def slowOperation(n: Int): Int =
            executionStartTimes = executionStartTimes :+ System.nanoTime()
            Thread.sleep(10) // Intentionally blocking to verify true serialization
            executionEndTimes = executionEndTimes :+ System.nanoTime()
            n
        
        val tracker = OrderTracker()
        val actor = Actor.create(tracker)
        
        // Send operations concurrently
        val futures = (1 to 3).map: i =>
          Future:
            actor.ask(_.slowOperation(i))
        
        // Wait for all to complete
        val results = Future.awaitAll(futures)
        
        // Verify all operations completed
        assert(results.toSet == Set(1, 2, 3))
        
        // Verify operations were serialized (no overlap in execution)
        // Each operation should start after the previous one ended
        for i <- 1 until executionStartTimes.length do
          assert(executionStartTimes(i) >= executionEndTimes(i - 1))
        
        assert(executionStartTimes.length == 3)
        assert(executionEndTimes.length == 3)


