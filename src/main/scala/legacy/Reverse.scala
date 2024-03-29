package legacy

import perfect._
import perfect.ImplicitTuples._
import legacy.ImplicitTuples._

import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import inox._
import inox.trees._
import inox.trees.dsl._
import InoxConvertible._
import inox.evaluators.EvaluationResults
import legacy.Implicits._
import org.apache.commons.lang3.StringEscapeUtils
import perfect.core.predef.{FilterLike, FlatMapReverseLike, MapReverseLike}

import scala.annotation.tailrec

/** Lense class.
  * Should provide the method get and put.
  */
abstract class ~~>[A: InoxConvertible, B: InoxConvertible] {
  type Input = A
  type Output = B
  def get(in: A): B

  // Basic implementation provided in __=>.put
  def put(out2: Variable, inId: Variable, in1: Option[A]): Constraint[A]

  lazy val constrainableInput: InoxConvertible[A] = implicitly[InoxConvertible[A]]
  lazy val constrainableOutput: InoxConvertible[B] = implicitly[InoxConvertible[B]]

  // Basic implementation provided in &~>.put
  def put(out2: B, in1: Option[A]): Iterable[A]

  final def put(out2: B, in1: A): Iterable[A] = put(out2, Some(in1))
  final def put(out2: B): Iterable[A] = put(out2, None)
  def andThen[C: InoxConvertible](f: B ~~> C) = Compose(f, this)
}

/** Particular case of ~~> where the only thing left to implement is the formula mapping inputs to outputs.
  * It implements the direct inverse put method as immediate constraint solving. */
abstract class &~>[A: InoxConvertible, B: InoxConvertible]  extends (A ~~> B) {
  val name: String
  def put(out: Output, st: Option[Input]): Iterable[Input] = report(s"$name.put($out, $st) = %s"){
    val outId = variable[Output]("out")
    val inId = variable[Input]("in")
    val constraint = put(outId, inId, st)
    constraint(outId -> out).toStream(inId)
  }
}
import perfect.ManualReverse

/** Particular case of ~~> where the only thing left to implement is the manual reverse.
  * It implements the constraint formula as a custom method name */
abstract class %~>[A: InoxConvertible, B: InoxConvertible] extends (A ~~> B) with ManualReverse[A, B] {
  val methodName: String
  def put(o: Variable, i: Variable, in: Option[A]): Constraint[A] = {
    val format = FreshIdentifier(methodName)
    val expr = Equals(E(format)(i, inoxExprOf(in)), o)
    Constraint[A](expr, Map(format -> this))
  }
  def put(out2: B, in1: Option[A]): Iterable[A] = putManual(out2, in1)

  def putExpr(outValue: Expr, inDefault: Expr): Iterable[Expr] = {
    val realOutValue = constrainableOutput.recoverFrom(outValue)
    val realDefaultValue = optionInoxConvertible(constrainableInput).recoverFrom(inDefault)
    val inValues = putManual(realOutValue, realDefaultValue)
    inValues.map(constrainableInput.produce)
  }
}

// Definition of multiple lenses

/** String concatenation lens */
object StringAppendReverse extends ((String, String) &~> String) {
  import ImplicitTuples._

  def get(in: (String, String)): String = in._1 + in._2

  val name = "StringAppend"

  override def put(o: Variable, i: Variable, in1: Option[(String, String)]): Constraint[(String, String)] = {
    val expr = in1 match {
      case None =>
        StringConcat(i.getField(_1), i.getField(_2)) === o
      case Some((a, b)) =>
          StringConcat(i.getField(_1), E(b)) === o && E(Utils.original)(i.getField(_2) === E(b)) ||
          StringConcat(E(a), i.getField(_2)) === o && E(Utils.original)(i.getField(_1) === E(a)) ||
          StringConcat(i.getField(_1), i.getField(_2)) === o
    }
    Constraint[(String, String)](expr)
  }
}

