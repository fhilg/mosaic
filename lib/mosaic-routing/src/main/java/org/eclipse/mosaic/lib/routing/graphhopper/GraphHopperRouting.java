/*
 * Copyright (c) 2020 Fraunhofer FOKUS and others. All rights reserved.
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contact: mosaic@fokus.fraunhofer.de
 */

package org.eclipse.mosaic.lib.routing.graphhopper;

import org.eclipse.mosaic.lib.database.Database;
import org.eclipse.mosaic.lib.database.DatabaseUtils;
import org.eclipse.mosaic.lib.database.road.Connection;
import org.eclipse.mosaic.lib.database.road.Node;
import org.eclipse.mosaic.lib.enums.VehicleClass;
import org.eclipse.mosaic.lib.routing.CandidateRoute;
import org.eclipse.mosaic.lib.routing.RoutingCostFunction;
import org.eclipse.mosaic.lib.routing.RoutingPosition;
import org.eclipse.mosaic.lib.routing.RoutingRequest;
import org.eclipse.mosaic.lib.routing.graphhopper.algorithm.AlternativeRoutesRoutingAlgorithm;
import org.eclipse.mosaic.lib.routing.graphhopper.algorithm.DijkstraCamvitChoiceRouting;
import org.eclipse.mosaic.lib.routing.graphhopper.extended.ExtendedGraphHopper;
import org.eclipse.mosaic.lib.routing.graphhopper.util.GraphhopperToDatabaseMapper;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.graphhopper.GraphHopper;
import com.graphhopper.routing.Path;
import com.graphhopper.routing.QueryGraph;
import com.graphhopper.routing.util.BikeFlagEncoder;
import com.graphhopper.routing.util.CarFlagEncoder;
import com.graphhopper.routing.util.DefaultEdgeFilter;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.routing.weighting.TurnWeighting;
import com.graphhopper.storage.TurnCostExtension;
import com.graphhopper.storage.index.QueryResult;
import com.graphhopper.util.DistanceCalc;
import com.graphhopper.util.DistancePlaneProjection;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.PointList;
import com.graphhopper.util.shapes.GHPoint;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class GraphHopperRouting {

    /**
     * If the distance of the query position to the closest node is lower than this
     * value, then the closest node is used definitely as the source or target of the route.
     * If the distance is larger, the route may start or end on the connection the query is matched on.
     */
    public static final double TARGET_NODE_QUERY_DISTANCE = 1d;

    /**
     * If the requested target point is this X meters away from the last node of
     * the found route, another connection is added on which the target
     * point is matched on.
     */
    public static double TARGET_REQUEST_CONNECTION_THRESHOLD = 5d;

    /**
     * Sometimes edges are dead ends. In these cases routing fails and invalid
     * routes are returned. To omit this, we check if the distance between the route end
     * and the original query target lies within the given threshold.
     */
    private static final double MAX_DISTANCE_TO_TARGET = 500d;

    private final Logger log = LoggerFactory.getLogger(getClass());

    private GraphHopper ghApi;
    private final DistanceCalc distanceCalculation = new DistancePlaneProjection();

    private GraphhopperToDatabaseMapper graphMapper;
    private Database db;

    public GraphHopperRouting loadGraphFromDatabase(Database db) {
        this.db = db;

        //initialize reader and mapper for database import into graphhopper
        GraphLoader reader = new DatabaseGraphLoader(db);
        graphMapper = new GraphhopperToDatabaseMapper();

        ghApi = new ExtendedGraphHopper(reader, graphMapper).forDesktop();
        //we only need a encoder for speed,flag and turn costs for cars
        ghApi.setEncodingManager(EncodingManager.create(
                new CarFlagEncoder(5, 5, 127),
                new BikeFlagEncoder(4, 2, 0)
        ));

        //load graph from database
        ghApi.importOrLoad();

        return this;
    }

    public List<CandidateRoute> findRoutes(RoutingRequest routingRequest) {
        if (ghApi == null) {
            throw new IllegalStateException("Load database at first");
        }

        final FlagEncoder flagEncoder;
        if (routingRequest.getRoutingParameters().getVehicleClass() == VehicleClass.Bicycle) {
            flagEncoder = ghApi.getEncodingManager().getEncoder("bike");
        } else {
            flagEncoder = ghApi.getEncodingManager().getEncoder("car");
        }

        final GraphHopperWeighting graphhopperWeighting = new GraphHopperWeighting(flagEncoder, graphMapper);

        // if there is no cost function given (initial routes), use the default
        if (routingRequest.getRoutingParameters().getRoutingCostFunction() == null) {
            graphhopperWeighting.setRoutingCostFunction(RoutingCostFunction.Default);
        } else {
            graphhopperWeighting.setRoutingCostFunction(routingRequest.getRoutingParameters().getRoutingCostFunction());
        }

        final RoutingPosition source = routingRequest.getSource();
        final RoutingPosition target = routingRequest.getTarget();

        final QueryResult querySource = createQueryForSource(source, flagEncoder);
        final QueryResult queryTarget = createQueryForTarget(target, flagEncoder);

        if (querySource.getClosestEdge() == null || queryTarget.getClosestEdge() == null) {
            log.warn("Could not find a route from {} to {}", routingRequest.getSource(), routingRequest.getTarget());
            return Lists.newArrayList();
        }

        final QueryGraph queryGraph = new QueryGraph(ghApi.getGraphHopperStorage());
        queryGraph.lookup(querySource, queryTarget);

        final TurnWeighting weighting = new TurnWeightingOptional(
                graphhopperWeighting, (TurnCostExtension) queryGraph.getExtension(),
                routingRequest.getRoutingParameters().isConsiderTurnCosts(),
                routingRequest.getRoutingParameters().getRestrictionCosts()
        );

        // create algorithm
        final AlternativeRoutesRoutingAlgorithm algo = new DijkstraCamvitChoiceRouting(queryGraph, weighting);
        algo.setRequestAlternatives(routingRequest.getRoutingParameters().getNumAlternativeRoutes());

        final List<Path> paths = new ArrayList<>();

        // Calculates all paths and returns the best one
        paths.add(algo.calcPath(querySource.getClosestNode(), queryTarget.getClosestNode()));

        //add alternative paths to path set
        paths.addAll(algo.getAlternativePaths());

        final Set<String> duplicateSet = new HashSet<>();
        final List<CandidateRoute> result = new ArrayList<>();

        // convert paths to routes
        for (final Path path : paths) {
            final CandidateRoute route = convertPath(path, queryGraph, querySource, queryTarget, target);
            if (route != null
                    && !route.getConnectionIds().isEmpty()
                    && checkForDuplicate(route, duplicateSet)
                    && checkRouteOnRequiredSourceConnection(route, source)) {
                result.add(route);
            } else if (route != null && log.isDebugEnabled()) {
                log.debug("Path is invalid and will be ignored [" + StringUtils.join(route.getConnectionIds(), ",") + "]");
            }
        }
        return result;
    }

    private QueryResult createQueryForTarget(RoutingPosition target, FlagEncoder flagEncoder) {
        final EdgeFilter toEdgeFilter = createEdgeFilterForRoutingPosition(target, flagEncoder);
        QueryResult queryTarget = ghApi.getLocationIndex().findClosest(target.getPosition().getLatitude(), target.getPosition().getLongitude(), toEdgeFilter);
        if (target.getConnectionId() != null) {
            return fixQueryResultIfNoClosestEdgeFound(queryTarget, target, flagEncoder);
        } else {
            return fixQueryResultIfSnappedPointIsCloseToTowerNode(queryTarget, target);
        }
    }

    private QueryResult createQueryForSource(RoutingPosition source, FlagEncoder flagEncoder) {
        final EdgeFilter fromEdgeFilter = createEdgeFilterForRoutingPosition(source, flagEncoder);
        QueryResult querySource = ghApi.getLocationIndex().findClosest(source.getPosition().getLatitude(), source.getPosition().getLongitude(), fromEdgeFilter);
        if (source.getConnectionId() != null) {
            querySource = fixQueryResultIfSnappedPointIsTowerNode(querySource, source, fromEdgeFilter);
            return fixQueryResultIfNoClosestEdgeFound(querySource, source, flagEncoder);
        } else {
            return fixQueryResultIfSnappedPointIsCloseToTowerNode(querySource, source);
        }
    }

    private QueryResult fixQueryResultIfSnappedPointIsCloseToTowerNode(QueryResult queryResult, RoutingPosition target) {
        if (queryResult.getSnappedPosition() == QueryResult.Position.TOWER || target.getConnectionId() != null) {
            return queryResult;
        }
        Node closestNode = graphMapper.toNode(queryResult.getClosestNode());
        /* If the query result is snapped to an edge, but the matched node is very close to the query, than we use the actual closest node
         * as the target of the route.*/
        if (closestNode != null && target.getPosition().distanceTo(closestNode.getPosition()) < TARGET_NODE_QUERY_DISTANCE) {
            queryResult.setSnappedPosition(QueryResult.Position.TOWER);
        }
        return queryResult;
    }

    private QueryResult fixQueryResultIfNoClosestEdgeFound(QueryResult queryResult, RoutingPosition routingPosition, FlagEncoder flagEncoder) {
        if (queryResult.getClosestEdge() == null) {
            log.warn("Wrong routing request: The from-connection {} does not fit with the given position {}", routingPosition.getConnectionId(), routingPosition.getPosition());
            return ghApi.getLocationIndex().findClosest(
                    routingPosition.getPosition().getLatitude(), routingPosition.getPosition().getLongitude(), DefaultEdgeFilter.allEdges(flagEncoder)
            );
        }
        return queryResult;
    }

    private QueryResult fixQueryResultIfSnappedPointIsTowerNode(QueryResult queryResult, RoutingPosition routingPosition, EdgeFilter fromEdgeFilter) {
        /* If the requested position is in front or behind the edge it is mapped either on the start or end of the edge (one of the tower nodes).
         * As a result, the resulting route can bypass turn restrictions in very rare cases. To avoid this, we choose an alternative
         * node based on the queried connection.*/
        if (queryResult.getSnappedPosition() == QueryResult.Position.TOWER) {
            // use the node before target node (index -2) as the alternative query node to find a QueryResult _on_ the connection.
            Node alternativeQueryNode = DatabaseUtils.getNodeByIndex(db.getConnection(routingPosition.getConnectionId()), -2);
            if (alternativeQueryNode != null) {
                return ghApi.getLocationIndex().findClosest(
                        alternativeQueryNode.getPosition().getLatitude(), alternativeQueryNode.getPosition().getLongitude(), fromEdgeFilter
                );
            }
        }
        return queryResult;
    }

    /**
     * Checks the {@param duplicateSet} whether it contains the {@param route}'s nodeIdList.
     *
     * @param route        Route to check for duplicate.
     * @param duplicateSet Set of node Ids.
     * @return True, if not duplicate in the set.
     */
    private boolean checkForDuplicate(CandidateRoute route, Set<String> duplicateSet) {
        String nodeIdList = StringUtils.join(route.getConnectionIds(), ",");
        return duplicateSet.add(nodeIdList);
    }

    private EdgeFilter createEdgeFilterForRoutingPosition(final RoutingPosition position, final FlagEncoder flagEncoder) {
        if (position.getConnectionId() == null) {
            return DefaultEdgeFilter.allEdges(flagEncoder);
        }
        final int forcedEdge = graphMapper.fromConnection(db.getConnection(position.getConnectionId()));
        if (forcedEdge < 0) {
            return DefaultEdgeFilter.allEdges(flagEncoder);
        }
        return edgeState -> edgeState.getEdge() == forcedEdge;
    }

    private CandidateRoute convertPath(Path newPath, QueryGraph queryGraph, QueryResult source, QueryResult target, RoutingPosition targetPosition) {
        PointList pointList = newPath.calcPoints();
        GHPoint pathTarget = Iterables.getLast(pointList);
        GHPoint origTarget = new GHPoint(targetPosition.getPosition().getLatitude(), targetPosition.getPosition().getLongitude());
        double distanceToOriginalTarget = distanceCalculation.calcDist(pathTarget.lat, pathTarget.lon, origTarget.lat, origTarget.lon);
        if (distanceToOriginalTarget > MAX_DISTANCE_TO_TARGET) {
            return null;
        }
        Iterator<EdgeIteratorState> edgesIt = newPath.calcEdges().iterator();
        if (!edgesIt.hasNext()) {
            return null;
        }

        List<String> pathConnections = new ArrayList<>();

        while (edgesIt.hasNext()) {
            EdgeIteratorState ghEdge = edgesIt.next();

            /*
             * If the requested source or target point is in the middle of the road, an artificial node
             * (and artificial edges) is created in the QueryGraph. As a consequence, the first
             * and/or last edge of the route might be such virtual edge. We use the queried source
             * and target to extract the original edge where the requested points have been matched on.
             */
            if (queryGraph.isVirtualEdge(ghEdge.getEdge())) {
                if (pathConnections.isEmpty() && queryGraph.isVirtualNode(source.getClosestNode())) {
                    ghEdge = queryGraph.getOriginalEdgeFromVirtNode(source.getClosestNode());
                } else if (!edgesIt.hasNext() && queryGraph.isVirtualNode(target.getClosestNode())) {
                    ghEdge = queryGraph.getOriginalEdgeFromVirtNode(target.getClosestNode());
                } else {
                    continue;
                }
            }

            Connection con = graphMapper.toConnection(ghEdge.getEdge());
            if (con != null) {
                /*
                 * In some cases, virtual edges are created at the target even though they are only some
                 * centimeters away from the requested node. In that case, we would have an unwanted
                 * last connection at the end of the route, which is eliminated here.
                 */
                boolean lastConnectionStartsAtTarget = !edgesIt.hasNext()
                        && targetPosition.getConnectionId() == null
                        && targetPosition.getPosition().distanceTo(con.getFrom().getPosition()) < TARGET_REQUEST_CONNECTION_THRESHOLD;
                if (lastConnectionStartsAtTarget) {
                    continue;
                }

                pathConnections.add(con.getId());

                if (Double.isInfinite(newPath.getWeight())) {
                    log.warn(
                            "Something went wrong during path search: The found route has infinite weight. Maybe there's a turn restriction or unconnected "
                                    + "sub-graphs in the network. Route will be ignored.");
                    return null;
                }
            } else {
                log.debug(String.format("A connection could be resolved by internal ID %d.", ghEdge.getEdge()));
            }
        }

        return new CandidateRoute(pathConnections, newPath.getDistance(), newPath.getTime() / (double) 1000);
    }

    private boolean checkRouteOnRequiredSourceConnection(CandidateRoute route, RoutingPosition source) {
        if (source.getConnectionId() != null) {
            return source.getConnectionId().equals(route.getConnectionIds().get(0));
        }
        return true;
    }

}
