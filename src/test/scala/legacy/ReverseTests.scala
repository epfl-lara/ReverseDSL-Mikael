package legacy
import perfect._

import org.scalatest._
import Matchers.{ === => _, _}
import inox._
import inox.trees.{not => inoxNot, _}
import inox.trees.dsl._

import scala.reflect.runtime.universe.TypeTag

object Make {
  def apply[A: InoxConvertible, B](in: Id[A] => (A ~~> B)): (A ~~> B) = in(Id[A]())
}


class StringAppendTest extends FunSuite {
  import InoxConvertible._
  import legacy.ImplicitTuples._
  import perfect.ImplicitTuples._
  import legacy.Implicits._

  def doubleAppend(in: Id[(String, String)]) = {
    in._1 + in._2
  }

  test("Double decomposition") {
    val d = Make(doubleAppend)
    d.get(("Hello ", "world")) shouldEqual "Hello world"
    var o = variable[String]("o")
    var i = variable[(String, String)]("i")
    val init = ("Hello ", "world")
    val constraint = d.put(o, i, Some(init))
    def sort[A](s: Stream[A], num: Int) = s.take(num).sortBy(Distances.distance(_, init)) #::: s.drop(num)

    sort(constraint(o -> "Hello world").toStream(i), 2).head shouldEqual ("Hello ", "world")
    sort(constraint(o -> "Hello  world").toStream(i), 2).head shouldEqual ("Hello  ", "world")
    sort(constraint(o -> "Hello Buddy").toStream(i), 2).head shouldEqual ("Hello ", "Buddy")
    sort(constraint(o -> "Hi world").toStream(i), 2).head shouldEqual ("Hi ", "world")
    sort(constraint(o -> "Helloooo world").toStream(i), 2).head shouldEqual ("Helloooo ", "world")
    sort(constraint(o -> "Hello aworld").toStream(i), 2).head shouldEqual (("Hello ", "aworld"))
    sort(constraint(o -> "Hi buddy").toStream(i), 10).head shouldEqual (("Hi ", "buddy"))
  }

  def appendTwice(in: Id[String]) = {
    in + in
  }

  test("Append twice") {
    val d = Make(appendTwice)
    d.get("Hello") shouldEqual "HelloHello"
    var o = variable[String]("o")
    var i = variable[String]("i")
    val init = "Hello"
    val constraint = d.put(o, i, Some(init))
    def sort[A](s: Stream[A], num: Int) = s.take(num).sortBy(Distances.distance(_, init)) #::: s.drop(num)

    //println(constraint.simplify(varI))

    sort(constraint(o -> "MikaelMikael").toStream(i), 2).head shouldEqual "Mikael" // Longer disambiguated
    sort(constraint(o -> "MikMik").toStream(i), 2).head shouldEqual "Mik" // Shorter disambiguated

    sort(constraint(o -> "HelloMikael").toStream(i), 2).head shouldEqual "Mikael" // Longer
    sort(constraint(o -> "MikaelHello").toStream(i), 2).head shouldEqual "Mikael"
    sort(constraint(o -> "HelloMik").toStream(i), 2).head shouldEqual "Mik" // Shorter
    sort(constraint(o -> "MikHello").toStream(i), 2).head shouldEqual "Mik"
  }

  def appendTwicePlusMiddle(in: Id[(String, String)]) = {
    in._1 + in._2 + in._1
  }

  test("Append twice plus middle") {
    val d = Make(appendTwicePlusMiddle)
    val init = ("Hello", " ")
    d.get(init) shouldEqual "Hello Hello"
    var o = variable[String]("o")
    var i = variable[(String, String)]("i")
    val constraint = d.put(o, i, Some(init))
    def sort[A](s: Stream[A], num: Int) = s.take(num).sortBy(Distances.distance(_, init)) #::: s.drop(num)

    //println(constraint.simplify(i))

    sort(constraint(o -> "Mikael Mikael").toStream(i), 2).head shouldEqual (("Mikael", " ")) // Longer disambiguated
    sort(constraint(o -> "Mik Mik").toStream(i), 2).head shouldEqual (("Mik", " ")) // Shorter disambiguated

    sort(constraint(o -> "Mikael Hello").toStream(i), 3).head shouldEqual (("Mikael", " ")) // Longer
    sort(constraint(o -> "Hello Mikael").toStream(i), 2).head shouldEqual (("Mikael", " "))
    sort(constraint(o -> "Hello Mik").toStream(i), 2).head shouldEqual (("Mik", " ")) // Shorter
    sort(constraint(o -> "Mik Hello").toStream(i), 2).head shouldEqual (("Mik", " "))

    sort(constraint(o -> "Hello  Hello").toStream(i), 3).head shouldEqual (("Hello", "  ")) // Space editiong
    sort(constraint(o -> "HelloHello").toStream(i), 2).head shouldEqual (("Hello", "")) // Space editiong
  }
}


class IntPlusReverseTest extends FunSuite  {
  import InoxConvertible._
  import legacy.ImplicitTuples._
  import legacy.Implicits._


  def add2and3(in: Id[(Int, Int)]) = {
    in._1 + in._2 + in._1
  }

  test("IntPlusReverse") {
    val d = Make(add2and3)
    val init = (1, 3)
    d.get(init) shouldEqual 5
    var o = variable[Int]("o")
    var i = variable[(Int, Int)]("i")
    val constraint: Constraint[(Int, Int)] = d.put(o, i, Some(init))
    //println(constraint)
    //println(constraint.simplify(varI))
    //println(varI)
    def sort[A](s: Stream[A], num: Int) = s.take(num).sortBy(res => Distances.distance(res, init)) #::: s.drop(num)

    sort(constraint(o -> 3).toStream(i), 3).take(2).toSet shouldEqual Set((1, 1), (0, 3))
    constraint(o -> 4).toStream(i).head shouldEqual ((1, 2))
    constraint(o -> 6).toStream(i).head shouldEqual ((1, 4))
    sort(constraint(o -> 7).toStream(i), 3).take(2).toSet shouldEqual Set((1, 5), (2, 3))
    constraint(o -> 8).toStream(i).head shouldEqual ((1, 6))
    sort(constraint(o -> 9).toStream(i), 3).take(2).toSet shouldEqual Set((1, 7), (3, 3))
  }

