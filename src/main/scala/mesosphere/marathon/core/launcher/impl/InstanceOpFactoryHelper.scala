package mesosphere.marathon
package core.launcher.impl

import mesosphere.marathon.core.instance.{ LegacyAppInstance, Instance }
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.launcher.InstanceOp
import mesosphere.marathon.core.matcher.base.util.OfferOperationFactory
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.Task.LocalVolume
import mesosphere.marathon.state.DiskSource
import mesosphere.util.state.FrameworkId
import org.apache.mesos.{ Protos => Mesos }

class InstanceOpFactoryHelper(
    private val principalOpt: Option[String],
    private val roleOpt: Option[String]) {

  private[this] val offerOperationFactory = new OfferOperationFactory(principalOpt, roleOpt)

  def launchEphemeral(
    taskInfo: Mesos.TaskInfo,
    newTask: Task.LaunchedEphemeral,
    instance: Instance): InstanceOp.LaunchTask = {

    assume(newTask.taskId.mesosTaskId == taskInfo.getTaskId, "marathon task id and mesos task id must be equal")

    def createOperations = Seq(offerOperationFactory.launch(taskInfo))

    val stateOp = InstanceUpdateOperation.LaunchEphemeral(instance)
    InstanceOp.LaunchTask(taskInfo, stateOp, oldInstance = None, createOperations)
  }

  def launchEphemeral(
    executorInfo: Mesos.ExecutorInfo,
    groupInfo: Mesos.TaskGroupInfo,
    launched: Instance.LaunchRequest): InstanceOp.LaunchTaskGroup = {

    assume(
      executorInfo.getExecutorId.getValue == launched.instance.instanceId.executorIdString,
      "marathon pod instance id and mesos executor id must be equal")

    def createOperations = Seq(offerOperationFactory.launch(executorInfo, groupInfo))

    val stateOp = InstanceUpdateOperation.LaunchEphemeral(launched.instance)
    InstanceOp.LaunchTaskGroup(executorInfo, groupInfo, stateOp, oldInstance = None, createOperations)
  }

  def launchOnReservation(
    taskInfo: Mesos.TaskInfo,
    newState: InstanceUpdateOperation.LaunchOnReservation,
    oldState: Task.Reserved): InstanceOp.LaunchTask = {

    def createOperations = Seq(offerOperationFactory.launch(taskInfo))

    InstanceOp.LaunchTask(taskInfo, newState, Some(LegacyAppInstance(oldState)), createOperations)
  }

  /**
    * Returns a set of operations to reserve ALL resources (cpu, mem, ports, disk, etc.) and then create persistent
    * volumes against them as needed
    */
  @SuppressWarnings(Array("TraversableHead"))
  def reserveAndCreateVolumes(
    frameworkId: FrameworkId,
    newState: InstanceUpdateOperation.Reserve,
    resources: Seq[Mesos.Resource],
    localVolumes: Seq[(DiskSource, LocalVolume)]): InstanceOp.ReserveAndCreateVolumes = {

    require(
      newState.instance.tasksMap.values.size == 1,
      "reserveAndCreateVolumes() is not implemented for multi container instances")
    val task = newState.instance.tasksMap.values.head
    def createOperations = Seq(
      offerOperationFactory.reserve(frameworkId, task.taskId, resources),
      offerOperationFactory.createVolumes(
        frameworkId,
        task.taskId,
        localVolumes))

    InstanceOp.ReserveAndCreateVolumes(newState, resources, createOperations)
  }
}
