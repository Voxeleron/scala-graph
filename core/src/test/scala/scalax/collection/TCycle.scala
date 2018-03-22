package scalax.collection

import language.{higherKinds, postfixOps}
import collection.Set
import collection.immutable.{Range, SortedSet}
import collection.mutable.{Set => MutableSet}

import GraphPredef._, GraphEdge._
import generic.GraphCoreCompanion

import edge._, edge.WBase._, edge.LBase._, edge.WLBase._
import io._

import org.scalatest.refspec.RefSpec
import org.scalatest.{Matchers, Suites}
import org.scalatest.matchers.{MatchResult, Matcher}
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

@RunWith(classOf[JUnitRunner])
class TCycleRootTest
    extends Suites(
      new TCycle[immutable.Graph](immutable.Graph),
      new TCycle[  mutable.Graph](  mutable.Graph))

trait CycleMatcher[N, E[X] <: EdgeLikeIn[X]] {
  protected type C = Graph[N, E]#Cycle

  def haveOneNodeSequenceOf(expected: Seq[N]*): Matcher[Option[C]] =
    Matcher { (ns: Option[C]) =>
      val found: Seq[N] = ns match {
        case None => Seq()
        case Some(path) => path.nodes.toSeq.map(_.value)
      }
      MatchResult(
        expected.contains(found),
        found + " has none of the sequences " + expected,
        found + " has one of the sequences " + expected
      )
    }
}

