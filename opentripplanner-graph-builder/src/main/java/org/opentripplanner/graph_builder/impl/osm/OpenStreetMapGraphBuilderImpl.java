/* This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public License
 as published by the Free Software Foundation, either version 3 of
 the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>. */

package org.opentripplanner.graph_builder.impl.osm;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Iterator;

import org.opentripplanner.common.model.P2;
import org.opentripplanner.graph_builder.model.osm.*;
import org.opentripplanner.graph_builder.services.GraphBuilder;
import org.opentripplanner.graph_builder.services.StreetUtils;
import org.opentripplanner.graph_builder.services.TurnRestriction;
import org.opentripplanner.graph_builder.services.TurnRestrictionType;
import org.opentripplanner.graph_builder.services.osm.CustomNamer;
import org.opentripplanner.graph_builder.services.osm.OpenStreetMapContentHandler;
import org.opentripplanner.graph_builder.services.osm.OpenStreetMapProvider;
import org.opentripplanner.routing.core.Edge;
import org.opentripplanner.routing.core.Graph;
import org.opentripplanner.routing.core.GraphVertex;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.core.Vertex;
import org.opentripplanner.routing.edgetype.EndpointVertex;
import org.opentripplanner.routing.edgetype.PlainStreetEdge;
import org.opentripplanner.routing.edgetype.StreetTraversalPermission;
import org.opentripplanner.routing.impl.DistanceLibrary;
import org.opentripplanner.routing.patch.Alert;
import org.opentripplanner.util.MapUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.LineString;

/**
 * Builds a street graph from OpenStreetMap data.
 * 
 */
public class OpenStreetMapGraphBuilderImpl implements GraphBuilder {

    private static Logger _log = LoggerFactory.getLogger(OpenStreetMapGraphBuilderImpl.class);

    private List<OpenStreetMapProvider> _providers = new ArrayList<OpenStreetMapProvider>();

    private Map<Object, Object> _uniques = new HashMap<Object, Object>();

    private WayPropertySet wayPropertySet = new WayPropertySet();

    private CustomNamer customNamer;

    /**
     * The source for OSM map data
     */
    public void setProvider(OpenStreetMapProvider provider) {
        _providers.add(provider);
    }

    /**
     * Multiple sources for OSM map data
     */
    public void setProviders(List<OpenStreetMapProvider> providers) {
        _providers.addAll(providers);
    }

    /**
     * Set the way properties from a {@link WayPropertySetSource} source.
     * 
     * @param source the way properties source
     */
    public void setDefaultWayPropertySetSource(WayPropertySetSource source) {
        wayPropertySet = source.getWayPropertySet();
    }

    @Override
    public void buildGraph(Graph graph) {
        Handler handler = new Handler();
        for (OpenStreetMapProvider provider : _providers) {
            _log.debug("gathering osm from provider: " + provider);
            provider.readOSM(handler);
        }
        _log.debug("building osm street graph");
        handler.buildGraph(graph);
    }

    @SuppressWarnings("unchecked")
    private <T> T unique(T value) {
        Object v = _uniques.get(value);
        if (v == null) {
            _uniques.put(value, value);
            v = value;
        }
        return (T) v;
    }

    public void setWayPropertySet(WayPropertySet wayDataSet) {
        this.wayPropertySet = wayDataSet;
    }

    public WayPropertySet getWayPropertySet() {
        return wayPropertySet;
    }

    private class Handler implements OpenStreetMapContentHandler {

        private Map<Long, OSMNode> _nodes = new HashMap<Long, OSMNode>();

        private Map<Long, OSMWay> _ways = new HashMap<Long, OSMWay>();

        private Map<Long, OSMRelation> _relations = new HashMap<Long, OSMRelation>();

        private Set<Long> _nodesWithNeighbors = new HashSet<Long>();

        private Map<Long, List<TurnRestrictionTag>> turnRestrictionsByFromWay = new HashMap<Long, List<TurnRestrictionTag>>();

        private Map<Long, List<TurnRestrictionTag>> turnRestrictionsByToWay = new HashMap<Long, List<TurnRestrictionTag>>();

        private Map<TurnRestrictionTag, TurnRestriction> turnRestrictionsByTag = new HashMap<TurnRestrictionTag, TurnRestriction>();

