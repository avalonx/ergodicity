package com.ergodicity.engine.service

import akka.actor.FSM._
import akka.actor.{FSM, LoggingFSM, Actor, Props}
import akka.util.duration._
import com.ergodicity.cgate.config.Replication.ReplicationMode.Combined
import com.ergodicity.cgate.config.Replication.ReplicationParams
import com.ergodicity.cgate.{Connection => _, _}
import com.ergodicity.core.SessionsTracking
import com.ergodicity.core.SessionsTracking.SubscribeOngoingSessions
import com.ergodicity.engine.Listener.{OptInfoListener, FutInfoListener}
import com.ergodicity.engine.service.InstrumentDataState.StreamStates
import com.ergodicity.engine.service.Service.{Stop, Start}
import com.ergodicity.engine.{Engine, Services}

object InstrumentData {

  implicit case object InstrumentData extends ServiceId

}

trait InstrumentData {
  this: Services =>

  import InstrumentData._

  def engine: Engine with FutInfoListener with OptInfoListener

  register(
    Props(new InstrumentDataService(engine.futInfoListener, engine.optInfoListener)),
    dependOn = ReplicationConnection.Connection :: Nil
  )
}

protected[service] sealed trait InstrumentDataState

object InstrumentDataState {

  case object Idle extends InstrumentDataState

  case object Starting extends InstrumentDataState

  case object Started extends InstrumentDataState

  case object Stopping extends InstrumentDataState

  case class StreamStates(fut: Option[DataStreamState] = None, opt: Option[DataStreamState] = None)

}

protected[service] class InstrumentDataService(underlyingFutInfoListener: ListenerBinding, underlyingOptInfoListener: ListenerBinding)
                                              (implicit val services: Services, id: ServiceId) extends Actor with LoggingFSM[InstrumentDataState, StreamStates] with Service {

  import InstrumentDataState._
  import services._

  val FutInfoStream = context.actorOf(Props(new DataStream), "FutInfoDataStream")
  val OptInfoStream = context.actorOf(Props(new DataStream), "OptInfoDataStream")

  val Sessions = context.actorOf(Props(new SessionsTracking(FutInfoStream, OptInfoStream)), "SessionsTracking")

  // Listeners
  underlyingFutInfoListener.bind(new DataStreamSubscriber(FutInfoStream))
  val futInfoListener = context.actorOf(Props(new Listener(underlyingFutInfoListener.listener)).withDispatcher(Engine.ReplicationDispatcher), "FutInfoListener")

  underlyingOptInfoListener.bind(new DataStreamSubscriber(OptInfoStream))
  val optInfoListener = context.actorOf(Props(new Listener(underlyingOptInfoListener.listener)).withDispatcher(Engine.ReplicationDispatcher), "OptInfoListener")

  startWith(Idle, StreamStates())

  when(Idle) {
    case Event(Start, _) =>
      log.info("Start " + id + " service")
      // Open FutInfo & OptInfo listeners
      futInfoListener ! Listener.Open(ReplicationParams(Combined))
      optInfoListener ! Listener.Open(ReplicationParams(Combined))
      // and subscribe for stream states
      FutInfoStream ! SubscribeTransitionCallBack(self)
      OptInfoStream ! SubscribeTransitionCallBack(self)
      goto(Starting)
  }

  when(Starting, stateTimeout = 10.seconds) {
    case Event(CurrentState(FutInfoStream, state: DataStreamState), states) => startUp(states.copy(fut = Some(state)))
    case Event(CurrentState(OptInfoStream, state: DataStreamState), states) => startUp(states.copy(opt = Some(state)))
    case Event(Transition(FutInfoStream, _, to: DataStreamState), states) => startUp(states.copy(fut = Some(to)))
    case Event(Transition(OptInfoStream, _, to: DataStreamState), states) => startUp(states.copy(opt = Some(to)))

    case Event(FSM.StateTimeout, _) => failed("Starting timed out")
  }

  when(Started) {
    case Event(Stop, states) =>
    log.info("Stop " + id + " service")
    futInfoListener ! Listener.Close
    optInfoListener ! Listener.Close
    goto(Stopping)
  }

  when(Stopping, stateTimeout = 10.seconds) {
    case Event(Transition(FutInfoStream, _, to: DataStreamState), states) => shutDown(states.copy(fut = Some(to)))
    case Event(Transition(OptInfoStream, _, to: DataStreamState), states) => shutDown(states.copy(opt = Some(to)))

    case Event(FSM.StateTimeout, _) => failed("Stopping timed out")
  }

  onTransition {
    case Starting -> Started =>
      // Let dispatch all session messages before notify Services
      context.system.scheduler.scheduleOnce(500.millis)(serviceStarted)
  }

  whenUnhandled {
    case Event(subscribe: SubscribeOngoingSessions, _) =>
      Sessions ! subscribe
      stay()
  }

  private def shutDown(states: StreamStates) = states match {
    case StreamStates(Some(DataStreamState.Closed), Some(DataStreamState.Closed)) =>
      futInfoListener ! Listener.Dispose
      optInfoListener ! Listener.Dispose
      serviceStopped
      stop(Shutdown)
    case _ => stay() using states
  }

  private def startUp(states: StreamStates) = states match {
    case StreamStates(Some(DataStreamState.Online), Some(DataStreamState.Online)) => goto(Started)
    case _ => stay() using states
  }

  initialize
}