/** String concatenation lens */
/*case class ListAppendReverse[A : InoxConvertible]() extends ((List[A], List[A]) &~> List[A]) {
  import ImplicitTuples._

  def get(in: (List[A], List[A])): List[A] = in._1 ++ in._2

  val name = "ListAppend"

  override def put(o: Variable, i: Variable, in1: Option[(List[A], List[A])]): Constraint[(List[A], List[A])] = {
    val expr = in1 match {
      case None =>
        StringConcat(i.getField(_1), i.getField(_2)) === o
      case Some((a, b)) =>
        StringConcat(i.getField(_1), E(b)) === o && E(Utils.original)(i.getField(_2) === E(b)) ||
          StringConcat(E(a), i.getField(_2)) === o && E(Utils.original)(i.getField(_1) === E(a)) ||
          StringConcat(i.getField(_1), i.getField(_2)) === o
    }
    Constraint[(String, String)](expr)
  }
}*/


/** Integer addition lens */
object IntPlusReverse extends ((Int, Int) &~> Int) {
  import ImplicitTuples._
  def get(st: (Int, Int)) = st._1 + st._2
  val name = "IntPlus"

  def put(o: Variable, i: Variable, in1: Option[(Int, Int)]) = {
    val expr = in1 match {
      case None =>
        i.getField(_1) + i.getField(_2) === o
      case Some((a, b)) =>
        (i.getField(_1) + i.getField(_2) === o) && E(Utils.original)(i.getField(_1) === E(a)) && E(Utils.original)(i.getField(_2) === E(b))
    }
    Constraint[(Int, Int)](expr)
  }
}

/*
object StringExtractReverse {
  def substring(s: String, start: Int, end: Int) = s.substring(s, start, end)
  
  def substringRev(s: String, start: Int, end: Int, out: String) = s.substring(0, start) + out + s.substring(end)
}*/

object StringFormatReverse extends ((String, List[Either[String, Int]]) %~> String) {
  import java.util.regex.Pattern
  def format(s: String, args: List[Either[String, Int]]) = {
    //println(s"Calling '$s'.format(" + args.map(x => (x, x.getClass)).mkString(",") + ")")
    s.format(args.map{ case Left(i) => i : Any case Right(i) => i : Any}: _*)
  }
  def get(in: Input) = format(in._1, in._2)
  val name = "StringFormat"
  val methodName = "format"

  def putManual(out2: Output, in: Option[Input]): Iterable[Input] =
    in match {
    case None =>
      List(("%s", List(Left(out2))))
    case Some(in) =>
      formatRev(in._1, in._2, out2)
  }