  def add3(in: Id[(Int, Int, Int)]) = {
    in._1 + in._2 + in._3
  }

  test("(2+3)+1 = 6, 5, 4, 7 repair") {
    val d = Make(add3)
    val init = (2, 3, 1)
    var o = variable[Int]("o")
    var i = variable[(Int, Int, Int)]("i")
    d.get(init) shouldEqual 6
    //println(d.put(o, i, Some(init)).simplify(i))
    def fRev(k: Int) = d.put(o, i, Some(init))(o -> k).toStream(i).take(3).toList
    def sort[A](s: Stream[A], num: Int) = s.take(num).sortBy(res => Distances.distance(res, init)) #::: s.drop(num)
    fRev(6) shouldEqual List((2,3,1))
    fRev(5) shouldEqual List((2,3,0), (1,3,1), (2,2,1))
    fRev(4) shouldEqual List((2,3,-1), (0,3,1), (2,1,1))
    fRev(7) shouldEqual List((2,3,2), (3,3,1), (2,4,1))
  }
}
/*
class ListAppendTest extends FunSuite {
  def doubleAppend(in: (List[Int], List[Int])): List[Int] = {
    in._1 ++ in._2
  }
  import Implicits._
  def doubleAppend2(in: Id[(List[Int], List[Int])]) = {
    in._1 ++ in._2
  }
  
  test("Double decomposition") {
    doubleAppend((List(1, 2), List(3, 4, 5))) shouldEqual List(1, 2, 3, 4, 5)
    val d = doubleAppend2(Id())
    d.get((List(1, 2), List(3, 4, 6))) shouldEqual List(1, 2, 3, 4, 6)
    d.put(List(1, 2, 3, 4, 5), (List(1, 2), List(4, 5))).take(2).toList shouldEqual (List((List(1, 2), List(3, 4, 5)), (List(1, 2, 3), List(4, 5))))
    d.put(List(1, 2, 4, 5, 3), (List(1, 2), List(4, 5))).take(1).toList shouldEqual (List((List(1, 2), List(4, 5, 3))))
  }
  
  def tripleAppend2(in: Id[(List[Int], List[Int], List[Int])]) = {
    in._1 ++ in._2 ++ in._3
  }
  
  test("Triple decomposition") {
    val t = tripleAppend2(Id())

    def tRev(i: List[Int]) = t.put(i, Option((List(1, 2), List(), List(4)))).toList
    tRev(List(1, 2, 4)) shouldEqual List((List(1, 2), List(), List(4)))
    tRev(List(1, 2, 3, 4)).take(3) shouldEqual List((List(1, 2), List(), List(3, 4)), (List(1, 2), List(3), List(4)), (List(1, 2, 3), List(), List(4)))
    tRev(List(3, 1, 2, 4)).take(1) shouldEqual List((List(3, 1, 2), List(), List(4)))
  }
  
  test("Use of a variable twice") {
    def doubleAppend2(in: Id[(List[Int], List[Int])]) = {
      in._1 ++ in._2 ++ in._1
    }
    val d = doubleAppend2(Id())
    d.get((List(1, 3), List(2))) shouldEqual List(1, 3, 2, 1, 3)
    d.put(List(1, 3, 2, 1, 3), Some((List(1, 3), List(2)))).toList shouldEqual List((List(1, 3), List(2)))
    d.put(List(1, 4, 2, 1, 3), Some((List(1, 3), List(2)))).take(1).toList shouldEqual List((List(1, 4), List(2)))
    d.put(List(1, 3, 3, 1, 3), Some((List(1, 3), List(2)))) should contain ((List(1, 3), List(3)))
    d.put(List(1, 3, 4, 5, 1, 3), Some((List(1, 3), List(2)))) should contain ((List(1, 3), List(4, 5)))
    val r = d.put(List(1, 3, 1, 3, 1, 3, 1, 3, 1), Some((List(1, 3, 1), List(3))))
    r should contain (List(1, 3, 1, 3, 1), List(3))
    r should contain (List(1, 3, 1), List(3, 1, 3))
    d.put(List(2, 2, 2), Some(List(1), List(2))) should contain((List(2), List(2)))
    d.put(List(3, 2, 2, 3, 2), Some(List(1), List(2))) should contain((List(3, 2), List(2)))
  }
}*/

/*

*/

class StringFormatReverseTest extends FunSuite  {
  import legacy.Implicits._
  import legacy.ImplicitTuples._

  def format(in: Id[(String, List[Either[String, Int]])]) = {
    in._1.format(in._2)
  }
  
  test("Formatting reverse decomposition") {
    val f = Make(format)
    object CList {
      def apply(i: Any*): List[Either[String, Int]] = listAnyToListEither(i.toList)

      def listAnyToListEither(l: List[Any]): List[Either[String, Int]] = l match {
        case Nil => Nil
        case (s: String):: tail => Left(s) :: listAnyToListEither(tail)
        case (i: Int)::tail => Right(i) :: listAnyToListEither(tail)
        case _ => throw new Exception(s"$l is not a list of String | Int")
      }
    }
    def formatRev(s: String, args: List[Either[String, Int]], output: String) =
      f.put(output, Some((s, args)))
    formatRev("%s %s %d", CList("Hello", "world", 42), "Hello buddy 42") shouldEqual List(("%s %s %d", CList("Hello", "buddy", 42)))
    StringFormatReverse.formatRev("%s,%s %s!", CList("Hello", "obscure", "world"), "Hello, obscure world!") should contain (("%s, %s %s!", CList("Hello", "obscure", "world")))
    formatRev("%s,%s %s!", CList("Hello", "obscure", "world"), "Hello, obscure world!") should contain (("%s, %s %s!", CList("Hello", "obscure", "world")))
    formatRev("%s,%s %s!", CList("Hello", "obscure", "world"), "Hello,clear world!") should contain (("%s,%s %s!", CList("Hello", "clear", "world")))
    formatRev("%s,%s %s!", CList("Hello", "obscure", "world"), "Good bye,awesome friend!") should contain (("%s,%s %s!", CList("Good bye", "awesome", "friend")))
    formatRev("%2$s,%3$s %1$s!", CList("world", "Hello", "obscure"), "Hello,clear world!") should contain (("%2$s,%3$s %1$s!", CList("world", "Hello", "clear")))
    formatRev("Hello %1$s! %1$s is ok?", CList("Marion"), "Hello Mikael! Marion is ok?") should contain(("Hello %1$s! %1$s is ok?", CList("Mikael")))
    formatRev("Hello %1$s! %1$s is ok?", CList("Marion"), "Hello Mikael! Marion is ok?") should not contain (("Hello %1$s! %1$s is ok?", CList("Marion")))
  }
}


