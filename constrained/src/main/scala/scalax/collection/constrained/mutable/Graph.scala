package scalax.collection.constrained
package mutable

import java.io.{ObjectInputStream, ObjectOutputStream}

import scala.language.{higherKinds, postfixOps}
import scala.collection.Set
import scala.collection.generic.{CanBuildFrom, Growable, Shrinkable}
import scala.collection.mutable.{Builder, Cloneable, ListBuffer, Set => MutableSet}
import scala.reflect.ClassTag

import scalax.collection.{Graph => CommonGraph, GraphTraversalImpl}
import scalax.collection.GraphEdge.{EdgeLike, EdgeCompanionBase}
import scalax.collection.GraphPredef.{EdgeLikeIn, Param, InParam, OutParam,
                                      NodeParam, OuterNode, InnerNodeParam, OuterEdge, InnerEdgeParam}
import scalax.collection.mutable.{ArraySet, BuilderImpl}
import scalax.collection.config.AdjacencyListArrayConfig

import scalax.collection.constrained.{Graph => CGraph, GraphLike => CGraphLike}
import PreCheckFollowUp._
import generic.GraphConstrainedCompanion
import config.GenConstrainedConfig

class GraphBuilder[N,
                   E[X] <: EdgeLikeIn[X],
                   GC[N,E[X] <: EdgeLikeIn[X]] <: CGraph[N,E] with CGraphLike[N,E,GC]]
      (companion: GraphConstrainedCompanion[GC])
      (implicit edgeT: ClassTag[E[N]],
       config: GenConstrainedConfig)
  extends BuilderImpl[N,E,GC]
{
  def result: This =
    companion.from(nodes, edges)(edgeT, config.asInstanceOf[companion.Config])
}
trait GraphLike[N,
                E[X] <: EdgeLikeIn[X],
               +This[X, Y[X]<:EdgeLikeIn[X]] <: GraphLike[X,Y,This] with Graph[X,Y]]
	extends scalax.collection.mutable.GraphLike[N, E, This]
	with	  scalax.collection.constrained.GraphLike[N, E, This]
  with    Growable  [Param[N,E]]
	with	  Shrinkable[Param[N,E]] 
	with	  Cloneable [Graph[N,E]] 
  with    Mutable
{ selfGraph: // This[N,E] => see https://youtrack.jetbrains.com/issue/SCL-13199
             This[N,E] with GraphLike[N,E,This] with Graph[N,E]=>
  trait NodeSet extends super.NodeSet {
    /** generic constrained subtraction */
    protected def checkedRemove(node: NodeT, ripple: Boolean): Boolean = {
      def remove = withoutChecks { subtract(node, ripple,  minus, minusEdges) }
      var removed, handle = false
      if (checkSuspended) removed = remove
      else {
        val preCheckResult = preSubtract(node.asInstanceOf[self.NodeT], ripple)
        preCheckResult.followUp match { 
          case Complete  => removed = remove
          case PostCheck => removed = remove
            if (removed &&
                ! postSubtract(selfGraph, Set(node), Set.empty[E[N]], preCheckResult)) {
              handle = true
              selfGraph  += node.value
              selfGraph ++= node.edges
            }
          case Abort     => handle = true
        }
      }
      if (handle) onSubtractionRefused(Set(node.asInstanceOf[self.NodeT]),
                                       Set.empty[self.EdgeT], selfGraph)
      removed && ! handle
    }
    override def remove      (node: NodeT) = checkedRemove(node, true)
    override def removeGently(node: NodeT) = checkedRemove(node, false)
  }
  /** generic checked addition */
  protected def checkedAdd[G >: This[N,E]]
            ( contained: => Boolean,
              preAdd:    => PreCheckResult,
              copy:      => G,
              nodes:     => Traversable[N],
              edges:     => Traversable[E[N]] ): This[N,E] =
  { if (contained) this
    else if (checkSuspended) copy.asInstanceOf[This[N,E]]
    else {
      var graph = this
      var handle = false
      val preCheckResult = preAdd
      preCheckResult.followUp match { 
        case Complete  => graph = copy.asInstanceOf[This[N,E]]
        case PostCheck => graph = copy.asInstanceOf[This[N,E]]
          if (! postAdd(graph, nodes, edges, preCheckResult)) {
            handle = true
            graph = this
          }
        case Abort     => handle = true
      }
      if (handle) onAdditionRefused(nodes, edges, this)
      graph
    }
  }
  override def + (node: N) = 
    checkedAdd (contained = nodes contains Node(node),
                preAdd    = preAdd(node),
                copy      = clone += node,
                nodes     = Set(node),
                edges     = Set.empty[E[N]])

  override def ++=(elems: TraversableOnce[Param[N,E]]): this.type =
  { elems match {
      case elems: Traversable[Param[N,E]] => 
        val p = new Param.Partitions[N,E](elems)
        val inFiltered = p.toInParams.toSet.filter(elem => ! (this contains elem)).toSeq 
        var handle = false
        val preCheckResult = preAdd(inFiltered: _*)
        if (preCheckResult.abort)
          handle = true
        else {
          withoutChecks { super.++=(elems) }
          if (preCheckResult.postCheck) {
            val (outerNodes, outerEdges) = (p.toOuterNodes, p.toOuterEdges)
            if (! postAdd(this, outerNodes, outerEdges, preCheckResult)) {
              handle = true
              withoutChecks {
                super.--=(allNodes(outerNodes, outerEdges) map (n => OuterNode(n)))
              }
            }
          }
        }
        if (handle) onAdditionRefused(p.toOuterNodes, p.toOuterEdges, this)

      case _ => throw new IllegalArgumentException("Traversable expected")
    }
    this
  } 
  override def --=(elems: TraversableOnce[Param[N,E]]): this.type =
  { lazy val p = partition(elems)
    lazy val (outerNodes, outerEdges) = (p.toOuterNodes.toSet, p.toOuterEdges.toSet)
    def innerNodes =
       (outerNodes.view map (this find _) filter (_.isDefined) map (_.get) force).toSet
    def innerEdges =
       (outerEdges.view map (this find _) filter (_.isDefined) map (_.get) force).toSet

    type C_NodeT = self.NodeT
    type C_EdgeT = self.EdgeT
    var handle = false
    val preCheckResult = preSubtract(innerNodes.asInstanceOf[Set[C_NodeT]],
                                     innerEdges.asInstanceOf[Set[C_EdgeT]], true)
    preCheckResult.followUp match { 
      case Complete  => withoutChecks { super.--=(elems) }
      case PostCheck =>
        val subtractables = elems filter (this contains _)
        withoutChecks { super.--=(subtractables) }
        if (! postSubtract(this, outerNodes, outerEdges, preCheckResult)) {
          handle = true
          withoutChecks { super.++=(subtractables) }
        }
      case Abort     => handle = true
    }
    if (handle) onSubtractionRefused(innerNodes.asInstanceOf[Set[C_NodeT]],
                                     innerEdges.asInstanceOf[Set[C_EdgeT]], this)
    this
  }
}

