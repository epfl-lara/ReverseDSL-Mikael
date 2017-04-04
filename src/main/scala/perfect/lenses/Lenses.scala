package perfect
package lenses

import inox.Identifier
import inox._
import inox.trees._
import inox.trees.dsl._
import inox.solvers._
import perfect.ImplicitTuples.{_1, _2, tuple2}

trait Lenses { self: ReverseProgram.type =>

  val reversers = List[Reverser](
    FilterReverser,
    MapReverser,
    ListConcatReverser,
    FlattenReverser,
    FlatMapReverser,
    StringConcatReverser,
    SplitEvenReverser,
    MergeReverser,
    SortWithReverser,
    MkStringReverser
  )

  val reversions = reversers.map(x => x.identifier -> x).toMap
  val funDefs = reversers.map(_.funDef) ++ List(
    mkFunDef(Utils.stringCompare)(){case _ =>
      (Seq("left"::StringType, "right"::StringType),
        Int32Type,
        { case Seq(l, r) =>
            StringLength(l) - StringLength(r) // Dummy
        })
    }
  )

  abstract class Reverser {
    def identifier: Identifier
    def funDef: FunDef
    def mapping = identifier -> this
    def put(tpes: Seq[Type])(originalArgsValues: Seq[Expr], newOutput: ProgramFormula)(implicit cache: Cache, symbols: Symbols): Stream[(Seq[ProgramFormula], Formula)]
  }

  object ListLiteral {
    import Utils._
    def unapply(e: Expr): Option[(List[Expr], Type)] = e match {
      case ADT(ADTType(cons, Seq(tp)), Seq(head, tail)) =>
        unapply(tail).map(res => (head :: res._1, tp))
      case ADT(ADTType(nil, Seq(tp)), Seq()) =>
        Some((Nil, tp))
      case _ => None
    }
    def apply(e: List[Expr], tp: Type): Expr = e match {
      case head :: tail =>
        ADT(ADTType(cons, Seq(tp)), Seq(head, apply(tail, tp)))
      case Nil =>
        ADT(ADTType(nil, Seq(tp)), Seq())
    }
    /** Requires either f to be empty, or e to be a ListLiteral */
    def concat(e: Expr, f: Expr*): Expr = {
      if(f.isEmpty) e else {
        e match {
          case ADT(ADTType(cons, Seq(tp)), Seq(head, tail)) =>
            ADT(ADTType(cons, Seq(tp)), Seq(head, concat(tail, f: _*)))
          case ADT(ADTType(nil, Seq(tp)), Seq()) =>
            concat(f.head, f.tail: _*)
          case _ => throw new Exception("[internal error] cannot concatenate non-literal on the left using ListLiteral.concat")
        }
      }
    }
  }

  /** Lense-like filter */
  case object FilterReverser extends Reverser with FilterLike[Expr] { // TODO: Incorporate filterRev as part of the sources.
    import Utils._
    val identifier = Utils.filter
    def put(tpes: Seq[Type])(originalArgsValues: Seq[Expr], newOutputProgram: ProgramFormula)(implicit cache: Cache, symbols: Symbols): Stream[(Seq[ProgramFormula], Formula)] = {
      val lambda = originalArgsValues.tail.head
      val newOutput = newOutputProgram.expr
      val ListLiteral(originalInput, _) = originalArgsValues.head
      //Log(s"Reversing $originalArgs: $originalOutput => $newOutput")
      filterRev(originalInput, (expr: Expr) => evalWithCache(Application(lambda, Seq(expr))) == BooleanLiteral(true), ListLiteral.unapply(newOutput).get._1).map{ (e: List[Expr]) =>
        (Seq(ProgramFormula(ListLiteral(e, tpes.head)), ProgramFormula(lambda)), Formula())
      }
    }

    // filter definition in inox
    val funDef = mkFunDef(identifier)("A"){ case Seq(tp) =>
      (Seq("ls" :: T(list)(tp), "f" :: FunctionType(Seq(tp), BooleanType)),
        T(list)(tp),
        { case Seq(ls, f) =>
          if_(ls.isInstOf(T(cons)(tp))) {
            let("c"::T(cons)(tp), ls.asInstOf(T(cons)(tp)))(c =>
              let("head"::tp, c.getField(head))( head =>
                if_(Application(f, Seq(head))){
                  ADT(T(cons)(tp), Seq(head, E(identifier)(tp)(c.getField(tail), f)))
                } else_ {
                  E(identifier)(tp)(c.getField(tail), f)
                }
              )
            )
          } else_ {
            ADT(T(nil)(tp), Seq())
          }
        })
    }
  }

