package gears.async.actors

import gears.async.*
import gears.async.default.given
import utest.*

// Example 1: Simple Counter
class Counter:
  private var count = 0
  
  def increment(n: Int): Int =
    count += n
    count
  
  def decrement(n: Int): Int =
    count -= n
    count
  
  def getValue(): Int = count
  
  def reset(): Unit =
    count = 0

// Example 2: Bank Account with validation
class BankAccount(initialBalance: Double):
  private var balance = initialBalance
  
  def deposit(amount: Double): Double =
    require(amount > 0, "Deposit amount must be positive")
    balance += amount
    balance
  
  def withdraw(amount: Double): Double =
    require(amount > 0, "Withdrawal amount must be positive")
    if balance >= amount then
      balance -= amount
      balance
    else
      throw new IllegalStateException(s"Insufficient funds: $balance < $amount")
  
  def getBalance(): Double = balance
  
  def transfer(to: BankAccount, amount: Double): Double =
    withdraw(amount)
    to.deposit(amount)
    balance

// Example 3: Resource Manager with cleanup
class ResourceManager:
  private var resources = List.empty[String]
  private var closed = false
  
  def acquire(resource: String): List[String] =
    if closed then
      throw new IllegalStateException("ResourceManager is closed")
    resources = resource :: resources
    resources
  
  def release(resource: String): List[String] =
    resources = resources.filter(_ != resource)
    resources
  
  def listResources(): List[String] = resources
  
  def cleanup(): Unit =
    resources = List.empty
    closed = true

object ActorExamplesTest extends TestSuite:
  val tests = Tests:
    test("Example: Basic counter operations"):
      Async.blocking:
        val counter = Counter()
        val actor = Actor.create(counter, name = "counter")
        
        val r1 = actor.ask(_.increment(5))
        assert(r1 == 5)
        
        val r2 = actor.ask(_.increment(3))
        assert(r2 == 8)
        
        val r3 = actor.ask(_.decrement(2))
        assert(r3 == 6)
        
        val current = actor.ask(_.getValue())
        assert(current == 6)
    
    test("Example: Concurrent counter operations"):
      Async.blocking:
        val counter = Counter()
        val actor = Actor.create(counter, name = "concurrent-counter")
        
        // Launch 10 concurrent increments
        val futures = (1 to 10).map: _ =>
          Future:
            actor.ask(_.increment(1))
        
        val results = Future.awaitAll(futures)
        assert(results.length == 10)
        
        val final_value = actor.ask(_.getValue())
        assert(final_value == 10)
    
    test("Example: Bank account with error handling"):
      Async.blocking:
        val account = BankAccount(100.0)
        val actor = Actor.create(account, name = "bank-account")
        
        assert(actor.ask(_.getBalance()) == 100.0)
        
        try
          actor.ask(_.withdraw(150.0))
          assert(false) // Should have thrown exception
        catch
          case e: IllegalStateException =>
            assert(e.getMessage.contains("Insufficient funds"))
        
        // Actor continues working after error
        val balance = actor.ask(_.deposit(50.0))
        assert(balance == 150.0)
    
    test("Example: Structured concurrency"):
      Async.blocking:
        val finalBalance = Async.group:
          val account = BankAccount(1000.0)
          val actor = Actor.create(account, name = "scoped-account")
          
          actor.ask(_.deposit(500.0))
          actor.ask(_.withdraw(200.0))
          actor.ask(_.getBalance())
          // Actor automatically terminates when scope ends
        
        assert(finalBalance == 1300.0)
    
    test("Example: Resource cleanup"):
      Async.blocking:
        val manager = ResourceManager()
        var cleanupCalled = false
        
        Async.group:
          val actor = Actor.create(
            manager,
            name = "resource-manager",
            close = Some { m =>
              m.cleanup()
              cleanupCalled = true
            }
          )
          
          actor.ask(_.acquire("database"))
          actor.ask(_.acquire("file-handle"))
          actor.ask(_.acquire("network-socket"))
          
          val resources = actor.ask(_.listResources())
          assert(resources.length == 3)
          assert(resources.contains("database"))
          assert(resources.contains("file-handle"))
          assert(resources.contains("network-socket"))
        // Cleanup is called automatically when scope ends
        
        assert(cleanupCalled)
        assert(manager.listResources().isEmpty)
    
    test("Example: Backpressure with buffered channels"):
      Async.blocking:
        val counter = Counter()
        val actor = Actor.create(
          counter,
          name = "buffered-actor",
          bufferSize = Some(3)  // Only 3 pending requests allowed
        )
        
        // This will apply backpressure if too many requests queue up
        val futures = (1 to 5).map: i =>
          Future:
            actor.ask(_.increment(i))
        
        Future.awaitAll(futures)
        assert(actor.ask(_.getValue()) == 15) // 1+2+3+4+5
    
    test("Example: Actor cancellation"):
      Async.blocking:
        val counter = Counter()
        val actor = Actor.create(counter, name = "cancellable")
        
        actor.ask(_.increment(10))
        assert(actor.ask(_.getValue()) == 10)
        
        // Explicitly cancel the actor
        actor.cancel()
        
        try
          actor.ask(_.increment(5))
          assert(false) // Should have thrown exception
        catch
          case e: ActorTerminatedException =>
            assert(e.getMessage.contains("cancellable"))
    
    test("Example: Multiple independent actors"):
      Async.blocking:
        val counter1 = Counter()
        val counter2 = Counter()
        val actor1 = Actor.create(counter1, "actor1")
        val actor2 = Actor.create(counter2, "actor2")
        
        // Send operations to both actors concurrently
        val futures = List(
          Future(actor1.ask(_.increment(10))),
          Future(actor2.ask(_.increment(20))),
          Future(actor1.ask(_.increment(5))),
          Future(actor2.ask(_.increment(3)))
        )
        
        Future.awaitAll(futures)
        
        // Each actor should have its own state
        assert(actor1.ask(_.getValue()) == 15)
        assert(actor2.ask(_.getValue()) == 23)
    
    test("Example: Bank account transfers"):
      Async.blocking:
        val account1 = BankAccount(1000.0)
        val account2 = BankAccount(500.0)
        
        // Note: This is NOT transactional across actors
        // Each actor manages its own state independently
        val actor1 = Actor.create(account1, "account1")
        val actor2 = Actor.create(account2, "account2")
        
        // Simulate transfer by coordinating operations
        val amount = 200.0
        actor1.ask(_.withdraw(amount))
        actor2.ask(_.deposit(amount))
        
        assert(actor1.ask(_.getBalance()) == 800.0)
        assert(actor2.ask(_.getBalance()) == 700.0)