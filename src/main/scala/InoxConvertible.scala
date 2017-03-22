/**
  * Created by Mikael on 22/03/2017.
  */
import ImplicitTuples._
import inox._
import inox.trees._
import inox.trees.dsl._
import inox.solvers._
import inox.InoxProgram

import scala.xml.MetaData

/** A type which can be converted to inox types, and whose expressions can be obtained from inox expressions */
trait InoxConvertible[A] { self =>
  type theType = A
  def getType: inox.trees.dsl.trees.Type
  def recoverFrom(e: Expr): A
  def produce(a: theType): Expr
}

/** Implicit helpers to build constrainable types.*/
object InoxConvertible {
  implicit object StringInoxConvertible extends InoxConvertible[String] {
    def getType = StringType
    def recoverFrom(e: Expr): String = e match {
      case StringLiteral(s) => s
      case _ => throw new Exception("Could not recover string from " + e)
    }
    def produce(a: String): inox.trees.Expr = E(a)
  }
  implicit object IntInoxConvertible extends InoxConvertible[Int] {
    def getType = Int32Type
    def recoverFrom(e: Expr): Int = e match {
      case IntLiteral(s) => s
      case _ => throw new Exception("Could not recover int from " + e)
    }
    def produce(a: Int): inox.trees.Expr = E(a)
  }

  private def c[A : InoxConvertible] = implicitly[InoxConvertible[A]]

  implicit def tuple2Constrainable[A : InoxConvertible, B: InoxConvertible] : InoxConvertible[(A, B)] =
    ImplicitTuples.Combination2(c[A], c[B])
  implicit def tuple3Constrainable[A : InoxConvertible, B: InoxConvertible, C: InoxConvertible] : InoxConvertible[(A, B, C)] =
    ImplicitTuples.Combination3(c[A], c[B], c[C])
  /*implicit def tuple4Constrainable[A : InoxConvertible, B: InoxConvertible, C: InoxConvertible, D: InoxConvertible]
  : InoxConvertible[(A, B, C, D)] =
    ImplicitTuples.Combination[(A, B, C, D)](c[A], c[B], c[C], c[D])*/
  //If need more tuples, add them here.

  implicit def listConstrainable[A: InoxConvertible]: InoxConvertible[List[A]] = new InoxConvertible[List[A]] {
    import Utils.{list, nil, cons}
    val underlying = implicitly[InoxConvertible[A]]
    def getType: inox.trees.dsl.trees.Type = T(list)(underlying.getType)

    def recoverFrom(e: inox.trees.Expr): List[A] = e match {
      case ADT(_, Seq(head, tail)) => underlying.recoverFrom(head) :: recoverFrom(tail)
      case ADT(_, Seq()) => Nil
      case _ => throw new Exception("Could not recover list from " + e)

    }

    def produce(a: List[A]): inox.trees.Expr = a match {
      case Nil => ADT(ADTType(nil, Seq(underlying.getType)), Seq())
      case head :: tail => ADT(ADTType(cons, Seq(underlying.getType)), Seq(underlying.produce(head), produce(tail)))
    }
  }

  implicit def optionInoxConvertible[A: InoxConvertible]: InoxConvertible[Option[A]] = new InoxConvertible[Option[A]] {
    import Utils.{option, some, none}
    val underlying = implicitly[InoxConvertible[A]]
    def getType: inox.trees.dsl.trees.Type = T(option)(underlying.getType)

    def recoverFrom(e: inox.trees.Expr): Option[A] = e match {
      case ADT(_, Seq(value)) => Some(underlying.recoverFrom(value))
      case ADT(_, Seq()) => None
      case _ => throw new Exception("Could not recover list from " + e)
    }

    def produce(a: Option[A]): inox.trees.Expr = a match {
      case None => ADT(ADTType(none, Seq(underlying.getType)), Seq())
      case Some(value) => ADT(ADTType(some, Seq(underlying.getType)), Seq(underlying.produce(value)))
    }
  }