  // Parsing !
  def formatRev(s: String, args: List[Either[String, Int]], out: String): List[(String, List[Either[String, Int]])] = {
    // Replace all %s in s by (.*) regexes, and %d in s by (\d*) regexes.
    val formatters = "%(?:(\\d+)\\$)?((?:s|d))".r
    var i = -1
    val indexes = new ArrayBuffer[Int]()
    val splitters = formatters.findAllIn(s).matchData.map{ m => 
      (m.start, m.end, m.toString, m.subgroups(1), if(m.subgroups(0) == null) {i += 1; indexes += i; i} else { val k = m.subgroups(0).toInt - 1; indexes += k; k})
    }.toList
    val argsLength = args.length
    val substrings = ((ListBuffer[(Int, Int, String)](), 0) /: (splitters :+ ((s.length, s.length, "", "", "")))) {
      case ((lb, prevIndex), (startIndex, endIndex, _, _, _)) => (lb += ((prevIndex, startIndex, s.substring(prevIndex, startIndex))), endIndex)
    }._1.toList
    
    // given the elements, put them in the right order. Provides alternatives.
    def reverse(indexes: IndexedSeq[Int], l: List[Either[String, Int]]): List[List[Either[String, Int]]] = {
      //print(s"indexes : $indexes, l: $l, result = ")
      // indexes = [1, 1] and l = List(a, b) => List(List(a), List(b))
      // indexes = [1, 2] and l = List(a, b) =>  List(List(a, b))
      // indexes = [2, 3, 1] and l = List(b, c, a) =>  List(List(a, b, c))
      // indexes = [1, 2, 1] and l = List(a, b, c) =>  List(List(a, b), List(c, b))
      val zipped = indexes.zip(l).groupBy(_._1).mapValues(v => v.map(_._2))
      // zipped {1 -> List(a, b)} => List(List(a), List(b))
      // zipped {1 -> List(a), 2 -> List(b)} =>  List(List(a, b))
      // zipped {2 -> List(b), 3 -> List(c), 1 -> List(a)} =>  List(List(a, b, c))
      // zipped {1 -> List(a, c), 2 -> List(b)} =>  List(List(a, b), List(c, b))
      (List(List[Either[String, Int]]()) /: (argsLength to 1 by -1)) {
        case (lb, i) =>
          val maps: Seq[Either[String, Int]] = zipped.getOrElse(i-1, List(args(i-1)))
          maps.flatMap(pos => lb.map(pos::_)).toList.distinct
      }
    }
    
    // Replace all splitters by regular expressions to pattern match against anything.
    val ifargsmodifiedRegex = Pattern.quote(substrings.head._3) + splitters.map{ x => x._4 match {
      case "s" => "(.*)"
      case "d" => "(\\d*)"
    }
    }.zip(substrings.tail.map(x => Pattern.quote(x._3))).map(x => x._1 + x._2).mkString
    
    val ifargsmodified = ifargsmodifiedRegex.r.unapplySeq(out) match { // Maybe it's an argument which has been formatted.
      case Some(args2) => 
        //println("splitters:" + splitters)
        // TODO: Reorder out for comparison.
        var res = reverse(indexes, args2.zip(splitters).map{ arg_s => 
          arg_s._2._4 match {
            case "s" => Left(arg_s._1)
            case "d" => Right(arg_s._1.toInt)
          }
        })
        //println("Classes:" + res.map(l => l.map(_.getClass).mkString(",")))
        val expected = format(s, args)
        if (res.length > 1) {
          res = res.filter(otherargs => format(s, otherargs) != expected)
        }
        res.map((s, _))
      case None => Nil
    }
    val regexschange = args.map{ case Left(x) => Pattern.quote(x.toString) case Right(x) =>Pattern.quote(x.toString) }.mkString("(.*)", "(.*)", "(.*)")
    //println("Regex if changed: " + regexschange)
    val ifsmodified =  regexschange.r.unapplySeq(out) match {
      case Some(sSplitted) => List((sSplitted.mkString("%s"), args))
      case None => Nil
    }
    //println("Regex solution: " + ifsmodified)
    ifsmodified ++ ifargsmodified
  }
}

case class InoxLambda[A: InoxConvertible, B: InoxConvertible](args: Variable, body: Expr)(implicit p: Program { val trees: inox.trees.type }) extends (A => B) {
  def apply(a: A) = {
    val exprA = inoxExprOf[A](a)
    val exprToEval = Application(Lambda(Seq(args.toVal), body), Seq(exprA))
    p.getEvaluator.eval(exprToEval, Model.empty(p)) match {
      case EvaluationResults.Successful(b) => exprOfInox[B](b)
      case e => throw new Exception(s"error while executing $exprToEval, got $e")
    }
  }
}


/* Macro reversing

def f(s: String) = {
  Element(“li”, TextElement(s)::Nil)
}
f(“abc”)
⇒
def f(s: String, l: String => Element) = {
  apply(l, s)
}
f(“abc”, (s: String) => Element(“li”, TextElement(s)::Nil))

Requested output: Element(“li”, Element(“div”, TextElement(“abc”)::Nil)::Nil). Just finding a new value for “abc” does not work.

meta-reversing.


case class ApplyADTReverse[A: InoxConvertible, B: InoxConvertible]() extends ((A, InoxFun[A, B]) ~~> B) {
  applyReverse(out: Expr, init: Option[(Lambda, Expr)] ): Iterable[(Lambda, Expr)] = {
    val (lambda, initExpr) = init.get
    val Lambda(argName, body) = lambda
    val initOut = execute(lambda(initExpr))
    if(out == initOut) return Stream(init)
    // look on how to transform initOut to out and apply this transformation on body.
  }
}

*/

