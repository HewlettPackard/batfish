package org.batfish.bddreachability;

import static org.batfish.common.util.CommonUtil.toImmutableMap;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.sf.javabdd.BDD;
import org.batfish.common.bdd.BDDPacket;
import org.batfish.z3.IngressLocation;
import org.batfish.z3.expr.StateExpr;
import org.batfish.z3.state.OriginateInterfaceLink;
import org.batfish.z3.state.OriginateVrf;
import org.batfish.z3.state.Query;
import org.batfish.z3.state.visitors.DefaultTransitionGenerator;

/**
 * A new reachability analysis engine using BDDs. The analysis maintains a graph that describes how
 * packets flow through the network and through logical phases of a router. The graph is similar to
 * the one generated by {@link DefaultTransitionGenerator} for reachability analysis using NOD. In
 * particular, the graph nodes are {@link StateExpr StateExprs} and the edges are mostly the same as
 * the NOD program rules/transitions. {@link BDD BDDs} label the nodes and edges of the graph. A
 * node label represent the set of packets that can reach that node, and an edge label represents
 * the set of packets that can traverse the edge. There is a single designated {@link Query}
 * StateExpr that we compute reachability sets (i.e. sets of packets that reach the query state).
 * The query state never has any out-edges, and has in-edges from the dispositions of interest.
 *
 * <p>The two main departures from the NOD program are: 1) ACLs are encoded as a single BDD that
 * labels an edge (rather than a series of states/transitions in NOD programs). 2) Source NAT is
 * handled differently -- we don't maintain separate original and current source IP variables.
 * Instead, we keep track of where/how the packet is transformed as it flows through the network,
 * and reconstruct it after the fact. This requires some work that can't be expressed in BDDs.
 *
 * <p>We currently implement backward all-pairs reachability. Forward reachability is useful for
 * questions with a tight source constraint, e.g. "find me packets send from node A that get
 * dropped". When reasoning about many sources simultaneously, we have to somehow remember the
 * source, which is very expensive for a large number of sources. For queries that have to consider
 * all packets that can reach the query state, backward reachability is much more efficient.
 */
public class BDDReachabilityAnalysis {
  private final BDDPacket _bddPacket;

  // preState --> postState --> predicate
  private final Map<StateExpr, Map<StateExpr, Edge>> _edges;

  // postState --> preState --> predicate
  private final Map<StateExpr, Map<StateExpr, Edge>> _reverseEdges;

  // stateExprs that correspond to the IngressLocations of interest
  private final ImmutableSet<StateExpr> _ingressLocationStates;

  private final BDD _queryHeaderSpaceBdd;

  private final Map<List<StateExpr>, BDD> _pathDB;

  BDDReachabilityAnalysis(
      BDDPacket packet,
      Set<StateExpr> ingressLocationStates,
      Map<StateExpr, Map<StateExpr, Edge>> edges,
      BDD queryHeaderSpaceBdd) {
    _bddPacket = packet;
    _edges = edges;
    _reverseEdges = computeReverseEdges(_edges);
    _ingressLocationStates = ImmutableSet.copyOf(ingressLocationStates);
    _queryHeaderSpaceBdd = queryHeaderSpaceBdd;
    //_pathDB = buildPathDB();
    _pathDB = null;
  }

  private static Map<StateExpr, Map<StateExpr, Edge>> computeReverseEdges(
      Map<StateExpr, Map<StateExpr, Edge>> edges) {
    Map<StateExpr, Map<StateExpr, Edge>> reverseEdges = new HashMap<>();
    edges.forEach(
        (preState, preStateOutEdges) ->
            preStateOutEdges.forEach(
                (postState, edge) ->
                    reverseEdges
                        .computeIfAbsent(postState, k -> new HashMap<>())
                        .put(preState, edge)));
    // freeze
    return toImmutableMap(
        reverseEdges, Entry::getKey, entry -> ImmutableMap.copyOf(entry.getValue()));
  }

