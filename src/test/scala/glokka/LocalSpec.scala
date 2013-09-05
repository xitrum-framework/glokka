package glokka

import org.specs2.mutable._

import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.util.Timeout

class ParserSpec extends Specification {
  // Use "implicit" so that we can use actor DSL
  implicit val system = ActorSystem("ClusterSystem")

  // For "ask" timeout
  implicit val timeout = Timeout(5000)

  val registry = Registry.start(system, "test")

  "Local mode" should {
    "RegisterResultOk" in {
      val actorRef = registry

      val future = registry ? Registry.Register("name", actorRef)
      val result = Await.result(future, timeout.duration)

      result must haveClass[Registry.RegisterResultOk]

      val ok = result.asInstanceOf[Registry.RegisterResultOk]
      ok.name     mustEqual "name"
      ok.actorRef mustEqual actorRef
    }

    // Copy from above
    "RegisterResultOk same name same actor" in {
      val actorRef = registry

      val future = registry ? Registry.Register("name", actorRef)
      val result = Await.result(future, timeout.duration)

      result must haveClass[Registry.RegisterResultOk]

      val ok = result.asInstanceOf[Registry.RegisterResultOk]
      ok.name     mustEqual "name"
      ok.actorRef mustEqual actorRef
    }

    //--------------------------------------------------------------------------

    "RegisterResultConflict same name different actor" in {
      import akka.actor.ActorDSL._

      val actorRef = actor(new Act {
        become { case "hello" => sender ! "hi" }
      })

      val future = registry ? Registry.Register("name", actorRef)
      val result = Await.result(future, timeout.duration)

      result must haveClass[Registry.RegisterResultConflict]

      val conflict = result.asInstanceOf[Registry.RegisterResultConflict]
      conflict.name     mustEqual "name"
      conflict.actorRef mustEqual registry
    }

    "RegisterResultOk different name different actor" in {
      import akka.actor.ActorDSL._

      val actorRef = actor(new Act {
        become { case "hello" => sender ! "hi" }
      })

      val future = registry ? Registry.Register("name2", actorRef)
      val result = Await.result(future, timeout.duration)

      result must haveClass[Registry.RegisterResultOk]

      val conflict = result.asInstanceOf[Registry.RegisterResultOk]
      conflict.name     mustEqual "name2"
      conflict.actorRef mustEqual actorRef
    }

    //--------------------------------------------------------------------------

    "LookupResultOk" in {
      val future = registry ? Registry.Lookup("name")
      val result = Await.result(future, timeout.duration)

      result must haveClass[Registry.LookupResultOk]

      val ok = result.asInstanceOf[Registry.LookupResultOk]
      ok.name     mustEqual "name"
      ok.actorRef mustEqual registry
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

      val actorRef = actor(new Act {
        become { case "die" => context.stop(self) }
      })

      registry ! Registry.Register("die", actorRef)
      Thread.sleep(100)

      val future1 = registry ? Registry.Lookup("die")
      val result1 = Await.result(future1, timeout.duration)
      result1 must haveClass[Registry.LookupResultOk]

      actorRef ! "die"
      Thread.sleep(100)

      val future2 = registry ? Registry.Lookup("die")
      val result2 = Await.result(future2, timeout.duration)
      result2 must haveClass[Registry.LookupResultNone]
    }
  }
}