import scala.util.matching.Regex
case class RegexReplaceAllInReverse(regex: Regex, f: List[String] ~~> String) extends (String %~> String) {
  import java.util.regex.Pattern
  val methodName = "replaceAllIn" + Math.abs(this.hashCode()/2)
  def get(in: String): String = regex.replaceAllIn(in, m => f.get(m.subgroups))
  def putManual(out: String, in: Option[String]): Iterable[String] = {
    in match {
      case None => Nil // Maybe something better than Nil?
      case Some(in) => // Let's figure out where f did some replacement.
        val matches = regex.findAllMatchIn(in)

        var lastEnd = 0
        val stringMatchPairs = (for {m <- matches.toList.view
                                     start = m.start
                                     end = m.end
                                     str = in.substring(lastEnd, start)
        } yield {
          lastEnd = end
          (str, m)
        })
        val (strings, mm) = stringMatchPairs.toList.unzip
        val allstrings = strings :+ in.substring(lastEnd)
        // Now we are going to parse the original string using all strings to recover the new output of each f element:
        val ifReplacedContentChanged = (Pattern.quote(allstrings.head) + allstrings.tail.map {
          case s => "(.*)" + Pattern.quote(s)
        }.mkString).r
        val ifReplacedConstantChanged = ("(.*)" + mm.toList.map {
          case m => Pattern.quote(f.get(m.subgroups)) + "(.*)"
        }.mkString).r
        val replacedConstantSolutions = ifReplacedConstantChanged.unapplySeq(out) match {
          case Some(ss) =>
            List(ss.zip(mm).map { case (a, b) => a + b.matched }.mkString + ss.last)
          case None => Nil
        }
        val replacedContentSolutions: Iterable[String] = ifReplacedContentChanged.unapplySeq(out) match {
          case Some(ss) =>
            // Need to compute the values of f now and put back the content into them.
            val unplexedResult = mm.zip(ss) map { case (m, s) =>
              val init = m.subgroups
              f.put(s, Some(init)).toStream.map {
                (fargs: List[String]) =>
                  // Need to re-plug fargs inside the regex.
                  // For that we need to split the group.
                  val globalstart = m.start(0)
                  var lastEnd = m.start(0)
                  val externalGroups = for {i <- (1 to m.groupCount).toList.view
                                            start = m.start(i)
                                            end = m.end(i)
                                            str = m.matched.substring(lastEnd - globalstart, start - globalstart)
                  } yield {
                    lastEnd = end
                    str
                  }
                  externalGroups.zip(fargs).map { case (a, b) => a + b }.mkString + m.matched.substring(lastEnd - globalstart)
              }
            }
            val plexedResult = inox.utils.StreamUtils.cartesianProduct(unplexedResult)
            plexedResult.map { (contents: List[String]) =>
              allstrings.zip(contents).map { case (a, b) => a + b }.mkString + allstrings.last
            }
          // These are the new values to pass to f.
          case None => Nil
        }
        replacedConstantSolutions ++ replacedContentSolutions
    }
  }
}

/*
object IntReverse extends ((Int, Int) ~~> Int) {
  def add(s: Int, t: Int) = s+t
  def get(in: Input): Output = add(in._1, in._2)
  def put(out2: Output, in: Option[Input]): Constraint[Input] = in match {
    case None => List((out2, 0))
    case Some(in) => addRev(in._1, in._2, out2)
  }
  
  def addRev(s: Int, t: Int, out: Int): List[(Int, Int)] = {
    if(s + t == out) List((s, t)) else
    List((s, out-s),  (out-t, t))
  }// ensuring { ress => ress.forall(res => add(res._1, res._2) == out) }
}
*/
object Interleavings {
  def allInterleavings[A](l1: List[A], l2: List[A]): List[List[A]] = {
    if(l1.isEmpty) List(l2)
    else if(l2.isEmpty) List(l1)
    else allInterleavings(l1.tail, l2).map(l1.head :: _) ++ allInterleavings(l1, l2.tail).map(l2.head :: _)
  }
}