  private Map<StateExpr, BDD> computeReverseReachableStates() {
    Map<StateExpr, BDD> reverseReachableStates = new HashMap<>();
    reverseReachableStates.put(Query.INSTANCE, _queryHeaderSpaceBdd);

    backwardFixpoint(reverseReachableStates);

    return ImmutableMap.copyOf(reverseReachableStates);
  }

  public Map<IngressLocation, BDD> findLoops() {
    Map<StateExpr, BDD> loopBDDs = new HashMap<>();
    // run DFS for each ingress location
    for (StateExpr node : _ingressLocationStates) {
      BDD loopBDD =
          findLoopsPerSource(node);
      if (loopBDD != null) loopBDDs.put(node, loopBDD);
    }
    // freeze
    return getIngressLocationBDDs(loopBDDs);
  }

  private BDD findLoopsPerSource(
      StateExpr root) {
    List<StateExpr> history = new ArrayList<>();
    Set<StateExpr> visitedNodes = new HashSet<>();
    List<BDD> historyBDD = new ArrayList<>();

    BDD symbolicPacket = _bddPacket.getFactory().one();
    history.add(root);
    historyBDD.add(_bddPacket.getFactory().one());
    visitedNodes.add(root);

    while (!history.isEmpty()) {
      StateExpr currentNode = history.get(history.size()-1);

      Map<StateExpr, Edge> postStateInEdges = _edges.get(currentNode);
      if (postStateInEdges != null) {
        Iterator<StateExpr> iterator = postStateInEdges.keySet().iterator();
        StateExpr nextNode = iterator.next();
        Edge edge = postStateInEdges.get(nextNode);
        BDD nextSymbolicPacket = edge.traverseForward(symbolicPacket);

        if (!nextSymbolicPacket.isZero()) {
          if (visitedNodes.contains(nextNode)) {
            // find a loop
          } else {
            history.add();
          }
        }
      } else {

      }
    }


    if (visitedNodes.contains(currentNode)) {
      // Loop detected
      return symbolicPacket;
    } else {
      history.add(currentNode);
      visitedNodes.add(currentNode);
      Map<StateExpr, Edge> postStateInEdges = _edges.get(currentNode);
      if (postStateInEdges != null) {
        for (StateExpr nextNode : postStateInEdges.keySet()) {
          Edge edge = postStateInEdges.get(nextNode);
          BDD nextSymbolicPacket = edge.traverseForward(symbolicPacket);

          if (!nextSymbolicPacket.isZero()) {
            BDD bdd = findLoopsPerSource(nextSymbolicPacket, history, visitedNodes, nextNode);
            if (bdd != null) {
              return bdd;
            }
          }
        }
      }
      history.remove(history.size()-1);
      visitedNodes.remove(currentNode);
    }
    return null;
  }

  public Map<IngressLocation, BDD> getLoopBDDs() {
    Map<StateExpr, BDD> loopBDDs = new HashMap<>();

    _pathDB
        .entrySet()
        .stream()
//        .filter(
//            entry -> {
//              List<StateExpr> path = entry.getKey();
//
//              StateExpr lastHop = path.get(path.size() - 1);
//              return path.subList(0, path.size()-1).contains(lastHop);
//            })
        .forEach(
            entry -> {
              StateExpr firstNode = entry.getKey().get(0);
              loopBDDs.putIfAbsent(firstNode, entry.getValue());
            });

    return getIngressLocationBDDs(loopBDDs);
  }

  public Map<List<StateExpr>, BDD> buildPathDB() {
    HashMap<List<StateExpr>, BDD> pathDB = new HashMap<>();
    // run DFS for each ingress location
    for (StateExpr node : _ingressLocationStates) {
      symbolicRun(_bddPacket.getFactory().one(), new ArrayList<>(), new HashSet<>(), node, pathDB);
    }
    // freeze
    return toImmutableMap(pathDB, entry -> ImmutableList.copyOf(entry.getKey()), Entry::getValue);
  }