        /** The bike safety factor of the safest street */
        private double bestBikeSafety = 1;

        public void buildGraph(Graph graph) {
            // handle turn restrictions and road names in relations
            processRelations();

            // Remove all simple islands
            _nodes.keySet().retainAll(_nodesWithNeighbors);

            long wayIndex = 0;

            // figure out which nodes that are actually intersections
            Set<Long> possibleIntersectionNodes = new HashSet<Long>();
            Set<Long> intersectionNodes = new HashSet<Long>();
            for (OSMWay way : _ways.values()) {
                List<Long> nodes = way.getNodeRefs();
                for (long node : nodes) {
                    if (possibleIntersectionNodes.contains(node)) {
                        intersectionNodes.add(node);
                    } else {
                        possibleIntersectionNodes.add(node);
                    }
                }
            }
            GeometryFactory geometryFactory = new GeometryFactory();

            /* build an ordinary graph, which we will convert to an edge-based graph */
            ArrayList<Vertex> endpoints = new ArrayList<Vertex>();

            for (OSMWay way : _ways.values()) {

                if (wayIndex % 10000 == 0)
                    _log.debug("ways=" + wayIndex + "/" + _ways.size());
                wayIndex++;

                WayProperties wayData = wayPropertySet.getDataForWay(way);

                if (!way.hasTag("name")) {
                    String creativeName = wayPropertySet.getCreativeNameForWay(way);
                    if (creativeName != null) {
                        way.addTag("otp:gen_name", creativeName);
                    }
                }
                Set<Alert> note = wayPropertySet.getNoteForWay(way);

                StreetTraversalPermission permissions = getPermissionsForEntity(way,
                        wayData.getPermission());
                if (permissions == StreetTraversalPermission.NONE)
                    continue;

                List<Long> nodes = way.getNodeRefs();

                Vertex startEndpoint = null, endEndpoint = null;

                ArrayList<Coordinate> segmentCoordinates = new ArrayList<Coordinate>();

                /*
                 * Traverse through all the nodes of this edge. For nodes which are not shared with
                 * any other edge, do not create endpoints -- just accumulate them for geometry. For
                 * nodes which are shared, create endpoints and StreetVertex instances.
                 */

                Long startNode = null;
                OSMNode osmStartNode = null;
                for (int i = 0; i < nodes.size() - 1; i++) {
                    Long endNode = nodes.get(i + 1);
                    if (osmStartNode == null) {
                        startNode = nodes.get(i);
                        osmStartNode = _nodes.get(startNode);
                    }
                    OSMNode osmEndNode = _nodes.get(endNode);

                    if (osmStartNode == null || osmEndNode == null)
                        continue;

                    LineString geometry;

                    /*
                     * skip vertices that are not intersections, except that we use them for
                     * geometry
                     */
                    if (segmentCoordinates.size() == 0) {
                        segmentCoordinates.add(getCoordinate(osmStartNode));
                    }

                    if (intersectionNodes.contains(endNode) || i == nodes.size() - 2) {
                        segmentCoordinates.add(getCoordinate(osmEndNode));
                        geometry = geometryFactory.createLineString(segmentCoordinates
                                .toArray(new Coordinate[0]));
                        segmentCoordinates.clear();
                    } else {
                        segmentCoordinates.add(getCoordinate(osmEndNode));
                        continue;
                    }

                    /* generate endpoints */
                    if (startEndpoint == null) {
                        // first iteration on this way
                        String label = "osm node " + osmStartNode.getId();

                        startEndpoint = graph.getVertex(label);
                        if (startEndpoint == null) {
                            Coordinate coordinate = getCoordinate(osmStartNode);
                            startEndpoint = new EndpointVertex(label, coordinate.x, coordinate.y,
                                    label);
                            graph.addVertex(startEndpoint);
                            endpoints.add(startEndpoint);
                        }
                    } else {
                        startEndpoint = endEndpoint;
                    }

                    String label = "osm node " + osmEndNode.getId();
                    endEndpoint = graph.getVertex(label);
                    if (endEndpoint == null) {
                        Coordinate coordinate = getCoordinate(osmEndNode);
                        endEndpoint = new EndpointVertex(label, coordinate.x, coordinate.y, label);
                        graph.addVertex(endEndpoint);
                        endpoints.add(endEndpoint);
                    }

                    P2<PlainStreetEdge> streets = getEdgesForStreet(startEndpoint, endEndpoint,
                            way, i, permissions, geometry);

                    PlainStreetEdge street = streets.getFirst();

                    if (street != null) {
                        graph.addEdge(street);
                        double safety = wayData.getSafetyFeatures().getFirst();
                        street.setBicycleSafetyEffectiveLength(street.getLength() * safety);
                        if (safety < bestBikeSafety) {
                            bestBikeSafety = safety;
                        }
                        if (note != null) {
                            street.setNote(note);
                        }
                    }

                    PlainStreetEdge backStreet = streets.getSecond();
                    if (backStreet != null) {
                        graph.addEdge(backStreet);
                        double safety = wayData.getSafetyFeatures().getSecond();
                        if (safety < bestBikeSafety) {
                            bestBikeSafety = safety;
                        }
                        backStreet.setBicycleSafetyEffectiveLength(backStreet.getLength() * safety);
                        if (note != null) {
                            backStreet.setNote(note);
                        }
                    }

                    /* Check if there are turn restrictions starting on this segment */
                    List<TurnRestrictionTag> restrictionTags = turnRestrictionsByFromWay.get(way
                            .getId());
                    if (restrictionTags != null) {
                        for (TurnRestrictionTag tag : restrictionTags) {
                            if (tag.via == startNode) {
                                TurnRestriction restriction = turnRestrictionsByTag.get(tag);
                                restriction.from = backStreet;
                            } else if (tag.via == endNode) {
                                TurnRestriction restriction = turnRestrictionsByTag.get(tag);
                                restriction.from = street;
                            }
                        }
                    }

                    restrictionTags = turnRestrictionsByToWay.get(way.getId());
                    if (restrictionTags != null) {
                        for (TurnRestrictionTag tag : restrictionTags) {
                            if (tag.via == startNode) {
                                TurnRestriction restriction = turnRestrictionsByTag.get(tag);
                                restriction.to = street;
                            } else if (tag.via == endNode) {
                                TurnRestriction restriction = turnRestrictionsByTag.get(tag);
                                restriction.to = backStreet;
                            }
                        }
                    }
                    startNode = endNode;
                    osmStartNode = _nodes.get(startNode);
                }
            }

            /* unify turn restrictions */
            Map<Edge, TurnRestriction> turnRestrictions = new HashMap<Edge, TurnRestriction>();
            for (TurnRestriction restriction : turnRestrictionsByTag.values()) {
                turnRestrictions.put(restriction.from, restriction);
            }
            if (customNamer != null) {
                customNamer.postprocess(graph);
            }
            applyBikeSafetyFactor(graph);
            StreetUtils.pruneFloatingIslands(graph);
            StreetUtils.makeEdgeBased(graph, endpoints, turnRestrictions);

        }

