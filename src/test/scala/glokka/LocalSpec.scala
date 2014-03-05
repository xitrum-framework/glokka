package glokka

import org.specs2.mutable._

import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor.{Actor, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout

class DummyActor extends Actor {
  def receive = { case _ => }
}

class LocalSpec extends Specification {
  // Use "implicit" so that we can use actor DSL
  implicit val system = ActorSystem("ClusterSystem")

  // For "ask" timeout
  implicit val timeout = Timeout(5000)

  val registry = Registry.start(system, "test")

  "Local mode" should {
    "RegisterResultOk RegisterByRef" in {
      val ref = registry

      val future = registry ? Registry.RegisterByRef("name", ref)
      val result = Await.result(future, timeout.duration)

      result must haveClass[Registry.RegisterResultOk]

      val ok = result.asInstanceOf[Registry.RegisterResultOk]
      ok.name mustEqual "name"
      ok.ref  mustEqual ref
    }

    "RegisterResultOk RegisterByProps" in {
      val props = Props[DummyActor]

      val name   = System.currentTimeMillis().toString
      val future = registry ? Registry.RegisterByProps(name, props)
      val result = Await.result(future, timeout.duration)

      result must haveClass[Registry.RegisterResultOk]

      val ok = result.asInstanceOf[Registry.RegisterResultOk]
      ok.name mustEqual name
    }

    // Copy from above
    "RegisterResultOk same name same actor" in {
      val ref = registry

      val future = registry ? Registry.RegisterByRef("name", ref)
      val result = Await.result(future, timeout.duration)

      result must haveClass[Registry.RegisterResultOk]

      val ok = result.asInstanceOf[Registry.RegisterResultOk]
      ok.name mustEqual "name"
      ok.ref  mustEqual ref
    }

    //--------------------------------------------------------------------------

    "RegisterResultConflict same name different actor" in {
      import akka.actor.ActorDSL._

      val ref = actor(new Act {
        become { case "hello" => sender ! "hi" }
      })

      val future = registry ? Registry.RegisterByRef("name", ref)
      val result = Await.result(future, timeout.duration)

      result must haveClass[Registry.RegisterResultConflict]

      val conflict = result.asInstanceOf[Registry.RegisterResultConflict]
      conflict.name mustEqual "name"
      conflict.ref  mustEqual registry
    }

    "RegisterResultOk different name different actor" in {
      import akka.actor.ActorDSL._

      val ref = actor(new Act {
        become { case "hello" => sender ! "hi" }
      })

      val future = registry ? Registry.RegisterByRef("name2", ref)
      val result = Await.result(future, timeout.duration)

      result must haveClass[Registry.RegisterResultOk]

      val conflict = result.asInstanceOf[Registry.RegisterResultOk]
      conflict.name mustEqual "name2"
      conflict.ref  mustEqual ref
    }

    //--------------------------------------------------------------------------

    "LookupResultOk" in {
      val future = registry ? Registry.Lookup("name")
      val result = Await.result(future, timeout.duration)

      result must haveClass[Registry.LookupResultOk]

      val ok = result.asInstanceOf[Registry.LookupResultOk]
      ok.name mustEqual "name"
      ok.ref  mustEqual registry
    }

    "LookupResultNone" in {
      val future = registry ? Registry.Lookup("namexxx")
      val result = Await.result(future, timeout.duration)

      result must haveClass[Registry.LookupResultNone]

      val ok = result.asInstanceOf[Registry.LookupResultNone]
      ok.name mustEqual "namexxx"
    }

    //--------------------------------------------------------------------------

    "Unregister dead actor" in {
      import akka.actor.ActorDSL._

      val ref = actor(new Act {
        become { case "die" => context.stop(self) }
      })

      registry ! Registry.RegisterByRef("die", ref)
      Thread.sleep(500)

      val future1 = registry ? Registry.Lookup("die")
      val result1 = Await.result(future1, timeout.duration)
      result1 must haveClass[Registry.LookupResultOk]

      ref ! "die"
      Thread.sleep(500)

      val future2 = registry ? Registry.Lookup("die")
      val result2 = Await.result(future2, timeout.duration)
      result2 must haveClass[Registry.LookupResultNone]
    }
  }
}
