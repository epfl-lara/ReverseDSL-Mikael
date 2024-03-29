package perfect
package renderer

import inox.trees._

object Python extends Enumeration {
  type Version = Value
  val `2`, `3.6` = Value
}

object Python2Renderer extends PythonRenderer(Python.`2`)
object Python3_6Renderer extends PythonRenderer(Python.`3.6`)

/**
  * Created by Mikael on 27/03/2017.
  */
class PythonRenderer(version: Python.Version) extends inox.ast.Printer {
  val trees = inox.trees
  import ImplicitTuples._

  var printTypes = false
  private val q = "\""
  private val t = "`"

  val pydefs = """from collections import defaultdict"""

  object StringConcats {
    private object StringConcatExtract {
      def unapply(e: Expr): Some[List[Expr]] = e match {
        case StringConcat(StringConcatExtract(a), StringConcatExtract(b)) => Some(a ++ b)
        case e => Some(List(e))
      }
    }

    def unapply(e: Expr): Option[List[Expr]] = e match {
      case StringConcatExtract(l) if l.length >= 2 => Some(l)
      case _ => None
    }
  }

  override protected def ppBody(tree: Tree)(implicit ctx: PrinterContext): Unit = tree match {
    case fm @ FiniteMap(rs, dflt, kt, vt) =>
      p"""defaultdict(lambda: $dflt"""
      if(rs.isEmpty) {
        p""")"""
      } else {
        nary(rs.map{case (k, v) => _Tuple2(kt, vt)(k, v)}, ",\n[|", ",\n|", "])").print(ctx.copy(lvl = ctx.lvl + 1))
      }
    case vd @ ValDef(id, tpe, flags) =>
      if (flags.isEmpty) {
        if(printTypes) p"$id/*: $tpe*/" else p"$id"
      } else {
        if(printTypes) p"$id/*: $tpe*/" else p"$id"
        p"($id/*: $tpe*/)"
        for (f <- flags) p" /*${f.asString(ctx.opts)}*/"
      }
    case Lambda(args, body) =>
      optP {
        p"lambda $args: $body"
      }
    case Not(Equals(a, b)) => p"$a != $b"
    case Equals(a, b) => p"$a == $b"
    case Let(c:ValDef, AsInstanceOf(v: Variable, ADTType(Utils.cons, Seq(t))), body) => // No need for let-def printing.
      ppBody(exprOps.replaceFromSymbols(Map(c -> v), body))
    case Let(b, d, e) =>
      p"""|$b = $d
          |$e"""
    case ADTSelector(e, Utils.head) => p"$e[0]"
    case ADTSelector(e, Utils.tail) => p"$e[1:]"
    case ADTSelector(e, TupleSelector(i)) => p"$e[$i]"
    case ADT(ADTType(ITupleType(i), _), args) =>
      nary(args, "(", ", ", ")").print(ctx)
    case ListLiteral(l, tpe) =>
      nary(l, ", ", "[", "]").print(ctx)
    case e @ ADT(adt, args) =>
      p"new $adt($args)"
    case MapApply(a, i) =>
      p"$a[$i]"
    case StringConcats(ss) if ss.exists(_.isInstanceOf[StringLiteral]) && version >= Python.`3.6` => // Use tick printing.
      p"f$q$q$q"
      for(s <- ss) {
        s match {
          case StringLiteral(v) =>
            val escaped = v.replaceAllLiterally("\\", "\\\\").replaceAllLiterally("{", "\\{") // We can keep newlines !
            ctx.sb append escaped // We appen the string raw.
          case e => p"{$e}"
        }
      }
      p"$q$q$q"

    case StringConcat(StringConcat(a, sl@StringLiteral(s)), b) if s.endsWith("\n") => // Is not used anymore normally
      p"""$a + $sl + \
         |$b"""
    case IsInstanceOf(e, ADTType(Utils.nil, Seq())) => p"len($e) == 0"
    case IsInstanceOf(e, ADTType(Utils.cons, Seq(t))) => p"len($e) > 0"
    case AsInstanceOf(e, ADTType(Utils.cons, Seq(t))) => p"""$e"""
    case AsInstanceOf(e, ct) => p"""$e"""
    case IsInstanceOf(e, cct) => p"$e.tag == $q$cct$q"
    case FunctionInvocation(id, tps, args) =>
      p"$id($args)"
    /*case StringLiteral(v) =>
      val escaped = v.replaceAllLiterally("\\", "\\\\") // We can keep newlines !
      p"$q$q$q$escaped$q$q$q"*/
    case _ => super.ppBody(tree)
  }
}