        /**
         * The safest bike lane should have a safety weight no lower than the time weight of a flat
         * street.  This method divides the safety lengths by the length ratio of the safest street,
         * ensuring this property.
         * @param graph
         */
        private void applyBikeSafetyFactor(Graph graph) {
            _log.info("Multiplying all bike safety values by " + (1 / bestBikeSafety));
            HashSet<Edge> seenEdges = new HashSet<Edge>();
            for (GraphVertex gv : graph.getVertices()) {
                Vertex vertex = gv.vertex;
                for (Edge e : graph.getOutgoing(vertex)) {
                    if (!(e instanceof PlainStreetEdge)) {
                        continue;
                    }
                    PlainStreetEdge pse = (PlainStreetEdge) e;

                    if (!seenEdges.contains(e)) {
                        seenEdges.add(e);
                        pse.setBicycleSafetyEffectiveLength(pse.getBicycleSafetyEffectiveLength() / bestBikeSafety);
                    }
                }
                for (Edge e : graph.getIncoming(vertex)) {
                    if (!(e instanceof PlainStreetEdge)) {
                        continue;
                    }
                    PlainStreetEdge pse = (PlainStreetEdge) e;

                    if (!seenEdges.contains(e)) {
                        seenEdges.add(e);
                        pse.setBicycleSafetyEffectiveLength(pse.getBicycleSafetyEffectiveLength() / bestBikeSafety);
                    }
                }
            }
        }

