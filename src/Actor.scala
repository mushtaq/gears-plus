package gears.async.actors

import gears.async.*
import java.util.concurrent.atomic.AtomicReference
import scala.util.{Try, Success, Failure}
import scala.util.control.NonFatal

// Custom exception types
class AskTimeoutException(message: String) extends Exception(message)
class ActorTerminatedException(message: String) extends Exception(message)

object Actor:
  /** Creates a new actor that protects a mutable resource and processes invocations serially.
    * 
    * The actor runs as a Future in the current Async.Spawn scope and will automatically
    * stop when the scope ends (structured concurrency).
    * 
    * @param logic The mutable state to protect
    * @param bufferSize Optional buffer size for the actor's mailbox (default: unbounded)
    * @return An ActorRef that can be used to send messages to the actor
    */
  def create[T](logic: T, bufferSize: Option[Int] = None, close: Option[T => Unit] = None)(using Async.Spawn): ActorRef[T] =
    val channel: Channel[Message[T, ?]] = bufferSize match
      case Some(size) => BufferedChannel(size)
      case None => UnboundedChannel()
    
    // Lock-free tracking of pending promises
    val pendingPromises = AtomicReference(Set.empty[Future.Promise[?]])
    
    val future = Future:
      try
        var continue = true
        while continue do
          channel.read() match
            case Right(msg) => 
              msg.process(logic, pendingPromises)
            case Left(_) => 
              continue = false
      finally
        // Immediately fail all pending promises (like Ox's behavior)
        val promises = pendingPromises.getAndSet(Set.empty)
        promises.foreach: promise =>
          promise.asInstanceOf[Future.Promise[Any]].complete(
            Failure(ActorTerminatedException("Actor has been terminated"))
          )
        
        // Close channel and run cleanup
        channel.close()
        close.foreach: cleanup =>
          try cleanup(logic)
          catch case NonFatal(_) => () // ignore cleanup errors
    
    ActorRef(channel, future, pendingPromises)

/** A reference to an actor that can be used to send messages via the ask pattern.
  * 
  * @param T The type of the actor's internal state
  */
class ActorRef[T] private[actors] (
  private val channel: Channel[Message[T, ?]], 
  private val future: Future[Unit],
  private val pendingPromises: AtomicReference[Set[Future.Promise[?]]]
) extends Cancellable:
  
  /** Send an invocation to the actor and await the result.
    * 
    * The function should be an invocation of a method on T and should not
    * directly or indirectly return the T value, as this might expose the
    * actor's internal mutable state to other threads.
    * 
    * Non-fatal exceptions thrown by f will be propagated to the caller
    * and the actor will continue processing other invocations.
    * Fatal exceptions will terminate the actor.
    * 
    * @param f The function to execute on the actor's state
    * @return The result of the function
    * @throws ActorTerminatedException if the actor is terminated
    */
  def ask[U](f: T => U)(using Async): U =
    val promise = Future.Promise[U]()
    
    // Add promise atomically
    pendingPromises.updateAndGet(_ + promise)
    
    val msg = Message(f, promise)
    
    try
      channel.send(msg.asInstanceOf[Message[T, ?]])
      
      // await directly returns U, not Try[U] in Gears
      val result = promise.asFuture.await
      // Remove promise on completion
      pendingPromises.updateAndGet(_ - promise)
      result
    catch
      case e: ChannelClosedException =>
        // Remove promise if channel closed
        pendingPromises.updateAndGet(_ - promise)
        throw ActorTerminatedException("Actor channel is closed")
  
  /** Cancel the actor, closing its channel and cancelling its future. */
  def cancel(): Unit =
    // Immediately fail all pending promises (like Ox's behavior)
    val promises = pendingPromises.getAndSet(Set.empty)
    promises.foreach: promise =>
      promise.asInstanceOf[Future.Promise[Any]].complete(
        Failure(ActorTerminatedException("Actor was cancelled"))
      )
    
    channel.close()
    future.cancel()
  
  /** Link this actor to a cancellation group. */
  override def link(group: CompletionGroup): this.type =
    future.link(group)
    this
  
  /** Unlink this actor from its cancellation group. */
  override def unlink(): this.type =
    future.unlink()
    this

/** Internal message type for actor communication */
private[actors] trait Message[T, U]:
  def process(logic: T, pendingPromises: AtomicReference[Set[Future.Promise[?]]]): Unit

private[actors] object Message:
  def apply[T, U](f: T => U, promise: Future.Promise[U]): Message[T, U] =
    new Message[T, U]:
      def process(logic: T, pendingPromises: AtomicReference[Set[Future.Promise[?]]]): Unit =
        try
          val result = f(logic)
          // Remove promise atomically on success
          pendingPromises.updateAndGet(_ - promise)
          promise.complete(Success(result))
        catch
          case NonFatal(e) =>
            // Remove promise atomically on non-fatal error
            pendingPromises.updateAndGet(_ - promise)
            promise.complete(Failure(e))
          case e: Throwable =>
            // Remove promise atomically on fatal error
            pendingPromises.updateAndGet(_ - promise)
            promise.complete(Failure(e))
            throw e  // Fatal exceptions kill the actor