  private void symbolicRun(
      BDD symbolicPacket,
      List<StateExpr> history,
      Set<StateExpr> visitedNodes,
      StateExpr currentNode,
      Map<List<StateExpr>, BDD> pathDB) {
    if (visitedNodes.contains(currentNode)) {
      // Loop detected
      history.add(currentNode);
      pathDB.put(ImmutableList.copyOf(history), symbolicPacket);
      history.remove(history.size()-1);
    } else {
      history.add(currentNode);
      visitedNodes.add(currentNode);
      Map<StateExpr, Edge> postStateInEdges = _edges.get(currentNode);
      if (postStateInEdges != null) {
        for (StateExpr nextNode : postStateInEdges.keySet()) {
          Edge edge = postStateInEdges.get(nextNode);
          BDD nextSymbolicPacket = edge.traverseForward(symbolicPacket);

          if (!nextSymbolicPacket.isZero()) {
            symbolicRun(nextSymbolicPacket, history, visitedNodes, nextNode, pathDB);
          } else {
            //pathDB.put(ImmutableList.copyOf(history), symbolicPacket);
          }
        }
      } else {
        //pathDB.put(ImmutableList.copyOf(history), symbolicPacket);
      }
      history.remove(history.size()-1);
      visitedNodes.remove(currentNode);
    }
  }

  private void backwardFixpoint(Map<StateExpr, BDD> reverseReachableStates) {
    Set<StateExpr> dirty = ImmutableSet.copyOf(reverseReachableStates.keySet());

    while (!dirty.isEmpty()) {
      Set<StateExpr> newDirty = new HashSet<>();

      dirty.forEach(
          postState -> {
            Map<StateExpr, Edge> postStateInEdges = _reverseEdges.get(postState);
            if (postStateInEdges == null) {
              // postState has no in-edges
              return;
            }

            BDD postStateBDD = reverseReachableStates.get(postState);
            postStateInEdges.forEach(
                (preState, edge) -> {
                  BDD result = edge.traverseBackward(postStateBDD);
                  if (result.isZero()) {
                    return;
                  }

                  // update preState BDD reverse-reachable from leaf
                  BDD oldReach = reverseReachableStates.get(preState);
                  BDD newReach = oldReach == null ? result : oldReach.or(result);
                  if (oldReach == null || !oldReach.equals(newReach)) {
                    reverseReachableStates.put(preState, newReach);
                    newDirty.add(preState);
                  }
                });
          });

      dirty = newDirty;
    }
  }

  private Map<StateExpr, BDD> reachableInNRounds(int numRounds) {
    BDD one = _bddPacket.getFactory().one();

    // All ingress locations are reachable in 0 rounds.
    Map<StateExpr, BDD> reachableInNRounds =
        toImmutableMap(_ingressLocationStates, Function.identity(), k -> one);

    for (int round = 0; !reachableInNRounds.isEmpty() && round < numRounds; round++) {
      reachableInNRounds = propagate(reachableInNRounds);
    }
    return reachableInNRounds;
  }

  /*
   * Detect infinite routing loops in the network.
   */
  public Map<IngressLocation, BDD> detectLoops() {
    /*
     * Run enough rounds to exceed the max TTL (255). It takes at most 5 iterations to go between
     * hops:
     * PreInInterface -> PostInVrf -> PreOutVrf -> PreOutEdge -> PreOutEdgePostNat -> PreInInterface
     *
     * Since we don't model TTL, all packets on loops will loop forever. But most paths will stop
     * long before numRounds. What's left will be a few candidate location/headerspace pairs that
     * may be on loops. In practice this is most likely way more iterations than necessary.
     */
    int numRounds = 256 * 5;
    Map<StateExpr, BDD> reachableInNRounds = reachableInNRounds(numRounds);

    /*
     * Identify which of the candidates are actually on loops
     */
    Map<StateExpr, BDD> loopBDDs =
        reachableInNRounds
            .entrySet()
            .stream()
            .filter(entry -> confirmLoop(entry.getKey(), entry.getValue()))
            .collect(Collectors.toMap(Entry::getKey, Entry::getValue));

    /*
     * Run backward to find the ingress locations/headerspaces that lead to loops.
     */
    backwardFixpoint(loopBDDs);

    /*
     * Extract the ingress location BDDs.
     */
    return getIngressLocationBDDs(loopBDDs);
  }