case class ListSplit[A: InoxConvertible](p: A => Boolean) extends (List[A] %~> (List[A], List[A])) {
  import Interleavings._

  val methodName = "ListSplit" + Math.abs(this.hashCode()/2)
  
  def get(in: Input): Output = split(in)
  def putManual(out2: Output, in: Option[Input]) = in match {
    case None => List(out2._1 ++ out2._2)
    case Some(in) => splitRev(in, out2)
  }
  
  def split(l: List[A]): (List[A], List[A]) = l match {
    case Nil => (Nil, Nil)
    case a::b => val (lfalse, ltrue) = split(b)
     if(p(a)) (lfalse, a::ltrue) else (a::lfalse, ltrue)
  }
  
  // Ensures that every created list has out._1.size + out._2.size
  def splitRev(l: List[A], out: (List[A], List[A])):List[List[A]] = {
    out match {
      case (Nil, l2) => List(l2)
      case (l1, Nil) => List(l1)
      case (l1@ (a::b), l2 @ (c::d)) => 
        if(p(a)) Nil
        else if(!p(c)) Nil
        else if(l.isEmpty) allInterleavings(l1, l2)
        else if(!l.exists(_ == c)) // c has been added.
          splitRev(l, (a::b, d)).map(c::_) ++ (if(l.head == a) splitRev(l.tail, (b, c::d)).map(a::_) else Nil)
        else if(!l.exists(_ == a)) // a has been added.
          splitRev(l, (b, c::d)).map(a::_) ++ (if(l.head == c) splitRev(l.tail, (a::b, d)).map(c::_) else Nil)
        else if(!p(l.head) && !l1.exists(_ == l.head)) // l.head has been deleted and was in the first.
          splitRev(l.tail, (l1, l2))
        else if(p(l.head) && !l2.exists(_ == l.head)) // l.head has been deleted and was in the second.
          splitRev(l.tail, (l1, l2))
        else if(!p(l.head) && a == l.head) 
          splitRev(l.tail, (b, l2)).map(a::_)
        else if(p(l.head) && c == l.head)
          splitRev(l.tail, (l1, d)).map(c::_)
        else /*if(l.head != a && l.head != c)*/ { // l.head is no longer there.
          splitRev(l.tail, (l1, l2))
        }
    }
  }
}

import perfect.WebTrees._

object TypeSplit extends (List[WebTree] %~> (List[WebElement], List[WebAttribute], List[WebStyle])) {
  import Interleavings._
  val methodName = "TypeSplit"
  def get(in: Input): Output = split(in)
  def putManual(out2: Output, in1: Option[Input]): Iterable[Input] = in1 match {
    case None =>
      List(out2._1 ++ out2._2 ++ out2._3)
    case Some(in1) =>
      splitRev(in1, out2)
  }
  
  def split(l: List[WebTree]): (List[WebElement], List[WebAttribute], List[WebStyle]) = l match {
    case Nil => (Nil, Nil, Nil)
    case a::b => val (l1, l2, l3) = split(b)
     a match {
       case w: WebElement => (w::l1, l2, l3)
       case w: WebAttribute => (l1, w::l2, l3)
       case w: WebStyle => (l1, l2, w::l3)
     }
  }

  // Ensures that every created list has out._1.size + out._2.size
  def splitRev(l: List[WebTree], out: (List[WebElement], List[WebAttribute], List[WebStyle])):List[List[WebTree]] = {
    out match {
      case (l1, l2, l3) =>
        l match {
          case Nil => allInterleavings(l1, l2).flatMap(l1l2 => allInterleavings(l1l2, l3))
          case lh::lt => 
            lh match {
              case lh: WebElement =>
                l1 match {
                  case Nil => // An element was deleted.
                    splitRev(lt, (l1, l2, l3))
                  case l1h::l1t =>
                    if (lh == l1h) {
                      splitRev(lt, (l1t, l2, l3)).map(lh::_)
                    } else if(!l.exists(_ == l1h)) { // l1h was added
                      splitRev(l, (l1t, l2, l3)).map(l1h::_)
                    } else { // It exists later, so lh was removed.
                      splitRev(lt, (l1, l2, l3))
                    }
                }
              case lh: WebAttribute =>
                l2 match {
                  case Nil => // An element was deleted.
                    splitRev(lt, (l1, l2, l3))
                  case l2h::l2t =>
                    if (lh == l2h) {
                      splitRev(lt, (l1, l2t, l3)).map(lh::_)
                    } else if(!l.exists(_ == l2h)) { // l2h was added
                      splitRev(l, (l1, l2t, l3)).map(l2h::_)
                    } else { // It exists later, so lh was removed.
                      splitRev(lt, (l1, l2, l3))
                    }
                }
              case lh: WebStyle =>
                l3 match {
                  case Nil => // An element was deleted.
                    splitRev(lt, (l1, l2, l3))
                  case l3h::l3t =>
                    if (lh == l3h) {
                      splitRev(lt, (l1, l2, l3t)).map(lh::_)
                    } else if(!l.exists(_ == l3h)) { // l3h was added
                      splitRev(l, (l1, l2, l3t)).map(l3h::_)
                    } else { // It exists later, so lh was removed.
                      splitRev(lt, (l1, l2, l3))
                    }
                }
            }
        }
    }
  }
}


