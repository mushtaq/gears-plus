package gears.async.dst

import gears.async.*
import scala.concurrent.duration.*
import utest.*

object DSTTest extends TestSuite:
  val tests = Tests:
    test("sleep suspends until time advances"):
      DST.withVirtualTime:
        var completed = false
        
        Future:
          AsyncOperations.sleep(100.millis)
          completed = true
        
        assert(!completed) // Future should not complete before time advances
        
        DST.advance(100.millis)
        assert(completed) // Future should complete after time advances
    
    test("multiple tasks execute at correct times"):
      DST.withVirtualTime:
        var counter = 0
        
        Future:
          AsyncOperations.sleep(50.millis)
          counter += 1
          
        Future:
          AsyncOperations.sleep(100.millis)
          counter += 10
          
        assert(counter == 0)
        
        DST.advance(50.millis)
        assert(counter == 1) // Only 50ms task should have run
        
        DST.advance(50.millis)
        assert(counter == 11) // Both tasks should have completed
    
    test("virtual time tracking"):
      DST.withVirtualTime:
        assert(DST.nowMillis == 0)
        
        DST.advance(500.millis)
        assert(DST.nowMillis == 500)
        
        DST.advance(1.second)
        assert(DST.nowMillis == 1500)
    
    test("task cancellation"):
      DST.withVirtualTime:
        var executed = false
        
        val future = Future:
          AsyncOperations.sleep(100.millis)
          executed = true
        
        future.cancel()
        DST.advance(100.millis)
        
        assert(!executed) // Cancelled task should not execute
    
    test("scheduler resets between tests"):
      DST.withVirtualTime:
        DST.advance(1.hour)
        assert(DST.nowMillis == 3600000)
      
      DST.withVirtualTime:
        assert(DST.nowMillis == 0) // Time should reset for new test