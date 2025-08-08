import gears.async.*
import gears.async.actors.*
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
        val futures = (1 to 10).map: i =>
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
          case _: ChannelClosedException =>
            // Expected

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
          case _: ChannelClosedException =>
            // Expected - actor was cancelled when scope ended