object WebElementAddition extends ((Element, (List[WebElement], List[WebAttribute], List[WebStyle])) %~> Element) {
  def get(in: Input) = apply(in._1, in._2._1, in._2._2, in._2._3)
  val methodName = "WebElementAddition"
  def putManual(out2: Output, in: Option[Input]) = in match {
    case None => List((out2, (Nil, Nil, Nil)))
    case Some(in) => applyRev(in, out2)
  }

  def apply(elem: Element, children: List[WebElement], attributes: List[WebAttribute], styles: List[WebStyle]): Element = {
    Element(elem.tag, elem.children ++ children, elem.attributes ++ attributes, elem.styles ++ styles)
  }
  
  def applyRev(in: Input, out: Output): List[Input] = {
    val elem = in._1
    val children = in._2._1
    val attributes = in._2._2
    val styles = in._2._3
    // If the original element could have been not modified
    val ifOriginalElemNotModified : List[Input] = if(
        out.children.take(elem.children.length) == elem.children &&
        out.attributes.take(elem.attributes.length) == elem.attributes &&
        out.styles.take(elem.styles.length) == elem.styles) {
      List((elem,
            (out.children.drop(elem.children.length),
            out.attributes.drop(elem.attributes.length),
            out.styles.drop(elem.styles.length)))
      )
    } else Nil
    val ifAdditionsNotModified : List[Input] = if(
      out.children.takeRight(children.length) == children &&
      out.attributes.takeRight(attributes.length) == attributes &&
      out.styles.takeRight(styles.length) == styles) {
      List((Element(elem.tag,
        out.children.dropRight(children.length),
        out.attributes.dropRight(attributes.length),
        out.styles.dropRight(styles.length)),
           (children,
           attributes,
           styles)
        ))
    } else Nil
    (ifOriginalElemNotModified ++ ifAdditionsNotModified).distinct
  }
}

object WebElementComposition extends ((Element, List[WebTree])  %~> Element) {
  def get(in: Input): Output = {
    WebElementAddition.get(in._1, TypeSplit.get(in._2))
  }

  val methodName = "WebElementComposition"
  def putManual(out2: Output, in: Option[Input]): Iterable[Input] = Implicits.report(s"WebElementComposition($in, $out2) = %s")(in match {
    case None =>
      val l = out2.children ++ out2.attributes ++ out2.styles
      for{ i <- 0 to l.length
        (lhs, rhs) = l.splitAt(i)
      } yield { (get((Element(out2.tag), lhs)), rhs)}
    case Some(in) =>
    val originalMiddle = TypeSplit.get(in._2)
    WebElementAddition.put(out2, (in._1, originalMiddle)).flatMap{ case (elem, cas) => 
      TypeSplit.put(cas, in._2).map{ s => (elem, s) }
    }
  })
}

case class Compose[A: InoxConvertible, B: InoxConvertible, C: InoxConvertible](a: B ~~> C, b: A ~~> B) extends (A ~~> C) {
  def get(in: Input): Output = a.get(b.get(in).asInstanceOf[a.Input])
  def put(out2: Output, in: Option[Input]) = {
   val intermediate_out = in.map(b.get)
   a.put(out2, intermediate_out).flatMap(x => b.put(x, in))
  }

  override def put(idC: Variable, idA: Variable, in1: Option[A]): Constraint[A] = {
    val idB = variable[B]("t", true)
    val intermediate_out = in1.map(b.get) // TODO: Have it pre-computed already
    b.put(idB, idA, in1) &&
      a.put(idC, idB, intermediate_out)
  }
}

