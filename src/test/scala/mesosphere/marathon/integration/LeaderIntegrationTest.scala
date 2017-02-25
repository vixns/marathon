package mesosphere.marathon
package integration

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.facades.MarathonFacade
import mesosphere.marathon.integration.setup._
import org.scalatest.concurrent.PatienceConfiguration.Timeout

import scala.concurrent.duration._

/**
  * Do not add tests to this class. See the notes in [[NonDestructiveLeaderIntegrationTest]].
  */
abstract class LeaderIntegrationTest extends AkkaIntegrationTest with MarathonClusterTest {

  protected def nonLeader(leader: String): MarathonFacade = {
    marathonFacades.find(!_.url.contains(leader)).get
  }

  protected def leadingServerProcess(leader: String): LocalMarathon =
    (additionalMarathons :+ marathonServer).find(_.client.url.contains(leader)).getOrElse(
      fail("could not determine the which marathon process was running as leader")
    )

  protected def runningServerProcesses: Seq[LocalMarathon] =
    (additionalMarathons :+ marathonServer).filter(_.isRunning())

  protected def firstRunningProcess = runningServerProcesses.headOption.getOrElse(
    fail("there are no marathon servers running")
  )
}

/**
  * Tests in this suite MUST NOT do anything to cause a cluster servers to die (like abdication).
  * If you need to write such a destructive test then subclass [[LeaderIntegrationTest]] and add
  * your **single** destructive test case to it, as per the other test suites in this file.
  */
@IntegrationTest
class NonDestructiveLeaderIntegrationTest extends LeaderIntegrationTest {
  "NonDestructiveLeader" should {
    "all nodes return the same leader" in {
      Given("a leader has been elected")
      WaitTestSupport.waitUntil("a leader has been elected", 30.seconds) { marathon.leader().code == 200 }

      When("calling /v2/leader on all nodes of a cluster")
      val results = marathonFacades.map(marathon => marathon.leader())

      Then("the requests should all be successful")
      results.foreach(_.code should be (200))

      And("they should all be the same")
      results.map(_.value).distinct should have length 1
    }

    "all nodes return a redirect on GET /" in {
      Given("a leader has been elected")
      WaitTestSupport.waitUntil("a leader has been elected", 30.seconds) { marathon.leader().code == 200 }

      When("get / on all nodes of a cluster")
      val results = marathonFacades.map { marathon =>
        val url = new java.net.URL(s"${marathon.url}/")
        val httpConnection = url.openConnection().asInstanceOf[java.net.HttpURLConnection]
        httpConnection.setInstanceFollowRedirects(false)
        httpConnection.connect()
        httpConnection
      }

      Then("all nodes send a redirect")
      results.foreach { connection =>
        connection.getResponseCode should be(302) withClue s"Connection to ${connection.getURL} was not a redirect."
      }
    }
  }
}

@IntegrationTest
class DeathUponAbdicationLeaderIntegrationTest extends AkkaIntegrationTest with MarathonFixture with MesosClusterTest with ZookeeperServerTest {
  "LeaderAbdicationDeath" should {
    "the leader abdicates and dies when it receives a DELETE" in withMarathon("death-abdication") { (server, f) =>
      Given("a leader")
      WaitTestSupport.waitUntil("a leader has been elected", 30.seconds) {
        f.marathon.leader().code == 200
      }
      val leader = f.marathon.leader().value

      When("calling DELETE /v2/leader")
      val result = f.marathon.abdicate()

      Then("the request should be successful")
      result.code should be(200)
      (result.entityJson \ "message").as[String] should be("Leadership abdicated")

      And("the leader must have died")
      WaitTestSupport.waitUntil("the leading marathon dies changes", 30.seconds) {
        !server.isRunning()
      }
    }
  }
}

@UnstableTest
class ReelectionLeaderIntegrationTest extends LeaderIntegrationTest {

  val zkTimeout = 2000L
  override val marathonArgs: Map[String, String] = Map(
    "zk_timeout" -> s"$zkTimeout"
  )

  override val numAdditionalMarathons = 2

  "Reelecting a leader" should {
    "it survives a small reelection test" in {

      for (_ <- 1 to 15) {
        Given("a leader")
        WaitTestSupport.waitUntil("a leader has been elected", 30.seconds) { firstRunningProcess.client.leader().code == 200 }

        // pick the leader to communicate with because it's the only known survivor
        val leader = firstRunningProcess.client.leader().value
        val leadingProcess: LocalMarathon = leadingServerProcess(leader.leader)
        val client = leadingProcess.client

        When("calling DELETE /v2/leader")
        val result = client.abdicate()

        Then("the request should be successful")
        result.code should be (200)
        (result.entityJson \ "message").as[String] should be ("Leadership abdicated")

        And("the leader must have died")
        WaitTestSupport.waitUntil("the former leading marathon process dies", 30.seconds) { !leadingProcess.isRunning() }
        leadingProcess.stop() // already stopped, but still need to clear old state

        And("the leader must have changed")
        WaitTestSupport.waitUntil("the leader changes", 30.seconds) {
          val result = firstRunningProcess.client.leader()
          result.code == 200 && result.value != leader
        }

        And("all instances agree on the leader")
        WaitTestSupport.waitUntil("all instances agree on the leader", 30.seconds) {
          val results = runningServerProcesses.map(_.client.leader())
          results.forall(_.code == 200) && results.map(_.value).distinct.size == 1
        }

        // allow ZK session for former leader to timeout before proceeding
        Thread.sleep((zkTimeout * 2.5).toLong)

        And("the old leader should restart just fine")
        leadingProcess.start().futureValue(Timeout(60.seconds))
      }
    }
  }
}