  implicit def eitherInoxConvertible[A: InoxConvertible, B: InoxConvertible]: InoxConvertible[Either[A, B]] =
    new InoxConvertible[Either[A, B]] {
      import Utils.{either, left, right}
      def getType: inox.trees.dsl.trees.Type = T(either)(inoxTypeOf[A], inoxTypeOf[B])

      def recoverFrom(e: inox.trees.Expr): Either[A, B] = e match {
        case ADT(ADTType(`left`, _), Seq(value)) => Left(exprOfInox[A](value))
        case ADT(ADTType(`right`, _), Seq(value)) => Right(exprOfInox[B](value))
        case _ => throw new Exception("Could not recover either from " + e)
      }

      def produce(a: Either[A, B]): inox.trees.Expr = a match {
        case Left(value) =>   ADT(ADTType(left, Seq(inoxTypeOf[A], inoxTypeOf[B])), Seq(inoxExprOf[A](value)))
        case Right(value) =>  ADT(ADTType(right, Seq(inoxTypeOf[A], inoxTypeOf[B])), Seq(inoxExprOf[B](value)))
      }
    }

  import WebTrees._

  implicit val WebTreeInoxConvertible: InoxConvertible[WebTree] = new  InoxConvertible[WebTree] {
    import Utils._
    def getType: inox.trees.dsl.trees.Type = T(webTree)()
    def produce(a: WebTree): inox.trees.Expr = a match {
      case a: WebElement => WebElementInoxConvertible.produce(a)
      case a: WebAttribute => WebAttributeInoxConvertible.produce(a)
      case a: WebStyle => WebStyleInoxConvertible.produce(a)
    }
    def recoverFrom(e: inox.trees.Expr): WebTree = e match {
      case ADT(ADTType(`webAttribute`, Seq()), _) => WebAttributeInoxConvertible.recoverFrom(e)
      case ADT(ADTType(`webStyle`, Seq()), _) => WebStyleInoxConvertible.recoverFrom(e)
      case e => WebElementInoxConvertible.recoverFrom(e)
    }
  }

  implicit val WebElementInoxConvertible: InoxConvertible[WebElement] = new  InoxConvertible[WebElement] {
    import Utils._
    def getType: inox.trees.dsl.trees.Type = T(webElement)()
    def produce(a: WebElement): inox.trees.Expr =
      ADT(ADTType(webElement, Seq()), Seq(InnerWebElementInoxConvertible.produce(a.inner)))
    def recoverFrom(e: inox.trees.Expr): WebElement = e match {
      case ADT(ADTType(webElement, Seq()), Seq(arg)) => WebElement(InnerWebElementInoxConvertible.recoverFrom(arg))
      case _ => throw new Exception("Could not recover WebElement from " + e)
    }
  }

  implicit val InnerWebElementInoxConvertible: InoxConvertible[InnerWebElement] = new  InoxConvertible[InnerWebElement] {
    import Utils._
    def getType: inox.trees.dsl.trees.Type = T(innerWebElement)()
    def produce(a: InnerWebElement): inox.trees.Expr = a match {
      case a: TextNode => TextNodeInoxConvertible.produce(a)
      case a: Element =>  ElementInoxConvertible.produce(a)
    }
    def recoverFrom(e: inox.trees.Expr): InnerWebElement = e match {
      case ADT(ADTType(`textNode`, Seq()), _) => TextNodeInoxConvertible.recoverFrom(e)
      case ADT(ADTType(`element`, Seq()), _) => ElementInoxConvertible.recoverFrom(e)
      case _ => throw new Exception("Could not recover InnerWebElement from " + e)
    }
  }

  implicit val TextNodeInoxConvertible: InoxConvertible[TextNode] = new InoxConvertible[TextNode] {
    import Utils._
    def getType: inox.trees.dsl.trees.Type = T(textNode)()
    def produce(a: TextNode): inox.trees.Expr = a match {
      case TextNode(s) => ADT(ADTType(textNode, Seq()), Seq(inoxExprOf[String](s)))
    }
    def recoverFrom(e: inox.trees.Expr): TextNode = e match {
      case ADT(ADTType(`textNode`, Seq()), Seq(s)) => TextNode(exprOfInox[String](s))
      case _ => throw new Exception("Could not recover TextNode from " + e)
    }
  }