class RegexReplaceAllInReverseTest extends FunSuite  {
  import legacy.Implicits._
  
  val f1 = new (List[String] %~> String) {
    val methodName = "fst"
    def get(in: List[String]) = in(0)
    def putManual(out: String, in: Option[List[String]]): Iterable[List[String]] = in match {
      case None => List(List(out))
      case Some(in) => List(out +: in.tail)
    }
    
  }
  val f2 = new (List[String] %~> String) {
    val methodName = "snd"
    def get(in: List[String]) = in(1)
    def putManual(out: String, in: Option[List[String]]): Iterable[List[String]] = in match {
      case None => List(List(out, out))
      case Some(in) => List(in.take(1) ++ List(out) ++ in.drop(2))
    }
  }
  val sentence = "My name is Mikaël[me kah el], my last name is Mayer[mah ee er]"
  val regex = """(?<=\s|^)(\p{L}+)\[([^\]]*)\]""".r
  def matcherToSubgroups = (m: scala.util.matching.Regex.Match) => m.subgroups
  def replacerDisplay0(in: String): String =
    regex.replaceAllIn(in, matcherToSubgroups andThen f1.get)
  def replacerSpeech0(in: String): String =
    regex.replaceAllIn(in, matcherToSubgroups andThen f2.get)
    
  def replacerDisplay(in: Id[String] = Id()): (String ~~> String) =
    regex.replaceAllIn(in, f1)
  def replacerSpeech(in: Id[String] = Id()): (String ~~> String) =
    regex.replaceAllIn(in, f2)
  
  test("Formatting reverse decomposition") {
    val display = replacerDisplay().get(sentence)
    val speech = replacerSpeech().get(sentence)
    
    display shouldEqual "My name is Mikaël, my last name is Mayer"
    speech shouldEqual "My name is me kah el, my last name is mah ee er"
    
    def displayRev(s: String) = replacerDisplay().put(s, Some(sentence))
    def speechRev(s: String) = replacerSpeech().put(s, Some(sentence))
    //Constants (adding " and ")
    displayRev("My name is Mikaël, and my last name is Mayer").toList.take(1) shouldEqual List("My name is Mikaël[me kah el], and my last name is Mayer[mah ee er]")
    speechRev("My name is me kah el, and my last name is mah ee er").toList.take(1) shouldEqual List("My name is Mikaël[me kah el], and my last name is Mayer[mah ee er]")

    // Replaced values (replacing Mikael => Marion and me kah el to mi ka el)
    displayRev("My name is Marion, my last name is Cichella Mayer").toList.distinct shouldEqual List("My name is Marion[me kah el], my last name is Cichella Mayer[mah ee er]")
    speechRev("My name is mi ka el, my last name is mah ee er").toList.distinct shouldEqual List("My name is Mikaël[mi ka el], my last name is Mayer[mah ee er]")
    
    // Ambiguity
    displayRev("My name is Mikaël of course, my last name is Mayer").toList.take(2) shouldEqual List("My name is Mikaël[me kah el] of course, my last name is Mayer[mah ee er]", "My name is Mikaël of course[me kah el], my last name is Mayer[mah ee er]")
    speechRev("My name is me kah el of course, my last name is mah ee er").toList.take(2) shouldEqual List("My name is Mikaël[me kah el] of course, my last name is Mayer[mah ee er]", "My name is Mikaël[me kah el of course], my last name is Mayer[mah ee er]")
  }
}



class ListSplitTest extends FunSuite  {
  val f = (x: Int) => x % 2 == 0
  val b = ListSplit[Int](f)
  import b._
  test("Testing split reverse") {
    splitRev(List(3, 5), (List(3, 5), List(4))) shouldEqual List(List(4, 3, 5), List(3, 4, 5), List(3, 5, 4))
    splitRev(List(3, 4, 5), (List(3, 5), List())) shouldEqual List(List(3, 5))
    splitRev(List(1, 2, 3, 4, 5), (List(1, 3, 5), List(2, 6, 4))) shouldEqual List(List(1, 2, 6, 3, 4, 5), List(1, 2, 3, 6, 4, 5))
  }
}


import WebTrees._

class TypeSplitTest extends FunSuite  {
  import TypeSplit._
  
  test("Recovering split based on type") {
    val initTest = List[WebTree](WebStyle("width", "100px"), Element("pre"), WebStyle("height", "100px"), WebAttribute("src", "http"))
    
    val sinit = split(initTest)

    splitRev(initTest, (sinit._1, sinit._2, sinit._3(0) :: WebStyle("outline", "bold") :: sinit._3(1) :: Nil)) shouldEqual
    List(List(WebStyle("width","100px"), WebElement(Element("pre")), WebStyle("outline","bold"), WebStyle("height","100px"), WebAttribute("src","http")))
    splitRev(initTest, (sinit._1, sinit._2, sinit._3(0) :: Nil)) shouldEqual
    List(List(WebStyle("width","100px"), WebElement(Element("pre")), WebAttribute("src","http")))
  }
}

class WebElementAdditionTest extends FunSuite  {
  import TypeSplit._
  import WebElementAddition._
  val initArg1 = Element("div", Nil, Nil, List(WebStyle("display", "none")))
  val initArg2: (List[WebElement], List[WebAttribute], List[WebStyle]) =
    (List(Element("pre")), List(WebAttribute("src", "http")), List(WebStyle("width", "100px"), WebStyle("height", "100px")))
  val sinit = WebElementAddition(initArg1, initArg2._1, initArg2._2, initArg2._3)
  
