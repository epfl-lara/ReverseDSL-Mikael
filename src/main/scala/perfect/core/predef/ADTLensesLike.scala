package perfect.core
package predef


/**
  * Created by Mikael on 15/05/2017.
  */
trait ADTLensesLike { self: ProgramUpdater with ContExps with Lenses =>
  trait ADTExtractor {
    def unapply(e: Exp): Option[(Seq[Exp], (Seq[Exp]) => Exp)]
    def isSame(e: Exp, g: Exp): Boolean
  }

  trait ADTSelectorExtractor {
    def unapply(e: Exp): Option[(Exp, (Exp) => Exp, Seq[Var], Int)]
  }

  class ADTLensLike(ADT: ADTExtractor, ADTSelector: ADTSelectorExtractor) extends SemanticLens {
    override def put(in: ContExp, out: ContExp)(implicit symbols: Symbols, cache: Cache): Stream[ContExp] = {
      in.exp match {
        case inExp@ADT(argsIn, builder) =>
          out.simplifiedExpr match {
            case outExpr@ADT(argsOut, outBuilder) if ADT.isSame(inExp, outExpr) && !in.getFunctionValue.contains(outExpr) => // Same type ! Maybe the arguments will change or move.
              val argsInOptValue = in.getFunctionValue match {
                case Some(ADT(argsInValue, _)) => argsInValue.map(x => Some(x))
                case _ => argsIn.map(_ => None)
              }

              val seqOfStreamSolutions = argsIn.zip(argsInOptValue).zip(argsOut).map { case ((aFun, aFunVal), newOutArg) =>
                repair(ContExp(aFun, in.context).withComputedValue(aFunVal), out.subExpr(newOutArg))
              }
              val streamOfSeqSolutions = inox.utils.StreamUtils.cartesianProduct(seqOfStreamSolutions)
              for {seq <- streamOfSeqSolutions
                   (newArgs, assignments) = ContExp.combineResults(seq)
              } yield {
                ContExp(builder(newArgs), assignments)
              }
            case Var(_) | ADT(_, _) =>
              Stream(out)

            case a =>
              //Log(s"[Warning] Don't know how to handle this case : $a is supposed to be put in place of a ${tp}")
              Stream(out)
          }

        case as@ADTSelector(adt, selectorBuilder, vrs, index) =>
         in.maybeEval(adt) match {
            case Some(ADT(args, builder)) =>
              val constraints = vrs.zipWithIndex.map {
                case (vd, i) => vd -> (if (i == index) StrongValue(out.exp) else OriginalValue(args(i)))
              }.toMap

              for {pf <- repair(in.subExpr(adt),
                out.subExpr(builder(vrs)) combineWith Cont(constraints))} yield {
                pf.wrap(x => selectorBuilder(x))
              }
            case _ => Stream.empty
          }

        case _ => Stream.empty
      }
    }
  }
}