  /** Lense-like map, with the possibility of changing the mapping lambda. */
  case object MapReverser extends Reverser {
    import Utils._
    val identifier = map

    def put(tpes: Seq[Type])(originalArgsValues: Seq[Expr], newOutput: ProgramFormula)(implicit cache: Cache, symbols: Symbols): Stream[(Seq[ProgramFormula], Formula)] = {
      Log(s"map.apply($newOutput)")
      val lambda = castOrFail[Expr, Lambda](originalArgsValues.tail.head)
      val ListLiteral(originalInput, _) = originalArgsValues.head
      val uniqueUnknownValue = defaultValue(lambda.args.head.getType)
      // Maybe we change only arguments. If not possible, we will try to change the lambda.
      val mapr = new MapReverseLike[Expr, Expr, (Expr, Lambda)] {
        override def f = (expr: Expr) => evalWithCache(Application(lambda, Seq(expr)))

        override def fRev = (prevIn: Option[Expr], out: Expr) => {
          Log(s"Map.fRev: $prevIn, $out")
          val (Seq(in), newFormula) =
            prevIn.map(x => (Seq(x), Formula(Map[ValDef, Expr]()))).getOrElse {
              val unknown = ValDef(FreshIdentifier("unknown"),lambda.args.head.getType)
              (Seq(unknown.toVariable), Formula(Map[ValDef, Expr](unknown -> uniqueUnknownValue)))
            }
          Log(s"in:$in\nnewformula:$newFormula")
          Log.prefix("res=") :=
          repair(ProgramFormula(Application(lambda, Seq(in)), newFormula), newOutput.subExpr(out)).flatMap {
            case ProgramFormula(Application(_, Seq(in2)), _)
              if in2 != in => //The argument's values have changed
              Stream(Left(in2))
            case ProgramFormula(Application(_, Seq(in2)), f:Formula)
              if in2 == in && in2.isInstanceOf[Variable] =>
              // The repair introduced a variable. We evaluate all possible values.
              // TODO: Alternatively, propagate the constraint on the variable
              f.evalPossible(in2).map(Left(_))
            case e@ProgramFormula(Application(lambda2: Lambda, Seq(in2)), f:Formula)
              if in2 == in && lambda2 != lambda => // The lambda has changed.
              f.evalPossible(lambda2).map(lambda => Right((in, castOrFail[Expr, Lambda](lambda))))
            case e@ProgramFormula(app, f) =>
              throw new Exception(s"Don't know how to invert both the lambda and the value: $e")
          }.filter(_ != Left(uniqueUnknownValue))
        }
      }

      //Log(s"Reversing $originalArgs: $originalOutput => $newOutput")
      mapr.mapRev(originalInput, ListLiteral.unapply(newOutput.expr).get._1).flatMap(recombineArgumentsLambdas(lambda, tpes))
    }

    // Map definition in inox
    val funDef = mkFunDef(identifier)("A", "B"){ case Seq(tA, tB) =>
      (Seq("ls" :: T(list)(tA), "f" :: FunctionType(Seq(tA), tB)),
        T(list)(tB),
        { case Seq(ls, f) =>
          if_(ls.isInstOf(T(cons)(tA))) {
            let("c"::T(cons)(tA), ls.asInstOf(T(cons)(tA)))(c =>
              let("head"::tA, c.getField(head))( head =>
                ADT(T(cons)(tB), Seq(Application(f, Seq(head)), E(identifier)(tA, tB)(c.getField(tail), f)))
              )
            )
          } else_ {
            ADT(T(nil)(tB), Seq())
          }
        })
    }
  }

  /** Lense-like map, with the possibility of changing the mapping lambda. */
  case object FlattenReverser extends Reverser {
    import Utils._
    val identifier = flatten

    def put(tpes: Seq[Type])(originalArgsValues: Seq[Expr], newOutputProgram: ProgramFormula)(implicit cache: Cache, symbols: Symbols): Stream[(Seq[ProgramFormula], Formula)] = {
???
    }

    // Flatten definition in inox
    val funDef = mkFunDef(identifier)("A"){ case Seq(tA) =>
      (Seq("ls" :: T(list)(T(list)(tA))),
        T(list)(tA),
        { case Seq(ls) =>
          if_(ls.isInstOf(T(cons)(T(list)(tA)))) {
            let("c"::T(cons)(T(list)(tA)), ls.asInstOf(T(cons)(T(list)(tA))))(c =>
              let("head"::T(list)(tA), c.getField(head))( head =>
                FunctionInvocation(listconcat, Seq(tA), Seq(head,
                  FunctionInvocation(identifier, Seq(tA), Seq(c.getField(tail)))
                ))
              )
            )
          } else_ {
            ADT(T(nil)(T(list)(tA)), Seq())
          }
        })
    }
  }