  // No modification
  val reverseInit = WebElementAddition.applyRev((Element("div", Nil, Nil, List(WebStyle("display", "none"))), (List(Element("pre")), List(WebAttribute("src", "http")), List(WebStyle("width", "100px"), WebStyle("height", "100px")))),
    Element("div",List(Element("pre",List(),List(),List())),List(WebAttribute("src","http")),List(WebStyle("display","none"), WebStyle("width","100px"), WebStyle("height","100px")))
  )
  reverseInit.toList shouldEqual List((initArg1, initArg2))
  
  // Last element changed
  val reverseInit2: List[(Element, (List[WebElement], List[WebAttribute], List[WebStyle]))] = WebElementAddition.applyRev((Element("div", Nil, Nil, List(WebStyle("display", "none"))), (List(Element("pre")), List(WebAttribute("src", "http")), List(WebStyle("width", "100px"), WebStyle("height", "100px")))),
    Element("div",List(Element("pre",List(),List(),List())),List(WebAttribute("src","http")),List(WebStyle("display","none"), WebStyle("width","100px"), WebStyle("height","200px")))
  )
  reverseInit2.toList shouldEqual List((Element("div",List(),List(),List(WebStyle("display","none"))),
    (List(WebElement(Element("pre",List(),List(),List()))),List(WebAttribute("src","http")),List(WebStyle("width","100px"), WebStyle("height","200px")))))
  
  // Added a child at the beginning
  val reverseInit3 = WebElementAddition.applyRev((Element("div", Nil, Nil, List(WebStyle("display", "none"))), (List(Element("pre")), List(WebAttribute("src", "http")), List(WebStyle("width", "100px"), WebStyle("height", "100px")))),
    Element("div",List(Element("pre",List(),List(),List()), Element("span",List(),List(),List())),List(WebAttribute("src","http")),List(WebStyle("display","none"), WebStyle("width","100px"), WebStyle("height","200px")))
  )
  reverseInit3.toList shouldEqual List((Element("div",List(),List(),List(WebStyle("display","none"))),
    (List(WebElement(Element("pre",List(),List(),List())), WebElement(Element("span",List(),List(),List()))),List(WebAttribute("src","http")),List(WebStyle("width","100px"), WebStyle("height","200px")))))
  
  // Changed the display
  val reverseInit4 = WebElementAddition.applyRev((Element("div", Nil, Nil, List(WebStyle("display", "none"))), (List(Element("pre")), List(WebAttribute("src", "http")), List(WebStyle("width", "100px"), WebStyle("height", "100px")))),
    Element("div",List(WebElement(Element("pre",List(),List(),List()))),List(WebAttribute("src","http")),List(WebStyle("display","block"), WebStyle("width","100px"), WebStyle("height","100px")))
  )
  reverseInit4.toList shouldEqual List((Element("div",List(),List(),List(WebStyle("display","block"))),
    (List(WebElement(Element("pre",List(),List(),List()))), List(WebAttribute("src","http")),List(WebStyle("width","100px"), WebStyle("height","100px")))))
  
  // Added a style before the original style
  val reverseInit5 = WebElementAddition.applyRev((Element("div", Nil, Nil, List(WebStyle("display", "none"))), (List(Element("pre")), List(WebAttribute("src", "http")), List(WebStyle("width", "100px"), WebStyle("height", "100px")))),
    Element("div",List(Element("pre",List(),List(),List())),List(WebAttribute("src","http")),List(WebStyle("outline","1px solid black"), WebStyle("display","none"), WebStyle("width","100px"), WebStyle("height","100px")))
  )
  reverseInit5.toList shouldEqual List((Element("div",List(),List(),List(WebStyle("outline","1px solid black"), WebStyle("display","none"))),
    (List(WebElement(Element("pre",List(),List(),List()))), List(WebAttribute("src","http")),List(WebStyle("width","100px"), WebStyle("height","100px")))))
}

class WebElementCompositionTest extends FunSuite  {
  import TypeSplit._
  import WebElementComposition._
  test("should compose and decompose elements correctly") {
    val initArg1 = Element("div", Nil, Nil, List(WebStyle("display", "none")))
    val initArg2: List[WebTree] = List(WebStyle("width", "100px"), Element("pre"), WebStyle("height", "100px"), WebAttribute("src", "http"))
    val in = (initArg1, initArg2)
    val sinit = WebElementComposition.get(in)
    // Verification that the function computes correctly
    sinit shouldEqual Element("div", List(Element("pre")), List(WebAttribute("src", "http")), List(WebStyle("display", "none"), WebStyle("width", "100px"), WebStyle("height", "100px")))
    // Replacing an element in the argument's list.
    WebElementComposition.put(Element("div", List(Element("span")), List(WebAttribute("src", "http")), List(WebStyle("display", "none"), WebStyle("width", "100px"), WebStyle("height", "100px"))), in).toList shouldEqual List((initArg1, initArg2.updated(1, WebElement(Element("span")))))
    // Adding an element at the end of the argument's list.
    WebElementComposition.put(Element("div", List(Element("pre"), Element("span")), List(WebAttribute("src", "http")), List(WebStyle("display", "none"), WebStyle("width", "100px"), WebStyle("height", "100px"))), in).toList shouldEqual List((initArg1, List(WebStyle("width", "100px"), WebElement(Element("pre")), WebStyle("height", "100px"), WebAttribute("src", "http"), WebElement(Element("span")))))
    // Adding an element before one of the argument's list.
    val r = WebElementComposition.put(Element("div", List(Element("span"), Element("pre")), List(WebAttribute("src", "http")), List(WebStyle("display", "none"), WebStyle("width", "100px"), WebStyle("height", "100px"))), in).toList 
    r should contain ((initArg1, List(WebStyle("width", "100px"), WebElement(Element("span")), WebElement(Element("pre")), WebStyle("height", "100px"), WebAttribute("src", "http"))))
    r should have size 2
    // Replacing the main element
    WebElementComposition.put(Element("div", List(Element("pre")), List(WebAttribute("src", "http")), List(WebStyle("display", "block"), WebStyle("width", "100px"), WebStyle("height", "100px"))), in).toList shouldEqual List((initArg1.copy(styles=List(WebStyle("display", "block"))), initArg2))
  }
}

