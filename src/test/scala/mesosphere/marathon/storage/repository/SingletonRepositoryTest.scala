package mesosphere.marathon
package storage.repository

import java.util.UUID

import akka.Done
import com.codahale.metrics.MetricRegistry
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.storage.repository.SingletonRepository
import mesosphere.marathon.core.storage.store.impl.cache.{ LazyCachingPersistenceStore, LoadTimeCachingPersistenceStore }
import mesosphere.marathon.core.storage.store.impl.memory.InMemoryPersistenceStore
import mesosphere.marathon.core.storage.store.impl.zk.ZkPersistenceStore
import mesosphere.marathon.integration.setup.ZookeeperServerTest
import mesosphere.marathon.metrics.Metrics
import mesosphere.util.state.FrameworkId

import scala.concurrent.duration._

class SingletonRepositoryTest extends AkkaUnitTest with ZookeeperServerTest {
  def basic(name: String, createRepo: => SingletonRepository[FrameworkId]): Unit = {
    name should {
      "return none if nothing has been stored" in {
        val repo = createRepo
        repo.get().futureValue should be ('empty)
      }
      "delete should succeed if nothing has been stored" in {
        val repo = createRepo
        repo.delete().futureValue should be(Done)
      }
      "retrieve the previously stored value" in {
        val repo = createRepo
        val id = FrameworkId(UUID.randomUUID().toString)
        repo.store(id).futureValue
        repo.get().futureValue.value should equal(id)
      }
      "delete a previously stored value should unset the value" in {
        val repo = createRepo
        val id = FrameworkId(UUID.randomUUID().toString)
        repo.store(id).futureValue
        repo.delete().futureValue should be(Done)
        repo.get().futureValue should be ('empty)
      }
    }
  }

  def createInMemRepo(): FrameworkIdRepository = {
    implicit val metrics = new Metrics(new MetricRegistry)
    FrameworkIdRepository.inMemRepository(new InMemoryPersistenceStore())
  }

  def createLoadTimeCachingRepo(): FrameworkIdRepository = {
    implicit val metrics = new Metrics(new MetricRegistry)
    val cached = new LoadTimeCachingPersistenceStore(new InMemoryPersistenceStore())
    cached.preDriverStarts.futureValue
    FrameworkIdRepository.inMemRepository(cached)
  }

  def createZKRepo(): FrameworkIdRepository = {
    implicit val metrics = new Metrics(new MetricRegistry)
    FrameworkIdRepository.zkRepository(new ZkPersistenceStore(zkClient(), 10.seconds))
  }

  def createLazyCachingRepo(): FrameworkIdRepository = {
    implicit val metrics = new Metrics(new MetricRegistry)
    FrameworkIdRepository.inMemRepository(LazyCachingPersistenceStore(new InMemoryPersistenceStore()))
  }

  behave like basic("InMemoryPersistence", createInMemRepo())
  behave like basic("ZkPersistence", createZKRepo())
  behave like basic("LoadTimeCachingPersistence", createLoadTimeCachingRepo())
  behave like basic("LazyCachingPersistence", createLazyCachingRepo())
}