  case object MkStringReverser extends Reverser {
    import Utils._
    import StringConcatExtended._
    import InoxConvertible._
    val identifier = mkString

    private def separateModifs(in: List[String], infix: String, out: String): (List[String], String, List[String]) = {
      in match {
        case Nil => (Nil, out, Nil)
        case lOutHead::tail =>
          if(out.startsWith(lOutHead + infix)) {
            val (before, middle, after) = separateModifs(in.tail, infix, out.drop(lOutHead.length + infix.length))
            (lOutHead :: before, middle, after)
          } else if(out == lOutHead) { // Last element
            (lOutHead::Nil, "", Nil)
          } else {
            val optI = (0 to in.length).find { i =>
              out.endsWith(in.drop(i).map(infix + _).mkString)
            }
            assert(optI.isInstanceOf[Some[Int]], "[Internal error] should not happen")
            val i = optI.asInstanceOf[Some[Int]].get
            val after = in.drop(i)
            (Nil, out.take(out.length - after.map(infix + _).mkString.length), after)
          }
      }
    }
    import InoxConvertible.conversions._

    implicit class AugmentedList[A](l: List[A]) {
      def mapFirst(p: A => A): List[A] = l match {
        case Nil => Nil
        case head::tail => p(head)::tail
      }
      def mapLast(p: A => A): List[A] = l match {
        case Nil => Nil
        case init :+ last => init :+ p(last)
      }
    }
    
    def put(tpes: Seq[Type])(originalArgsValues: Seq[Expr], newOutputProgram: ProgramFormula)(implicit cache: Cache, symbols: Symbols): Stream[(Seq[ProgramFormula], Formula)] = {
      val originalInputExpr@ListLiteral(originalInput, _) = originalArgsValues.head
      val infixExpr@StringLiteral(infix) = originalArgsValues.tail.head
      val originalInputList: List[String] = originalInput.map{
        case StringLiteral(l) => l
        case e => throw new Exception(s"not a string: $e")
      }
      newOutputProgram match {
        case ProgramFormula.StringInsert(left, inserted_, right) =>
          var inserted = inserted_

          def middleInsertedPossible(inserted: String) = // All possible lists of inserted elements in the middle.
            (if (inserted != "" && infix.nonEmpty) {
              List(inserted.split(java.util.regex.Pattern.quote(infix)).toList)
            } else {
              Nil
            }) ++ List(List(inserted))

          // Priority to the insertion/modification/deletion of elements
          // If not ambiguous (e.g. infix selected and replaced, or infix empty), change the infix.
          // Todo: Return an index of where the start selection occurred (position in list, inside element + maybe prefix)
          // Return same index for out.
          // StringInsert for left element being changed, right element being changed (if not the same).
          // ListInsert for elements in the middle.
          sealed abstract class ElementOrInfix {
            def s: String
          }
          case class Element(s: String) extends ElementOrInfix
          case class Infix(s: String) extends ElementOrInfix
          
          implicit class AugmentedElementOrInfixList(l: List[ElementOrInfix]) {
            def toOriginalListExpr: List[Expr] = l.collect{ case e: Element => StringLiteral(e.s)}
          }

          val lElementOrInfix: List[ElementOrInfix] = originalInputList match {
            case Nil => Nil
            case head :: tail => (collection.mutable.ListBuffer[ElementOrInfix](Element(head)) /: tail) { case (l, e) => (l += Infix(infix)) += Element(e) }.toList
          }

          // If infix explicitly selected, we replace it.
          val all@(leftUntouched: List[ElementOrInfix],
          leftUpdated: String, // should be a prefix of notHandledList.map(_.s).mkString
          notHandledList: List[ElementOrInfix],
          rightUpdated: String, // should be a suffix of notHandledList.map(_.s).mkString
          rightUntouched: List[ElementOrInfix]) = {
            val Some(init) = lElementOrInfix.inits.find(l => left.startsWith(l.map(_.s).mkString))
            val remaining = lElementOrInfix.drop(init.length)
            val Some(tail) = remaining.tails.find(l => right.endsWith(l.map(_.s).mkString))
            (init, left.substring(init.map(_.s).mkString.length), remaining.take(remaining.length - tail.length),
              right.substring(0, right.length - tail.map(_.s).mkString.length), tail)
          }
          Log("(lun,lup,nh,rup,run)="+all)

          assert(notHandledList.map(_.s).mkString.startsWith(leftUpdated), "The repair given was inconsistent with previous function value")
          assert(notHandledList.map(_.s).mkString.endsWith(rightUpdated), "The repair given was inconsistent with previous function value")

          val solutions = collection.mutable.ListBuffer[(Seq[ProgramFormula], Formula)]()

          //     [leftUntouched   ][          notHandledList           ][rightUntouched]
          // ==> [leftUntouched   ]leftUpdated [inserted]rightUpdated   [rightUntouched]
            //When complete coverage, replace this if-then-else by this body because it's the same code with coverage

          if(notHandledList.length == 1 && notHandledList.head.isInstanceOf[Infix]) { // The prefix was selected and updated !
            solutions += ((Seq(ProgramFormula(originalInputExpr),
              ProgramFormula.StringInsert(StringLiteral(leftUpdated), StringLiteral(inserted), StringLiteral(rightUpdated))), Formula()))
          } else if(notHandledList.length % 2 == 0) { // An insertion occurred either on the element before or the element after.
            // Some more elements have been possibly inserted as well. We prioritize splitting on the infix if it is not empty.
            (leftUntouched.lastOption, rightUntouched.headOption) match {
              case (someInfixOrNone, Some(firstElem: Element)) =>
                assert(someInfixOrNone.isEmpty || someInfixOrNone.get.isInstanceOf[Infix])
                inserted = leftUpdated + inserted + rightUpdated
                if(infix != "") { // TODO: Add weights to attach to lastElem or firstElem
                  if(inserted.endsWith(infix)) { // Only elements have been inserted.
                    solutions ++= (for{ lInserted <- middleInsertedPossible(inserted.substring(0, inserted.length - infix.length)) } yield
                      (Seq(ProgramFormula.ListInsert(
                        StringType,
                        leftUntouched.toOriginalListExpr,
                        lInserted.map{ (s: String) => StringLiteral(s)},
                        rightUntouched.toOriginalListExpr,
                        BooleanLiteral(true)),
                        ProgramFormula(infixExpr)), Formula())
                      )
                  } else { // Does not end with infix, hence there is an StringInsertion and maybe a ListInsert.
                    solutions ++= (for{ lInserted <- middleInsertedPossible(inserted)} yield {
                      val pf = ProgramFormula.StringInsert("", lInserted.last, firstElem.s)
                      if(lInserted.length == 1) {
                        val newExpr = ListLiteral.concat(
                          ListLiteral(leftUntouched.toOriginalListExpr, StringType),
                          ListLiteral(List(pf.expr), StringType),
                          ListLiteral(rightUntouched.tail.toOriginalListExpr, StringType))
                        (Seq(ProgramFormula(newExpr, pf.formula), ProgramFormula(infixExpr)), Formula())
                      } else { // New elements have been furthermore inserted.
                        val newPf = ProgramFormula.ListInsert(
                          StringType,
                          leftUntouched.toOriginalListExpr,
                          lInserted.init.map{ (s: String) => StringLiteral(s)},
                          pf.expr :: rightUntouched.tail.toOriginalListExpr,
                          BooleanLiteral(true)) combineWith pf.formula
                        (Seq(newPf, ProgramFormula(infixExpr)), Formula())
                      }
                    })
                  }
                } else { // Infix is empty.
                  assert(infix == "")
                  val ifPrepend = ProgramFormula.StringInsert("", inserted, firstElem.s)
                  val toAdd =
                  // First suppose that the infix has been updated if it was present
                    (if(someInfixOrNone != None)
                      List((Seq(ProgramFormula(originalInputExpr), ProgramFormula.StringInsert("", inserted, "")), Formula()))
                    else Nil) ++ List(
                      // Then the element to the right.
                      (Seq(ProgramFormula(
                        ListLiteral.concat(
                          ListLiteral(leftUntouched.toOriginalListExpr, StringType),
                          ListLiteral(List(ifPrepend.expr), StringType),
                          ListLiteral(rightUntouched.tail.toOriginalListExpr, StringType)
                        ), ifPrepend.formula), ProgramFormula(infixExpr)), Formula())
                    ) ++ {  // Then the element to the left, if any
                      if(someInfixOrNone != None) {
                        val ifAppend = ProgramFormula.StringInsert(leftUntouched.init.last.s, inserted, "")
                        List((Seq(ProgramFormula(
                          ListLiteral.concat(
                            ListLiteral(leftUntouched.init.toOriginalListExpr, StringType),
                            ListLiteral(List(ifAppend.expr), StringType),
                            ListLiteral(rightUntouched.toOriginalListExpr, StringType)
                          ), ifAppend.formula), ProgramFormula(infixExpr)), Formula()))
                      } else Nil
                    }

                  solutions ++= toAdd
                }

              case (Some(lastElem: Element), someInfixOrNone) =>
                assert(someInfixOrNone.isEmpty || someInfixOrNone.get.isInstanceOf[Infix])
                assert(infix != "" || someInfixOrNone == None) // Because else it would have been into left.
                inserted = leftUpdated + inserted + rightUpdated

                if(inserted.startsWith(infix) && infix != "") { // Only elements have been inserted.
                  solutions ++= (for{ lInserted <- middleInsertedPossible(inserted.substring(infix.length)) } yield
                    (Seq(ProgramFormula.ListInsert(
                      StringType,
                      leftUntouched.toOriginalListExpr,
                      lInserted.map{ (s: String) => StringLiteral(s)},
                      rightUntouched.toOriginalListExpr,
                      BooleanLiteral(true)),
                      ProgramFormula(infixExpr)), Formula())
                    )
                } else { // Does not start with infix, hence there is an StringInsertion and maybe a ListInsert.
                  solutions ++= (for{ lInserted <- middleInsertedPossible(inserted)} yield {
                    val pf = ProgramFormula.StringInsert(lastElem.s, lInserted.head, "")
                    if(lInserted.length == 1) {
                      val newExpr = ListLiteral.concat(
                        ListLiteral(leftUntouched.init.toOriginalListExpr, StringType),
                        ListLiteral(List(pf.expr), StringType),
                        ListLiteral(rightUntouched.toOriginalListExpr, StringType))
                      (Seq(ProgramFormula(newExpr, pf.formula), ProgramFormula(infixExpr)), Formula())
                    } else { // New elements have been furthermore inserted.
                      val newPf = ProgramFormula.ListInsert(
                        StringType,
                        leftUntouched.init.toOriginalListExpr :+ pf.expr,
                        lInserted.tail.map{ (s: String) => StringLiteral(s)},
                        rightUntouched.toOriginalListExpr,
                        BooleanLiteral(true)) combineWith pf.formula
                      (Seq(newPf, ProgramFormula(infixExpr)), Formula())
                    }
                  })
                }

              case (None, None) => // Empty list since the beginning. We just add elements, splitting them if needed.
                solutions ++= (for { lInserted <- middleInsertedPossible(inserted) } yield {
                  (Seq(
                    ProgramFormula.ListInsert(StringType,
                      Nil,
                      lInserted.map(x => StringLiteral(x)),
                      Nil,
                      BooleanLiteral(true)
                    ),
                    ProgramFormula(infixExpr)
                  ), Formula())
                })
              case e => throw new Exception(s"[Internal error]: did not expect: $e")
            }
          } else { // notHandledList.length % 2 == 1
            (leftUntouched.lastOption, rightUntouched.headOption) match {
              case (Some(prev: Element), Some(next: Element)) =>
                assert(infix != "") // Else infix would have been taken in leftUntouched.
                inserted = leftUpdated + inserted + rightUpdated
                val (startsWithInfix, remainingInserted) = if(inserted.startsWith(infix)) (true, inserted.substring(infix.length)) else (false, inserted)
                val (endsWithInfix, finalInserted) = if(remainingInserted.endsWith(infix)) (true, remainingInserted.substring(0, remainingInserted.length - infix.length)) else (false, remainingInserted)

                solutions ++= (for{ lInserted <- middleInsertedPossible(finalInserted) } yield {
                  if(startsWithInfix && endsWithInfix) { // Pure insertion of the elements.
                    (Seq(ProgramFormula.ListInsert(
                      StringType,
                      leftUntouched.toOriginalListExpr,
                      lInserted.map(x => StringLiteral(x)),
                      rightUntouched.toOriginalListExpr,
                      BooleanLiteral(true)
                    ), ProgramFormula(infixExpr)), Formula())
                  } else if(lInserted.length == 1) {
                    if(!startsWithInfix && !endsWithInfix) { // Merge of two elements by inserting something else.
                      // We report this as deleting the second argument and updating the first one with the value of the second.
                      // TODO: Do better once we have the technology of argument value rewriting.
                      val insertion = ProgramFormula.StringInsert(prev.s, lInserted.head + next.s, "")
                      (Seq(ProgramFormula.ListInsert(
                        StringType,
                        leftUntouched.init.toOriginalListExpr :+ insertion.expr,
                        Nil,
                        rightUntouched.tail.toOriginalListExpr,
                        BooleanLiteral(true)
                      ) combineWith insertion.formula, ProgramFormula(infixExpr)), Formula())
                    } else if(startsWithInfix && !endsWithInfix) { // We merge the insertion with the right element
                      val insertion = ProgramFormula.StringInsert("", lInserted.last, next.s)
                      (Seq(ProgramFormula(
                        ListLiteral(
                          leftUntouched.toOriginalListExpr ++
                            (insertion.expr +: rightUntouched.tail.toOriginalListExpr),
                          StringType
                        ), insertion.formula), ProgramFormula(infixExpr)), Formula())
                    } else /*if(!startsWithInfix && endsWithInfix) */{ // We merge the insertion with the right element
                      val insertion = ProgramFormula.StringInsert(prev.s, lInserted.head, "")
                      (Seq(ProgramFormula(
                        ListLiteral(
                          (leftUntouched.init.toOriginalListExpr :+ insertion.expr) ++
                            rightUntouched.toOriginalListExpr,
                          StringType
                        ), insertion.formula), ProgramFormula(infixExpr)), Formula())
                    }
                  } else { // lInserted.length > 1
                    if(!startsWithInfix && !endsWithInfix) { // There is room for modifying two elements and inserting the rest.
                      val insertionPrev = ProgramFormula.StringInsert(prev.s, lInserted.head, "")
                      val insertionNext = ProgramFormula.StringInsert("", lInserted.last, next.s)
                      (Seq(ProgramFormula.ListInsert(
                        StringType,
                        leftUntouched.init.toOriginalListExpr :+ insertionPrev.expr,
                        lInserted.tail.init.map( x => StringLiteral(x) ),
                        insertionNext.expr +: rightUntouched.tail.toOriginalListExpr,
                        BooleanLiteral(true)
                      ) combineWith insertionPrev.formula combineWith insertionNext.formula, ProgramFormula(infixExpr)), Formula())
                    } else if(startsWithInfix && !endsWithInfix) { // We merge the insertion with the right element
                      val insertion = ProgramFormula.StringInsert("", lInserted.last, next.s)
                      (Seq(ProgramFormula.ListInsert(
                        StringType,
                        leftUntouched.toOriginalListExpr,
                        lInserted.init.map(x => StringLiteral(x)),
                        (insertion.expr +: rightUntouched.tail.toOriginalListExpr),
                        insertion.formula.unknownConstraints
                      ), ProgramFormula(infixExpr)), Formula())
                    } else /*if(!startsWithInfix && endsWithInfix)*/ { // We merge the insertion with the right element
                      val insertion = ProgramFormula.StringInsert(prev.s, lInserted.head, "")
                      (Seq(ProgramFormula.ListInsert(
                        StringType,
                        leftUntouched.init.toOriginalListExpr :+ insertion.expr,
                        lInserted.tail.map(x => StringLiteral(x)),
                        rightUntouched.toOriginalListExpr,
                        insertion.formula.unknownConstraints), ProgramFormula(infixExpr)), Formula())
                    }
                  }
                })
              case (Some(_: Infix) | None, Some(_: Infix) | None) =>

                inserted = leftUpdated + inserted + rightUpdated
                // Simple insertion between two infixes.
                solutions ++= (for{ lInserted <- middleInsertedPossible(inserted) } yield {
                  (Seq(ProgramFormula.ListInsert(
                    StringType,
                    leftUntouched.toOriginalListExpr,
                    lInserted.map(x => StringLiteral(x)),
                    rightUntouched.toOriginalListExpr,
                    BooleanLiteral(true)
                  ), ProgramFormula(infixExpr)), Formula())
                })
              case (Some(prev: Infix), Some(next: Element)) => throw new Exception("[Internal error]")
              case (Some(prev: Element), Some(next: Infix)) => throw new Exception("[Internal error]")
              case (None, Some(next: Element)) => // This would mean that notHandled is of even size, impossible
                throw new Exception("[Internal error]")
              case (Some(next: Element), None) => // This would mean that notHandled is of even size, impossible
                throw new Exception("[Internal error]")
            }
          }

          return solutions.toStream

        case _ =>
          val StringLiteral(newOut) = newOutputProgram.functionValue
          val (before, middle, after) = separateModifs(originalInputList, infix, newOut)
          Log(s"Separator($originalInputList, '$infix', '$newOut')=")
          Log(s"($before, $middle, $after)")

          val notHandled = originalInputList.drop(before.length).take(originalInputList.length - before.length - after.length)
          val middleList: List[String] = if(infix.nonEmpty) { // We split middle using infix.
            middle.split(java.util.regex.Pattern.quote(infix)).toList
          } else { // We consider the new element as a new one.
            List(middle)
          }
          val list = ListLiteral((before ++ middleList ++ after).map(x => StringLiteral(x)), StringType)
          Stream((Seq(ProgramFormula(list), ProgramFormula(StringLiteral(infix))), Formula()))
      }
    }

    // Flatten definition in inox
    val funDef = mkFunDef(identifier)(){ case Seq() =>
      (Seq("ls" :: T(list)(StringType), "infix"::StringType),
        StringType,
        { case Seq(ls, infix) =>
          if_(ls.isInstOf(T(cons)(StringType))) {
            let("c"::T(cons)(StringType), ls.asInstOf(T(cons)(StringType)))(c =>
              let("head"::StringType, c.getField(head))( head =>
                let("tail"::T(list)(StringType), c.getField(tail))( tail =>
                  if_(tail.isInstOf(T(cons)(StringType))) {
                    head +& infix +& FunctionInvocation(identifier, Seq(), Seq(tail, infix))
                  } else_ {
                    head
                  }
              )
            )
            )
          } else_ {
            StringLiteral("")
          }
        })
    }
  }

