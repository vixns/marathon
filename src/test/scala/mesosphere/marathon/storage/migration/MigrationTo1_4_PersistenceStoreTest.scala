package mesosphere.marathon.storage.migration

import java.util.UUID

import akka.stream.scaladsl.Sink
import com.codahale.metrics.MetricRegistry
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.event.EventSubscribers
import mesosphere.marathon.core.storage.store.impl.memory.InMemoryPersistenceStore
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.Task.{ AgentInfo, Status }
import mesosphere.marathon.core.task.state.MarathonTaskStatus
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state._
import mesosphere.marathon.storage.{ LegacyInMemConfig, LegacyStorageConfig }
import mesosphere.marathon.storage.repository.{ AppRepository, DeploymentRepository, EventSubscribersRepository, FrameworkIdRepository, GroupRepository, StoredGroupRepositoryImpl, TaskFailureRepository, TaskRepository }
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.util.state.FrameworkId

class MigrationTo1_4_PersistenceStoreTest extends AkkaUnitTest with Mockito {
  val maxVersions = 25
  import mesosphere.marathon.state.PathId._

  def migration(legacyConfig: Option[LegacyStorageConfig] = None, maxVersions: Int = maxVersions): Migration = {
    implicit val metrics = new Metrics(new MetricRegistry)
    val persistenceStore = new InMemoryPersistenceStore()
    val appRepository = AppRepository.inMemRepository(persistenceStore)
    val groupRepository = GroupRepository.inMemRepository(persistenceStore, appRepository)
    val deploymentRepository = DeploymentRepository.inMemRepository(persistenceStore, groupRepository, appRepository, 25)
    val taskRepo = TaskRepository.inMemRepository(persistenceStore)
    val taskFailureRepository = TaskFailureRepository.inMemRepository(persistenceStore)
    val frameworkIdRepository = FrameworkIdRepository.inMemRepository(persistenceStore)
    val eventSubscribersRepository = EventSubscribersRepository.inMemRepository(persistenceStore)

    new Migration(legacyConfig, Some(persistenceStore), appRepository, groupRepository, deploymentRepository,
      taskRepo, taskFailureRepository, frameworkIdRepository, eventSubscribersRepository)
  }

