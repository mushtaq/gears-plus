package gears.async.actors

import gears.async.*
import scala.util.{Try, Success, Failure}
import scala.util.control.NonFatal

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
  def create[T](logic: T, bufferSize: Option[Int] = None)(using Async.Spawn): ActorRef[T] =
    val channel: Channel[Message[T, ?]] = bufferSize match
      case Some(size) => BufferedChannel(size)
      case None => UnboundedChannel()
    
    val future = Future:
      try
        var continue = true
        while continue do
          channel.read() match
            case Right(msg) => 
              msg.process(logic)
            case Left(_) => 
              continue = false
      finally
        // Ensure channel is closed when future ends
        channel.close()
    
    ActorRef(channel, future)

/** A reference to an actor that can be used to send messages via the ask pattern.
  * 
  * @param T The type of the actor's internal state
  */
class ActorRef[T] private[actors] (
  private val channel: Channel[Message[T, ?]], 
  private val future: Future[Unit]
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
    * @throws Exception if f throws an exception
    */
  def ask[U](f: T => U)(using Async): U =
    val promise = Future.Promise[U]()
    val msg = Message(f, promise)
    
    channel.send(msg.asInstanceOf[Message[T, ?]])
    
    promise.asFuture.await
  
  /** Cancel the actor, closing its channel and cancelling its future. */
  def cancel(): Unit =
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
  def process(logic: T): Unit

private[actors] object Message:
  def apply[T, U](f: T => U, promise: Future.Promise[U]): Message[T, U] =
    new Message[T, U]:
      def process(logic: T): Unit =
        try
          val result = f(logic)
          promise.complete(Success(result))
        catch
          case NonFatal(e) =>
            promise.complete(Failure(e))
          case e: Throwable =>
            promise.complete(Failure(e))
            throw e