  private def recombineArgumentsLambdas(lambda: Lambda, tpes: Seq[Type])(e: List[Either[Expr, (Expr, Lambda)]]) = {
    val argumentsChanged = e.map{
      case Left(e) => e
      case Right((e, lambda)) => e
    }
    val newLambdas = if(e.exists(_.isInstanceOf[Right[_, _]])) {
      e.collect{ case Right((expr, lambda: Lambda)) => lambda }.toStream
    } else Stream(lambda)
    for(l <- newLambdas) yield {
      (Seq(ProgramFormula(ListLiteral(argumentsChanged, tpes.head)), ProgramFormula(l)), Formula())
    }
  }

  /** Lense-like map, with the possibility of changing the mapping lambda. */
  case object FlatMapReverser extends Reverser {
    import Utils._
    val identifier = flatmap

    def put(tpes: Seq[Type])(originalArgsValues: Seq[Expr], newOutput: ProgramFormula)(implicit cache: Cache, symbols: Symbols): Stream[(Seq[ProgramFormula], Formula)] = {
      val ListLiteral(originalInput, _) = originalArgsValues.head
      val lambda = castOrFail[Expr, Lambda](originalArgsValues.tail.head)
      val uniqueUnknownValue = defaultValue(lambda.args.head.getType)

      val fmapr = new FlatMapReverseLike[Expr, Expr, (Expr, Lambda)] {
        def f: Expr => List[Expr] = { (expr: Expr) =>
          val ListLiteral(l, _) = evalWithCache(Application(lambda, Seq(expr)))
          l
        }
        def fRev: (Option[Expr], List[Expr]) => Stream[Either[Expr, (Expr, Lambda)]] = { (prevIn, out) =>
          Log(s"Flatmap.fRev: $prevIn, $out")
          val (Seq(in), newFormula) =
            prevIn.map(x => (Seq(x), Formula(Map[ValDef, Expr]()))).getOrElse {
              val unknown = ValDef(FreshIdentifier("unknown"),lambda.args.head.getType)
              (Seq(unknown.toVariable), Formula(Map[ValDef, Expr](unknown -> uniqueUnknownValue)))
            }
          Log(s"flatmap in:$in\nnewformula:$newFormula")
          Log.res :=
            repair(ProgramFormula(Application(lambda, Seq(in)), newFormula), newOutput.subExpr(ListLiteral(out, tpes(1)))).flatMap {
              case ProgramFormula(Application(_, Seq(in2)), _)
                if in2 != in => //The argument's values have changed
                Stream(Left(in2))
              case ProgramFormula(Application(_, Seq(in2)), f:Formula)
                if in2 == in && in2.isInstanceOf[Variable] =>
                // The repair introduced a variable. We evaluate all possible values.
                // TODO: Alternatively, propagate the constraint on the variable
                f.evalPossible(in2).map(Left(_))
              case e@ProgramFormula(Application(lambda2: Lambda, Seq(in2)), f:Formula)
                if in2 == in && lambda2 != lambda => // The lambda has changed.
                f.evalPossible(lambda2).map(lambda => Right((in, castOrFail[Expr, Lambda](lambda))))
              case e@ProgramFormula(app, f) =>
                throw new Exception(s"Don't know how to invert both the lambda and the value: $e")
            }.filter(_ != Left(uniqueUnknownValue))
        }
      }

      fmapr.flatMapRev(originalInput, ListLiteral.unapply(newOutput.expr).get._1).flatMap(recombineArgumentsLambdas(lambda, tpes))
    }

    // Flatmap definition in inox
    val funDef = mkFunDef(identifier)("A", "B"){ case Seq(tA, tB) =>
      (Seq("ls" :: T(list)(tA), "f" :: FunctionType(Seq(tA), T(list)(tB))),
        T(list)(tB),
        { case Seq(ls, f) =>
          if_(ls.isInstOf(T(cons)(tA))) {
            let("c"::T(cons)(tA), ls.asInstOf(T(cons)(tA)))(c =>
              let("headMapped"::T(list)(tB), f(c.getField(head)))( headMapped =>
                E(listconcat)(tB)(headMapped, E(identifier)(tA, tB)(c.getField(tail), f))
              )
            )
          } else_ {
            ADT(T(nil)(tB), Seq())
          }
        })
    }
  }