case class PairSame[A: InoxConvertible, B: InoxConvertible, D: InoxConvertible](a: A ~~> B, b: A ~~> D)
    extends (A &~> (B, D)) {
  def get(in: Input): Output = (a.get(in), b.get(in))
  //def put(out2: Output, in: Option[Input]): Iterable[Input] = ???
  import ImplicitTuples._
  val name = "PairSame" + Math.abs(this.hashCode()/2)

  def put(varBD: Variable, varA: Variable, in: Option[A]): Constraint[A] = {
    val varB = variable[B]("b", true)
    val varD = variable[D]("d", true)
    a.put(varB, varA, in) &&
      b.put(varD, varA, in) &&
      varB === varBD.getField(_1) &&
      varD === varBD.getField(_2)
  }
}

import InoxConvertible.listConvertible

case class Flatten[A: InoxConvertible]() extends %~>[List[List[A]], List[A]]()(listConvertible(implicitly[InoxConvertible[List[A]]]), implicitly[InoxConvertible[List[A]]]) {
  val methodName = "flatten"+ Math.abs(this.hashCode()/2)
  def get(in: Input) = flatten(in)
  def putManual(out2: Output, in: Option[Input]) = in match {
    case None => List(List(out2))
    case Some(in) => flattenRev(in, out2)
  }

  def allUnflattenings(out: List[A]): Stream[List[List[A]]] = {
    out match {
      case Nil => Stream(List())
      case out => 
        for{i <- Stream.from(1).take(out.length)
            outhead = out.take(i)
            outtail = out.drop(i)
            other <- allUnflattenings(outtail)
          } yield {
          outhead :: other
        }
    }
  }

  def flatten(l: List[List[A]]): List[A] = l.flatten
  
  def flattenRev(l: List[List[A]], out: List[A]): Stream[List[List[A]]] = {
    l match {
      case Nil => 
        if(out == Nil) Stream(List()) else
        allUnflattenings(out) // We should create all possible decompositions of out.
      //case a::Nil => if(a == out) List(List(out)) else allUnflattenings(out)
      case a::q => if(out.take(a.length) == a) {
        (if (out.length == a.length) {
          Stream(List(a))
        } else Stream.empty) #:::
        (flattenRev(q, out.drop(a.length)).map(a::_))
      } else { // Something happened, a sequence inserted or deleted, or some elements changed.
        if (out == a.take(out.length)) { // it was a prefix of the current list.
          Stream(List(out))
        } else {
          val expected_flatten = flatten(l)
          if (out.endsWith(expected_flatten)) {
            val missing = out.dropRight(expected_flatten.length)
            ((missing ++ a) :: q) #:: (
            for{ toTakeAlone <- Stream.from(1).take(missing.length)
                 alone = missing.take(toTakeAlone)
                 missingAttached = missing.drop(toTakeAlone)
                 alone_decomposition <- allUnflattenings(alone)
                 } yield {
              alone_decomposition ++ ((missingAttached ++ a)::q)
            })
          } else if (expected_flatten.endsWith(out)) { // Elements have been deleted.
            val flattenq = flatten(q)
            if (flattenq.endsWith(out)) {
              flattenRev(q, out)
            } else { // out is slightly longer than q and ends with flattenQ.
              val missing = out.dropRight(flattenq.length)
              assert(a.endsWith(missing))
              flattenRev(a.drop(a.length - missing.length)::q, out)
            }
          } else {
            val aZipOut = a.zip(out) // of length a.length
            val sameStart = aZipOut.takeWhile(x => x._1 == x._2) // of length < a.length
            val missedSeq = a.drop(sameStart.length)
            val restartIndex = out.indexOfSlice(missedSeq)
            if (restartIndex != -1) { // There has been an addition in the output, but we can recover.
              val t = restartIndex + missedSeq.length
              flattenRev(q, out.drop(t)).map(out.take(t)::_)
            } else { // Maybe there has been a deletion in the output.
              for{sizeOfDeletion <- Stream.from(1).take(a.length - sameStart.length)
                  lookingFor = a.drop(sameStart.length + sizeOfDeletion)
                  indexOfLookingFor = (if(lookingFor.length > 0) out.indexOfSlice(lookingFor, sameStart.length)
                                       else sameStart.length)
                  if indexOfLookingFor != -1
                  t = indexOfLookingFor + lookingFor.length
                  y <- flattenRev(q, out.drop(t))
              } yield (out.take(t)::y)
            }
          }
//          ini =a[1 2 3 4 9] [5 6] [] [7] // restartIndex != -1
//          out = [1 2 3 2 4 9 5 6 7]

//          ini =a[1 2 3 2 4 8] [5 6] [] [7] // restartIndex == -1
//          out = [1 2 3 5 6 7]
        }
      }
    }
  } // ensuring res => res.forall(sol => sol.flatten == out && lehvenstein(l, sol) == lehvenstein(out, sol.flatten))
}