        private Coordinate getCoordinate(OSMNode osmNode) {
            return new Coordinate(osmNode.getLon(), osmNode.getLat());
        }

        public void addNode(OSMNode node) {
            if (!_nodesWithNeighbors.contains(node.getId()))
                return;

            if (_nodes.containsKey(node.getId()))
                return;

            _nodes.put(node.getId(), node);

            if (_nodes.size() % 10000 == 0)
                _log.debug("nodes=" + _nodes.size());
        }

        public void addWay(OSMWay way) {
            if (_ways.containsKey(way.getId()))
                return;

            _ways.put(way.getId(), way);

            if (_ways.size() % 1000 == 0)
                _log.debug("ways=" + _ways.size());
        }

        public void addRelation(OSMRelation relation) {
            if (_relations.containsKey(relation.getId()))
                return;

            /* Currently only type=route;route=road relations are handled */
            if (!(relation.isTag("type", "restriction"))
                    && !(relation.isTag("type", "route") && relation.isTag("route", "road"))
                    && !(relation.isTag("type", "multipolygon") && relation.hasTag("highway"))) {
                return;
            }

            _relations.put(relation.getId(), relation);

            if (_relations.size() % 100 == 0)
                _log.debug("relations=" + _relations.size());

        }

        public void secondPhase() {
            int count = _ways.values().size();
            for (Iterator<OSMWay> it = _ways.values().iterator(); it.hasNext();) {
                OSMWay way = it.next();
                if (!(way.hasTag("highway") || way.isTag("railway", "platform"))) {
                    it.remove();
                } else if (way.isTag("highway", "conveyer") || way.isTag("highway", "proposed")) {
                    it.remove();
                } else {
                    // Since the way is kept, update nodes-with-neighbots
                    List<Long> nodes = way.getNodeRefs();
                    if (nodes.size() > 1) {
                        _nodesWithNeighbors.addAll(nodes);
                    }
                }
            }

            _log.trace("purged " + (count - _ways.values().size()) + " ways out of " + count);
        }

        /**
         * Copies useful metadata from relations to the relavant ways/nodes.
         */
        private void processRelations() {
            _log.debug("Processing relations...");

            for (OSMRelation relation : _relations.values()) {
                if (relation.isTag("type", "restriction")) {
                    processRestriction(relation);
                } else {
                    processRoad(relation);
                }
            }
        }

        /**
         * A temporary holder for turn restrictions while we have only way/node ids but not yet edge
         * objects
         */
        class TurnRestrictionTag {
            private long via;

            private TurnRestrictionType type;

            TurnRestrictionTag(long via, TurnRestrictionType type) {
                this.via = via;
                this.type = type;
            }
        }

