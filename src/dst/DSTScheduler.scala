package gears.async.dst

import gears.async.{Scheduler, Cancellable}
import scala.concurrent.duration.*
import scala.collection.mutable

/** Deterministic Simulation Testing (DST) scheduler for virtual time control.
  * 
  * This scheduler maintains a virtual clock that only advances when explicitly
  * instructed, enabling deterministic testing of time-dependent async code.
  * Tasks scheduled for future execution are queued and only run when virtual
  * time is advanced past their scheduled time.
  */
class DSTScheduler extends Scheduler:
  private case class Task(at: Long, body: Runnable, var cancelled: Boolean = false)
  private given Ordering[Task] = Ordering.by[Task, Long](_.at).reverse // min-heap
  
  private val queue = mutable.PriorityQueue.empty[Task]
  private val readyQueue = new java.util.ArrayDeque[Runnable]()
  private var currentTimeMillis: Long = 0L
  
  def nowMillis: Long = currentTimeMillis
  
  // Prevents recursive draining when tasks schedule immediate work
  private var draining = false
  
  override def execute(body: Runnable): Unit = 
    readyQueue.addLast(body)
    if !draining then
      drainReadyQueue()
    
  override def schedule(delay: FiniteDuration, body: Runnable): Cancellable =
    val runAt = currentTimeMillis + delay.toMillis
    val task = Task(runAt, body)
    queue.enqueue(task)
    new Cancellable:
      def cancel(): Unit = task.cancelled = true
  
  /** Advances virtual time by the specified duration and runs all tasks
    * scheduled to execute up to the new time.
    */
  def advance(duration: FiniteDuration): Unit =
    require(duration >= Duration.Zero, "Cannot advance time backwards")
    currentTimeMillis += duration.toMillis
    
    // Move all due tasks to ready queue
    while queue.headOption.exists(_.at <= currentTimeMillis) do
      val task = queue.dequeue()
      if !task.cancelled then readyQueue.addLast(task.body)
    
    drainReadyQueue()
    
  private def drainReadyQueue(): Unit =
    draining = true
    try
      while !readyQueue.isEmpty do 
        readyQueue.removeFirst().run()
    finally
      draining = false
      
  /** Drains all remaining ready tasks without advancing time. */
  def drainAll(): Unit =
    drainReadyQueue()
  
  /** Resets the scheduler to initial state for a new test. */
  def reset(): Unit =
    currentTimeMillis = 0L
    queue.clear()
    readyQueue.clear()
    draining = false
