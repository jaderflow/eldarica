/**
 * Copyright (c) 2022 Zafer Esen, Philipp Ruemmer. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * * Neither the name of the authors nor the names of their
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package lazabs.horn.symex

import ap.api.SimpleAPI.ProverStatus
import ap.{DialogUtil, SimpleAPI}
import ap.parser.IAtom
import ap.terfor.preds.Predicate
import ap.terfor.{ConstantTerm, Term}
import ap.terfor.conjunctions.Conjunction
import ap.terfor.substitutions.ConstantSubst
import ap.theories.{Theory, TheoryCollector}
import ap.types.MonoSortedPredicate
import lazabs.horn.bottomup.{HornClauses, NormClause, RelationSymbol}
import lazabs.horn.bottomup.HornClauses.{Clause, ConstraintClause}
import lazabs.horn.bottomup.HornPredAbs.predArgumentSorts
import lazabs.horn.bottomup.Util.{Dag, DagEmpty, DagNode}
import lazabs.horn.preprocessor.HornPreprocessor.{CounterExample, Solution}

import collection.mutable.{
  HashMap => MHashMap,
  ListBuffer => MList,
  Queue => MQueue,
  HashSet => MHashSet
}

object Symex {
  sealed trait Result
  case object Unknown extends Result {
    override def toString: String = "UNKNOWN"
  }
  case class Sat(solution: Solution) extends Result {
    override def toString: String =
      "SAT\n\nSolution\n" + "-" * 80 + "\n" +
        solution.mkString("\n") + "\n" + "-" * 80
  }
  case class Unsat[CC](cex: Dag[(IAtom, CC)]) extends Result {
    override def toString: String =
      "UNSAT\n\nCounterexample\n" + "-" * 80 + "\n" + DialogUtil.asString(
        cex.prettyPrint) + "\n" + "-" * 80
  }

  class SymexException(msg: String) extends Exception(msg)

  var printInfo = true

  def printInfo(s: String, newLine: Boolean = true): Unit = {
    if (printInfo)
      print(s + (if (newLine) "\n" else ""))
  }

  implicit def toUnitClause(normClause: NormClause)(
      implicit sf:                      SymexSymbolFactory): UnitClause = {
    normClause match {
      case NormClause(constraint, Nil, (rel, 0)) // a fact
          if rel.pred != HornClauses.FALSE =>
        UnitClause(rel, constraint, isPositive = true, headOccInConstraint = 0)
      case NormClause(constraint, Seq((rel, 0)), (headRel, 0))
          if headRel.pred == HornClauses.FALSE =>
        UnitClause(rel, constraint, isPositive = false, headOccInConstraint = 0)
      case _ =>
        throw new UnsupportedOperationException(
          "Trying to create a unit clause from a non-unit clause: " + normClause)
    }
  }

}

abstract class Symex[CC](iClauses:    Iterable[CC])(
    implicit clause2ConstraintClause: CC => ConstraintClause
) extends SubsumptionChecker
    with ConstraintSimplifier {

  import Symex._

  val theories: Seq[Theory] = {
    val coll = new TheoryCollector
    coll addTheory ap.types.TypeTheory
    for (c <- iClauses)
      c collectTheories coll
    coll.theories
  }

  if (theories.nonEmpty)
    printInfo("Theories: " + (theories mkString ", ") + "\n")

  val preds: Set[Predicate] =
    (for (c   <- iClauses.iterator;
          lit <- (Iterator single c.head) ++ c.body.iterator;
          p = lit.predicate)
      yield p).toSet

  // We keep a prover initialized with all the symbols running, which we will
  // use to check the satisfiability of constraints.
  // Note that the prover must be manually shut down for clean-up.
  implicit val prover: SimpleAPI = SimpleAPI.spawn // todo: shut down after use
  prover.addTheories(theories)
  prover.addRelations(preds)

  // Keeps track of all the terms and adds new symbols to the prover
  // must be initialized after normClauses are generated.
  implicit val symex_sf: SymexSymbolFactory =
    new SymexSymbolFactory(theories, prover)

  val relationSymbols: Map[Predicate, RelationSymbol] =
    (for (p <- preds) yield (p -> RelationSymbol(p))).toMap

  // translate clauses to internal form
  val normClauses: Seq[(NormClause, CC)] = (
    for (c <- iClauses) yield {
      lazabs.GlobalParameters.get.timeoutChecker()
      (NormClause(c, p => relationSymbols(p)), c)
    }
  ).toSeq

  val normClauseToCC: Map[NormClause, CC] = normClauses.toMap

  val clausesWithRelationInBody: Map[RelationSymbol, Seq[NormClause]] =
    (for (rel <- relationSymbols.values) yield {
      (rel,
       normClauses.filter(_._1.body.exists(brel => brel._1 == rel)).map(_._1))
    }).toMap

  val clausesWithRelationInHead: Map[RelationSymbol, Seq[NormClause]] =
    (for (rel <- relationSymbols.values) yield {
      (rel, normClauses.filter(_._1.head._1 == rel).map(_._1))
    }).toMap

  val predicatesAndMaxOccurrences: Map[Predicate, Int] =
    for ((rp, clauses) <- clausesWithRelationInHead)
      yield {
        val clausesWithPred = clauses.map(_.head._2)
        val maxOcc = clausesWithPred match {
          case Nil => 0
          case seq => seq.max
        }
        (rp.pred, maxOcc)
      }

  // this must be done before we can use the symbol factory during resolution
  symex_sf.initialize(predicatesAndMaxOccurrences.toSet)

  val (facts: Seq[UnitClause], factToNormClause: Map[UnitClause, NormClause]) = {
    val (facts, factToNormClause) =
      (for ((normClause, _) <- normClauses
            if normClause.body.isEmpty && normClause.head._1.pred != HornClauses.FALSE)
        yield {
          (toUnitClause(normClause), (toUnitClause(normClause), normClause))
        }).unzip
    (facts, factToNormClause.toMap)
  }

  val (goals: Seq[UnitClause], goalToNormClause: Map[UnitClause, NormClause]) = {
    val (goals, goalToNormClause) =
      (for ((normClause, _) <- normClauses
            if normClause.head._1.pred == HornClauses.FALSE && normClause.body.length == 1)
        yield {
          (toUnitClause(normClause), (toUnitClause(normClause), normClause))
        }).unzip
    (goals, goalToNormClause.toMap)
  }

  /**
   * Returns optionally a nucleus and electrons that can be hyper-resolved into
   * another electron. The sequence indices i of returned electrons correspond
   * to atoms at nucleus.body(i). Returns None if the search space is exhausted.
   */
  def getClausesForResolution: Option[(NormClause, Seq[UnitClause])]

  /**
   * Applies hyper-resolution using nucleus and electrons and returns the
   * resulting unit clause.
   * @note This implementation only infers positive CUCs.
   * @note "FALSE :- constraint" is considered a unit clause.
   * @todo Move out of this class.
   */
  private def hyperResolve(nucleus:   NormClause,
                           electrons: Seq[UnitClause]): UnitClause = {

    assert(electrons.length == nucleus.body.length)

    val constraintFromElectrons =
      for (((rp, occ), ind) <- nucleus.body.zipWithIndex) yield {
        assert(rp == electrons(ind).rs) // todo:
        if ((electrons(ind)
              .constraintAtOcc(occ)
              .constants intersect nucleus.head._1.arguments.head.toSet) nonEmpty)
          electrons(ind).constraintAtOcc(occ + 1)
        else
          electrons(ind).constraintAtOcc(occ)
      }

    val unsimplifiedConstraint =
      Conjunction.conj(constraintFromElectrons ++ Seq(nucleus.constraint),
                       symex_sf.order)

    val localSymbols =
      (unsimplifiedConstraint.constants -- nucleus.headSyms)
        .map(_.asInstanceOf[Term])

    val simplifiedConstraint =
      simplifyConstraint(unsimplifiedConstraint,
                         localSymbols,
                         reduceBeforeSimplification = true)

    //new UnitClause(nucleus.head._1, newConstraint, isPositive = true) // todo: currently only positive CUCs are generated
    UnitClause(rs = nucleus.head._1,
               constraint = simplifiedConstraint,
               isPositive = true,
               headOccInConstraint = nucleus.head._2)
  }

  val unitClauseDB = new UnitClauseDB(relationSymbols.values.toSet)

  def solve(): Result = {
    var result: Result = null

    val touched = new MHashSet[NormClause]
    facts.foreach(fact => touched += factToNormClause(fact))

    // start traversal
    var ind = 0
    while (result == null) {
      ind += 1
      printInfo(ind + ".", false)
      getClausesForResolution match {
        case Some((nucleus, electrons)) => {
          touched += nucleus
          val newElectron = hyperResolve(nucleus, electrons)
          printInfo("\t" + nucleus + "\n  +\n\t" + electrons.mkString("\n\t"))
          printInfo("  =\n\t" + newElectron)
          // todo: only do a single feasibility check
          if (hasContradiction(newElectron)) { // false :- true
            unitClauseDB.add(newElectron, (nucleus, electrons))
            result = Unsat(buildCounterExample(newElectron))
          } else if (constraintIsFalse(newElectron)) {
            printInfo("")
            handleFalseConstraint(nucleus, electrons)
          } else if (checkForwardSubsumption(newElectron, unitClauseDB)) {
            printInfo("subsumed by existing unit clauses.")
            handleForwardSubsumption(nucleus, electrons)
          } else {
            val backSubsumed =
              checkBackwardSubsumption(newElectron, unitClauseDB)
            if (backSubsumed nonEmpty) {
              printInfo(
                "subsumes " + backSubsumed.size + " existing unit clause(s)_...",
                newLine = false)
              handleBackwardSubsumption(backSubsumed)
            }
            if (unitClauseDB.add(newElectron, (nucleus, electrons))) {
              printInfo("\n  (Added to database.)\n")
              handleNewUnitClause(newElectron)
            } else {
              printInfo("\n  (Derived clause already exists in the database.)")
              handleForwardSubsumption(nucleus, electrons)
            }
          }
        }
        case None => // nothing left to explore, the clauses are SAT.
          printInfo("\t(Search space exhausted.)\n")

          val untouchedClauses = normClauses.map(_._1).toSet -- touched
          if (untouchedClauses nonEmpty) {
            assert(untouchedClauses.forall(clause =>
              clause.head._1.pred == HornClauses.FALSE))
            printInfo("\t(Dangling assertions detected, checking those too.)")
            for (clause <- untouchedClauses if result == null) {
              val cuc = // for the purpose of checking feasibility
                if (clause.body.isEmpty) {
                  new UnitClause(RelationSymbol(HornClauses.FALSE),
                                 clause.constraint,
                                 false)
                } else toUnitClause(clause)
              unitClauseDB.add(cuc, (clause, Nil))
              if (hasContradiction(cuc)) {
                result = Unsat(buildCounterExample(cuc))
              }
            }
            if (result == null) { // none of the assertions failed, so this is SAT
              result = Sat(buildSolution())
            }
          } else {
            result = Sat(buildSolution())
          }
        case other =>
          throw new SymexException(
            "Cannot hyper-resolve clauses: " + other.toString)
      }
    }
    result
  }

  // methods handling derivation of useless clauses (merge?)
  def handleForwardSubsumption(nucleus:   NormClause,
                               electrons: Seq[UnitClause]): Unit

  def handleBackwardSubsumption(subsumed: Set[UnitClause]): Unit

  def handleNewUnitClause(clause: UnitClause): Unit

  def handleFalseConstraint(nucleus:   NormClause,
                            electrons: Seq[UnitClause]): Unit

  private def buildSolution(): Solution = {
    for ((pred, rs) <- relationSymbols if pred != HornClauses.FALSE)
      yield {
        val predCucs = unitClauseDB.inferred(rs).getOrElse(Nil)
        val predSolution = symex_sf.reducer(Conjunction.TRUE)(
          Conjunction.disj(predCucs.map(_.constraint), symex_sf.order))
        // substitute args such as p_0_0 with _0
        val argSubst = for ((arg, i) <- rs.arguments(0) zipWithIndex)
          yield (arg, symex_sf.genConstant("_" + i))
        // substitute local symbols such as p_c_0_0 with c0
        val localSymbols = predSolution.constants -- rs.arguments(0)
        val localSymbolSubst = for ((c, i) <- localSymbols zipWithIndex)
          yield (c, symex_sf.genConstant("c" + i))

        val backtranslatedPredSolution =
          ConstantSubst((argSubst ++ localSymbolSubst).toMap, symex_sf.order)(
            predSolution)
        (pred, prover.asIFormula(backtranslatedPredSolution))
      }
  }

  /**
   * Returns a counterexample DAG given the last derived unit clause as root,
   * i.e., FALSE :- TRUE.
   */
  private def buildCounterExample(root: UnitClause): Dag[(IAtom, CC)] = {

    /**
     * Uses Kahn's algorithm to generate a topologically sorted sequence of
     * nodes.
     * @param source   Node to start the sequence.
     * @param getChildren A mapping from a node to its children.
     * @return a topologically sorted sequence of nodes and a backmapping from
     *         nodes to their indices.
     * @note Assumes a single source node in the DAG - this works for the purpose
     *       of computing counterexamples because it is always rooted at the
     *       failing assertion clause (i.e., FALSE :- TRUE).
     */
    def constructTopo[T](source:      T,
                         getChildren: T => Seq[T]): (Seq[T], Map[T, Int]) = {

      // compute in-degree of each node
      val inDegrees = new MHashMap[T, Int]
      @annotation.tailrec
      def computeInDegrees(node:              T,
                           remainingChildren: List[T],
                           toBeComputed:      List[T]): Unit = {
        remainingChildren match {
          case child :: rest =>
            inDegrees += ((child,
                           inDegrees
                             .getOrElse(child, 0) + 1))
            computeInDegrees(node, rest, child :: toBeComputed)
          case Nil =>
            toBeComputed match {
              case n :: rest =>
                computeInDegrees(n, getChildren(n).toList, rest)
              case Nil => // nothing left to do
            }
        }
      }
      computeInDegrees(source, getChildren(source).toList, Nil)

      val backMapping = new MHashMap[T, Int]
      val nodeQueue   = new MQueue[T]
      val nodesSorted = new MList[T]
      nodeQueue enqueue source
      while (nodeQueue nonEmpty) {
        val cur = nodeQueue.dequeue
        nodesSorted += cur
        backMapping += ((cur, nodesSorted.length - 1))
        getChildren(cur) match {
          case children: Seq[T] =>
            for (child <- children) {
              val inDegree = inDegrees(child) - 1
              if (inDegree == 0)
                nodeQueue enqueue child
              inDegrees += ((child, inDegree))
            }
          case Nil => // nothing
        }
      }
      (nodesSorted, backMapping.toMap)
    }

    def getChildren(node: UnitClause): Seq[UnitClause] = {
      unitClauseDB.parentsOption(node) match {
        case Some((_, cucs)) => cucs
        case None            => Nil
      }
    }
    val (cucsSorted, cucIndices) = constructTopo(root, getChildren)

    val cucAtoms = new MHashMap[UnitClause, IAtom]
    def computeAtoms(headAtom: IAtom, cuc: UnitClause): Unit = {
      cucAtoms += ((cuc, headAtom))
      unitClauseDB.parentsOption(cuc) match {
        case None =>
        //
        case Some((nucleus, electrons)) =>
          import prover._
          scope {
            !!(asIFormula(nucleus.constraint))
            !!(headAtom.args === nucleus.headSyms)
            for ((electron, occ) <- electrons zip nucleus.body.map(_._2))
              !!(asIFormula(electron.constraintAtOcc(occ)))
            val pRes = ???
            assert(pRes == ProverStatus.Sat)
            withCompleteModel { comp =>
              for (((rs, occ), electron) <- nucleus.body zip electrons) {
                val atom = IAtom(rs.pred,
                                 rs.arguments(occ)
                                   .map(arg => comp.evalToTerm(arg)))
                cucAtoms += ((electron, atom))
              }
            }
          }
          for (electron <- electrons)
            computeAtoms(cucAtoms(electron), electron)
      }
    }
    computeAtoms(IAtom(HornClauses.FALSE, Nil), root)

    var dag: Dag[(IAtom, CC)] = DagEmpty
    for ((cuc, ind) <- (cucsSorted zipWithIndex).reverse) {
      unitClauseDB.parentsOption(cuc) match {
        case Some((nucleus, electrons)) =>
          val nuc  = normClauseToCC(nucleus)
          val atom = cucAtoms(cuc)
          val children =
            electrons.map(electron => cucIndices(electron) - ind)
          dag = DagNode((atom, nuc), children.toList, dag)
        case None =>
          // no parent -> an entry clause / fact
          dag = DagNode((cucAtoms(cuc), normClauseToCC(factToNormClause(cuc))),
                        Nil,
                        dag)
      }
    }
    dag
  }

  // true if cuc = "FALSE :- c" and c is satisfiable, false otherwise.
  private def hasContradiction(cuc: UnitClause)(
      implicit prover:              SimpleAPI): Boolean = {
    ((cuc.rs.pred == HornClauses.FALSE) || (!cuc.isPositive)) &&
    prover.scope {
      import prover._
      addAssertion(cuc.constraint)
      ??? match { // check if cuc constraint is satisfiable
        case ProverStatus.Valid | ProverStatus.Sat     => true
        case ProverStatus.Invalid | ProverStatus.Unsat => false
        case s => {
          Console.err.println(
            "Warning: Verification of constraint was not possible:"
          )
          Console.err.println(cuc)
          Console.err.println("Checker said: " + s)
          true
        }
      }
    }
  }

  private def constraintIsFalse(cuc: UnitClause)(
      implicit prover:               SimpleAPI): Boolean = {
    prover.scope {
      import prover._
      addAssertion(cuc.constraint)
      val result = ??? match { // check if cuc constraint is satisfiable
        case ProverStatus.Valid | ProverStatus.Sat     => false // ok
        case ProverStatus.Invalid | ProverStatus.Unsat => true
        case s => {
          Console.err.println(
            "Warning: Verification of constraint was not possible:"
          )
          Console.err.println(cuc)
          Console.err.println("Checker said: " + s)
          false
        }
      }
      result
    }
  }
}

// todo: ctor order is different with traits, syntax for ordering ctors
