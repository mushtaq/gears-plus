package gears.async.dst

import gears.async.*
import scala.concurrent.duration.*

/** AsyncOperations implementation for deterministic time control.
  * 
  * Critical: JvmAsyncOperations uses Thread.sleep() which bypasses the scheduler.
  * This implementation ensures sleep() properly delegates to the scheduler,
  * enabling virtual time control for deterministic testing.
  */
object DSTAsyncOperations extends AsyncOperations:
  override def sleep(millis: Long)(using async: Async): Unit =
    Future
      .withResolver[Unit]: resolver =>
        val cancellable = async.scheduler.schedule(millis.millis, () => resolver.resolve(()))
        resolver.onCancel: () =>
          cancellable.cancel()
          resolver.rejectAsCancelled()
      .link()
      .await