  implicit val ElementInoxConvertible: InoxConvertible[Element] = new  InoxConvertible[Element] {
    import Utils._
    def getType: inox.trees.dsl.trees.Type = T(element)()
    def produce(a: Element): inox.trees.Expr = a match {
      case Element(tag, children, attributes, styles) => ADT(ADTType(element, Seq()),
        Seq(inoxExprOf[String](tag), inoxExprOf[List[WebElement]](children), inoxExprOf[List[WebAttribute]](attributes), inoxExprOf[List[WebStyle]](styles)))
    }
    def recoverFrom(e: inox.trees.Expr): Element = e match {
      case ADT(ADTType(`element`, Seq()), Seq(tag, children, attributes, styles)) =>
        Element(exprOfInox[String](tag), exprOfInox[List[WebElement]](children), exprOfInox[List[WebAttribute]](attributes), exprOfInox[List[WebStyle]](styles))
      case _ => throw new Exception("Could not recover Element from " + e)
    }
  }

  implicit val WebAttributeInoxConvertible: InoxConvertible[WebAttribute] = new  InoxConvertible[WebAttribute] {
    import Utils._
    def getType: inox.trees.dsl.trees.Type = T(webAttribute)()
    def produce(a: WebAttribute): inox.trees.Expr = a match {
      case WebAttribute(name, value) => ADT(ADTType(webAttribute, Seq()),
        Seq(inoxExprOf[String](name), inoxExprOf[String](value)))
    }
    def recoverFrom(e: inox.trees.Expr): WebAttribute = e match {
      case ADT(ADTType(`webAttribute`, Seq()), Seq(name, value)) =>
        WebAttribute(exprOfInox[String](name), exprOfInox[String](value))
      case _ => throw new Exception("Could not recover Element from " + e)
    }
  }

  implicit val WebStyleInoxConvertible: InoxConvertible[WebStyle] = new  InoxConvertible[WebStyle] {
    import Utils._
    def getType: inox.trees.dsl.trees.Type = T(webStyle)()
    def produce(a: WebStyle): inox.trees.Expr = a match {
      case WebStyle(name, value) => ADT(ADTType(webStyle, Seq()),
        Seq(inoxExprOf[String](name), inoxExprOf[String](value)))
    }
    def recoverFrom(e: inox.trees.Expr): WebStyle = e match {
      case ADT(ADTType(`webStyle`, Seq()), Seq(name, value)) =>
        WebStyle(exprOfInox[String](name), exprOfInox[String](value))
      case _ => throw new Exception("Could not recover Element from " + e)
    }
  }

  implicit val UnitInoxConvertible: InoxConvertible[Unit] = new InoxConvertible[Unit] {
    def getType = UnitType
    def produce(a: Unit) = UnitLiteral()
    def recoverFrom(e: inox.trees.Expr): Unit = ()
  }

  implicit val XMLInoxConvertible: InoxConvertible[XmlTrees.Node] = new InoxConvertible[XmlTrees.Node] {
    import Utils._
    def getType = T(xmlNode)()
    def produce(a: XmlTrees.Node): inox.trees.Expr = a match {
      case XmlTrees.Node(tag, attrs, children) =>
        ADT(ADTType(xmlNode, Seq()),
          Seq(inoxExprOf[String](tag), inoxExprOf[List[XmlTrees.XMLAttribute]](attrs), inoxExprOf[List[XmlTrees.Node]](children)))
    }
    def recoverFrom(e: inox.trees.Expr): XmlTrees.Node = e match {
      case ADT(ADTType(`xmlNode`, Seq()), Seq(tag, attributes, children)) =>
        XmlTrees.Node(exprOfInox[String](tag), exprOfInox[List[XmlTrees.XMLAttribute]](attributes), exprOfInox[List[XmlTrees.Node]](children))
      case _ => throw new Exception("Could not recover XmlTrees.Node from " + e)
    }
  }

  implicit val XMLAttributeInoxConvertible: InoxConvertible[XmlTrees.XMLAttribute] = new  InoxConvertible[XmlTrees.XMLAttribute] {
    import Utils._
    def getType: inox.trees.dsl.trees.Type = T(xmlAttribute)()
    def produce(a: XmlTrees.XMLAttribute): inox.trees.Expr = a match {
      case XmlTrees.XMLAttribute(name, value) => ADT(ADTType(xmlAttribute, Seq()),
        Seq(inoxExprOf[String](name), inoxExprOf[String](value)))
    }
    def recoverFrom(e: inox.trees.Expr): XmlTrees.XMLAttribute = e match {
      case ADT(ADTType(`xmlAttribute`, Seq()), Seq(name, value)) =>
        XmlTrees.XMLAttribute(exprOfInox[String](name), exprOfInox[String](value))
      case _ => throw new Exception("Could not recover XmlTrees.XMLAttribute from " + e)
    }
  }