        /**
         * Handle turn restrictions
         * 
         * @param relation
         */
        private void processRestriction(OSMRelation relation) {
            long from = -1, to = -1, via = -1;
            for (OSMRelationMember member : relation.getMembers()) {
                String role = member.getRole();
                if (role.equals("from")) {
                    from = member.getRef();
                } else if (role.equals("to")) {
                    to = member.getRef();
                } else if (role.equals("via")) {
                    via = member.getRef();
                }
            }
            if (from == -1 || to == -1 || via == -1) {
                _log.debug("Bad restriction " + relation.getId());
                return;
            }

            Set<TraverseMode> modes = EnumSet.of(TraverseMode.BICYCLE, TraverseMode.CAR);
            String exceptModes = relation.getTag("except");
            if (exceptModes != null) {
                for (String m : exceptModes.split(";")) {
                    if (m.equals("motorcar")) {
                        modes.remove(TraverseMode.CAR);
                    } else if (m.equals("bicycle")) {
                        modes.remove(TraverseMode.BICYCLE);
                        _log.debug("turn restriction with bicycle exception at node {} from {}", via, from);
                    }
                }
            }
            modes = TraverseMode.internSet(modes);

            TurnRestrictionTag tag;
            if (relation.isTag("restriction", "no_right_turn")) {
                tag = new TurnRestrictionTag(via, TurnRestrictionType.NO_TURN);
            } else if (relation.isTag("restriction", "no_left_turn")) {
                tag = new TurnRestrictionTag(via, TurnRestrictionType.NO_TURN);
            } else if (relation.isTag("restriction", "no_straight_on")) {
                tag = new TurnRestrictionTag(via, TurnRestrictionType.NO_TURN);
            } else if (relation.isTag("restriction", "no_u_turn")) {
                tag = new TurnRestrictionTag(via, TurnRestrictionType.NO_TURN);
            } else if (relation.isTag("restriction", "only_straight_on")) {
                tag = new TurnRestrictionTag(via, TurnRestrictionType.ONLY_TURN);
            } else if (relation.isTag("restriction", "only_right_turn")) {
                tag = new TurnRestrictionTag(via, TurnRestrictionType.ONLY_TURN);
            } else if (relation.isTag("restriction", "only_left_turn")) {
                tag = new TurnRestrictionTag(via, TurnRestrictionType.ONLY_TURN);
            } else {
                _log.debug("unknown restriction type " + relation.getTag("restriction"));
                return;
            }
            TurnRestriction restriction = new TurnRestriction();
            restriction.type = tag.type;
            restriction.modes = modes;
            turnRestrictionsByTag.put(tag, restriction);

            MapUtils.addToMapList(turnRestrictionsByFromWay, from, tag);
            MapUtils.addToMapList(turnRestrictionsByToWay, to, tag);

        }

        private void processRoad(OSMRelation relation) {
            for (OSMRelationMember member : relation.getMembers()) {
                if ("way".equals(member.getType()) && _ways.containsKey(member.getRef())) {
                    OSMWay way = _ways.get(member.getRef());
                    if (way != null) {
                        if (relation.hasTag("name")) {
                            if (way.hasTag("otp:route_name")) {
                                way.addTag(
                                        "otp:route_name",
                                        addUniqueName(way.getTag("otp:route_name"),
                                                relation.getTag("name")));
                            } else {
                                way.addTag(new OSMTag("otp:route_name", relation.getTag("name")));
                            }
                        }
                        if (relation.hasTag("ref")) {
                            if (way.hasTag("otp:route_ref")) {
                                way.addTag(
                                        "otp:route_ref",
                                        addUniqueName(way.getTag("otp:route_ref"),
                                                relation.getTag("ref")));
                            } else {
                                way.addTag(new OSMTag("otp:route_ref", relation.getTag("ref")));
                            }
                        }
                        if (relation.hasTag("highway") && relation.isTag("type", "multipolygon")
                                && !way.hasTag("highway")) {
                            way.addTag("highway", relation.getTag("highway"));
                        }
                    }
                }
            }
        }

        private String addUniqueName(String routes, String name) {
            String[] names = routes.split(", ");
            for (String existing : names) {
                if (existing.equals(name)) {
                    return routes;
                }
            }
            return routes + ", " + name;
        }