class ComposeTest extends FunSuite  {
  
  object F extends (Int %~> Int) {
    val methodName = "function_f"
    def get(x: Int) = x - (x % 2)
    def putManual(x: Int, in: Option[Int]) = if(x % 2 == 0) List(x, x+1) else Nil
  }

  object G extends (Int %~> Int)  {
    val methodName = "function_g"
    def get(x: Int) = x - (x % 3)
    def putManual(x: Int, in: Option[Int]) = if(x % 3 == 0) List(x, x+1, x+2) else Nil
  }

  val b = Compose(G, F)

  def composeRev(i: Int) = b.put(i, 0).toList

  test("Reverse composing of functions") {
    val test1 = composeRev(0) shouldEqual List(0, 1, 2, 3)
    val test2 = composeRev(3) shouldEqual List(4, 5)
    val test3 = composeRev(6) shouldEqual List(6, 7, 8, 9)
  }
}

class FlattenTest extends FunSuite  {
  val c = new Flatten[Int]
  import c._

  test("Reversing Flatten") {
    flattenRev(List(List(1, 2, 3), List(5, 6), List(), List(7)), List(1, 2, 3, 5, 6, 7)) should contain 
      List(List(1, 2, 3), List(5, 6), List(), List(7))
    val test2 = flattenRev(List(List(1, 2, 3), List(5, 6), List(), List(7)), List(1, 2, 5, 6, 7)) should contain  
      List(List(1, 2), List(5, 6), List(), List(7))
    val test3 = flattenRev(List(List(1, 2, 3), List(5, 6), List(), List(7)), List(1, 3, 5, 6, 7)) should 
    contain (List(List(1, 3), List(5, 6), List(), List(7))) //'
    val test4 = flattenRev(List(List(1, 2, 3), List(5, 6), List(), List(7)), List(1, 2, 3, 4, 5, 6, 7)) should contain  
      List(List(1, 2, 3), List(4, 5, 6), List(), List(7))
    val test5 = flattenRev(List(List(1, 2, 3), List(5, 6), List(), List(7)), List(1, 2, 4, 3, 5, 6, 7)) should contain  
      List(List(1, 2, 4, 3), List(5, 6), List(), List(7))
    val test6 = flattenRev(List(List(1, 2, 3), List(5, 6), List(), List(7)), List(1, 2, 3, 5, 6, 4, 7)) should contain  
      List(List(1, 2, 3), List(5, 6), List(), List(4, 7))
    val test7 = flattenRev(List(List(1, 2, 3), List(5, 6), List(), List(7)), List(1, 2, 3, 5, 6, 7, 4)) should contain  
      List(List(1, 2, 3), List(5, 6), List(), List(7, 4))
  }
}

class MapReverseTest extends FunSuite  {
  object f extends %~>[Int, Int] {
    def get(x: Int) = x - (x%2)
    val methodName = "f"
    def putManual(y: Int, in: Option[Int]) =
      if(y % 2 == 0) {
        in match {
          case Some(x) if get(x) == y => List(x)
          case _ =>
           List(y, y+1)
        }
      } else Nil
  }
  val c = MapReverse[Int, Int](f)
  import c._
  test("Reversing map") {
    put(List(0, 2, 2, 4, 4), List(1, 2, 3, 4, 5)) shouldEqual
    List(List(1, 2, 3, 4, 5))
    put(List(0, 4, 2, 4, 4), List(1, 2, 3, 4, 5)) shouldEqual
    List(List(1, 4, 3, 4, 5), List(1, 5, 3, 4, 5))
    put(List(0, 2, 4, 4), List(1, 2, 3, 4, 5)) should contain
    (List(1, 2, 4, 5)) //'
    put(List(0, 2, 2, 2, 4, 4), List(1, 2, 3, 4, 5)) shouldEqual
    List(List(1, 2, 3, 2, 4, 5), List(1, 2, 3, 3, 4, 5))
  }
}

class FilterReverseTest extends FunSuite  {
  val isEven = (x: Int) => x % 2 == 0
  val c = FilterReverse(isEven)
  test("Filter reverse") {
    import c.put
    put(List(2, 4, 8), List(1, 2, 3, 4, 8, 5)) shouldEqual
    List(List(1, 2, 3, 4, 8, 5))
    put(List(2, 8), List(1, 2, 3, 4, 8, 5)) shouldEqual
    List(List(1, 2, 3, 8, 5))
    put(List(4, 8), List(1, 2, 3, 4, 8, 5)) shouldEqual
    List(List(1, 3, 4, 8, 5))
    put(List(2, 4, 6, 8), List(1, 2, 3, 4, 8, 5)) shouldEqual
    List(List(1, 2, 3, 4, 6, 8, 5))
    put(List(2, 6, 4, 8), List(1, 2, 3, 4, 8, 5)) shouldEqual
    List(List(1, 2, 3, 6, 4, 8, 5))
  }
  
  test("Filter reverse with strings") {
    val d = FilterReverse((s: String) => s.startsWith("S"))
    import d.put
    put(List("Salami", "Sudjuk"), List("Margharita", "Sudjuk")).toList should contain (List("Margharita", "Salami", "Sudjuk"))
  }
}

// Combines map and flatten directly.
class FlatMapByComposeTest extends FunSuite  {
  object f extends %~>[Int, List[Int]] {
    def get(x: Int) = if(x % 4 == 0) List(x, x+1, x+2) else if(x % 4 == 2) List(x+1, x+2) else if(x % 4 == 1) List(x-1, x) else List(x-1, x, x+1)
    val methodName = "f"
    def putManual(lx: List[Int], in: Option[Int]) = if(lx.length == 3 && lx(1) == lx(0) + 1 && lx(2) == lx(1) + 1) {
      if(lx(0) % 4 == 0) List(lx(0))
      else if(lx(0) % 4 == 2) List(lx(1))
      else Nil
    } else if(lx.length == 2 && lx(1) == lx(0) + 1) {
      if(lx(0) % 4 == 3) List(lx(0)-1)
      else if(lx(0) % 4 == 0) List(lx(1))
      else Nil
    } else Nil
  }

