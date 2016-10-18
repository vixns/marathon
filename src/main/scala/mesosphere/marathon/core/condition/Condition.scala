package mesosphere.marathon.core.condition

import mesosphere.marathon.core.task.TaskCondition
import org.apache.mesos
import play.api.libs.json.Json

/**
  * To define the status of an Instance, this trait is used and stored for each Task in Task.Status.
  * The existing case objects are:
  * - marathon exclusive status
  * - representations of the mesos.Protos.TaskStatus
  * - mapping of existing (soon-to-be deprecated) mesos.Protos.TaskStatus.TASK_LOST to the new representations
  */
sealed trait Condition extends Product with Serializable {
  // TODO(jdef) pods was this renamed too aggressively? Should it really be TaskStatus instead?
  lazy val toMesosStateName: String = {
    import Condition._
    this match {
      case Gone | Unreachable | Unknown | Dropped => mesos.Protos.TaskState.TASK_LOST.toString
      case Created | Reserved => mesos.Protos.TaskState.TASK_STAGING.toString
      case s: Condition => "TASK_" + s.toString.toUpperCase()
    }
  }

  def isLost: Boolean = {
    import Condition._
    this match {
      case Gone | Unreachable | Unknown | Dropped => true
      case _ => false
    }
  }

  def isTerminal: Boolean = {
    import Condition._
    this match {
      case _: Terminal => true
      case _ => false
    }
  }
}

object Condition {

  sealed trait Terminal extends Condition

  // Reserved: Task with persistent volume has reservation, but is not launched yet
  case object Reserved extends Condition

  // Created: Task is known in marathon and sent to mesos, but not staged yet
  case object Created extends Condition

  // Error: indicates that a task launch attempt failed because of an error in the task specification
  case object Error extends Condition with Terminal

  // Failed: task aborted with an error
  case object Failed extends Condition with Terminal

  // Finished: task completes successfully
  case object Finished extends Condition with Terminal

  // Killed: task was killed
  case object Killed extends Condition with Terminal

  // Killing: the request to kill the task has been received, but the task has not yet been killed
  case object Killing extends Condition

  // Running: the state after the task has begun running successfully
  case object Running extends Condition

  // Staging: the master has received the framework’s request to launch the task but the task has not yet started to run
  case object Staging extends Condition

  // Starting: task is currently starting
  case object Starting extends Condition

  // Unreachable: the master has not heard from the agent running the task for a configurable period of time
  case object Unreachable extends Condition

  // Gone: the task was running on an agent that has been terminated
  case object Gone extends Condition with Terminal

  // Dropped: the task failed to launch because of a transient error (e.g., spontaneously disconnected agent)
  case object Dropped extends Condition with Terminal

  // Unknown: the master has no knowledge of the task
  case object Unknown extends Condition with Terminal

  object Terminal {
    def unapply(condition: Condition): Option[Terminal] = condition match {
      case terminal: Terminal => Some(terminal)
      case _ => None
    }
    def unapply(taskStatus: mesos.Protos.TaskStatus): Option[mesos.Protos.TaskStatus] =
      TaskCondition(taskStatus) match {
        case _: Condition.Terminal => Some(taskStatus)
        case _ => None
      }
  }

  // scalastyle:off
  def apply(str: String): Condition = str.toLowerCase match {
    case "reserved" => Reserved
    case "created" => Created
    case "error" => Error
    case "failed" => Failed
    case "killed" => Killed
    case "killing" => Killing
    case "running" => Running
    case "staging" => Staging
    case "starting" => Starting
    case "unreachable" => Unreachable
    case "gone" => Gone
    case "dropped" => Dropped
    case _ => Unknown
  }
  // scalastyle:on

  def unapply(condition: Condition): Option[String] = Some(condition.toString.toLowerCase)

  implicit val conditionFormat = Json.format[Condition]
}
