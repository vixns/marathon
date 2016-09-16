package mesosphere.marathon.integration

import java.util

import mesosphere.marathon.integration.facades.MarathonFacade._
import mesosphere.marathon.integration.setup._
import org.apache.zookeeper.ZooDefs.Perms
import org.apache.zookeeper.data.{ ACL, Id }
import org.apache.zookeeper.{ WatchedEvent, Watcher, ZooDefs, ZooKeeper }
import org.scalatest.{ ConfigMap, GivenWhenThen, Matchers }

import scala.concurrent.duration._

class ZooKeeperTest extends IntegrationFunSuite with SingleMarathonIntegrationTest with GivenWhenThen with Matchers {

  test("/marathon has OPEN_ACL_UNSAFE acls") {
    Given("a leader has been elected")
    val watcher = new Watcher { override def process(event: WatchedEvent): Unit = {} }
    val zooKeeper = new ZooKeeper(config.zkHostAndPort, 30 * 1000, watcher)
    try {
      WaitTestSupport.waitUntil("a leader has been elected", 30.seconds) {
        marathon.leader().code == 200
      }

      Then("the /leader node exists")
      val stat = zooKeeper.exists(config.zkPath + "/leader", false)
      Option(stat) should not be empty

      And("it has the default OPEN_ACL_UNSAFE permissions")
      val acls = zooKeeper.getACL(config.zkPath + "/leader", stat)
      val expectedAcl = new util.ArrayList[ACL]
      expectedAcl.addAll(ZooDefs.Ids.OPEN_ACL_UNSAFE)
      expectedAcl.addAll(ZooDefs.Ids.READ_ACL_UNSAFE)
      acls.toArray.toSet should equal(expectedAcl.toArray.toSet)
    } finally {
      zooKeeper.close()
    }
  }
}

class AuthorizedZooKeeperTest extends IntegrationFunSuite
    with SingleMarathonIntegrationTest with GivenWhenThen with Matchers {

  lazy val credentials = "user:secret"
  lazy val digest = org.apache.zookeeper.server.auth.DigestAuthenticationProvider.generateDigest(credentials)

  override protected def beforeAll(configMap: ConfigMap): Unit = {
    super.beforeAll(configMap + ("zkCredentials" -> credentials))
  }

  test("/marathon has OPEN_ACL_UNSAFE acls") {
    val watcher = new Watcher { override def process(event: WatchedEvent): Unit = {} }
    val zooKeeper = new ZooKeeper(config.zkHostAndPort, 30 * 1000, watcher)
    zooKeeper.addAuthInfo("digest", digest.getBytes("UTF-8"))

    try {
      Given("a leader has been elected")
      WaitTestSupport.waitUntil("a leader has been elected", 30.seconds) {
        marathon.leader().code == 200
      }

      Then("the /leader node exists")
      var stat = zooKeeper.exists(config.zkPath + "/leader", false)
      Option(stat) should not be empty

      And(s"the /leader node has $credentials:rcdwa + world:r")
      var acls = zooKeeper.getACL(config.zkPath + "/leader", stat)
      var expectedAcl = new util.ArrayList[ACL]
      expectedAcl.add(new ACL(Perms.ALL, new Id("digest", digest)))
      expectedAcl.addAll(ZooDefs.Ids.READ_ACL_UNSAFE)
      acls.toArray.toSet should equal(expectedAcl.toArray.toSet)

      Then("the /state node exists")
      stat = zooKeeper.exists(config.zkPath + "/state", false)
      Option(stat) should not be empty

      And(s"the /state node has $credentials:rcdwa")
      acls = zooKeeper.getACL(config.zkPath + "/state", stat)
      expectedAcl = new util.ArrayList[ACL]
      expectedAcl.add(new ACL(Perms.ALL, new Id("digest", digest)))
      acls.toArray.toSet should equal(expectedAcl.toArray.toSet)

      And("marathon can read and write to the state")
      val app = appProxy(testBasePath / "app", "v1", instances = 1, withHealth = false)

      When("The app is deployed")
      val result = marathon.createAppV2(app)

      Then("The app is created")
      result.code should be (201) //Created
      extractDeploymentIds(result) should have size 1
      waitForEvent("deployment_success")
      waitForTasks(app.id, 1) //make sure, the app has really started
    } finally {
      zooKeeper.close()
    }
  }
}