        /**
         * Handle oneway streets, cycleways, and whatnot. See
         * http://wiki.openstreetmap.org/wiki/Bicycle for various scenarios, along with
         * http://wiki.openstreetmap.org/wiki/OSM_tags_for_routing#Oneway.
         * 
         * @param end
         * @param start
         */
        private P2<PlainStreetEdge> getEdgesForStreet(Vertex start, Vertex end, OSMWay way,
                long startNode, StreetTraversalPermission permissions, LineString geometry) {
            // get geometry length in meters, irritatingly.
            Coordinate[] coordinates = geometry.getCoordinates();
            double d = 0;
            for (int i = 1; i < coordinates.length; ++i) {
                d += DistanceLibrary.distance(coordinates[i - 1], coordinates[i]);
            }

            LineString backGeometry = (LineString) geometry.reverse();

            Map<String, String> tags = way.getTags();

            if (permissions == StreetTraversalPermission.NONE)
                return new P2<PlainStreetEdge>(null, null);

            PlainStreetEdge street = null, backStreet = null;

            /*
             * pedestrian rules: everything is two-way (assuming pedestrians are allowed at all)
             * bicycle rules: default: permissions;
             * 
             * cycleway=dismount means walk your bike -- the engine will automatically try walking
             * bikes any time it is forbidden to ride them, so the only thing to do here is to
             * remove bike permissions
             * 
             * oneway=... sets permissions for cars and bikes oneway:bicycle overwrites these
             * permissions for bikes only
             * 
             * now, cycleway=opposite_lane, opposite, opposite_track can allow once oneway has been
             * set by oneway:bicycle, but should give a warning if it conflicts with oneway:bicycle
             * 
             * bicycle:backward=yes works like oneway:bicycle=no bicycle:backwards=no works like
             * oneway:bicycle=yes
             */

            String foot = way.getTag("foot");
            if ("yes".equals(foot) || "designated".equals(foot)) {
                permissions = permissions.add(StreetTraversalPermission.PEDESTRIAN);
            }

            if (OSMWithTags.isFalse(foot)) {
                permissions = permissions.remove(StreetTraversalPermission.PEDESTRIAN);
            }

            boolean forceBikes = false;
            String bicycle = way.getTag("bicycle");
            if ("yes".equals(bicycle) || "designated".equals(bicycle)) {
                permissions = permissions.add(StreetTraversalPermission.BICYCLE);
                forceBikes = true;
            }

            if (way.isTag("cycleway", "dismount") || "dismount".equals(bicycle)) {
                permissions = permissions.remove(StreetTraversalPermission.BICYCLE);
                if (forceBikes) {
                    _log.warn("conflicting tags bicycle:[yes|designated] and cycleway:dismount on way "
                            + way.getId() + ", assuming dismount");
                }
            }

            StreetTraversalPermission permissionsFront = permissions;
            StreetTraversalPermission permissionsBack = permissions;

            if (way.isTagTrue("oneway") || "roundabout".equals(tags.get("junction"))) {
                permissionsBack = permissionsBack.remove(StreetTraversalPermission.BICYCLE_AND_CAR);
            }
            if (way.isTag("oneway", "-1")) {
                permissionsFront = permissionsFront
                        .remove(StreetTraversalPermission.BICYCLE_AND_CAR);
            }
            String oneWayBicycle = way.getTag("oneway:bicycle");
            if (OSMWithTags.isTrue(oneWayBicycle) || way.isTagFalse("bicycle:backwards")) {
                permissionsBack = permissionsBack.remove(StreetTraversalPermission.BICYCLE);
            }
            if ("-1".equals(oneWayBicycle)) {
                permissionsFront = permissionsFront.remove(StreetTraversalPermission.BICYCLE);
            }
            if (OSMWithTags.isFalse(oneWayBicycle) || way.isTagTrue("bicycle:backwards")) {
                if (permissions.allows(StreetTraversalPermission.BICYCLE)) {
                    permissionsFront = permissionsFront.add(StreetTraversalPermission.BICYCLE);
                    permissionsBack = permissionsBack.add(StreetTraversalPermission.BICYCLE);
                }
            }

            // any cycleway which is opposite* allows contraflow biking
            String cycleway = way.getTag("cycleway");
            String cyclewayLeft = way.getTag("cycleway:left");
            String cyclewayRight = way.getTag("cycleway:right");
            if ((cycleway != null && cycleway.startsWith("opposite"))
                    || (cyclewayLeft != null && cyclewayLeft.startsWith("opposite"))
                    || (cyclewayRight != null && cyclewayRight.startsWith("opposite"))) {

                permissionsBack = permissionsBack.add(StreetTraversalPermission.BICYCLE);
            }

            String access = way.getTag("access");
            boolean noThruTraffic = "destination".equals(access)
                    || "private".equals(access) || "customers".equals(access)
                    || "delivery".equals(access) || "forestry".equals(access)
                    || "agricultural".equals(access);

            if (permissionsFront != StreetTraversalPermission.NONE) {
                street = getEdgeForStreet(start, end, way, startNode, d, permissionsFront,
                        geometry, false);
                street.setNoThruTraffic(noThruTraffic);
            }
            if (permissionsBack != StreetTraversalPermission.NONE) {
                backStreet = getEdgeForStreet(end, start, way, startNode, d, permissionsBack,
                        backGeometry, true);
                backStreet.setNoThruTraffic(noThruTraffic);
            }

            /* mark edges that are on roundabouts */
            if ("roundabout".equals(tags.get("junction"))) {
                if (street != null)
                    street.setRoundabout(true);
                if (backStreet != null)
                    backStreet.setRoundabout(true);
            }

            return new P2<PlainStreetEdge>(street, backStreet);
        }

