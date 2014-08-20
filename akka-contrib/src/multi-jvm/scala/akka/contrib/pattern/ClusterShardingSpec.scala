/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.pattern

import akka.contrib.pattern.ShardCoordinator.Internal.{ ShardStopped, HandOff }
import akka.contrib.pattern.ShardRegion.Passivate

import language.postfixOps
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor.ActorIdentity
import akka.actor.Identify
import akka.actor.PoisonPill
import akka.actor.Props
import akka.cluster.Cluster
import akka.persistence.PersistentActor
import akka.persistence.Persistence
import akka.persistence.journal.leveldb.SharedLeveldbJournal
import akka.persistence.journal.leveldb.SharedLeveldbStore
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.testkit._
import akka.testkit.TestEvent.Mute
import java.io.File
import org.apache.commons.io.FileUtils
import akka.actor.ReceiveTimeout
import akka.actor.ActorRef

object ClusterShardingSpec extends MultiNodeConfig {
  val controller = role("controller")
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")
  val fifth = role("fifth")
  val sixth = role("sixth")

  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.remote.log-remote-lifecycle-events = off
    akka.cluster.auto-down-unreachable-after = 0s
    akka.cluster.roles = ["backend"]
    akka.persistence.journal.plugin = "akka.persistence.journal.leveldb-shared"
    akka.persistence.journal.leveldb-shared.store {
      native = off
      dir = "target/journal-ClusterShardingSpec"
    }
    akka.persistence.snapshot-store.local.dir = "target/snapshots-ClusterShardingSpec"
    akka.contrib.cluster.sharding {
      role = backend
      retry-interval = 1 s
      handoff-timeout = 10 s
      shard-start-timeout = 5s
      entry-restart-backoff = 1s
      rebalance-interval = 2 s
      least-shard-allocation-strategy {
        rebalance-threshold = 2
        max-simultaneous-rebalance = 1
      }
    }
    """))

  nodeConfig(sixth) {
    ConfigFactory.parseString("""akka.cluster.roles = ["frontend"]""")
  }

  //#counter-actor
  case object Increment
  case object Decrement
  final case class Get(counterId: Long)
  final case class EntryEnvelope(id: Long, payload: Any)

  case object Stop
  final case class CounterChanged(delta: Int)

  class Counter extends PersistentActor {
    import ShardRegion.Passivate

    context.setReceiveTimeout(120.seconds)

    // self.path.parent.parent.name is the type name (utf-8 URL-encoded)
    // self.path.name is the entry identifier (utf-8 URL-encoded)
    override def persistenceId: String = self.path.parent.parent.name + "-" + self.path.name

    var count = 0
    //#counter-actor

    override def postStop(): Unit = {
      super.postStop()
      // Simulate that the passivation takes some time, to verify passivation bufffering
      Thread.sleep(500)
    }
    //#counter-actor

    def updateState(event: CounterChanged): Unit =
      count += event.delta

    override def receiveRecover: Receive = {
      case evt: CounterChanged ⇒ updateState(evt)
    }

    override def receiveCommand: Receive = {
      case Increment      ⇒ persist(CounterChanged(+1))(updateState)
      case Decrement      ⇒ persist(CounterChanged(-1))(updateState)
      case Get(_)         ⇒ sender() ! count
      case ReceiveTimeout ⇒ context.parent ! Passivate(stopMessage = Stop)
      case Stop           ⇒ context.stop(self)
    }
  }
  //#counter-actor

  //#counter-extractor
  val idExtractor: ShardRegion.IdExtractor = {
    case EntryEnvelope(id, payload) ⇒ (id.toString, payload)
    case msg @ Get(id)              ⇒ (id.toString, msg)
  }

  val shardResolver: ShardRegion.ShardResolver = msg ⇒ msg match {
    case EntryEnvelope(id, _) ⇒ (id % 12).toString
    case Get(id)              ⇒ (id % 12).toString
  }
  //#counter-extractor

}

class ClusterShardingMultiJvmNode1 extends ClusterShardingSpec
class ClusterShardingMultiJvmNode2 extends ClusterShardingSpec
class ClusterShardingMultiJvmNode3 extends ClusterShardingSpec
class ClusterShardingMultiJvmNode4 extends ClusterShardingSpec
class ClusterShardingMultiJvmNode5 extends ClusterShardingSpec
class ClusterShardingMultiJvmNode6 extends ClusterShardingSpec
class ClusterShardingMultiJvmNode7 extends ClusterShardingSpec

class ClusterShardingSpec extends MultiNodeSpec(ClusterShardingSpec) with STMultiNodeSpec with ImplicitSender {
  import ClusterShardingSpec._

  override def initialParticipants = roles.size

  val storageLocations = List(
    "akka.persistence.journal.leveldb.dir",
    "akka.persistence.journal.leveldb-shared.store.dir",
    "akka.persistence.snapshot-store.local.dir").map(s ⇒ new File(system.settings.config.getString(s)))

  override protected def atStartup() {
    runOn(controller) {
      storageLocations.foreach(dir ⇒ if (dir.exists) FileUtils.deleteDirectory(dir))
    }
  }

  override protected def afterTermination() {
    runOn(controller) {
      storageLocations.foreach(dir ⇒ if (dir.exists) FileUtils.deleteDirectory(dir))
    }
  }

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      Cluster(system) join node(to).address
      createCoordinator()
    }
    enterBarrier(from.name + "-joined")
  }

  def createCoordinator(): Unit = {
    val allocationStrategy = new ShardCoordinator.LeastShardAllocationStrategy(rebalanceThreshold = 2, maxSimultaneousRebalance = 1)
    val coordinatorProps = ShardCoordinator.props(handOffTimeout = 10.seconds, shardStartTimeout = 10.seconds, rebalanceInterval = 2.seconds,
      snapshotInterval = 3600.seconds, allocationStrategy)
    system.actorOf(ClusterSingletonManager.props(
      singletonProps = ShardCoordinatorSupervisor.props(failureBackoff = 5.seconds, coordinatorProps),
      singletonName = "singleton",
      terminationMessage = PoisonPill,
      role = None),
      name = "counterCoordinator")
  }

  lazy val region = system.actorOf(ShardRegion.props(
    typeName = "counter",
    entryProps = Props[Counter],
    role = None,
    coordinatorPath = "/user/counterCoordinator/singleton/coordinator",
    retryInterval = 1.second,
    shardFailureBackoff = 1.second,
    entryRestartBackoff = 1.second,
    snapshotInterval = 1.hour,
    bufferSize = 1000,
    rememberEntries = false,
    idExtractor = idExtractor,
    shardResolver = shardResolver),
    name = "counterRegion")

  lazy val persistentRegion = ClusterSharding(system).start(
    typeName = "PersistentCounter",
    entryProps = Some(Props[Counter]),
    rememberEntries = true,
    idExtractor = idExtractor,
    shardResolver = shardResolver)

  "Cluster sharding" must {

    "setup shared journal" in {
      // start the Persistence extension
      Persistence(system)
      runOn(controller) {
        system.actorOf(Props[SharedLeveldbStore], "store")
      }
      enterBarrier("peristence-started")

      runOn(first, second, third, fourth, fifth, sixth) {
        system.actorSelection(node(controller) / "user" / "store") ! Identify(None)
        val sharedStore = expectMsgType[ActorIdentity].ref.get
        SharedLeveldbJournal.setStore(sharedStore, system)
      }

      enterBarrier("after-1")
    }

    "work in single node cluster" in within(20 seconds) {
      join(first, first)

      runOn(first) {
        region ! EntryEnvelope(1, Increment)
        region ! EntryEnvelope(1, Increment)
        region ! EntryEnvelope(1, Increment)
        region ! EntryEnvelope(1, Decrement)
        region ! Get(1)
        expectMsg(2)
      }

      enterBarrier("after-2")
    }

    "use second node" in within(20 seconds) {
      join(second, first)

      runOn(second) {
        region ! EntryEnvelope(2, Increment)
        region ! EntryEnvelope(2, Increment)
        region ! EntryEnvelope(2, Increment)
        region ! EntryEnvelope(2, Decrement)
        region ! Get(2)
        expectMsg(2)

        region ! EntryEnvelope(11, Increment)
        region ! EntryEnvelope(12, Increment)
        region ! Get(11)
        expectMsg(1)
        region ! Get(12)
        expectMsg(1)
      }
      enterBarrier("second-update")
      runOn(first) {
        region ! EntryEnvelope(2, Increment)
        region ! Get(2)
        expectMsg(3)
        lastSender.path should be(node(second) / "user" / "counterRegion" / "2" / "2")

        region ! Get(11)
        expectMsg(1)
        // local on first
        lastSender.path should be(region.path / "11" / "11")
        region ! Get(12)
        expectMsg(1)
        lastSender.path should be(node(second) / "user" / "counterRegion" / "0" / "12")
      }
      enterBarrier("first-update")

      runOn(second) {
        region ! Get(2)
        expectMsg(3)
        lastSender.path should be(region.path / "2" / "2")
      }

      enterBarrier("after-3")
    }

    "support passivation and activation of entries" in {
      runOn(second) {
        region ! Get(2)
        expectMsg(3)
        region ! EntryEnvelope(2, ReceiveTimeout)
        // let the Passivate-Stop roundtrip begin to trigger buffering of subsequent messages
        Thread.sleep(200)
        region ! EntryEnvelope(2, Increment)
        region ! Get(2)
        expectMsg(4)
      }
      enterBarrier("after-4")
    }

    "failover shards on crashed node" in within(30 seconds) {
      // mute logging of deadLetters during shutdown of systems
      if (!log.isDebugEnabled)
        system.eventStream.publish(Mute(DeadLettersFilter[Any]))
      enterBarrier("logs-muted")

      runOn(controller) {
        testConductor.exit(second, 0).await
      }
      enterBarrier("crash-second")

      runOn(first) {
        val probe1 = TestProbe()
        awaitAssert {
          within(1.second) {
            region.tell(Get(2), probe1.ref)
            probe1.expectMsg(4)
            probe1.lastSender.path should be(region.path / "2" / "2")
          }
        }
        val probe2 = TestProbe()
        awaitAssert {
          within(1.second) {
            region.tell(Get(12), probe2.ref)
            probe2.expectMsg(1)
            probe2.lastSender.path should be(region.path / "0" / "12")
          }
        }
      }

      enterBarrier("after-5")
    }

    "use third and fourth node" in within(15 seconds) {
      join(third, first)
      join(fourth, first)

      runOn(third) {
        for (_ ← 1 to 10)
          region ! EntryEnvelope(3, Increment)
        region ! Get(3)
        expectMsg(10)
      }
      enterBarrier("third-update")

      runOn(fourth) {
        for (_ ← 1 to 20)
          region ! EntryEnvelope(4, Increment)
        region ! Get(4)
        expectMsg(20)
      }
      enterBarrier("fourth-update")

      runOn(first) {
        region ! EntryEnvelope(3, Increment)
        region ! Get(3)
        expectMsg(11)
        lastSender.path should be(node(third) / "user" / "counterRegion" / "3" / "3")

        region ! EntryEnvelope(4, Increment)
        region ! Get(4)
        expectMsg(21)
        lastSender.path should be(node(fourth) / "user" / "counterRegion" / "4" / "4")
      }
      enterBarrier("first-update")

      runOn(third) {
        region ! Get(3)
        expectMsg(11)
        lastSender.path should be(region.path / "3" / "3")
      }

      runOn(fourth) {
        region ! Get(4)
        expectMsg(21)
        lastSender.path should be(region.path / "4" / "4")
      }

      enterBarrier("after-6")
    }

    "recover coordinator state after coordinator crash" in within(60 seconds) {
      join(fifth, fourth)

      runOn(controller) {
        testConductor.exit(first, 0).await
      }
      enterBarrier("crash-first")

      runOn(fifth) {
        val probe3 = TestProbe()
        awaitAssert {
          within(1.second) {
            region.tell(Get(3), probe3.ref)
            probe3.expectMsg(11)
            probe3.lastSender.path should be(node(third) / "user" / "counterRegion" / "3" / "3")
          }
        }
        val probe4 = TestProbe()
        awaitAssert {
          within(1.second) {
            region.tell(Get(4), probe4.ref)
            probe4.expectMsg(21)
            probe4.lastSender.path should be(node(fourth) / "user" / "counterRegion" / "4" / "4")
          }
        }

      }

      enterBarrier("after-7")
    }

    "rebalance to nodes with less shards" in within(60 seconds) {

      runOn(fourth) {
        // third, fourth and fifth are still alive
        // shards 3 and 4 are already allocated
        // make sure shards 1 and 2 (previously on crashed first) are allocated
        awaitAssert {
          val probe1 = TestProbe()
          within(1.second) {
            region.tell(Get(1), probe1.ref)
            probe1.expectMsg(2)
          }
        }
        awaitAssert {
          val probe2 = TestProbe()
          within(1.second) {
            region.tell(Get(2), probe2.ref)
            probe2.expectMsg(4)
          }
        }

        // add more shards, which should later trigger rebalance to new node sixth
        for (n ← 5 to 10)
          region ! EntryEnvelope(n, Increment)

        for (n ← 5 to 10) {
          region ! Get(n)
          expectMsg(1)
        }
      }
      enterBarrier("more-added")

      join(sixth, third)

      runOn(sixth) {
        awaitAssert {
          val probe = TestProbe()
          within(3.seconds) {
            var count = 0
            for (n ← 1 to 10) {
              region.tell(Get(n), probe.ref)
              probe.expectMsgType[Int]
              if (probe.lastSender.path == region.path / (n % 12).toString / n.toString)
                count += 1
            }
            count should be(2)
          }
        }
      }

      enterBarrier("after-8")

    }
  }

  "support proxy only mode" in within(10.seconds) {
    runOn(sixth) {
      val proxy = system.actorOf(ShardRegion.proxyProps(
        typeName = "counter",
        role = None,
        coordinatorPath = "/user/counterCoordinator/singleton/coordinator",
        retryInterval = 1.second,
        bufferSize = 1000,
        idExtractor = idExtractor,
        shardResolver = shardResolver),
        name = "regionProxy")

      proxy ! Get(1)
      expectMsg(2)
      proxy ! Get(2)
      expectMsg(4)
    }
    enterBarrier("after-9")
  }

  "easy to use with extensions" in within(50.seconds) {
    runOn(third, fourth, fifth, sixth) {
      //#counter-start
      val counterRegion: ActorRef = ClusterSharding(system).start(
        typeName = "Counter",
        entryProps = Some(Props[Counter]),
        rememberEntries = false,
        idExtractor = idExtractor,
        shardResolver = shardResolver)
      //#counter-start
      ClusterSharding(system).start(
        typeName = "AnotherCounter",
        entryProps = Some(Props[Counter]),
        rememberEntries = false,
        idExtractor = idExtractor,
        shardResolver = shardResolver)
    }
    enterBarrier("extension-started")
    runOn(fifth) {
      //#counter-usage
      val counterRegion: ActorRef = ClusterSharding(system).shardRegion("Counter")
      counterRegion ! Get(100)
      expectMsg(0)

      counterRegion ! EntryEnvelope(100, Increment)
      counterRegion ! Get(100)
      expectMsg(1)
      //#counter-usage

      ClusterSharding(system).shardRegion("AnotherCounter") ! EntryEnvelope(100, Decrement)
      ClusterSharding(system).shardRegion("AnotherCounter") ! Get(100)
      expectMsg(-1)
    }

    enterBarrier("extension-used")

    // sixth is a frontend node, i.e. proxy only
    runOn(sixth) {
      for (n ← 1000 to 1010) {
        ClusterSharding(system).shardRegion("Counter") ! EntryEnvelope(n, Increment)
        ClusterSharding(system).shardRegion("Counter") ! Get(n)
        expectMsg(1)
        lastSender.path.address should not be (Cluster(system).selfAddress)
      }
    }

    enterBarrier("after-9")

  }
  "easy API for starting" in within(50.seconds) {
    runOn(first) {
      val counterRegionViaStart: ActorRef = ClusterSharding(system).start(
        typeName = "ApiTest",
        entryProps = Some(Props[Counter]),
        rememberEntries = false,
        idExtractor = idExtractor,
        shardResolver = shardResolver)

      val counterRegionViaGet: ActorRef = ClusterSharding(system).shardRegion("ApiTest")

      counterRegionViaStart should equal(counterRegionViaGet)
    }
    enterBarrier("after-10")

  }

  "Persistent Cluster Shards" must {
    "recover entries upon restart" in within(50.seconds) {
      runOn(third, fourth, fifth) {
        ClusterSharding(system).start(
          typeName = "PersistentCounterEntries",
          entryProps = Some(Props[Counter]),
          rememberEntries = true,
          idExtractor = idExtractor,
          shardResolver = shardResolver)

        ClusterSharding(system).start(
          typeName = "AnotherPersistentCounter",
          entryProps = Some(Props[Counter]),
          rememberEntries = true,
          idExtractor = idExtractor,
          shardResolver = shardResolver)
      }
      enterBarrier("persistent-started")

      runOn(third) {
        val counterRegion: ActorRef = ClusterSharding(system).shardRegion("PersistentCounterEntries")

        //Create an increment counter 1
        counterRegion ! EntryEnvelope(1, Increment)
        counterRegion ! Get(1)
        expectMsg(1)

        //Shut down the shard and confirm it's dead
        val shard = system.actorSelection(lastSender.path.parent)
        val region = system.actorSelection(lastSender.path.parent.parent)

        //Stop the shard cleanly
        region ! HandOff("1")
        expectMsg(10 seconds, "ShardStopped not received", ShardStopped("1"))

        awaitAssert({
          shard ! Identify(1)
          expectMsg(1 second, "Shard was still around", ActorIdentity(1, None))
        }, 5 seconds, 500 millis)

        //Get the path to where the shard now resides
        counterRegion ! Get(13)
        expectMsg(0)

        //Check that counter 1 is now alive again, even though we have
        // not sent a message to it via the ShardRegion
        val counter1 = system.actorSelection(lastSender.path.parent / "1")
        counter1 ! Identify(2)
        receiveOne(1 second) match {
          case ActorIdentity(2, location) ⇒
            location should not be (None)
        }

        counter1 ! Get(1)
        expectMsg(1)
      }

      enterBarrier("after-shard-restart")

      runOn(fourth) {
        //Check a second region does not share the same persistent shards
        val anotherRegion: ActorRef = ClusterSharding(system).shardRegion("AnotherPersistentCounter")

        //Create a separate 13 counter
        anotherRegion ! EntryEnvelope(13, Increment)
        anotherRegion ! Get(13)
        expectMsg(1)

        //Check that no counter "1" exists in this shard
        val secondCounter1 = system.actorSelection(lastSender.path.parent / "1")
        secondCounter1 ! Identify(3)
        receiveOne(1 second) match {
          case ActorIdentity(3, location) ⇒
            location should be(None)
        }

      }
      enterBarrier("after-11")
    }

    "permanently stop entries which passivate" in within(50.seconds) {
      runOn(third, fourth, fifth) {
        persistentRegion
      }
      enterBarrier("cluster-started-12")

      runOn(third) {
        //Create and increment counter 1
        persistentRegion ! EntryEnvelope(1, Increment)
        persistentRegion ! Get(1)
        expectMsg(1)

        val counter1 = lastSender
        val shard = system.actorSelection(counter1.path.parent)
        val region = system.actorSelection(counter1.path.parent.parent)

        //Create and increment counter 13
        persistentRegion ! EntryEnvelope(13, Increment)
        persistentRegion ! Get(13)
        expectMsg(1)

        val counter13 = lastSender

        counter1.path.parent should be(counter13.path.parent)

        //Send the shard the passivate message from the counter
        shard.tell(Passivate(Stop), counter1)
        awaitAssert({
          //Check counter 1 is dead
          counter1 ! Identify(1)
          expectMsg(1 second, "Entry 1 was still around", ActorIdentity(1, None))
        }, 5 second, 500 millis)

        //Stop the shard cleanly
        region ! HandOff("1")
        expectMsg(10 seconds, "ShardStopped not received", ShardStopped("1"))
        awaitAssert({
          shard ! Identify(2)
          expectMsg(1 second, "Shard was still around", ActorIdentity(2, None))
        }, 5 seconds, 500 millis)
      }

      enterBarrier("shard-shutdown-12")

      runOn(fourth) {
        //Force the shard back up
        persistentRegion ! Get(25)
        expectMsg(0)

        val shard = lastSender.path.parent

        //Check counter 1 is still dead
        system.actorSelection(shard / "1") ! Identify(3)
        receiveOne(1 second) should be(ActorIdentity(3, None))

        //Check counter 13 is alive again                        8
        system.actorSelection(shard / "13") ! Identify(4)
        receiveOne(1 second) match {
          case ActorIdentity(4, location) ⇒
            location should not be (None)
        }
      }

      enterBarrier("after-12")
    }

    "restart entries which stop without passivating" in within(50.seconds) {
      runOn(third, fourth) {
        persistentRegion
      }
      enterBarrier("cluster-started-12")

      runOn(third) {
        //Create and increment counter 1
        persistentRegion ! EntryEnvelope(1, Increment)
        persistentRegion ! Get(1)
        expectMsg(2)

        val counter1 = system.actorSelection(lastSender.path)

        counter1 ! Stop

        awaitAssert({
          counter1 ! Identify(1)
          receiveOne(1 second) match {
            case ActorIdentity(1, location) ⇒
              location should not be (None)
          }
        }, 5.seconds, 500.millis)

      }

      enterBarrier("after-13")
    }

    "be migrated to new regions upon region failure" in within(50.seconds) {
      lazy val migrationRegion: ActorRef = ClusterSharding(system).start(
        typeName = "AutoMigrateRegionTest",
        entryProps = Some(Props[Counter]),
        rememberEntries = true,
        idExtractor = idExtractor,
        shardResolver = shardResolver)

      //Start only one region, and force an entry onto that region
      runOn(third) {
        migrationRegion ! EntryEnvelope(1, Increment)
      }
      enterBarrier("shard1-region3")

      //Start another region and test it talks to node 3
      runOn(fourth) {
        migrationRegion ! EntryEnvelope(1, Increment)

        migrationRegion ! Get(1)
        expectMsg(2)
        lastSender.path should be(node(third) / "user" / "sharding" / "AutoMigrateRegionTest" / "1" / "1")

        //Kill region 3
        system.actorSelection(lastSender.path.parent.parent) ! PoisonPill
      }
      enterBarrier("region4-up")

      //Wait for migration to happen
      Thread sleep 2500

      //Test the shard, thus counter was moved onto node 4 and started.
      runOn(fourth) {
        val counter1 = system.actorSelection(system / "sharding" / "AutoMigrateRegionTest" / "1" / "1")
        counter1 ! Identify(1)
        receiveOne(1 second) match {
          case ActorIdentity(1, location) ⇒
            location should not be (None)
        }

        counter1 ! Get(1)
        expectMsg(2)
      }

      enterBarrier("after-14")
    }

    "ensure rebalance restarts shards" in within(50.seconds) {
      runOn(fourth) {
        for (i ← 2 to 12) {
          persistentRegion ! EntryEnvelope(i, Increment)
        }

        for (i ← 2 to 12) {
          persistentRegion ! Get(i)
          expectMsg(1)
        }
      }
      enterBarrier("entries-started")

      runOn(fifth) {
        persistentRegion
      }
      enterBarrier("fifth-joined-shard")

      runOn(fifth) {
        var count = 0

        for (n ← 2 to 12) {
          var entry = system.actorSelection(system / "sharding" / "PersistentCounter" / (n % 12).toString / n.toString)
          entry ! Identify(n)
          receiveOne(1 second) match {
            case ActorIdentity(id, Some(_)) if id == n ⇒ count = count + 1
            case ActorIdentity(id, None)               ⇒ //Not on the fifth shard
          }
        }

        assert(count >= 3, s"Not enough entries migrated, only ${count}")
      }

      enterBarrier("after-15")
    }
  }
}
