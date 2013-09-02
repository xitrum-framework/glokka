package glokka

import org.specs2.mutable._

import akka.actor.ActorSystem

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.pattern.ask
import akka.util.Timeout

class ParserSpec extends Specification {
  implicit val system   = ActorSystem("ClusterSystem")
  val registry = ActorRegistry.start(system)

  implicit val timeout = Timeout(5000)

  "Local mode" should {
    "RegisterResultOk" in {
      val actorRef = registry

      val future = registry ? ActorRegistry.Register("name", actorRef)
      val result = Await.result(future, timeout.duration)

      result must haveClass[ActorRegistry.RegisterResultOk]

      val ok = result.asInstanceOf[ActorRegistry.RegisterResultOk]
      ok.name     mustEqual "name"
      ok.actorRef mustEqual actorRef
    }

    // Copy from above
    "RegisterResultOk same name same actor" in {
      val actorRef = registry

      val future = registry ? ActorRegistry.Register("name", actorRef)
      val result = Await.result(future, timeout.duration)

      result must haveClass[ActorRegistry.RegisterResultOk]

      val ok = result.asInstanceOf[ActorRegistry.RegisterResultOk]
      ok.name     mustEqual "name"
      ok.actorRef mustEqual actorRef
    }

    //--------------------------------------------------------------------------

    "RegisterResultConflict same name different actor" in {
      import akka.actor.ActorDSL._

      val actorRef = actor(new Act {
        become { case "hello" => sender ! "hi" }
      })

      val future = registry ? ActorRegistry.Register("name", actorRef)
      val result = Await.result(future, timeout.duration)

      result must haveClass[ActorRegistry.RegisterResultConflict]

      val conflict = result.asInstanceOf[ActorRegistry.RegisterResultConflict]
      conflict.name     mustEqual "name"
      conflict.actorRef mustEqual registry
    }

    "RegisterResultOk different name different actor" in {
      import akka.actor.ActorDSL._

      val actorRef = actor(new Act {
        become { case "hello" => sender ! "hi" }
      })

      val future = registry ? ActorRegistry.Register("name2", actorRef)
      val result = Await.result(future, timeout.duration)

      result must haveClass[ActorRegistry.RegisterResultOk]

      val conflict = result.asInstanceOf[ActorRegistry.RegisterResultOk]
      conflict.name     mustEqual "name2"
      conflict.actorRef mustEqual actorRef
    }

    //--------------------------------------------------------------------------

    "LookupResultOk" in {
      val future = registry ? ActorRegistry.Lookup("name")
      val result = Await.result(future, timeout.duration)

      result must haveClass[ActorRegistry.LookupResultOk]

      val ok = result.asInstanceOf[ActorRegistry.LookupResultOk]
      ok.name     mustEqual "name"
      ok.actorRef mustEqual registry
    }

    "LookupResultNone" in {
      val future = registry ? ActorRegistry.Lookup("namexxx")
      val result = Await.result(future, timeout.duration)

      result must haveClass[ActorRegistry.LookupResultNone]

      val ok = result.asInstanceOf[ActorRegistry.LookupResultNone]
      ok.name mustEqual "namexxx"
    }
  }
}