  /** Lense-like list concat, with the possibility of changing the mapping lambda. */
  case object ListConcatReverser extends Reverser {
    import Utils._
    val identifier = listconcat

    def startsWith(list: Expr, beginning: Expr): Boolean = (list, beginning) match {
      case (ADT(_, Seq(head, tail)), ADT(_, Seq())) => true
      case (ADT(_, Seq(head, tail)), ADT(_, Seq(head2, tail2))) =>
        head == head2 && startsWith(tail, tail2)
      case _ => false
    }

    def reverse(list: Expr, gather: Option[Expr]): Expr = list match {
      case ADT(tpe@ADTType(c, targs), Seq(head, tail)) =>
        val gathertail = gather.getOrElse(ADT(ADTType(nil, targs), Seq()))
        reverse(tail, Some(ADT(tpe, Seq(head, gathertail))))
      case ADT(_, Seq()) => gather.getOrElse(list)
    }

    def endsWith(list: Expr, end: Expr): Boolean = startsWith(reverse(list, None), reverse(end, None))

    def put(tps: Seq[Type])(originalArgsValues: Seq[Expr], newOutputProgram: ProgramFormula)(implicit cache: Cache, symbols: Symbols): Stream[(Seq[ProgramFormula], Formula)] = {
      val newOutput = newOutputProgram.expr
      val leftValue = originalArgsValues.head
      val rightValue = originalArgsValues.tail.head

      def defaultCase: Stream[(Seq[ProgramFormula], Formula)] = {
        val left = ValDef(FreshIdentifier("l", true), T(list)(tps.head), Set())
        val right = ValDef(FreshIdentifier("r", true), T(list)(tps.head), Set())
        Log(s"List default case: ${left.id} + ${right.id} == $newOutput")

        val f = Formula(
          newOutput === FunctionInvocation(listconcat, tps, Seq(left.toVariable, right.toVariable)) &&
          not(left.toVariable === leftValue) && not(right.toVariable === rightValue)
        )

        Stream((Seq(ProgramFormula(left.toVariable), ProgramFormula(right.toVariable)), f))
      }

      // Prioritize changes that touch only one of the two expressions.
      newOutput match {
        case ListLiteral(s, _) =>
          (leftValue match {
            case ListLiteral(lv, _) =>
              if (s.startsWith(lv)) {
                Stream((Seq(ProgramFormula(leftValue), ProgramFormula(ListLiteral(s.drop(lv.length), tps(0)))), Formula()))
              } else Stream.empty
            case _ => Stream.empty
          }) #::: (
            rightValue match {
              case ListLiteral(rv, _) =>
                if (s.endsWith(rv)) {
                  Stream((Seq(ProgramFormula(ListLiteral(s.take(s.length - rv.length), tps(0))), ProgramFormula(rightValue)), Formula()))
                } else Stream.empty
              case _ => Stream.empty
            }
            ) #::: defaultCase
        case newOut: Variable => defaultCase

        case l@Let(vd, value, newbody) =>
          /* Copy and paste, insertion, replacement:
        *  => A single let(v, newText, newbody) with a single occurrence of v in newbody
        *  Clone and paste
        *  => A double let(clone, oldText, let(paste, clone, newbody)) with two occurrences of clone in newbody
        *  Cut and paste
        *  => A double let(cut, "", let(paste, clone, newbody)) with one occurrences of paste in newbody
        *  Delete
        *  => A single let(delete, "", newbody) with a single occurrence of delete in newbody
        **/
          ???
      }
    }