  "Migration to PersistenceStore" when {
    "migrating framework id" should {
      "do nothing if it doesn't exist" in {
        implicit val metrics = new Metrics(new MetricRegistry)
        val config = LegacyInMemConfig(maxVersions)
        val oldRepo = FrameworkIdRepository.legacyRepository(config.entityStore[FrameworkId])

        val migrator = migration(Some(config))
        val migrate = new MigrationTo1_4_PersistenceStore(migrator)
        migrate.migrate().futureValue

        migrator.frameworkIdRepo.get().futureValue should be('empty)
      }
      "migrate an existing value" in {
        implicit val metrics = new Metrics(new MetricRegistry)
        val config = LegacyInMemConfig(maxVersions)
        val oldRepo = FrameworkIdRepository.legacyRepository(config.entityStore[FrameworkId])
        val id = FrameworkId(UUID.randomUUID.toString)
        oldRepo.store(id).futureValue

        val migrator = migration(Some(config))
        val migrate = new MigrationTo1_4_PersistenceStore(migrator)
        migrate.migrate().futureValue

        migrator.frameworkIdRepo.get().futureValue.value should equal(id)
        oldRepo.get().futureValue should be('empty)
      }
    }
    "migrating EventSubscribers" should {
      "do nothing if it doesn't exist" in {
        implicit val metrics = new Metrics(new MetricRegistry)
        val config = LegacyInMemConfig(maxVersions)
        val oldRepo = EventSubscribersRepository.legacyRepository(config.entityStore[EventSubscribers])

        val migrator = migration(Some(config))
        val migrate = new MigrationTo1_4_PersistenceStore(migrator)
        migrate.migrate().futureValue

        migrator.eventSubscribersRepo.get().futureValue should be('empty)
      }
      "migrate an existing value" in {
        implicit val metrics = new Metrics(new MetricRegistry)
        val config = LegacyInMemConfig(maxVersions)
        val oldRepo = EventSubscribersRepository.legacyRepository(config.entityStore[EventSubscribers])
        val subscribers = EventSubscribers(Set(UUID.randomUUID().toString))
        oldRepo.store(subscribers).futureValue

        val migrator = migration(Some(config))
        val migrate = new MigrationTo1_4_PersistenceStore(migrator)
        migrate.migrate().futureValue

        migrator.eventSubscribersRepo.get().futureValue.value should equal(subscribers)
        oldRepo.get().futureValue should be('empty)
      }
    }
    "migrating Tasks" should {
      "do nothing if no tasks exist" in {
        implicit val metrics = new Metrics(new MetricRegistry)
        val config = LegacyInMemConfig(maxVersions)
        val oldRepo = TaskRepository.legacyRepository(config.entityStore[MarathonTaskState])

        val migrator = migration(Some(config))
        val migrate = new MigrationTo1_4_PersistenceStore(migrator)
        migrate.migrate().futureValue

        migrator.taskRepo.all().runWith(Sink.seq).futureValue should be('empty)
      }
      "migrate all tasks" in {
        implicit val metrics = new Metrics(new MetricRegistry)
        val config = LegacyInMemConfig(maxVersions)
        val oldRepo = TaskRepository.legacyRepository(config.entityStore[MarathonTaskState])
        val tasks = Seq(
          Task.LaunchedEphemeral(
            Task.Id.forRunSpec("123".toRootPath),
            AgentInfo("abc", None, Nil), Timestamp(0), Status(Timestamp(0), taskStatus = MarathonTaskStatus.Created), Nil),
          Task.LaunchedEphemeral(
            Task.Id.forRunSpec("123".toRootPath),
            AgentInfo("abc", None, Nil), Timestamp(0), Status(Timestamp(0), taskStatus = MarathonTaskStatus.Created), Nil)
        )
        tasks.foreach(oldRepo.store(_).futureValue)

        val migrator = migration(Some(config))
        val migrate = new MigrationTo1_4_PersistenceStore(migrator)
        migrate.migrate().futureValue

        migrator.taskRepo.all().runWith(Sink.seq).futureValue should contain theSameElementsAs tasks
        oldRepo.all().runWith(Sink.seq).futureValue should be('empty)
      }
    }
    "migrating TaskFailures" should {
      "do nothing if there are no failures" in {
        implicit val metrics = new Metrics(new MetricRegistry)
        val config = LegacyInMemConfig(maxVersions)
        val oldRepo = TaskFailureRepository.legacyRepository(config.entityStore[TaskFailure])

        val migrator = migration(Some(config))
        val migrate = new MigrationTo1_4_PersistenceStore(migrator)
        migrate.migrate().futureValue

        migrator.taskRepo.all().runWith(Sink.seq).futureValue should be('empty)
      }
      "migrate the failures" in {
        implicit val metrics = new Metrics(new MetricRegistry)
        val config = LegacyInMemConfig(maxVersions)
        val oldRepo = TaskFailureRepository.legacyRepository(config.entityStore[TaskFailure])
        val failure1 = TaskFailure.empty.copy(appId = "123".toRootPath, timestamp = Timestamp(1))

        val failures = Seq(
          failure1,
          TaskFailure.empty.copy(appId = "234".toRootPath),
          failure1.copy(version = Timestamp(3))
        )
        failures.foreach(oldRepo.store(_).futureValue)

        val migrator = migration(Some(config))
        val migrate = new MigrationTo1_4_PersistenceStore(migrator)
        migrate.migrate().futureValue

        // we only keep 1 historical version, not 2
        migrator.taskFailureRepo.all().runWith(Sink.seq).futureValue should contain theSameElementsAs failures.tail
        oldRepo.all().runWith(Sink.seq).futureValue should be('empty)
      }
    }
    "migrating DeploymentPlans" should {
      "do nothing if there are no plans" in {
        implicit val metrics = new Metrics(new MetricRegistry)
        val config = LegacyInMemConfig(maxVersions)
        val oldRepo = DeploymentRepository.legacyRepository(config.entityStore[DeploymentPlan])

        val migrator = migration(Some(config))
        val migrate = new MigrationTo1_4_PersistenceStore(migrator)
        migrate.migrate().futureValue

        migrator.deploymentRepository.all().runWith(Sink.seq).futureValue should be('empty)
      }
      "migrate the plans" in {
        implicit val metrics = new Metrics(new MetricRegistry)
        val config = LegacyInMemConfig(maxVersions)
        val oldRepo = DeploymentRepository.legacyRepository(config.entityStore[DeploymentPlan])
        val appRepo = AppRepository.legacyRepository(config.entityStore[AppDefinition], maxVersions)
        val oldGroupRepo = GroupRepository.legacyRepository(config.entityStore[Group], maxVersions, appRepo)

        val plans = Seq(
          DeploymentPlan(
            Group.empty.copy(version = Timestamp(1)),
            Group.empty.copy(version = Timestamp(2))),
          DeploymentPlan(
            Group.empty.copy(version = Timestamp(3)),
            Group.empty.copy(version = Timestamp(4))),
          DeploymentPlan(
            Group.empty.copy(version = Timestamp(1)),
            Group.empty.copy(version = Timestamp(2)))
        )
        plans.foreach { plan =>
          oldGroupRepo.storeRoot(plan.original, Nil, Nil).futureValue
          oldGroupRepo.storeRoot(plan.target, Nil, Nil).futureValue
          oldRepo.store(plan).futureValue
        }
        val migrator = migration(Some(config))
        val migrate = new MigrationTo1_4_PersistenceStore(migrator)
        migrate.migrate().futureValue

        val migrated = migrator.deploymentRepository.all().runWith(Sink.seq).futureValue
        migrator.deploymentRepository.all().runWith(Sink.seq).futureValue should contain theSameElementsAs plans
        oldRepo.all().runWith(Sink.seq).futureValue should be('empty)
      }
    }
    "migrating Groups" should {
      "store an empty group if there are no groups" in {
        implicit val metrics = new Metrics(new MetricRegistry)
        val config = LegacyInMemConfig(maxVersions)
        val oldAppRepo = AppRepository.legacyRepository(config.entityStore[AppDefinition], maxVersions)
        val oldRepo = GroupRepository.legacyRepository(config.entityStore[Group], maxVersions, oldAppRepo)

        // intentionally storing an app, it should not be migrated and will be deleted.
        oldAppRepo.store(AppDefinition("deleted-app".toRootPath)).futureValue

        val migrator = migration(Some(config))
        val migrate = new MigrationTo1_4_PersistenceStore(migrator)
        migrate.migrate().futureValue

        oldAppRepo.all().runWith(Sink.seq).futureValue should be('empty)

        migrator.appRepository.all().runWith(Sink.seq).futureValue should be('empty)
        migrator.appRepository.ids()
          .flatMapConcat(migrator.appRepository.versions)
          .runWith(Sink.seq).futureValue should be('empty)
        val emptyRoot = migrator.groupRepository.root().futureValue
        emptyRoot.transitiveAppsById should be('empty)
        emptyRoot.groups should be('empty)
        emptyRoot.id should be(StoredGroupRepositoryImpl.RootId)
        emptyRoot.dependencies should be('empty)
        migrator.groupRepository.rootVersions()
          .runWith(Sink.seq).futureValue should contain theSameElementsAs Seq(emptyRoot.version.toOffsetDateTime)
      }
      "store all the previous roots" in {
        implicit val metrics = new Metrics(new MetricRegistry)
        val oldMax = 3
        val config = LegacyInMemConfig(oldMax)
        val oldAppRepo = AppRepository.legacyRepository(config.entityStore[AppDefinition], oldMax)
        val oldRepo = GroupRepository.legacyRepository(config.entityStore[Group], oldMax, oldAppRepo)

        // intentionally storing an app, it should not be migrated and will be deleted.
        oldAppRepo.store(AppDefinition("deleted-app".toRootPath)).futureValue

        val root1 = Group.empty.copy(version = Timestamp(1))
        val root2 = root1.copy(apps = Map("abc".toRootPath -> AppDefinition("abc".toRootPath)), version = Timestamp(2))
        val root3 = root1.copy(apps = Map("def".toRootPath -> AppDefinition("def".toRootPath)), groups =
          Set(Group("def".toRootPath, apps = Map("abc".toRootPath -> AppDefinition("def/abc".toRootPath)))),
          version = Timestamp(3))

        oldRepo.storeRoot(root1, Nil, Nil).futureValue
        oldRepo.storeRoot(root2, root2.transitiveApps.toVector, Nil).futureValue
        oldRepo.storeRoot(root3, root3.transitiveApps.toVector, root2.transitiveAppIds.toVector).futureValue

        val roots = Seq(root1, root2, root3)

        // one less root version than the old, but doesn't matter because it doesn't run GC.
        val migrator = migration(Some(config), 2)
        val migrate = new MigrationTo1_4_PersistenceStore(migrator)
        migrate.migrate().futureValue

        oldAppRepo.all().runWith(Sink.seq).futureValue should be('empty)
        oldRepo.rootVersions().runWith(Sink.seq).futureValue should be('empty)

        migrator.groupRepository.root().futureValue should equal(root3)
        migrator.groupRepository.rootVersions().mapAsync(Int.MaxValue)(migrator.groupRepository.rootVersion)
          .collect { case Some(g) => g }
          .runWith(Sink.seq).futureValue should contain theSameElementsAs roots

        // we don't need to verify app repository as the new persistence store doesn't
        // store the apps in the groups, so if the roots load, we're all good.
        val appIds = migrator.appRepository.ids().runWith(Sink.seq).futureValue
        appIds should not contain "deleted-app".toRootPath
        appIds should not be 'empty
      }
    }
  }
}

