package mesosphere.marathon.core.history.impl

import akka.actor.{ Actor, ActorLogging }
import akka.event.EventStream
import mesosphere.marathon.core.event.{ AppTerminatedEvent, MesosStatusUpdateEvent, UnhealthyTaskKillEvent }
import mesosphere.marathon.state.TaskFailure
import mesosphere.marathon.storage.repository.TaskFailureRepository

class HistoryActor(eventBus: EventStream, taskFailureRepository: TaskFailureRepository)
    extends Actor with ActorLogging {

  override def preStart(): Unit = {
    eventBus.subscribe(self, classOf[MesosStatusUpdateEvent])
    eventBus.subscribe(self, classOf[UnhealthyTaskKillEvent])
    eventBus.subscribe(self, classOf[AppTerminatedEvent])
  }

  def receive: Receive = {

    case TaskFailure.FromUnhealthyTaskKillEvent(taskFailure) =>
      taskFailureRepository.store(taskFailure)

    case TaskFailure.FromMesosStatusUpdateEvent(taskFailure) =>
      taskFailureRepository.store(taskFailure)

    case _: MesosStatusUpdateEvent => // ignore non-failure status updates

    case AppTerminatedEvent(appId, eventType, timestamp) =>
      taskFailureRepository.delete(appId)
  }
}