  val c = MapReverse(f) andThen Flatten[Int]()
  
  // f(0) ++ f(2) == f(1) ++ f(3)
  // f(0) == [0, 1, 2]
  // f(1) == [0, 1]
  // f(2) == [3, 4]
  // f(3) == [2, 3, 4]
  
  object fEven extends %~>[Int, List[Int]] {
    def get(x: Int) = if(x%2 == 0) List(x/2) else Nil
    val methodName = "fEven"
    def putManual(x: List[Int], in: Option[Int]) = if(x.length == 1) List(x(0)*2) else if(x.length == 0 && in.nonEmpty) List(in.get) else Nil
  }
 
  val d = MapReverse(fEven) andThen Flatten[Int]()

  test("Reverse flatmap - complicated") {
    import c.put
    Flatten[Int]().put(List(0, 1, 2, 3, 4), Some(Nil)) should contain (List(List(0, 1, 2), List(3, 4)))
    put(List(0, 1, 2, 3, 4), Nil) shouldEqual List(List(1, 3), List(0, 2))
    put(List(0, 1, 2, 3, 4), List(1, 3)) should contain(List(1, 3))
    put(List(0, 1, 2, 3, 4), List(0, 2)) should contain(List(0, 2))
    put(List(0, 1, 2, 3, 4, 0, 1), List(0, 2)) should contain(List(0, 2, 1)) // Addition at the end
    put(List(0, 1, 2, 0, 1, 3, 4), List(0, 2)) should contain(List(0, 1, 2)) // Addition in the middle
    put(List(0, 1, 0, 1, 2, 3, 4), List(0, 2)) should contain(List(1, 0, 2)) // Addition at the beg.
    put(List(3, 4), List(0, 2)) should contain(List(2)) // Deletion of beginning
    put(List(0, 1, 2), List(0, 2)) should contain(List(0)) // Deletion of end
    put(List(2, 3, 4), List(2)) should contain(List(3)) // Change
    put(List(0, 1, 2, 0, 1, 2, 3, 4), List(0, 1, 2)) should contain(List(0, 1, 3)) // Change
    put(List(0, 1, 0, 1, 2, 3, 4), List(1, 3)) should contain(List(1, 1, 3)) // Addition at beg.
  }
  test("Reverse flatmap - even") {
    import d.put

    // Keep elements producing empty lists
    put(List(1, 2), List(1, 2, 3, 4, 5)) should contain(List(1, 2, 3, 4, 5))
    put(List(1, 3, 2), List(1, 2, 3, 4, 5)) should contain(List(1, 2, 3, 6, 4, 5))
    put(List(1, 2), List(1, 2, 3, 6, 4, 5)) should contain(List(1, 2, 3, 4, 5))
  }
}

// Combines map and flatten directly.
class FlatMapTest extends FunSuite  {
  object f extends %~>[Int, List[Int]] {
    val methodName = "f"
    def get(x: Int) = if(x % 4 == 0) List(x, x+1, x+2) else if(x % 4 == 2) List(x+1, x+2) else if(x % 4 == 1) List(x-1, x) else List(x-1, x, x+1)
    def putManual(lx: List[Int], in: Option[Int]) = if(lx.length == 3 && lx(1) == lx(0) + 1 && lx(2) == lx(1) + 1) {
      if(lx(0) % 4 == 0) List(lx(0))
      else if(lx(0) % 4 == 2) List(lx(1))
      else Nil
    } else if(lx.length == 2 && lx(1) == lx(0) + 1) {
      if(lx(0) % 4 == 3) List(lx(0)-1)
      else if(lx(0) % 4 == 0) List(lx(1))
      else Nil
    } else Nil
  }

  val c = FlatMap(f)
  
  // f(0) ++ f(2) == f(1) ++ f(3)
  // f(0) == [0, 1, 2]
  // f(1) == [0, 1]
  // f(2) == [3, 4]
  // f(3) == [2, 3, 4]
  
  object fEven extends %~>[Int, List[Int]] {
    val methodName = "fEven"
     def get(x: Int) = if(x%2 == 0) List(x/2) else Nil
     def putManual(x: List[Int], in: Option[Int]) = if(x.length == 1) List(x(0)*2) else Nil
  }
 
  val d = FlatMap(fEven)

  test("Reverse flatmap - complicated") {
    import c.put
    put(List(0, 1, 2, 3, 4), Nil) shouldEqual List(List(1, 3), List(0, 2))
    put(List(0, 1, 2, 3, 4), List(1, 3)) shouldEqual List(List(1, 3))
    put(List(0, 1, 2, 3, 4), List(0, 2)) shouldEqual List(List(0, 2))
    put(List(0, 1, 2, 3, 4, 0, 1), List(0, 2)) shouldEqual List(List(0, 2, 1)) // Addition at the end
    put(List(0, 1, 2, 0, 1, 3, 4), List(0, 2)) shouldEqual List(List(0, 1, 2)) // Addition in the middle
    put(List(0, 1, 0, 1, 2, 3, 4), List(0, 2)) shouldEqual List(List(1, 0, 2)) // Addition at the beg.
    put(List(3, 4), List(0, 2)) shouldEqual List(List(2)) // Deletion of beginning
    put(List(0, 1, 2), List(0, 2)) shouldEqual List(List(0)) // Deletion of end
    put(List(2, 3, 4), List(2)) shouldEqual List(List(3)) // Change
    put(List(0, 1, 2, 0, 1, 2, 3, 4), List(0, 1, 2)) shouldEqual List(List(0, 1, 3)) // Change
    put(List(0, 1, 0, 1, 2, 3, 4), List(1, 3)) shouldEqual List(List(1, 1, 3)) // Addition at beg.
  }
  test("Reverse flatmap - even") {
    import d.put
    // Keep elements producing empty lists
    put(List(1, 2), List(1, 2, 3, 4, 5)) shouldEqual List(List(1, 2, 3, 4, 5))
    put(List(1, 3, 2), List(1, 2, 3, 4, 5)) shouldEqual List(List(1, 2, 3, 6, 4, 5))
    put(List(1, 2), List(1, 2, 3, 6, 4, 5)) shouldEqual List(List(1, 2, 3, 4, 5))
  }
}

