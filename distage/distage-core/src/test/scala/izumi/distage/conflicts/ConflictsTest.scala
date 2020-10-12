package izumi.distage.conflicts

import izumi.distage.model.definition.Axis.AxisPoint
import izumi.distage.planning.mutations.MutationResolver
import org.scalatest.wordspec.AnyWordSpec

class ConflictsTest extends AnyWordSpec {

  import ConflictFixtures._

  "Conflict Resolver" should {
    "resolve standard samples" in {
      val cases = Seq(
        (mutators, Set(AxisPoint("test", "prod")), true, Set("app")),
        (mutators, Set.empty[AxisPoint], false, Set("app")),
        (withLoop, Set(AxisPoint("test", "prod")), true, Set("app")),
        (withLoop, Set.empty[AxisPoint], false, Set("app")),
        (complexMutators, Set.empty[AxisPoint], true, Set("app")),
      )

      val resolver = new MutationResolver.MutationResolverImpl[String, Int, Int]

      for (((f, a, good, roots), idx) <- cases.zipWithIndex) {
        val result = resolver.resolve(f, roots, a, Set.empty)
        if (good) {
          assert(result.isRight, s"positive check #$idx failed")
        } else {
          assert(result.isLeft, s"negative check #$idx failed")
        }
      }
    }
  }
}
