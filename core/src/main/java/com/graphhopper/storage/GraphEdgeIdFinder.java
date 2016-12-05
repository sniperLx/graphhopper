/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.storage;

import com.graphhopper.coll.GHIntHashSet;
import com.graphhopper.json.geo.Geometry;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.util.HintsMap;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.QueryResult;
import com.graphhopper.util.BreadthFirstSearch;
import com.graphhopper.util.ConfigMap;
import com.graphhopper.util.EdgeIteratorState;
import static com.graphhopper.util.Parameters.Routing.*;
import com.graphhopper.util.PointList;
import com.graphhopper.util.shapes.BBox;
import com.graphhopper.util.shapes.Circle;
import com.graphhopper.util.shapes.GHPoint;
import com.graphhopper.util.shapes.Shape;
import java.util.ArrayList;
import java.util.List;

/**
 * This class allows to find edges or construct shapes from some edge and shape filter.
 *
 * TODO: Add Tests for this class (There are integration but no Unit tests)
 *
 * @author Robin Boldt
 */
public class GraphEdgeIdFinder {

    private final Graph graph;
    private final LocationIndex locationIndex;

    public GraphEdgeIdFinder(Graph graph, LocationIndex locationIndex) {
        this.graph = graph;
        this.locationIndex = locationIndex;
    }

    public void findClosestEdgeToPoint(GHIntHashSet edgeIds, GHPoint point, EdgeFilter filter) {
        findClosestEdge(edgeIds, point.getLat(), point.getLon(), filter);
    }

    public void findClosestEdge(GHIntHashSet edgeIds, double lat, double lon, EdgeFilter filter) {
        QueryResult qr = locationIndex.findClosest(lat, lon, filter);
        if (qr.isValid())
            edgeIds.add(qr.getClosestEdge().getEdge());
    }

    public void findEdgesInShape(final GHIntHashSet edgeIds, final Shape shape, EdgeFilter filter) {
        // Issue with this is approach is, if there is no street close by, it won't work as qr is not valid
        // Maybe we should check edge points (or random points in the Shape?) if we cannot find a valid edge at the center?
        GHPoint center = shape.getCenter();
        QueryResult qr = locationIndex.findClosest(center.getLat(), center.getLon(), filter);

        // We should throw an Exception here otherwise the user will not understand what is happening
        // It might happen that a BBox center does not match the underlying street
        if (!qr.isValid())
            return;

        BreadthFirstSearch bfs = new BreadthFirstSearch() {
            final NodeAccess na = graph.getNodeAccess();
            final Shape localShape = shape;

            @Override
            protected boolean goFurther(int nodeId) {
                return localShape.contains(na.getLatitude(nodeId), na.getLongitude(nodeId));
            }

            @Override
            protected boolean checkAdjacent(EdgeIteratorState edge) {
                if (localShape.contains(na.getLatitude(edge.getAdjNode()), na.getLongitude(edge.getAdjNode()))) {
                    edgeIds.add(edge.getEdge());
                    return true;
                }
                return false;
            }
        };
        bfs.start(graph.createEdgeExplorer(filter), qr.getClosestNode());
    }

    public void fillEdgeIDs(GHIntHashSet edgeIds, Geometry geometry, EdgeFilter filter) {
        if (geometry.isPoint()) {
            GHPoint point = geometry.asPoint();
            findClosestEdgeToPoint(edgeIds, point, filter);
        } else if (geometry.isPointList()) {
            PointList pl = geometry.asPointList();
            if (geometry.getType().equals("LineString")) {
                // TODO do map matching or routing
                int lastIdx = pl.size() - 1;
                if (pl.size() >= 2) {
                    double meanLat = (pl.getLatitude(0) + pl.getLatitude(lastIdx)) / 2;
                    double meanLon = (pl.getLongitude(0) + pl.getLongitude(lastIdx)) / 2;
                    findClosestEdge(edgeIds, meanLat, meanLon, filter);
                }
            } else {
                for (int i = 0; i < pl.size(); i++) {
                    findClosestEdge(edgeIds, pl.getLatitude(i), pl.getLongitude(i), filter);
                }
            }
        }
    }