    // Concat definition in inox
    val funDef = mkFunDef(identifier)("A"){ case Seq(tA) =>
      (Seq("left" :: T(list)(tA), "right" :: T(list)(tA)),
        T(list)(tA),
        { case Seq(left, right) =>
          if_(left.isInstOf(T(cons)(tA))) {
            let("c"::T(cons)(tA), left.asInstOf(T(cons)(tA)))(c =>
              ADT(T(cons)(tA),
                Seq(c.getField(head), E(identifier)(tA)(c.getField(tail), right)))
            )
          } else_ {
            right
          }
        })
    }
  }

  /** Lense-like list concat, with the possibility of changing the mapping lambda. */
  case object StringConcatReverser extends Reverser {
    import StringConcatExtended._
    import Utils._
    val identifier = FreshIdentifier("tmpstringconcat")

    @inline def charType(a: Char): Int =
      if(a.isUpper) 1 else if(a.isLower) 2 else if(a.isSpaceChar) 5 else if(a == '\n' || a == '\r') 10 else 4 // 4 for punctuation.

    @inline def differentCharType(a: Char, b: Char): Int = {
      Math.abs(charType(a) - charType(b))
    }

    @inline def typeJump(a: String, b: String): Int = {
      (if(a.nonEmpty && b.nonEmpty)
        differentCharType(a(a.length - 1), b(0))
      else 0) // We mostly insert into empty strings if available.
    }

    def put(tps: Seq[Type])(originalArgsValues: Seq[Expr], newOutputProgram: ProgramFormula)(implicit cache: Cache, symbols: Symbols): Stream[(Seq[ProgramFormula], Formula)] = {
      val newOutput = newOutputProgram.expr
      val leftValue = originalArgsValues.head
      val rightValue = originalArgsValues.tail.head

      def leftCase(s: String):  Stream[(Seq[ProgramFormula], Formula)] = {
        Log.prefix("Testing left:") := (leftValue match {
          case StringLiteral(lv) =>
            (if (s.startsWith(lv)) {
              Stream((Seq(ProgramFormula(leftValue), ProgramFormula(StringLiteral(s.drop(lv.length)))), Formula()))
            } else Stream.empty)  /:: Log.prefix("left worked:")
          case _ => Stream.empty
        })
      }

      def rightCase(s: String): Stream[(Seq[ProgramFormula], Formula)] = {
        Log.prefix("Testing right:") := (rightValue match {
          case StringLiteral(rv) =>
            (if (s.endsWith(rv)) {
              Stream((Seq(ProgramFormula(StringLiteral(s.take(s.length - rv.length))), ProgramFormula(rightValue)), Formula()))
            } else Stream.empty)  /:: Log.prefix("right worked:")
          case _ => Stream.empty
        })
      }

      def defaultCase(addMaybes: Boolean = false): Stream[(Seq[ProgramFormula], Formula)] = {
        val left = ValDef(FreshIdentifier("l", true), StringType, Set())
        val right = ValDef(FreshIdentifier("r", true), StringType, Set())
        Log(s"String default case: ${left.id} + ${right.id} == $newOutput:")

        val newConstraint = (newOutput === FunctionInvocation(identifier, tps, Seq(left.toVariable, right.toVariable))
        &<>& (if(addMaybes)
          E(maybe)(left.toVariable === leftValue) && E(maybe)(right.toVariable === rightValue)
        else BooleanLiteral(true)
        ) // Maybe use what is below for faster convergence?
        //            (if(addMaybes) not(left.toVariable === leftValue) && not(right.toVariable === rightValue)
          )
        val newVarsInConstraint = exprOps.variablesOf(newConstraint).map(_.toVal)
        val f = Formula(unknownConstraints = newConstraint)

        Stream((Seq(ProgramFormula(left.toVariable), ProgramFormula(right.toVariable)), f))
      }

      // Prioritize changes that touch only one of the two expressions.
      newOutputProgram match {
        case ProgramFormula.StringInsert(leftAfter, inserted, rightAfter) =>
          val StringLiteral(rightValue_s) = rightValue
          val StringLiteral(leftValue_s) = leftValue
          val totalValue_s = leftValue_s + rightValue_s
          // Put in first the one to which we removed the most chars.

          // leftValue + rightValue ==> leftAfter + inserted + rightAfter
          val res: List[((Seq[ProgramFormula], Formula), (Int, Int))] = (rightValue_s.endsWith(rightAfter)).flatMap{ // the insertion may have happened on the right.
            //  [  leftValue    ...     ][rightValue_s  ]
            //  [ leftAfter ....            ][rightAfter]

            // rightValue => (leftAfter \ leftValue) + inserted + rightAfter
            val newLeftAfter = if(leftValue_s.length < leftAfter.length) leftAfter.substring(leftValue_s.length) else ""
            val newRightAfter = rightAfter

            val newInsert = ProgramFormula.StringInsert(
              StringLiteral(newLeftAfter),
              StringLiteral(inserted),
              StringLiteral(newRightAfter))
            val newLeftValue = if(leftValue_s.length <= leftAfter.length) { // Nothing deleted.
              leftValue
            } else {
              StringLiteral(leftValue_s.substring(0, leftAfter.length))
            }
            val spaceWeight =
                typeJump(newLeftAfter, inserted) + typeJump(inserted, newRightAfter)
            val overwriteWeight = rightAfter.length - rightValue_s.length

            List((((Seq(ProgramFormula(newLeftValue), newInsert)), Formula()), (spaceWeight, overwriteWeight))) /: Log.Right_insert
          } ++
          (leftValue_s.startsWith(leftAfter)).flatMap{ // the insertion happened on the left.
            //  [  leftValue    ...     ][rightValue_s  ]
            //  [ leftAfter ...   ][        rightAfter  ]

            // leftValue => leftAfter + inserted + (rightAfter \ rightValue)

            val newLeftAfter = leftAfter
            val newRightAfter = if(rightValue_s.length < rightAfter.length) rightAfter.substring(0, rightAfter.length - rightValue_s.length) else ""

            val newInsert = ProgramFormula.StringInsert(
              StringLiteral(newLeftAfter),
              StringLiteral(inserted),
              StringLiteral(newRightAfter))

            val newRightValue = if(rightValue_s.length <= rightAfter.length) {
              rightValue
            } else {
              StringLiteral(rightValue_s.substring(rightValue_s.length - rightAfter.length))
            }
            val spaceWeight = typeJump(newLeftAfter, inserted) + typeJump(inserted, newRightAfter)
            val overwriteWeight = leftAfter.length - leftValue_s.length

            List(((Seq(newInsert, ProgramFormula(newRightValue)), Formula()), (spaceWeight, overwriteWeight))) /: Log.Left_insert
          }

          res.sortWith{ (x, y) =>
            if(y._2._2 < 0 && x._2._2 == 0) { // If did not change at one part, should take it first.
              false
            } else if(x._2._2 < 0 && y._2._2 == 0) {
              true
            } else {
              // It's a simple insertion. First, try to attach to where the characters are more alike.
              // Second, if equality, attach where the most characters have been removed.
              x._2._1 < y._2._1 || (x._2._1 == y._2._1 && x._2._2 < y._2._2)
            }}.map(_._1).toStream

        case ProgramFormula(StringLiteral(s), _) =>
          rightCase(s) append leftCase(s) append {
            defaultCase(false)
          }
        case ProgramFormula(StringConcat(StringLiteral(left), right), _) => // TODO !!
          val leftValue_s = asStr(leftValue)
          if(leftValue_s.startsWith(left)) {
            val newLeftValue = leftValue_s.substring(left.length)
            put(tps)(Seq(StringLiteral(newLeftValue), rightValue), newOutputProgram.subExpr(right))
          } else {
            ???
          }
        case ProgramFormula(StringConcat(left, StringLiteral(right)), _) => // TODO !!
          //leftValue = "Hello big " && rightValue == "beautiful world" && right = " world"
          val rightValue_s = asStr(rightValue)
          if(rightValue_s.endsWith(right)) {
            val newRightValue = rightValue_s.substring(0, rightValue_s.length - right.length)
            put(tps)(Seq(leftValue, StringLiteral(newRightValue)), newOutputProgram.subExpr(left))
          } else {
            ???
          }
        case _ =>
          defaultCase(true)
      }
    }

    // Concat definition in inox
    val funDef = mkFunDef(identifier)(){ case _ =>
      (Seq("a" :: StringType, "b" :: StringType),
        StringType,
        { case Seq(left, right) =>
          StringConcat(left, right)
        })
    }
  }

  case object SplitEvenReverser extends Reverser {
    import Utils._
    import ImplicitTuples._
    val identifier = splitEven
    val funDef: FunDef = mkFunDef(identifier)("A") { case Seq(tA) =>
      (Seq("l"::T(list)(tA)),
       T(tuple2)(T(list)(tA), T(list)(tA)),
        { case Seq(l) =>
          if_(l.isInstOf(T(cons)(tA))) {
            let("r"::T(tuple2)(T(list)(tA), T(list)(tA)),
              FunctionInvocation(identifier, Seq(tA), Seq(l.asInstOf(T(cons)(tA)).getField(tail))))(r =>
              ADT(T(tuple2)(T(list)(tA), T(list)(tA)), Seq(
                ADT(T(cons)(tA), Seq(l.asInstOf(T(cons)(tA)).getField(head), r.getField(_2))),
                r.getField(_1))))
          } else_ {
            ADT(T(tuple2)(T(list)(tA), T(list)(tA)), Seq(ADT(T(nil)(tA), Seq()), ADT(T(nil)(tA), Seq())))
          }
        }
      )
    }
    def put(tpes: Seq[Type])(originalArgsValues: Seq[Expr], newOutput: ProgramFormula)(implicit cache: Cache, symbols: Symbols): Stream[(Seq[ProgramFormula], Formula)] = {
      ???
    }
  }

  // Merge two sorted lists.
  case object MergeReverser extends Reverser {
    import Utils._
    val identifier = merge
    val funDef : FunDef = mkFunDef(identifier)("A") { case Seq(tA) =>
      (Seq("left":: T(list)(tA), "right":: T(list)(tA), "comp" :: FunctionType(Seq(tA, tA), Int32Type)),
        T(list)(tA),
        { case Seq(left, right, comp) =>
          if_(left.isInstOf(T(cons)(tA))) {
            if_(right.isInstOf(T(cons)(tA))) {
              let("leftcons"::T(cons)(tA), left.asInstOf(T(cons)(tA)))( leftcons =>
                let("rightcons"::T(cons)(tA), right.asInstOf(T(cons)(tA)))( rightcons =>
                  if_(Application(comp, Seq(leftcons.getField(head), rightcons.getField(head))) <= IntLiteral(0)) {
                    ADT(T(cons)(tA), Seq(leftcons.getField(head), FunctionInvocation(identifier, Seq(tA), Seq(leftcons.getField(tail), right, comp))))
                  } else_ {
                    ADT(T(cons)(tA), Seq(rightcons.getField(head), FunctionInvocation(identifier, Seq(tA), Seq(left, rightcons.getField(tail), comp))))
                  }
                )
              )
            } else_ {
              left
            }
          } else_ {
            right
          }
        })
    }
    def put(tpes: Seq[Type])(originalArgsValues: Seq[Expr],
                             newOutput: ProgramFormula)(implicit cache: Cache, symbols: Symbols): Stream[(Seq[ProgramFormula], Formula)] = {
      ???
    }
  }

  case object SortWithReverser extends Reverser {
    import Utils._
    val identifier = sortWith
    import InoxConvertible._

    val funDef: inox.trees.FunDef = mkFunDef(identifier)("A"){ case Seq(tA) =>
      (Seq("in" :: T(list)(tA), "comp" :: FunctionType(Seq(tA, tA), Int32Type)),
      T(list)(tA),
      { case Seq(input, comp) =>
          if_(input.isInstOf(T(nil)(tA)) || input.asInstOf(T(cons)(tA)).getField(tail).isInstOf(T(nil)(tA))) {
            input
          } else_ {
            let("r"::T(tuple2)(T(list)(tA), T(list)(tA)),
              FunctionInvocation(splitEven, Seq(tA), Seq(input)))( r=>
              FunctionInvocation(merge, Seq(tA), Seq(
                FunctionInvocation(sortWith, Seq(tA), Seq(r.getField(_1), comp)),
                FunctionInvocation(sortWith, Seq(tA), Seq(r.getField(_2), comp)),
                comp))
            )
          }
      })
    }

    def put(tpes: Seq[Type])(originalArgsValues: Seq[Expr], newOutput: ProgramFormula)(implicit cache: Cache, symbols: Symbols): Stream[(Seq[ProgramFormula], Formula)] = {
      ???
    }
  }
}
