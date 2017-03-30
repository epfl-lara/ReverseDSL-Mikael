package perfect
import inox._
import inox.trees._
import inox.trees.dsl._
import org.scalatest._
import matchers._
import Matchers.{=== => _, _}
import perfect.ReverseProgram.ProgramFormula

/**
  * Created by Mikael on 27/03/2017.
  */
class CloneCutWrapTest extends FunSuite with TestHelpers {
  import InoxConvertible._
  import XmlTrees._

  test("Formula assignment") {
    val va = variable[String]("a")
    val vb = variable[String]("b")
    val vc = variable[String]("c")
    val f = ReverseProgram.Formula(BooleanLiteral(true) && StringConcat(vb, "42") === vc && va === StringConcat(vc, vb) && vb === "17")
    f.assignments match {
      case None => fail(s"Could not extract assignments from $f")
      case Some(f) => f(va) shouldEqual Let(vb.toVal, "17", Let(vc.toVal, StringConcat(vb, "42"), Let(va.toVal, StringConcat(vc, vb), va)))
    }
    val f2 = ReverseProgram.Formula(BooleanLiteral(true) && StringConcat(vb, "42") === va && va === StringConcat(vc, vb) && vb === "17")
    f2.assignments shouldEqual None
  }
  test("Wrap") {
    val output = _Node("i", children=_List[Node](_Node("Hello")))
    val pfun = function(
      let("ad" :: inoxTypeOf[String], "Hello"){ av =>
        _Node("i", children=_List[Node](_Node(av)))
      }
    )(inoxTypeOf[Node])
    pfun shouldProduce output

    val tree = valdef[Node](ProgramFormula.tree)
    val newOut = ProgramFormula(
      _Node("b", children=_List[Node](tree.toVariable)),
      tree.toVariable === _Node("i", children=_List[Node](_Node("Hello")))
    )
    pfun repairFrom newOut shouldProduce {
      _Node("b", children=_List[Node](_Node("i", children=_List[Node](_Node("Hello")))))
    } matchBody {
      case Let(ad, StringLiteral("Hello"), e) =>
        exprOps.variablesOf(e) should contain(ad.toVariable)
    }
  }

  test("Unwrap") {
    val output = _Node("b", children=_List[Node](_Node("i", children=_List[Node](_Node("Hello")))))
    val pfun = function(
      let("ad" :: inoxTypeOf[String], "Hello"){ av =>
        _Node("b", children=_List[Node](_Node("i", children=_List[Node](_Node(av)))))
      }
    )(inoxTypeOf[Node])
    pfun shouldProduce output

    val tree = valdef[Node](ProgramFormula.tree)
    val subtree = valdef[Node](ProgramFormula.subtree)
    val newOut = ProgramFormula(
      subtree.toVariable,
      tree.toVariable === _Node("b", children=_List[Node](subtree.toVariable)) &&
      subtree.toVariable === _Node("i", children=_List[Node](_Node("Hello")))
    )
    pfun repairFrom newOut shouldProduce {
      _Node("i", children=_List[Node](_Node("Hello")))
    } matchBody {
      case Let(ad, StringLiteral("Hello"), e) =>
        exprOps.variablesOf(e) should contain(ad.toVariable)
    }
  }

  test("Split") {

  }

  test("Clone") {

  }

  test("Cut") {

  }
}