case class MapReverse[A: InoxConvertible, B: InoxConvertible](fr: A ~~> B) extends (List[A] %~> List[B]) with MapReverseLike[A, B, Nothing] {
  val f: A => B = fr.get _
  val fRev = (in: Option[A], b: B) => fr.put(b, in).toStream.map(Left(_))
  def get(in: Input) = map(in)
  val methodName = "map"+ Math.abs(this.hashCode()/2)
  @tailrec private def filterOnlyValChangeOrFail(in: List[Either[A, Nothing]],
                                                 collected: ListBuffer[A] = ListBuffer()): List[List[A]] = in match {
    case Nil => List(collected.toList)
    case Left(a)::tail => filterOnlyValChangeOrFail(tail, collected += a)
    case _ => Nil
  }
  def putManual(out2: Output, in: Option[Input]): Iterable[Input] = Implicits.report(s"MapReverse.put($out2, $in) = %s"){
    (in match {
    case None => mapRev(Nil, out2)
    case Some(in) => mapRev(in, out2)
    }).flatMap(filterOnlyValChangeOrFail(_))
  }
}

case class FilterReverse[A: InoxConvertible](f: A => Boolean) extends (List[A] %~> List[A]) with FilterLike[A] {
  def get(in: Input) = filter(in, f)
  val methodName = "filter"+ Math.abs(this.hashCode()/2)
  def putManual(out2: Output, in: Option[Input]) = Implicits.report(s"FilterReverse.put($out2, $in) = %s"){in match {
    case None => filterRev(Nil, f, out2)
    case Some(in) => filterRev(in, f, out2)
  }}
  // ensuring res => res.forall(sol => filter(sol, f) == out && lehvenstein(l, sol) == lehvenstein(out, filter(sol, f))
}

case class FlatMap[A: InoxConvertible, B: InoxConvertible](fr: A ~~> List[B]) extends (List[A] %~> List[B])
    with FlatMapReverseLike[A, B, Nothing] {
  val f = fr.get _
  val fRev = (opt: Option[A], x: List[B]) => fr.put(x, opt).toStream.map(Left[A, Nothing](_))
  def get(in: Input) = flatMap(in)
  val methodName = "flatMap"+ Math.abs(this.hashCode()/2)
  def putManual(out2: Output, in: Option[Input]) = flatMapRev(in.toList.flatten, out2).map{
    l => l.map {
      case Left(a) => a
      case _ => throw new Exception("Unprepared to reverse that")
    }
  }
}

case class Const[I: InoxConvertible, A: InoxConvertible](value: A) extends (I &~> A) {
  def get(u: I) = value

  override val name: String = "const"+ Math.abs(this.hashCode()/2)

  // Basic implementation provided in __=>.put
  override def put(out2: inox.trees.Variable, inId: inox.trees.Variable, in1: Option[I]): Constraint[I] = {
    Constraint(out2 === inoxExprOf(value))
  }
}

case class Id[A: InoxConvertible]() extends (A ~~> A) {
  def get(a: A) = a
  def put(out: A, orig: Option[A]) = Stream(out)
  def put(varIn: Variable, varOut: Variable, in: Option[A]) = {
    Constraint(varIn === varOut)
  }
}

case class CastUp[A: InoxConvertible, B: InoxConvertible, B2 >: B : InoxConvertible](f: A ~~> B) extends (A &~> B2) {
  def get(a: A) = f.get(a)

  override val name: String = "castup"+ Math.abs(this.hashCode()/2)

  // Basic implementation provided in __=>.put
  override def put(out2: inox.trees.Variable, inId: inox.trees.Variable, in1: Option[A]): Constraint[A] = {
    f.put(out2, inId, in1)
  }
}