import scalax.collection.constrained.generic.{MutableGraphCompanion}

trait Graph[N, E[X] <: EdgeLikeIn[X]]
	extends	scalax.collection.mutable.Graph[N,E]
  with    scalax.collection.constrained.Graph[N,E]
	with	  GraphLike[N, E, Graph]
{
  override def empty: Graph[N,E] = Graph.empty[N,E](edgeT, config)
}
object Graph
  extends MutableGraphCompanion[Graph]
{
  override def empty[N, E[X] <: EdgeLikeIn[X]](implicit edgeT: ClassTag[E[N]],
                                               config: Config): Graph[N,E] =
    DefaultGraphImpl.empty[N,E](edgeT, config)

  override protected[collection]
  def fromUnchecked[N, E[X] <: EdgeLikeIn[X]]
     (nodes: Traversable[N],
      edges: Traversable[E[N]])
     (implicit edgeT: ClassTag[E[N]],
      config: Config): DefaultGraphImpl[N,E] =
    DefaultGraphImpl.fromUnchecked[N,E](nodes, edges)(edgeT, config)

  override def from [N, E[X] <: EdgeLikeIn[X]]
     (nodes: Traversable[N],
      edges: Traversable[E[N]])
     (implicit edgeT: ClassTag[E[N]],
      config: Config): Graph[N,E] =
    DefaultGraphImpl.from[N,E](nodes, edges)(edgeT, config)

  // TODO: canBuildFrom
}
abstract class DefaultGraphImpl[N, E[X] <: EdgeLikeIn[X]]
   (iniNodes: Traversable[N]    = Set[N](),
    iniEdges: Traversable[E[N]] = Set[E[N]]())
   (implicit override val edgeT: ClassTag[E[N]],
    _config: DefaultGraphImpl.Config with GenConstrainedConfig with AdjacencyListArrayConfig)
  extends Graph[N,E]
  with    AdjacencyListGraph[N,E,DefaultGraphImpl]
  with    GraphTraversalImpl[N,E]
{
  override final val graphCompanion = DefaultGraphImpl
  protected type Config = DefaultGraphImpl.Config
  override final def config = _config.asInstanceOf[graphCompanion.Config with Config]

  class NodeSet extends super[AdjacencyListGraph].NodeSet with super[Graph].NodeSet
  
  @inline final protected def newNodeSet: NodeSetT = new NodeSet
  @transient protected[this] var _nodes: NodeSetT = newNodeSet
  @inline override final def nodes = _nodes

  @transient protected[this] var _edges: EdgeSetT = new EdgeSet
  @inline override final def edges = _edges  

  initialize(iniNodes, iniEdges)

  @inline final override def empty = DefaultGraphImpl.empty(edgeT, config)
  @inline final override def clone(): this.type = {
    graphCompanion.from[N,E](nodes.toOuter, edges.toOuter)(
                             edgeT, config).asInstanceOf[this.type]
  }
  @SerialVersionUID(8082L)
  protected class NodeBase(value: N, hints: ArraySet.Hints)
    extends InnerNodeImpl(value, hints)
    with    InnerNodeTraversalImpl
  type NodeT = NodeBase
  @inline final protected def newNodeWithHints(n: N, h: ArraySet.Hints) = new NodeT(n, h)
}
object DefaultGraphImpl extends MutableGraphCompanion[DefaultGraphImpl]
{
  override def empty[N, E[X] <: EdgeLikeIn[X]](implicit edgeT: ClassTag[E[N]],
                                               config: Config) =
    from(Set.empty[N], Set.empty[E[N]])(edgeT, config)