    /**
     * This method reads string values from the hints and fills the cMap with objects about
     * blocked edges and/or blocked shapes.
     */
    public ConfigMap parseStringHints(ConfigMap cMap, HintsMap hints, EdgeFilter filter) {
        final String objectSeparator = ";";
        final String innerObjSep = ",";

        final GHIntHashSet blockedEdges = new GHIntHashSet();
        final List<Shape> blockedShapes = new ArrayList<>();
        // We still need EdgeIds for point blocking
        final boolean blockByShape = hints.getBool(BLOCK_BY_SHAPE, true);

        // Add blocked edges
        String blockedEdgesStr = hints.get(BLOCKED_EDGES, "");
        if (!blockedEdgesStr.isEmpty()) {
            String[] blockedEdgesArr = blockedEdgesStr.split(objectSeparator);
            for (int i = 0; i < blockedEdgesArr.length; i++) {
                blockedEdges.add(Integer.parseInt(blockedEdgesArr[i]));
            }
        }

        // Add blocked points
        String blockedPointsStr = hints.get(BLOCKED_POINTS, "");
        if (!blockedPointsStr.isEmpty()) {
            String[] blockedPointsArr = blockedPointsStr.split(objectSeparator);
            for (int i = 0; i < blockedPointsArr.length; i++) {
                String object = blockedPointsArr[i];
                String[] splittedObject = object.split(innerObjSep);
                if (splittedObject.length != 2)
                    throw new IllegalArgumentException(object + " at index " + i + " need to be defined as lat,lon");

                double lat = Double.parseDouble(splittedObject[2 * i]);
                double lon = Double.parseDouble(splittedObject[2 * i + 1]);
                findClosestEdge(blockedEdges, lat, lon, filter);
            }
        }

        // Add blocked rectangular areas
        String blockedAreasStr = hints.get(BLOCKED_RECTANGULAR_AREAS, "");
        if (!blockedAreasStr.isEmpty()) {
            String[] blockedAreasArr = blockedAreasStr.split(objectSeparator);
            for (int i = 0; i < blockedAreasArr.length; i++) {
                String object = blockedAreasArr[i];
                String[] splittedObject = object.split(innerObjSep);
                if (splittedObject.length != 4)
                    throw new IllegalArgumentException(object + " at index " + i + " need to be defined as left,bottom,right,top");

                double left = Double.parseDouble(splittedObject[4 * i]);
                double bottom = Double.parseDouble(splittedObject[4 * i + 1]);
                double right = Double.parseDouble(splittedObject[4 * i + 2]);
                double top = Double.parseDouble(splittedObject[4 * i + 3]);

                final BBox bbox = new BBox(left, right, bottom, top);
                if (blockByShape)
                    blockedShapes.add(bbox);
                else
                    findEdgesInShape(blockedEdges, bbox, filter);
            }
        }

        // Add blocked circular areas
        String blockedCircularAreasStr = hints.get(BLOCKED_CIRCULAR_AREAS, "");
        if (!blockedCircularAreasStr.isEmpty()) {
            String[] blockedCircularAreasArr = blockedCircularAreasStr.split(objectSeparator);
            for (int i = 0; i < blockedCircularAreasArr.length; i++) {
                String object = blockedCircularAreasArr[i];
                String[] splittedObject = object.split(innerObjSep);
                if (splittedObject.length != 3)
                    throw new IllegalArgumentException(object + " at index " + i + " need to be defined as lat,lon,radius");

                double lat = Double.parseDouble(splittedObject[3 * i]);
                double lon = Double.parseDouble(splittedObject[3 * i + 1]);
                int radius = Integer.parseInt(splittedObject[3 * i + 2]);
                Circle circle = new Circle(lat, lon, radius);
                if (blockByShape) {
                    blockedShapes.add(circle);
                } else {
                    findEdgesInShape(blockedEdges, circle, filter);
                }
            }
        }

        cMap.put(BLOCKED_EDGES, blockedEdges);
        cMap.put(BLOCKED_SHAPES, blockedShapes);
        return cMap;
    }
}