  implicit def scalaNodeToXmlNode(node: scala.xml.Node): XmlTrees.Node = node match {
    case scala.xml.Text(text) =>
      XmlTrees.Node(text.trim(), List(), List())
    case n: scala.xml.Node =>
      val (tag, attributes, children) = scala.xml.Node.unapplySeq(n).get
      XmlTrees.Node(tag, scalaAttributesToXmlAttribute(attributes), children.toList.map(scalaNodeToXmlNode _).filter{
        case XmlTrees.Node(s, _, _) if s == "" => false
        case _ => true
      })
    case e =>
      println(e.getClass.getDeclaredMethods.mkString("\n"))
      throw new Exception(s"Could not match $e of class ${e.getClass}")
  }

  implicit def scalaAttributesToXmlAttribute(m: MetaData): List[XmlTrees.XMLAttribute] = {
    m.asAttrMap.toList.map{ case (key, value) => XmlTrees.XMLAttribute(key, value) }
  }

  /** Obtains the inox type of a given type. */
  def inoxTypeOf[A:InoxConvertible] = implicitly[InoxConvertible[A]].getType

  /** Creates a variable with the given name and type*/
  def variable[A:InoxConvertible](name: String, alwaysShowUniqueId: Boolean = false) =
    Variable(FreshIdentifier(name, alwaysShowUniqueId), inoxTypeOf[A], Set())

  /** Obtains the expression from an inox expression */
  def exprOfInox[A:InoxConvertible](e: inox.trees.Expr) = implicitly[InoxConvertible[A]].recoverFrom(e)

  /** Converts a value to an inox expression */
  def inoxExprOf[A: InoxConvertible](e: A) = implicitly[InoxConvertible[A]].produce(e)


  // Helper for building ADTs
  def _Cons[A: InoxConvertible](head: Expr, tail: Expr) = ADT(ADTType(Utils.cons, Seq(inoxTypeOf[A])), Seq(head, tail))
  def _Nil[A: InoxConvertible] = ADT(ADTType(Utils.nil, Seq(inoxTypeOf[A])), Seq())
  def _List[A: InoxConvertible](elements: Expr*): Expr = elements match {
    case Nil => _Nil[A]
    case head +: tail => _Cons[A](head, _List[A](tail: _*))
  }
  def _TextNode(value: Expr) = ADT(ADTType(Utils.textNode, Seq()), Seq(value))
  def _Element(tag: Expr, children: Expr, attributes: Expr, styles: Expr) =
    ADT(ADTType(Utils.element, Seq()), Seq(tag, children, attributes, styles))
  def _WebElement(inner: Expr) = ADT(ADTType(Utils.webElement, Seq()), Seq(inner))
  def _WebAttribute(key: Expr, value: Expr) = ADT(ADTType(Utils.webAttribute, Seq()), Seq(key, value))
  def _WebStyle(key: Expr, value: Expr) = ADT(ADTType(Utils.webStyle, Seq()), Seq(key, value))
  def _Some[A: InoxConvertible](e: Expr) = ADT(ADTType(Utils.some, Seq(inoxTypeOf[A])), Seq(e))
  def _None[A: InoxConvertible] = ADT(ADTType(Utils.none, Seq(inoxTypeOf[A])), Seq())
  def _Left[A: InoxConvertible, B: InoxConvertible](e: Expr) = ADT(ADTType(Utils.left, Seq(inoxTypeOf[A], inoxTypeOf[B])), Seq(e))
  def _Right[A: InoxConvertible, B: InoxConvertible](e: Expr) = ADT(ADTType(Utils.right, Seq(inoxTypeOf[A], inoxTypeOf[B])), Seq(e))
  def _XMLAttribute(key: Expr, value: Expr) = ADT(ADTType(Utils.xmlAttribute, Seq()), Seq(key, value))
  def _XMLNode(tag: Expr, attributes: Expr, children: Expr) =
    ADT(ADTType(Utils.xmlNode, Seq()), Seq(tag, attributes, children))
}