  override protected[collection]
  def fromUnchecked[N, E[X] <: EdgeLikeIn[X]](nodes: Traversable[N],
                                              edges: Traversable[E[N]])
                                             (implicit edgeT: ClassTag[E[N]],
                                              config: Config): DefaultGraphImpl[N,E] =
    new UserConstrainedGraphImpl[N,E](nodes, edges)(edgeT, config)

  override def from [N, E[X] <: EdgeLikeIn[X]]
     (nodes: Traversable[N],
      edges: Traversable[E[N]])
     (implicit edgeT: ClassTag[E[N]],
      config: Config) : DefaultGraphImpl[N,E] =
  { val existElems = nodes.nonEmpty || edges.nonEmpty 
    var preCheckResult = PreCheckResult(Abort)
    if (existElems) {
      val emptyGraph = empty[N,E](edgeT, config)
      val constraint = config.constraintCompanion(emptyGraph)
      preCheckResult = constraint.preCreate(nodes, edges)
      if (preCheckResult.abort) { 
        constraint onAdditionRefused (nodes, edges, emptyGraph) 
        return emptyGraph
      }
    }
    val newGraph = fromUnchecked[N,E](nodes, edges)(edgeT, config)
    if (existElems) {
      val emptyGraph = empty[N,E](edgeT, config)
      val constraint = config.constraintCompanion(emptyGraph)
      var handle = false
      preCheckResult.followUp match {
        case Complete  =>
        case PostCheck => handle = ! constraint.postAdd(newGraph, nodes, edges, preCheckResult)
        case Abort     => handle = true
      }
      if (handle) {
        constraint.onAdditionRefused(nodes, edges, newGraph)
        emptyGraph
      } else
        newGraph
    } else
      newGraph
  }
  // TODO canBuildFrom
}
@SerialVersionUID(7701L)
class UserConstrainedGraphImpl[N, E[X] <: EdgeLikeIn[X]]
   (iniNodes: Traversable[N]    = Nil,
    iniEdges: Traversable[E[N]] = Nil)
   (implicit override val edgeT: ClassTag[E[N]],
    _config: DefaultGraphImpl.Config)
  extends DefaultGraphImpl    [N,E](iniNodes, iniEdges)(edgeT, _config)
  with    UserConstrainedGraph[N,E]
{
  final override val self = this
  final override val constraintFactory = config.constraintCompanion
  final override val constraint  = constraintFactory(this)
  
  private def writeObject(out: ObjectOutputStream): Unit = serializeTo(out)

  private def readObject(in: ObjectInputStream): Unit = {
    _nodes = newNodeSet
    _edges = new EdgeSet
    initializeFrom(in, _nodes, _edges)
  }
}