        private PlainStreetEdge getEdgeForStreet(Vertex start, Vertex end, OSMWay way,
                long startNode, double length, StreetTraversalPermission permissions,
                LineString geometry, boolean back) {

            String id = "way " + way.getId() + " from " + startNode;
            id = unique(id);

            String name = way.getAssumedName();

            if (customNamer != null) {
                name = customNamer.name(way, name);
            }

            if (name == null) {
                name = id;
            }

            boolean steps = "steps".equals(way.getTag("highway"));
            if (steps) {
                // consider the elevation gain of stairs, roughly
                length *= 2;
            }

            PlainStreetEdge street = new PlainStreetEdge(start, end, geometry, name, length,
                    permissions, back);
            street.setId(id);

            if (!way.hasTag("name")) {
                street.setBogusName(true);
            }
            street.setStairs(steps);

            /* TODO: This should probably generalized somehow? */
            if (way.isTagFalse("wheelchair") || (steps && !way.isTagTrue("wheelchair"))) {
                street.setWheelchairAccessible(false);
            }

            street.setSlopeOverride(wayPropertySet.getSlopeOverride(way));

            if (customNamer != null) {
                customNamer.nameWithEdge(way, street);
            }

            return street;
        }

        private StreetTraversalPermission getPermissionsForEntity(OSMWithTags entity,
                StreetTraversalPermission def) {
            Map<String, String> tags = entity.getTags();
            StreetTraversalPermission permission = null;

            String highway = tags.get("highway");
            String cycleway = tags.get("cycleway");
            String access = tags.get("access");
            String motorcar = tags.get("motorcar");
            String bicycle = tags.get("bicycle");
            String foot = tags.get("foot");

            /*
             * Only a few tags are examined here, because we only care about modes supported by OTP
             * (wheelchairs are not of concern here)
             * 
             * Only a few values are checked for, all other values are presumed to be permissive (=>
             * This may not be perfect, but is closer to reality, since most people don't follow the
             * rules perfectly ;-)
             */
            if (access != null) {
                if ("no".equals(access) || "license".equals(access)) {
                    // this can actually be overridden
                    permission = StreetTraversalPermission.NONE;
                    if (entity.doesTagAllowAccess("motorcar")) {
                        permission = permission.add(StreetTraversalPermission.CAR);
                    }
                    if (entity.doesTagAllowAccess("bicycle")) {
                        permission = permission.add(StreetTraversalPermission.BICYCLE);
                    }
                    if (entity.doesTagAllowAccess("foot")) {
                        permission = permission.add(StreetTraversalPermission.PEDESTRIAN);
                    }
                } else {
                    permission = def;
                }
            } else if (motorcar != null || bicycle != null || foot != null) {
                permission = def;
            }

            if (motorcar != null) {
                if ("no".equals(motorcar) || "license".equals(motorcar)) {
                    permission = permission.remove(StreetTraversalPermission.CAR);
                } else {
                    permission = permission.add(StreetTraversalPermission.CAR);
                }
            }

            if (bicycle != null) {
                if ("no".equals(bicycle) || "license".equals(bicycle)) {
                    permission = permission.remove(StreetTraversalPermission.BICYCLE);
                } else {
                    permission = permission.add(StreetTraversalPermission.BICYCLE);
                }
            }

            if (foot != null) {
                if ("no".equals(foot) || "license".equals(foot)) {
                    permission = permission.remove(StreetTraversalPermission.PEDESTRIAN);
                } else {
                    permission = permission.add(StreetTraversalPermission.PEDESTRIAN);
                }
            }

            if (highway != null) {
                if ("construction".equals(highway)) {
                    permission = StreetTraversalPermission.NONE;
                }
            } else {
                if ("construction".equals(cycleway)) {
                    permission = StreetTraversalPermission.NONE;
                }
            }

            if (permission == null)
                return def;

            return permission;
        }
    }

    public CustomNamer getCustomNamer() {
        return customNamer;
    }

    public void setCustomNamer(CustomNamer customNamer) {
        this.customNamer = customNamer;
    }
}
