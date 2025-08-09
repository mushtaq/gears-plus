package gears.async.dst

import gears.async.{AsyncSupport, VThreadSupport}

/** AsyncSupport implementation that binds Gears' async machinery to DSTScheduler.
  * 
  * This bridges the gap between Gears' continuation system and our virtual time
  * scheduler by:
  * - Reusing VThreadSupport's suspension/resumption mechanism
  * - Binding the Scheduler type to DSTScheduler
  * - Ensuring boundaries are scheduled through our virtual scheduler
  */
object DSTSupport extends AsyncSupport:
  type Scheduler = DSTScheduler.type
  
  // Delegate suspension mechanics to VThreadSupport
  type Label[R] = VThreadSupport.Label[R]
  type Suspension[-T, +R] = VThreadSupport.Suspension[T, R]
  
  inline override def boundary[R](body: Label[R] ?=> R): R =
    VThreadSupport.boundary(body)
    
  inline override def suspend[T, R](body: Suspension[T, R] => R)(using l: Label[R]): T =
    VThreadSupport.suspend(body)
    
  override def scheduleBoundary(body: Label[Unit] ?=> Unit)(using sch: Scheduler): Unit =
    sch.execute(() => boundary(body))