package perfect

import inox.Identifier
import inox._
import inox.trees._
import inox.trees.dsl._
import inox.solvers._
import perfect.ReverseProgram.Cache
import perfect.Utils.isValue
import perfect.semanticlenses._
import perfect.wraplenses.MaybeWrappedSolutions
import scala.collection.mutable.{HashMap, ListBuffer}

/**
  * Created by Mikael on 03/03/2017.
  */
object ReverseProgram {
  type Cache = HashMap[Expr, Expr]

  import Utils._
  import InoxConvertible._
  lazy val context = Context.empty.copy(options = Options(Seq(optSelectedSolvers(Set("smt-cvc4")))))

  import Utils.ifEmpty

  /** Main entry point to reverse a program.
    * @param out The output that the program should produce
    * @param in The program to repair. May be empty, in which case it returns out
    * @return The program in such that it locally produces the changes given by out */
  def put(in: Option[Expr], out: ProgramFormula): Stream[Expr] = {
    if(in == None) {
      val outExpr = out.bodyDefinition.getOrElse(throw new Exception(s"Ill-formed program: $out"))
      return Stream(outExpr)
    }

    implicit val symbols = defaultSymbols.withFunctions(lenses.Lenses.funDefs)
    implicit val cache = new Cache
    for { r <- repair(ProgramFormula(in.get), out)
          ProgramFormula(newOutExpr, f) = r.insertVariables()                    /: Log.remaining_program
          assignments <- f.determinizeAll(exprOps.variablesOf(newOutExpr).toSeq) /:: Log.found_assignments
          finalNewOutExpr = exprOps.replaceFromSymbols(assignments, newOutExpr)  /: Log.final_expression
    } yield finalNewOutExpr
  }

  /** Alternative way of reversing a program.
    * @param out The output that the program should produce
    * @param in The program to repair, along with assignment formulas. May be empty, in which case it returns out
    * @return The program in such that it locally produces the changes given by out */
  def put(in: ProgramFormula, out: ProgramFormula): Stream[ProgramFormula] = {
    implicit val symbols = defaultSymbols.withFunctions(lenses.Lenses.funDefs)
    implicit val cache = new Cache
    for { r <- repair(in, out) } yield r.insertVariables() /: Log.remaining_program
  }

  /** Eval function. Uses a cache normally. Does not evaluate already evaluated expressions. */
  def maybeEvalWithCache(expr: Expr)(implicit symbols: Symbols, cache: Cache): Option[Expr] = {
    if(cache.contains(expr)) {
      Some(cache(expr))
    } else {
      import evaluators._
      val p = InoxProgram(context, symbols)
      val evaluator = LambdaPreservingEvaluator(p)
      evaluator.eval(expr) match {
        case EvaluationResults.Successful(e) =>
          cache(expr) = e
          Some(e)
        case m => Log(s"Could not evaluate: $expr, got $m")
          None
      }
    }
  }


  /** Returns an evaluator which preserves lambda shapes */
  def LambdaPreservingEvaluator(p: InoxProgram) = {
    import evaluators._
    new {
      val program: p.type = p
      val options = context.options
    } with LambdaPreservingEvaluator
      with HasDefaultGlobalContext with HasDefaultRecContext {
      val semantics: p.Semantics = p.getSemantics
    }
  }

  // Lenses which do not need the value of the program to invert it.
  val shapeLenses: semanticlenses.SemanticLens =
    ((TreeWrap.Lens andThen
      TreeUnwrap.Lens) andThen (
      TreeModification.Lens andThen
        ValueLens))

  lazy val functionInvocationLens: semanticlenses.SemanticLens =
    ShortcutLens(lenses.Lenses.reversions, {
      case FunctionInvocation(id, _, _) => Some(id)
      case _ => None
    })
  import perfect.lenses._

  // Lenses which need the value of the program to invert it.
  val semanticLenses: semanticlenses.SemanticLens =
     (PatternMatch.Lens andThen  // Stand-alone programs on how to repair the program for a given instruction
      PatternReplace.Lens) andThen
     (ListInsert.Lens andThen
      PasteVariable.Lens) andThen
     (StringInsert.Lens andThen
       functionInvocationLens) andThen // Matcher for function invocation in out.
     (FunctionInvocationUnificationLens andThen // Unification of arguments for function invocation.
      SetLens) andThen // Matchers for Set and SetApply constructions
     (MapDataLens andThen // Matcher for FiniteMap and MapApply constructions
      ADTExpr.Lens) // Matcher for ADT and ADTSelector constructions.

  /** Replaces the input by the output if the input is a value (with no free variables for lambdas) */
  case object ConstantReplaceLens extends semanticlenses.SemanticLens {
    isPreemptive = true
    def put(in: ProgramFormula, out: ProgramFormula)(implicit symbols: Symbols, cache: Cache): Stream[ProgramFormula] = {
      // Literals without any free variables should be immediately replaced by the new value
      if(isValue(in.expr) && isValue(out.simplifiedExpr)) Stream(out) else Stream.empty
    }
  }

  val lens = NoChangeLens andThen ConstantReplaceLens andThen
    shapeLenses andThen WrapperLens(semanticLenses andThen DefaultLens, MaybeWrappedSolutions)

  var repairid = 1

  /** Will try its best to transform in so that it produces out or at least incorporates the changes.
    * Entry point of all lenses.
    *
    * @param in An expression that computed the value before newOut, and the formula contains the current mappings.
    * @param out A ProgramFormula resulting from the action of the user on the datat.
    *            Either a literal value that should be produced by function,
    *            or a variable, in which case the result will have in the formula a constraint over this variable,
    *            Or an expression with constrained free variables to denote a clone-and-paste or many other things.
    * @return A set of possible expressions, along with a set of possible assignments to input variables.
    **/
  def repair(in: ProgramFormula, out: ProgramFormula)
            (implicit symbols: Symbols, cache: Cache): Stream[ProgramFormula] = {
    if(!Log.activate) {
      lens.put(in, out)
    } else {
      val stackLevel = {repairid += 1; repairid} //Thread.currentThread().getStackTrace.length
      val instr = in.toString.replaceAll("\n", "\n  ")
      val outstr = out.toString.replaceAll("\n", "\n  ")
      Log(s"\n@repair$stackLevel(\n  $instr\n, $outstr)")

      lens.put(in, out) #:::
        {Log(s"Finished repair$stackLevel"); Stream.empty[ProgramFormula]}  /:: Log.prefix(s"@return for repair$stackLevel(\n  $instr\n, $outstr):\n~>")
    }
  }
}