class PizzaTest extends FunSuite {
   import legacy.WebBuilder._
   import legacy.Implicits.{/* RemoveUnit => _ ,*/ _}
   /*object TN extends (String ~~> List[WebTree]) {
     def get(s: String) = List(TextNode(s))
     def put(out: List[WebTree], s: Option[String]): Iterable[String] = report(s"TN.put($s, $out) = %s"){
       out match {
       case List(TextNode(i)) => List(i)
       case _ => Nil
     }
   }
   }
   val pizzasCreator: (List[String] ~~> Element) =
     (<.ul(
       MapReverse(<.li(TN)): (List[String] ~~> List[Element])
     ): ((Unit, List[String]) ~~> Element))
   test("It can creat pizzas forms") {
     pizzasCreator.get(List("Margharita", "Sudjuk")).toString shouldEqual
      """<.ul(<.li("Margharita"), <.li("Sudjuk"))"""
   }
   test("It can find pizzas from an updated list of pizzas") {
     val expectedResult = Element("ul", Element("li", TextNode("Margharita")::Nil)::Element("li", TextNode("Salami")::Nil)::Element("li", TextNode("Sudjuk")::Nil)::Nil)
     val original = List("Margharita", "Sudjuk")
     pizzasCreator.put(expectedResult, Some(original)) should contain (
     List("Margharita", "Salami", "Sudjuk"))
   }
   val pizzasCreator2: (List[String] ~~> Element) =
     FilterReverse((s: String) => s.startsWith("S")) andThen (<.ul(
       MapReverse(<.li(TN)): (List[String] ~~> List[Element])
     ): ((Unit, List[String]) ~~> Element))
   test("it can deal with filters") {
     val expectedResult = Element("ul", Element("li", TextNode("Salami")::Nil)::Element("li", TextNode("Sudjuk")::Nil)::Nil)
     val original = List("Margharita", "Sudjuk")
     pizzasCreator2.put(expectedResult, Some(original)) should contain (
     List("Margharita", "Salami", "Sudjuk"))
   }
   */
   val ul = Element("ul")
   val li = Element("li")

   def pizzasCreator3Raw(l: List[String]) = {
     val filtered = l.filter((s: String)=> s.startsWith("S"))
     val mapped = filtered.map((x: String) => li(List(TextNode(x))))
     val result = ul(mapped)
     result
   }
   
   def pizzasCreator3(l: Id[List[String]]) = {
     val filtered = l.filter((s: String)=> s.startsWith("S"))
     val mapped = filtered.map((x: Id[String]) => li(List(CastUp[String, TextNode, InnerWebElement](TextNode(x)))))
     val result = ul(mapped)
     result
   }
   test("it can use standard methods") {
     val expectedResult = Element("ul")(Element("li")(TextNode("Salami")::Nil)::Element("li")(TextNode("Sudjuk")::Nil)::Nil)
     val original = List("Margharita", "Sudjuk")
     pizzasCreator3Raw(original) shouldEqual Element("ul")(Element("li")(TextNode("Sudjuk")::Nil)::Nil)
     pizzasCreator3(Id()).get(original) shouldEqual Element("ul")(Element("li")(TextNode("Sudjuk")::Nil)::Nil)
     pizzasCreator3(Id()).put(expectedResult, Some(original)) should contain (
     List("Margharita", "Salami", "Sudjuk"))
   }
}
/*
/**
This test focuses on the task of updating the result containing the duplication of an object.
It tests that the object which was not there before is given the priority to represent all the changes.
*/
class DuplicateTest extends FunSuite {
   import WebBuilder._
   import Implicits.{RemoveUnit => _, _}
   
   def duplicate(d: WebElement) = 
     Element("div", d::d::Nil)
   def duplicate2(d: Id[WebElement]) =
     Element("div", d::d::Nil)
     
   test("it can duplicate and un-duplicate") {
     duplicate(Element("pre")) shouldEqual Element("div", Element("pre")::Element("pre")::Nil)
     duplicate2(Id()).get(Element("pre")) shouldEqual Element("div", Element("pre")::Element("pre")::Nil)
     duplicate2(Id()).put(Element("div", Element("span")::Element("span")::Nil)) shouldEqual List(Element("span"))
   }
   
   test("it can un-duplicate partially") {
     duplicate2(Id()).put(Element("div", Element("span")::Element("pre")::Nil), Some(Element("span"))).toList shouldEqual (List(Element("pre")))
     duplicate2(Id()).put(Element("div", Element("span")::Element("pre")::Nil), Some(Element("pre"))).toList shouldEqual (List(Element("span")))
     duplicate2(Id()).put(Element("div", Element("br")::Element("pre")::Nil), Some(Element("span"))).toList shouldEqual (List(Element("br"), Element("pre")))
     //duplicate2(Id()).put(Element("div", Element("br")::Element("pre")::Element("span")::Nil), Some(Element("span"))).toList shouldEqual (List(Element("br"), Element("pre")))
   }
}

class ReproduceTest extends FunSuite {
   import WebBuilder._
   import Implicits.{RemoveUnit => _, _}
   
   def reproduce(d: (Int, WebElement)) = 
     Element("div", List.fill(d._1)(d._2))
   def reproduce2(d: Id[(Int, WebElement)]): ((Int, WebElement) ~~> Element) =
     Element("div", Listfill(d))
     
   test("it can reproduce and un-reproduce") {
     reproduce((3, Element("pre"))) shouldEqual Element("div", Element("pre")::Element("pre")::Element("pre")::Nil)
     reproduce2(Id()).get((3, Element("pre"))) shouldEqual Element("div", Element("pre")::Element("pre")::Element("pre")::Nil)
     reproduce2(Id()).put(Element("div", Element("span")::Element("span")::Nil)).toList shouldEqual (List((2, Element("span"))))
   }
   