  private Map<StateExpr, BDD> propagate(Map<StateExpr, BDD> bdds) {
    BDD zero = _bddPacket.getFactory().zero();
    Map<StateExpr, BDD> newReachableInNRounds = new HashMap<>();
    bdds.forEach(
        (source, sourceBdd) ->
            _edges
                .getOrDefault(source, ImmutableMap.of())
                .forEach(
                    (target, edge) -> {
                      BDD targetBdd = newReachableInNRounds.getOrDefault(target, zero);
                      BDD newTragetBdd = targetBdd.or(edge.traverseForward(sourceBdd));
                      if (!newTragetBdd.isZero()) {
                        newReachableInNRounds.put(target, newTragetBdd);
                      }
                    }));
    return newReachableInNRounds;
  }

  /**
   * Run BFS from one step past the initial state. Each round, check if the initial state has been
   * reached yet.
   */
  private boolean confirmLoop(StateExpr stateExpr, BDD bdd) {
    Map<StateExpr, BDD> reachable = propagate(ImmutableMap.of(stateExpr, bdd));
    Set<StateExpr> dirty = new HashSet<>(reachable.keySet());

    BDD zero = _bddPacket.getFactory().zero();
    while (!dirty.isEmpty()) {
      Set<StateExpr> newDirty = new HashSet<>();

      dirty.forEach(
          preState -> {
            Map<StateExpr, Edge> preStateOutEdges = _edges.get(preState);
            if (preStateOutEdges == null) {
              // preState has no out-edges
              return;
            }

            BDD preStateBDD = reachable.get(preState);
            preStateOutEdges.forEach(
                (postState, edge) -> {
                  BDD result = edge.traverseForward(preStateBDD);
                  if (result.isZero()) {
                    return;
                  }

                  // update postState BDD reverse-reachable from leaf
                  BDD oldReach = reachable.getOrDefault(postState, zero);
                  BDD newReach = oldReach == null ? result : oldReach.or(result);
                  if (oldReach == null || !oldReach.equals(newReach)) {
                    reachable.put(postState, newReach);
                    newDirty.add(postState);
                  }
                });
          });

      dirty = newDirty;
      if (dirty.contains(stateExpr)) {
        if (!reachable.get(stateExpr).and(bdd).isZero()) {
          return true;
        }
      }
    }
    return false;
  }

  public BDDPacket getBDDPacket() {
    return _bddPacket;
  }

  public Map<IngressLocation, BDD> getIngressLocationReachableBDDs() {
    Map<StateExpr, BDD> reverseReachableStates = computeReverseReachableStates();
    return getIngressLocationBDDs(reverseReachableStates);
  }

  private Map<IngressLocation, BDD> getIngressLocationBDDs(
      Map<StateExpr, BDD> reverseReachableStates) {
    BDD zero = _bddPacket.getFactory().zero();
    return _ingressLocationStates
        .stream()
        .collect(
            ImmutableMap.toImmutableMap(
                BDDReachabilityAnalysis::toIngressLocation,
                root -> reverseReachableStates.getOrDefault(root, zero)));
  }

  @VisibleForTesting
  static IngressLocation toIngressLocation(StateExpr stateExpr) {
    Preconditions.checkArgument(
        stateExpr instanceof OriginateVrf || stateExpr instanceof OriginateInterfaceLink);

    if (stateExpr instanceof OriginateVrf) {
      OriginateVrf originateVrf = (OriginateVrf) stateExpr;
      return IngressLocation.vrf(originateVrf.getHostname(), originateVrf.getVrf());
    } else {
      OriginateInterfaceLink originateInterfaceLink = (OriginateInterfaceLink) stateExpr;
      return IngressLocation.interfaceLink(
          originateInterfaceLink.getHostname(), originateInterfaceLink.getIface());
    }
  }

  @VisibleForTesting
  Map<StateExpr, Map<StateExpr, Edge>> getEdges() {
    return _edges;
  }
}
