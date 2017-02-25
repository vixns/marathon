package mesosphere.marathon
package api

import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider

import akka.event.EventStream
import com.codahale.metrics.MetricRegistry
import mesosphere.AkkaTest
import mesosphere.marathon.core.group.GroupManagerModule
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.RootGroup
import mesosphere.marathon.storage.repository.GroupRepository
import mesosphere.marathon.test.Mockito

import scala.concurrent.{ ExecutionContext, Future }

class TestGroupManagerFixture(initialRoot: RootGroup = RootGroup.empty) extends Mockito with AkkaTest {
  val service = mock[MarathonSchedulerService]
  val groupRepository = mock[GroupRepository]
  val eventBus = mock[EventStream]
  val provider = mock[StorageProvider]

  val config = AllConf.withTestConfig("--zk_timeout", "3000")

  val metricRegistry = new MetricRegistry()
  val metrics = new Metrics(metricRegistry)

  val actorId = new AtomicInteger(0)

  val schedulerProvider = new Provider[DeploymentService] {
    override def get() = service
  }

  groupRepository.root() returns Future.successful(initialRoot)

  private[this] val groupManagerModule = new GroupManagerModule(
    config = config,
    scheduler = schedulerProvider,
    groupRepo = groupRepository,
    storage = provider,
    metrics = metrics)(ExecutionContext.global, eventBus)

  val groupManager = groupManagerModule.groupManager
}