   test("it can un-reproduce partially") {
     reproduce2(Id()).put(Element("div", Element("span")::Element("pre")::Nil)).toList shouldEqual (List((2, Element("span")), (2, Element("pre"))))
     reproduce2(Id()).put(Element("div", Element("span")::Element("pre")::Nil), (2, Element("pre"))).toList shouldEqual
       (List((2, Element("span"))))
     reproduce2(Id()).put(Element("div", Element("span")::Element("pre")::Nil), (3, Element("pre"))).toList shouldEqual
       (List((2, Element("span"))))
     reproduce2(Id()).put(Element("div", Element("pre")::Element("pre")::Element("span")::Nil), (2, Element("pre"))).toList shouldEqual
       (List((3, Element("span"))))
   }
}

class StringLengthTest extends FunSuite {
  import Implicits._
  test("it can extend or reduce a string") {
    def action(d: String) = {
      d.length
    }
    def action1[A](d: A ~~> String) = {
      d.length
    }
    action("abcde") shouldEqual 5
    val ac = action1(Id())
    ac.get("abcde") shouldEqual 5
    ac.put(5, Some("abcde")).toList shouldEqual List("abcde")
    ac.put(4, Some("abcde")).toList shouldEqual List("abcd")
    ac.put(6, Some("abcde")).toList shouldEqual List("abcdea")
    ac.put(11, Some("abcde")).toList shouldEqual List("abcdeabcdea")
  }
}

class StringSubstringTest extends FunSuite {
  import Implicits._
  test("it can rebuild a string") {
    def action(d: (String, Int, Int)) = {
      d._1.substring(d._2, d._3)
    }
    def action1[A: TypeTag](d: A ~~> (String, Int, Int)) = {
      d._1.substring(d._2, d._3)
    }
    action(("abcde", 1, 3)) shouldEqual "bc"
    val ac = action1(Id())
    ac.get(("abcde", 1, 3)) shouldEqual "bc"
    ac.put("bc", Some(("abcde", 1, 3))).take(1).toList shouldEqual List(("abcde", 1, 3))
    ac.put("bck", Some(("abcde", 1, 3))).take(1).toList shouldEqual List(("abckde", 1, 4))
  }
}

class IndexOfSliceTest extends FunSuite {
  import Implicits._
  test("it can move strings") {
    def action(d: String) = {
      d.indexOfSlice("a b")
    }
    def action1[A](d: (A ~~> String)) = {
      d.indexOfSlice("a b")
    }
    val ac = action1(Id())
    action("This is a better string") shouldEqual 8
    ac.get("This is a better string") shouldEqual 8
    ac.put(7, Some("This is a better string")).head shouldEqual "This isa b etter string"
    ac.put(8, Some("This is a better string")).head shouldEqual "This is a better string"
    ac.put(9, Some("This is a better string")).head shouldEqual "This is ea btter string"
    ac.put(-1, Some("This is a better string")).head shouldEqual "This is etter string"
    ac.put(0, Some("No a and b")).head shouldEqual "a bNo a and b"
    ac.put(-1, Some("No a and b")).head shouldEqual "No a and b"
  }
  test("it can change the pattern") {
    def action(d: (String, String)) = {
      d._1.indexOfSlice(d._2)
    }
    def action1[A: TypeTag](d: (A ~~> (String, String))) = {
      d._1.indexOfSlice(d._2)
    }
    val ac = action1(Id())
    action(("This is a better string", "a b")) shouldEqual 8
    ac.get(("This is a better string", "a b")) shouldEqual 8
    ac.put(7, Some(("This is a better string", "a b"))).head shouldEqual ("This isa b etter string", "a b")
    ac.put(7, Some(("This is a better string", "a b"))) should contain("This is a better string", " a")
    ac.put(7, Some(("This is a better string", "a b"))) should not contain("This is a better string", " ")
    ac.put(8, Some(("This is a better string", "a b"))).head shouldEqual ("This is a better string", "a b")
    ac.put(9, Some(("This is a better string", "a b"))).head shouldEqual ("This is ea btter string", "a b")
    ac.put(9, Some(("This is a better string", "a b"))) should contain ("This is a better string", " b")
    ac.put(9, Some(("This is a better string", "a b"))) should not contain ("This is a better string", " ")
    ac.put(-1, Some(("This is a better string", "a b"))).head shouldEqual ("This is etter string", "a b")
    ac.put(0, Some(("No a and b", "a b"))).head shouldEqual ("a bNo a and b", "a b")
    ac.put(-1, Some(("No a and b", "a b"))).head shouldEqual ("No a and b", "a b")
    
  }
}
*/


class DistanceTest extends FunSuite {

  import Distances._

  case class Direct(a: Any, b: Option[Direct])

  test("should evaluate sizes correctly") {
    size("12345") shouldEqual 7
    size(12345) shouldEqual 5
    size(-12345) shouldEqual 6
    size(true) shouldEqual 4
    size(false) shouldEqual 5
    size(List(1, 2)) shouldEqual 10
    size(None) shouldEqual 4
    size(Direct("12345", None)) shouldEqual 21
    size(Direct(17, None)) shouldEqual 16
    size(Some(Direct(17, None))) shouldEqual 22
    size(Direct("12345", Some(Direct(17, None)))) shouldEqual 39
  }
  test("should evaluate edition distances correctly") {
    distance('1', '2') shouldEqual 2
    distance(true, false) shouldEqual 4
    distance("0", "12") shouldEqual 3
    distance(0, 12) shouldEqual 3
    distance(List(1, 2, 3), List(1, 3)) shouldEqual 1
    distance("", "1") shouldEqual 1
    distance("0", "01") shouldEqual 1
    distance("10", "0") shouldEqual 1
    distance(12345, 54321) shouldEqual 8
    distance(Direct(123, None), Direct(128, None)) shouldEqual 2
    distance(Direct(123, None), Direct(123, Some(Direct(1, None)))) shouldEqual 25 // Maybe we should keep the None when rewriting?
    distance("Hello ", "Hello a") shouldEqual 2
    distance("Hello ", "Hello  ") shouldEqual 1
    distance("world", "aworld") shouldEqual 1
    distance("world", " world") shouldEqual 2
    distance((1, 3), (1, 5)) shouldEqual 2
    distance((1, 3), (2, 3)) shouldEqual 2
    distance((1, 3), (3, 1)) shouldEqual 4
  }
}

/**
Future work: If cannot revert a function, abstract it using one more argument !

*/