class TCycle[CC[N,E[X] <: EdgeLikeIn[X]] <: Graph[N,E] with GraphLike[N,E,CC]] (val factory: GraphCoreCompanion[CC])
	  extends	RefSpec
	  with Matchers {

  object `given some directed graphs` extends CycleMatcher[Int, DiEdge]  {
    
    val acyclic_1 = factory(1 ~> 2, 1 ~> 3, 2 ~> 3, 3 ~> 4)
    val acyclic_2 = factory(1~>2, 1~>3, 1~>4, 1~>5, 2~>3, 3~>7, 7~>4, 7~>8, 4~>5, 5~>6)
    
    def makeCyclic(acyclic: CC[Int,DiEdge], byEdge: DiEdge[Int]) = {
      val cyclic = acyclic + byEdge  
          (cyclic, cyclic get byEdge)
    }
    val (cyclic_1,  cyclicEdge_1 ) = makeCyclic(acyclic_1, 4~>2)
    val (cyclic_21, cyclicEdge_21) = makeCyclic(acyclic_2, 8~>3)
    val (cyclic_22, cyclicEdge_22) = makeCyclic(acyclic_2, 6~>1)
  
    def c_1 (outer: Int) = cyclic_1  get outer
    def c_21(outer: Int) = cyclic_21 get outer
    def c_22(outer: Int) = cyclic_22 get outer
  
    def `the cycle returned by 'findCycle' contains the expected nodes` {
      (acyclic_1 get 1 findCycle) should be (None)
      c_1(2).findCycle should haveOneNodeSequenceOf(
        Seq(2, 3, 4, 2))
  
      (acyclic_2 get 1 findCycle) should be (None)
      c_21(1).findCycle should haveOneNodeSequenceOf(
        Seq(3, 7, 8, 3))
      c_22(1).findCycle should haveOneNodeSequenceOf(
        Seq(1, 5, 6, 1),
        Seq(1, 4, 5, 6, 1),
        Seq(1, 3, 7, 4, 5, 6, 1),
        Seq(1, 2, 3, 7, 4, 5, 6, 1))
      c_22(4).findCycle should haveOneNodeSequenceOf(
        Seq(5, 6, 1, 5),
        Seq(4, 5, 6, 1, 4),
        Seq(4, 5, 6, 1, 3, 7, 4),
        Seq(4, 5, 6, 1, 2, 3, 7, 4))
      
      val g = {
        var i, j=0
        Graph.fill(5) { i+=1; j=i+1; i~>j }
      }
      val (g1, g2) = (g + 4~>2, g + 5~>2)
      val (gCycle_1, gCycle_2) = (g1 get 3 findCycle, g2 get 3 findCycle)
      gCycle_1 should haveOneNodeSequenceOf(Seq(3, 4,    2, 3))
      gCycle_2 should haveOneNodeSequenceOf(Seq(3, 4, 5, 2, 3))

      def fromEachNode[N,E[X] <: EdgeLikeIn[X]](noCycles: Set[N], cycle: Graph[N,E]#Cycle) {
        val g = cycle.nodes.head.containingGraph
        def outer(out: N) = g get out
        g.nodes foreach { n =>
          val found = n.findCycle
          if (noCycles contains n.value) found                   should be (None)
          else                          (found.get sameAs cycle) should be (true)
        }
      }
      fromEachNode(Set(5, 6), gCycle_1 get)
      fromEachNode(Set(   6), gCycle_2 get)
    }
    
    def `the cycle returned by 'findCycle' contains the expected edges` {
      acyclic_1.findCycle           should be (None)
       cyclic_1.findCycle.get.edges should contain (cyclicEdge_1)
  
      acyclic_2 .findCycle           should be (None)
       cyclic_21.findCycle.get.edges should contain (cyclicEdge_21)
       cyclic_22.findCycle.get.edges should contain (cyclicEdge_22)
    }
    
    def `'isCyclic' returns the expected result` {
      acyclic_1  should be ('isAcyclic)
       cyclic_1  should be ('isCyclic)

      acyclic_2  should be ('isAcyclic)
       cyclic_21 should be ('isCyclic)
       cyclic_22 should be ('isCyclic)
    }
    
    def `they are cyclic if they contain a self loop #76` {
      val loop = 1~>1
      acyclic_1 + loop should be ('isCyclic)
      
      val maybeCycle = factory(loop).findCycle
      maybeCycle should be ('isDefined)
      val cycle = maybeCycle.get
      cycle should be ('isValid)
    }
  }

  object `given some undirected graphs` extends CycleMatcher[Int, UnDiEdge] {
    
    val unDiAcyclic_1 = factory(1~2, 2~3)
    val unDiCyclic_1  = unDiAcyclic_1 + 1~3
    
    val unDiAcyclic_2 = factory(1~2, 1~3, 2~4, 2~5)
    val unDiCyclic_21 = unDiAcyclic_2 + 3~5
    val unDiCyclic_22 = unDiAcyclic_2 ++ List(3~6, 6~7, 7~4)

    val unDiCyclic_3 = factory() ++ Data.elementsOfUnDi_1

    def uc_1 (outer: Int) = unDiCyclic_1   get outer
    def uc_21(outer: Int) = unDiCyclic_21  get outer
    def uc_22(outer: Int) = unDiCyclic_22  get outer
    def uc_3 (outer: Int) = unDiCyclic_3   get outer

    def `the cycle returned by 'findCycle' contains the expected nodes` {
      (unDiAcyclic_1 get 1 findCycle) should be (None)
      uc_1(2).findCycle should haveOneNodeSequenceOf(
        Seq(2, 3, 1, 2),
        Seq(2, 1, 3, 2))
      (unDiAcyclic_2 get 1 findCycle) should be (None)
      uc_21(1).findCycle should haveOneNodeSequenceOf(
        Seq(1, 3, 5, 2, 1),
        Seq(1, 2, 5, 3, 1))
      uc_22(3).findCycle should haveOneNodeSequenceOf(
        Seq(3, 1, 2, 4, 7, 6, 3),
        Seq(3, 6, 7, 4, 2, 1, 3))
    }

    def `the cycle returned by 'findCycleContaining' contains the expected nodes` {
      unDiAcyclic_1.findCycleContaining(unDiAcyclic_1 get 1) should be (None)
      unDiCyclic_1.findCycleContaining(uc_1(2)) should haveOneNodeSequenceOf(
        Seq(2, 3, 1, 2),
        Seq(2, 1, 3, 2))
      unDiAcyclic_2.findCycleContaining(unDiAcyclic_2 get 1) should be (None)
      unDiCyclic_21.findCycleContaining(uc_21(1)) should haveOneNodeSequenceOf(
        Seq(1, 3, 5, 2, 1),
        Seq(1, 2, 5, 3, 1))
      unDiCyclic_21.findCycleContaining(uc_21(4)).get.nodes.toList should be (None)
      unDiCyclic_22.findCycleContaining(uc_22(3)) should haveOneNodeSequenceOf(
        Seq(3, 1, 2, 4, 7, 6, 3),
        Seq(3, 6, 7, 4, 2, 1, 3))
      unDiCyclic_22.findCycleContaining(uc_22(5)).get.nodes.toList should be (None)
      unDiCyclic_3.findCycleContaining(uc_3(2)) should haveOneNodeSequenceOf(
        Seq(2, 1, 3, 2))
      unDiCyclic_3.findCycleContaining(uc_3(1)) should haveOneNodeSequenceOf(
        Seq(1, 3, 2, 1),
        Seq(1, 3, 5, 1))
      unDiCyclic_3.findCycleContaining(uc_3(4)) should haveOneNodeSequenceOf(
        Seq(4),
        Seq(4, 5, 3, 4))
    }

  }
  object `given an undirected multigraph` {
    
    def `the cycle returned by 'findCycle' contains the expected edges` {
      val (e1, e2) = (WkUnDiEdge(1, 2)(0), WkUnDiEdge(1, 2)(1))
      val g = factory(e1, e2)
      val c = (g get 1).findCycle
      c should be ('isDefined)
      c.get.edges should (be (List(e1, e2)) or
                          be (List(e2, e1)))
    }
  }
  
  object `given a mixed graph` {
    
    def `the cycle returned by 'findCycle' contains the expected edges` {
      val g = factory(1 ~ 2, 1 ~> 2, 2 ~ 3)
      val cycleEdges = List(1 ~>2, 1 ~ 2)
      g.graphSize should be (3)
      g.nodes foreach { n =>
        val c = n.findCycle
        (n, c.isDefined) should be ((n, true))
        c.get.edges should (be (cycleEdges) or
                            be (cycleEdges.reverse))
      }
    }
  }
}