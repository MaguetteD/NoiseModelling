/**
 * NoiseModelling is a library capable of producing noise maps. It can be freely used either for research and education, as well as by experts in a professional use.
 * <p>
 * NoiseModelling is distributed under GPL 3 license. You can read a copy of this License in the file LICENCE provided with this software.
 * <p>
 * Official webpage : http://noise-planet.org/noisemodelling.html
 * Contact: contact@noise-planet.org
 */

package org.noise_planet.noisemodelling.pathfinder.profilebuilder;

import org.locationtech.jts.algorithm.Angle;
import org.locationtech.jts.algorithm.CGAlgorithms3D;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.index.ItemVisitor;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.locationtech.jts.triangulate.quadedge.Vertex;
import org.noise_planet.noisemodelling.pathfinder.delaunay.LayerDelaunay;
import org.noise_planet.noisemodelling.pathfinder.delaunay.LayerDelaunayError;
import org.noise_planet.noisemodelling.pathfinder.delaunay.LayerTinfour;
import org.noise_planet.noisemodelling.pathfinder.delaunay.Triangle;
import org.noise_planet.noisemodelling.pathfinder.path.PointPath;
import org.noise_planet.noisemodelling.pathfinder.utils.IntegerTuple;
import org.noise_planet.noisemodelling.pathfinder.utils.geometry.JTSUtility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static java.lang.Double.NaN;
import static java.lang.Double.isNaN;
import static org.locationtech.jts.algorithm.Orientation.isCCW;
//import static org.noise_planet.noisemodelling.pathfinder.utils.geometry.JTSUtility.dist2D;
import static org.noise_planet.noisemodelling.pathfinder.profilebuilder.ProfileBuilder.IntersectionType.*;

//TODO use NaN for building height
//TODO fix wall references id in order to use also real wall database key
//TODO check how the wall alpha are set to the cut point
//TODO check how the topo and building height are set to cut point
//TODO check how the building pk is set to cut point
//TODO difference between Z and height (z = height+topo)
//TODO create class org.noise_planet.noisemodelling.pathfinder.cnossos.ComputeCnossosRays which is a copy of computeRays using ProfileBuilder

/**
 * Builder constructing profiles from buildings, topography and ground effects.
 */
public class ProfileBuilder {
    public static final double epsilon = 1e-7;
    /** Class {@link java.util.logging.Logger}. */
    private static final Logger LOGGER = LoggerFactory.getLogger(ProfileBuilder.class);
    /** Default RTree node capacity. */
    private static final int TREE_NODE_CAPACITY = 5;
    /** {@link Geometry} factory. */
    private static final GeometryFactory FACTORY = new GeometryFactory();
    private static final double DELTA = 1e-3;

    /** If true, no more data can be add. */
    private boolean isFeedingFinished = false;
    /** Wide angle points of a building polygon */
    private final Map<Integer, ArrayList<Coordinate>> buildingsWideAnglePoints = new HashMap<>();
    /** Building RTree node capacity. */
    private int buildingNodeCapacity = TREE_NODE_CAPACITY;
    /** Topographic RTree node capacity. */
    private int topoNodeCapacity = TREE_NODE_CAPACITY;
    /** Ground RTree node capacity. */
    private int groundNodeCapacity = TREE_NODE_CAPACITY;
    /**
     * Max length of line part used for profile retrieving.
     * @see ProfileBuilder#getProfile(Coordinate, Coordinate)
     */
    private double maxLineLength = 60;
    /** List of buildings. */
    private final List<Building> buildings = new ArrayList<>();
    /** List of walls. */
    private final List<Wall> walls = new ArrayList<>();
    /** Building RTree. */
    private final STRtree buildingTree;
    /** Building RTree. */
    private STRtree wallTree = new STRtree(TREE_NODE_CAPACITY);
    /** Global RTree. */
    private STRtree rtree;
    private STRtree groundEffectsRtree = new STRtree(TREE_NODE_CAPACITY);


    /** List of topographic points. */
    private final List<Coordinate> topoPoints = new ArrayList<>();
    /** List of topographic lines. */
    private final List<LineString> topoLines = new ArrayList<>();
    /** Topographic triangle facets. */
    private List<Triangle> topoTriangles = new ArrayList<>();
    /** Topographic triangle neighbors. */
    private List<Triangle> topoNeighbors = new ArrayList<>();
    /** Topographic Vertices .*/
    private List<Coordinate> vertices = new ArrayList<>();
    /** Topographic RTree. */
    private STRtree topoTree;

    /** List of ground effects. */
    private final List<GroundAbsorption> groundAbsorptions = new ArrayList<>();

    /** Receivers .*/
    private final List<Coordinate> receivers = new ArrayList<>();

    /** List of processed walls. */
    private final List<Wall> processedWalls = new ArrayList<>();

    /** Global envelope of the builder. */
    private Envelope envelope;
    /** Maximum area of triangles. */
    private double maxArea;

    /** if true take into account z value on Buildings Polygons
     * In this case, z represent the altitude (from the sea to the top of the wall) */
    private boolean zBuildings = false;


    public ProfileBuilder setzBuildings(boolean zBuildings) {
        this.zBuildings = zBuildings;
        return this;
    }


    /**
     * Main empty constructor.
     */
    public ProfileBuilder() {
        buildingTree = new STRtree(buildingNodeCapacity);
    }

    //TODO : when a source/receiver are underground, should an offset be applied ?
    /**
     * Constructor setting parameters.
     * @param buildingNodeCapacity Building RTree node capacity.
     * @param topoNodeCapacity     Topographic RTree node capacity.
     * @param groundNodeCapacity   Ground RTree node capacity.
     * @param maxLineLength        Max length of line part used for profile retrieving.
     */
    public ProfileBuilder(int buildingNodeCapacity, int topoNodeCapacity, int groundNodeCapacity, int maxLineLength) {
        this.buildingNodeCapacity = buildingNodeCapacity;
        this.topoNodeCapacity = topoNodeCapacity;
        this.groundNodeCapacity = groundNodeCapacity;
        this.maxLineLength = maxLineLength;
        buildingTree = new STRtree(buildingNodeCapacity);
    }

    /**
     * Add the given {@link Geometry} footprint.
     * @param building Building.
     */
    public ProfileBuilder addBuilding(Building building) {
        if(building.poly == null || building.poly.isEmpty()) {
            LOGGER.error("Cannot add a building with null or empty geometry.");
        }
        else if(!isFeedingFinished) {
            if(envelope == null) {
                envelope = building.poly.getEnvelopeInternal();
            }
            else {
                envelope.expandToInclude(building.poly.getEnvelopeInternal());
            }
            buildings.add(building);
            buildingTree.insert(building.poly.getEnvelopeInternal(), buildings.size());
            return this;
        }
        else{
            LOGGER.warn("Cannot add building, feeding is finished.");
        }
        return null;
    }

    /**
     * Add the given {@link Geometry} footprint.
     * @param geom   Building footprint.
     */
    public ProfileBuilder addBuilding(Geometry geom) {
        return addBuilding(geom, -1);
    }

    /**
     * Add the given {@link Geometry} footprint.
     * @param coords Building footprint coordinates.
     */
    public ProfileBuilder addBuilding(Coordinate[] coords) {
        Coordinate[] polyCoords;
        int l = coords.length;
        if(coords[0] != coords[l-1]) {
            polyCoords = Arrays.copyOf(coords, l+1);
            polyCoords[l] = new Coordinate(coords[0]);
        }
        else {
            polyCoords = coords;
        }
        return addBuilding(FACTORY.createPolygon(polyCoords), -1);
    }

    /**
     * Add the given {@link Geometry} footprint and height as building.
     * @param geom   Building footprint.
     * @param height Building height.
     */
    public ProfileBuilder addBuilding(Geometry geom, double height) {
        return addBuilding(geom, height, new ArrayList<>());
    }

    /**
     * Add the given {@link Geometry} footprint.
     * @param coords Building footprint coordinates.
     * @param height Building height.
     */
    public ProfileBuilder addBuilding(Coordinate[] coords, double height) {
        Coordinate[] polyCoords;
        int l = coords.length;
        if(coords[0] != coords[l-1]) {
            polyCoords = Arrays.copyOf(coords, l+1);
            polyCoords[l] = new Coordinate(coords[0]);
        }
        else {
            polyCoords = coords;
        }
        return addBuilding(FACTORY.createPolygon(polyCoords), height, new ArrayList<>());
    }

    /**
     * Add the given {@link Geometry} footprint.
     * @param geom   Building footprint.
     * @param id     Database primary key.
     */
    public ProfileBuilder addBuilding(Geometry geom, int id) {
        return addBuilding(geom, NaN, id);
    }

    /**
     * Add the given {@link Geometry} footprint.
     * @param coords Building footprint coordinates.
     * @param id     Database primary key.
     */
    public ProfileBuilder addBuilding(Coordinate[] coords, int id) {
        Coordinate[] polyCoords;
        int l = coords.length;
        if(coords[0] != coords[l-1]) {
            polyCoords = Arrays.copyOf(coords, l+1);
            polyCoords[l] = new Coordinate(coords[0]);
        }
        else {
            polyCoords = coords;
        }
        return addBuilding(FACTORY.createPolygon(polyCoords), id);
    }

    /**
     * Add the given {@link Geometry} footprint, height, alphas (absorption coefficients) and a database id as building.
     * @param geom   Building footprint.
     * @param height Building height.
     * @param id     Database id.
     */
    public ProfileBuilder addBuilding(Geometry geom, double height, int id) {
        return addBuilding(geom, height, new ArrayList<>(), id);
    }

    /**
     * Add the given {@link Geometry} footprint.
     * @param coords Building footprint coordinates.
     * @param height Building height.
     * @param id     Database primary key.
     */
    public ProfileBuilder addBuilding(Coordinate[] coords, double height, int id) {
        Coordinate[] polyCoords;
        int l = coords.length;
        if(coords[0] != coords[l-1]) {
            polyCoords = Arrays.copyOf(coords, l+1);
            polyCoords[l] = new Coordinate(coords[0]);
        }
        else {
            polyCoords = coords;
        }
        return addBuilding(FACTORY.createPolygon(polyCoords), height, new ArrayList<>(), id);
    }

    /**
     * Add the given {@link Geometry} footprint, height and alphas (absorption coefficients) as building.
     * @param geom   Building footprint.
     * @param height Building height.
     * @param alphas Absorption coefficients.
     */
    public ProfileBuilder addBuilding(Geometry geom, double height, List<Double> alphas) {
        return addBuilding(geom, height, alphas, -1);
    }

    /**
     * Add the given {@link Geometry} footprint.
     * @param coords Building footprint coordinates.
     * @param height Building height.
     * @param alphas Absorption coefficients.
     */
    public ProfileBuilder addBuilding(Coordinate[] coords, double height, List<Double> alphas) {
        Coordinate[] polyCoords;
        int l = coords.length;
        if(coords[0] != coords[l-1]) {
            polyCoords = Arrays.copyOf(coords, l+1);
            polyCoords[l] = new Coordinate(coords[0]);
        }
        else {
            polyCoords = coords;
        }
        return addBuilding(FACTORY.createPolygon(polyCoords), height, alphas, -1);
    }

    /**
     * Add the given {@link Geometry} footprint, height and alphas (absorption coefficients) as building.
     * @param geom   Building footprint.
     * @param alphas Absorption coefficients.
     */
    public ProfileBuilder addBuilding(Geometry geom, List<Double> alphas) {
        return addBuilding(geom, NaN, alphas, -1);
    }

    /**
     * Add the given {@link Geometry} footprint.
     * @param coords Building footprint coordinates.
     * @param alphas Absorption coefficients.
     */
    public ProfileBuilder addBuilding(Coordinate[] coords, List<Double> alphas) {
        Coordinate[] polyCoords;
        int l = coords.length;
        if(coords[0] != coords[l-1]) {
            polyCoords = Arrays.copyOf(coords, l+1);
            polyCoords[l] = new Coordinate(coords[0]);
        }
        else {
            polyCoords = coords;
        }
        return addBuilding(FACTORY.createPolygon(polyCoords), NaN, alphas, -1);
    }

    /**
     * Add the given {@link Geometry} footprint, height and alphas (absorption coefficients) as building.
     * @param geom   Building footprint.
     * @param alphas Absorption coefficients.
     * @param id     Database primary key.
     */
    public ProfileBuilder addBuilding(Geometry geom, List<Double> alphas, int id) {
        return addBuilding(geom, NaN, alphas, id);
    }

    /**
     * Add the given {@link Geometry} footprint.
     * @param coords Building footprint coordinates.
     * @param alphas Absorption coefficients.
     * @param id     Database primary key.
     */
    public ProfileBuilder addBuilding(Coordinate[] coords, List<Double> alphas, int id) {
        Coordinate[] polyCoords;
        int l = coords.length;
        if(coords[0] != coords[l-1]) {
            polyCoords = Arrays.copyOf(coords, l+1);
            polyCoords[l] = new Coordinate(coords[0]);
        }
        else {
            polyCoords = coords;
        }
        return addBuilding(FACTORY.createPolygon(polyCoords), NaN, alphas, id);
    }

    /**
     * Add the given {@link Geometry} footprint, height, alphas (absorption coefficients) and a database primary key
     * as building.
     * @param geom   Building footprint.
     * @param height Building height.
     * @param alphas Absorption coefficients.
     * @param id     Database primary key.
     */
    public ProfileBuilder addBuilding(Geometry geom, double height, List<Double> alphas, int id) {
        if(geom == null && !(geom instanceof Polygon)) {
            LOGGER.error("Building geometry should be Polygon");
            return null;
        }
        Polygon poly = (Polygon)geom;
        if(!isFeedingFinished) {
            if(envelope == null) {
                envelope = geom.getEnvelopeInternal();
            }
            else {
                envelope.expandToInclude(geom.getEnvelopeInternal());
            }
            Building building = new Building(poly, height, alphas, id, zBuildings);
            buildings.add(building);
            buildingTree.insert(building.poly.getEnvelopeInternal(), buildings.size());
            //TODO : generalization of building coefficient
            addGroundEffect(geom, 0);
            return this;
        }
        else{
            LOGGER.warn("Cannot add building, feeding is finished.");
            return null;
        }
    }

    /**
     * Add the given {@link Geometry} footprint.
     * @param height Building height.
     * @param alphas Absorption coefficients.
     * @param id     Database primary key.
     */
    public ProfileBuilder addBuilding(Coordinate[] coords, double height, List<Double> alphas, int id) {
        Coordinate[] polyCoords;
        int l = coords.length;
        if(coords[0] != coords[l-1]) {
            polyCoords = Arrays.copyOf(coords, l+1);
            polyCoords[l-1] = new Coordinate(coords[0]);
        }
        else {
            polyCoords = coords;
        }
        return addBuilding(FACTORY.createPolygon(polyCoords), height, alphas, id);
    }

    /**
     * Add the given {@link Geometry} footprint, height, alphas (absorption coefficients) and a database id as wall.
     * @param geom   Wall footprint.
     * @param height Wall height.
     * @param id     Database key.
     */
    /*public ProfileBuilder addWall(LineString geom, double height, int id) {
        return addWall(geom, height, new ArrayList<>(), id);
    }*/

    /**
     * Add the given {@link Geometry} footprint, height, alphas (absorption coefficients) and a database id as wall.
     * @param coords Wall footprint coordinates.
     * @param height Wall height.
     * @param id     Database key.
     */
    public ProfileBuilder addWall(Coordinate[] coords, double height, int id) {
        return addWall(FACTORY.createLineString(coords), height, new ArrayList<>(), id);
    }

    /**
     * Add the given {@link Geometry} footprint, height, alphas (absorption coefficients) and a database id as wall.
     * @param geom   Wall footprint.
     * @param id     Database key.
     */
    /*public ProfileBuilder addWall(LineString geom, int id) {
        return addWall(geom, 0.0, new ArrayList<>(), id);
    }*/

    /**
     * Add the given {@link Geometry} footprint, height, alphas (absorption coefficients) and a database id as wall.
     * @param coords Wall footprint coordinates.
     * @param id     Database key.
     */
    public ProfileBuilder addWall(Coordinate[] coords, int id) {
        return addWall(FACTORY.createLineString(coords), 0.0, new ArrayList<>(), id);
    }

    /**
     * Add the given {@link Geometry} footprint, height, alphas (absorption coefficients) and a database id as wall.
     * @param geom   Wall footprint.
     * @param height Wall height.
     * @param alphas Absorption coefficient.
     * @param id     Database key.
     */
    public ProfileBuilder addWall(LineString geom, double height, List<Double> alphas, int id) {
        if(!isFeedingFinished) {
            if(envelope == null) {
                envelope = geom.getEnvelopeInternal();
            }
            else {
                envelope.expandToInclude(geom.getEnvelopeInternal());
            }

            for(int i=0; i<geom.getNumPoints()-1; i++) {
                Wall wall = new Wall(geom.getCoordinateN(i), geom.getCoordinateN(i+1), id, IntersectionType.BUILDING, i!=0, i!=geom.getNumPoints()-2);
                wall.setHeight(height);
                wall.setAlpha(alphas);
                walls.add(wall);
                wallTree.insert(wall.line.getEnvelopeInternal(), walls.size());
            }
            return this;
        }
        else{
            LOGGER.warn("Cannot add building, feeding is finished.");
            return null;
        }
    }

    /**
     * Add the given {@link Geometry} footprint, height, alphas (absorption coefficients) and a database id as wall.
     * @param coords Wall footprint coordinates.
     * @param id     Database key.
     */
    public ProfileBuilder addWall(Coordinate[] coords, double height, List<Double> alphas, int id) {
        return addWall(FACTORY.createLineString(coords), height, alphas, id);
    }

    /**
     * Add the given {@link Geometry} footprint, height, alphas (absorption coefficients) and a database id as wall.
     * @param coords Wall footprint coordinates.
     * @param id     Database key.
     */
    public ProfileBuilder addWall(Coordinate[] coords, List<Double> alphas, int id) {
        return addWall(FACTORY.createLineString(coords), 0.0, alphas, id);
    }

    /**
     * Add the topographic point in the data, to complete the topographic data.
     * @param point Topographic point.
     */
    public ProfileBuilder addTopographicPoint(Coordinate point) {
        if(!isFeedingFinished) {
            //Force to 3D
            if (isNaN(point.z)) {
                point.setCoordinate(new Coordinate(point.x, point.y, 0.));
            }
            if(envelope == null) {
                envelope = new Envelope(point);
            }
            else {
                envelope.expandToInclude(point);
            }
            this.topoPoints.add(point);
        }
        return this;
    }

    /**
     * Add the topographic line in the data, to complete the topographic data.
     */
    public ProfileBuilder addTopographicLine(double x0, double y0, double z0, double x1, double y1, double z1) {
        if(!isFeedingFinished) {
            LineString lineSegment = FACTORY.createLineString(new Coordinate[]{new Coordinate(x0, y0, z0), new Coordinate(x1, y1, z1)});
            if(envelope == null) {
                envelope = lineSegment.getEnvelopeInternal();
            }
            else {
                envelope.expandToInclude(lineSegment.getEnvelopeInternal());
            }
            this.topoLines.add(lineSegment);
        }
        return this;
    }

    /**
     * Add the topographic line in the data, to complete the topographic data.
     * @param lineSegment Topographic line.
     */
    public ProfileBuilder addTopographicLine(LineString lineSegment) {
        if(!isFeedingFinished) {
            if(envelope == null) {
                envelope = lineSegment.getEnvelopeInternal();
            }
            else {
                envelope.expandToInclude(lineSegment.getEnvelopeInternal());
            }
            this.topoLines.add(lineSegment);
        }
        return this;
    }

    /**
     * Add a ground effect.
     * @param geom        Ground effect area footprint.
     * @param coefficient Ground effect coefficient.
     */
    public ProfileBuilder addGroundEffect(Geometry geom, double coefficient) {
        if(!isFeedingFinished) {
            if(envelope == null) {
                envelope = geom.getEnvelopeInternal();
            }
            else {
                envelope.expandToInclude(geom.getEnvelopeInternal());
            }
            this.groundAbsorptions.add(new GroundAbsorption(geom, coefficient));
        }
        return this;
    }

    /**
     * Add a ground effect.
     * @param minX        Ground effect minimum X.
     * @param maxX        Ground effect maximum X.
     * @param minY        Ground effect minimum Y.
     * @param maxY        Ground effect maximum Y.
     * @param coefficient Ground effect coefficient.
     */
    public ProfileBuilder addGroundEffect(double minX, double maxX, double minY, double maxY, double coefficient) {
        if(!isFeedingFinished) {
            Geometry geom = FACTORY.createPolygon(new Coordinate[]{
                    new Coordinate(minX, minY),
                    new Coordinate(minX, maxY),
                    new Coordinate(maxX, maxY),
                    new Coordinate(maxX, minY),
                    new Coordinate(minX, minY)
            });
            if(envelope == null) {
                envelope = geom.getEnvelopeInternal();
            }
            else {
                envelope.expandToInclude(geom.getEnvelopeInternal());
            }
            this.groundAbsorptions.add(new GroundAbsorption(geom, coefficient));
        }
        return this;
    }

    public List<Wall> getProcessedWalls() {
        return processedWalls;
    }

    /**
     * Retrieve the building list.
     * @return The building list.
     */
    public List<Building> getBuildings() {
        return buildings;
    }

    /**
     * Retrieve the count of building add to this builder.
     * @return The count of building.
     */
    public int getBuildingCount() {
        return buildings.size();
    }

    /**
     * Retrieve the building with the given id (id is starting from 1).
     * @param id Id of the building
     * @return The building corresponding to the given id.
     */
    public Building getBuilding(int id) {
        return buildings.get(id);
    }

    /**
     * Retrieve the wall list.
     * @return The wall list.
     */
    public List<Wall> getWalls() {
        return walls;
    }

    /**
     * Retrieve the count of wall add to this builder.
     * @return The count of wall.
     */
    /*public int getWallCount() {
        return walls.size();
    }*/

    /**
     * Retrieve the wall with the given id (id is starting from 1).
     * @param id Id of the wall
     * @return The wall corresponding to the given id.
     */
    public Wall getWall(int id) {
        return walls.get(id);
    }

    /**
     * Clear the building list.
     */
    /*public void clearBuildings() {
        buildings.clear();
    }

    /**
     * Retrieve the global profile envelope.
     * @return The global profile envelope.

    public Envelope getMeshEnvelope() {
        return envelope;
    }

    /**
     * Add a constraint on maximum triangle area.
     * @param maximumArea Value in square meter.

    public void setMaximumArea(double maximumArea) {
        maxArea = maximumArea;
    }*/

    /**
     * Retrieve the topographic triangles.
     * @return The topographic triangles.
     */
    public List<Triangle> getTriangles() {
        return topoTriangles;
    }

    /**
     * Retrieve the topographic vertices.
     * @return The topographic vertices.
     */
    public List<Coordinate> getVertices() {
        return vertices;
    }

    /**
     * Retrieve the receivers list.
     * @return The receivers list.
     */
    public List<Coordinate> getReceivers() {
        return receivers;
    }

    /**
     * Retrieve the ground effects.
     * @return The ground effects.
     */
    public List<GroundAbsorption> getGroundEffects() {
        return groundAbsorptions;
    }

    /**
     * Finish the data feeding. Once called, no more data can be added and process it in order to prepare the
     * profile retrieving.
     * The building are processed to include each facets into a RTree
     * The topographic points and lines are meshed using delaunay and triangles facets are included into a RTree
     *
     * @return True if the finishing has been successfully done, false otherwise.
     */
    public ProfileBuilder finishFeeding() {
        isFeedingFinished = true;

        //Process topographic points and lines
        if(topoPoints.size()+topoLines.size() > 1) {
            //Feed the Delaunay layer
            LayerDelaunay layerDelaunay = new LayerTinfour();
            layerDelaunay.setRetrieveNeighbors(true);
            try {
                layerDelaunay.setMaxArea(maxArea);
            } catch (LayerDelaunayError e) {
                LOGGER.error("Unable to set the Delaunay triangle maximum area.", e);
                return null;
            }
            try {
                for (Coordinate topoPoint : topoPoints) {
                    layerDelaunay.addVertex(topoPoint);
                }
            } catch (LayerDelaunayError e) {
                LOGGER.error("Error while adding topographic points to Delaunay layer.", e);
                return null;
            }
            try {
                for (LineString topoLine : topoLines) {
                    //TODO ensure the attribute parameter is useless
                    layerDelaunay.addLineString(topoLine, -1);
                }
            } catch (LayerDelaunayError e) {
                LOGGER.error("Error while adding topographic points to Delaunay layer.", e);
                return null;
            }
            //Process Delaunay
            try {
                layerDelaunay.processDelaunay();
            } catch (LayerDelaunayError e) {
                LOGGER.error("Error while processing Delaunay.", e);
                return null;
            }
            try {
                topoTriangles = layerDelaunay.getTriangles();
                topoNeighbors = layerDelaunay.getNeighbors();
            } catch (LayerDelaunayError e) {
                LOGGER.error("Error while getting triangles", e);
                return null;
            }
            //Feed the RTree
            topoTree = new STRtree(topoNodeCapacity);
            try {
                vertices = layerDelaunay.getVertices();
            } catch (LayerDelaunayError e) {
                LOGGER.error("Error while getting vertices", e);
                return null;
            }
            // wallIndex set will merge shared triangle segments
            Set<IntegerTuple> wallIndex = new HashSet<>();
            for (int i = 0; i < topoTriangles.size(); i++) {
                final Triangle tri = topoTriangles.get(i);
                wallIndex.add(new IntegerTuple(tri.getA(), tri.getB(), i));
                wallIndex.add(new IntegerTuple(tri.getB(), tri.getC(), i));
                wallIndex.add(new IntegerTuple(tri.getC(), tri.getA(), i));
                // Insert triangle in rtree
                Coordinate vA = vertices.get(tri.getA());
                Coordinate vB = vertices.get(tri.getB());
                Coordinate vC = vertices.get(tri.getC());
                Envelope env = FACTORY.createLineString(new Coordinate[]{vA, vB, vC}).getEnvelopeInternal();
                topoTree.insert(env, i);
            }
            topoTree.build();
            //TODO : Seems to be useless, to check
            /*for (IntegerTuple wallId : wallIndex) {
                Coordinate vA = vertices.get(wallId.nodeIndexA);
                Coordinate vB = vertices.get(wallId.nodeIndexB);
                Wall wall = new Wall(vA, vB, wallId.triangleIdentifier, TOPOGRAPHY);
                processedWalls.add(wall);
            }*/
        }
        //Update building z
        if(topoTree != null) {
            for (Building b : buildings) {
                if(isNaN(b.poly.getCoordinate().z) || b.poly.getCoordinate().z == 0.0 || !zBuildings) {
                    b.poly2D_3D();
                    b.poly.apply(new ElevationFilter.UpdateZ(b.height + b.updateZTopo(this)));
                }
            }
            for (Wall w : walls) {
                if(isNaN(w.p0.z) || w.p0.z == 0.0) {
                    w.p0.z = w.height + getZGround(w.p0);
                }
                if(isNaN(w.p1.z) || w.p1.z == 0.0) {
                    w.p1.z = w.height + getZGround(w.p1);
                }
            }
        }
        else {
            for (Building b : buildings) {
                if(b != null && b.poly != null && b.poly.getCoordinate() != null && (!zBuildings ||
                        isNaN(b.poly.getCoordinate().z) || b.poly.getCoordinate().z == 0.0)) {

                    b.poly2D_3D();
                    b.poly.apply(new ElevationFilter.UpdateZ(b.height));
                }

            }
            for (Wall w : walls) {
                if(isNaN(w.p0.z) || w.p0.z == 0.0) {
                    w.p0.z = w.height;
                }
                if(isNaN(w.p1.z) || w.p1.z == 0.0) {
                    w.p1.z = w.height;
                }
            }
        }
        //Process buildings
        rtree = new STRtree(buildingNodeCapacity);
        buildingsWideAnglePoints.clear();
        for (int j = 0; j < buildings.size(); j++) {
            Building building = buildings.get(j);
            buildingsWideAnglePoints.put(j + 1,
                    getWideAnglePointsByBuilding(j + 1, 0, 2 * Math.PI));
            List<Wall> walls = new ArrayList<>();
            Coordinate[] coords = building.poly.getCoordinates();
            for (int i = 0; i < coords.length - 1; i++) {
                LineSegment lineSegment = new LineSegment(coords[i], coords[i + 1]);
                Wall w = new Wall(lineSegment, j, IntersectionType.BUILDING).setProcessedWallIndex(processedWalls.size());
                walls.add(w);
                w.setAlpha(building.alphas);
                processedWalls.add(w);
                rtree.insert(lineSegment.toGeometry(FACTORY).getEnvelopeInternal(), processedWalls.size()-1);
            }
            building.setWalls(walls);
        }
        for (int j = 0; j < walls.size(); j++) {
            Wall wall = walls.get(j);
            Coordinate[] coords = new Coordinate[]{wall.p0, wall.p1};
            for (int i = 0; i < coords.length - 1; i++) {
                LineSegment lineSegment = new LineSegment(coords[i], coords[i + 1]);
                Wall w = new Wall(lineSegment, j, IntersectionType.WALL).setProcessedWallIndex(processedWalls.size());
                w.setAlpha(wall.alphas);
                processedWalls.add(w);
                rtree.insert(lineSegment.toGeometry(FACTORY).getEnvelopeInternal(), processedWalls.size()-1);
            }
        }
        //Process the ground effects
        groundEffectsRtree = new STRtree(TREE_NODE_CAPACITY);
        for (int j = 0; j < groundAbsorptions.size(); j++) {
            GroundAbsorption effect = groundAbsorptions.get(j);
            List<Polygon> polygons = new ArrayList<>();
            if (effect.geom instanceof Polygon) {
                polygons.add((Polygon) effect.geom);
            }
            if (effect.geom instanceof MultiPolygon) {
                MultiPolygon multi = (MultiPolygon) effect.geom;
                for (int i = 0; i < multi.getNumGeometries(); i++) {
                    polygons.add((Polygon) multi.getGeometryN(i));
                }
            }
            for (Polygon poly : polygons) {
                groundEffectsRtree.insert(poly.getEnvelopeInternal(), j);
                Coordinate[] coords = poly.getCoordinates();
                for (int k = 0; k < coords.length - 1; k++) {
                    LineSegment line = new LineSegment(coords[k], coords[k + 1]);
                    processedWalls.add(new Wall(line, j, GROUND_EFFECT).setProcessedWallIndex(processedWalls.size()));
                    rtree.insert(new Envelope(line.p0, line.p1), processedWalls.size() - 1);
                }
            }
        }
        rtree.build();
        groundEffectsRtree.build();
        return this;
    }


    /**
     *
     * @param reflectionPt
     * @return
     */
    public double getZ(Coordinate reflectionPt) {
        List<Integer> ids = buildingTree.query(new Envelope(reflectionPt));
        if(ids.isEmpty()) {
            return getZGround(reflectionPt);
        }
        else {
            for(Integer id : ids) {
                Geometry buildingGeometry =  buildings.get(id - 1).getGeometry();
                if(buildingGeometry.getEnvelopeInternal().intersects(reflectionPt)) {
                    return buildingGeometry.getCoordinate().z;
                }
            }
            return getZGround(reflectionPt);
        }
    }


    /**
     *
     * @param env
     * @return
     */
    public List<Wall> getWallsIn(Envelope env) {
        List<Wall> list = new ArrayList<>();
        List<Integer> indexes = rtree.query(env);
        for(int i : indexes) {
            Wall w = getProcessedWalls().get(i);
            if(w.getType().equals(BUILDING) || w.getType().equals(WALL)) {
                list.add(w);
            }
        }
        return list;
    }



    /**
     * Retrieve the cutting profile following the line build from the given coordinates.
     * @param c0 Starting point.
     * @param c1 Ending point.
     * @return Cutting profile.
     */
    public CutProfile getProfile(Coordinate c0, Coordinate c1) {
        return getProfile(c0, c1, 0.0);
    }

    /**
     * Retrieve the cutting profile following the line build from the given cut points.
     * @param c0 Starting point.
     * @param c1 Ending point.
     * @return Cutting profile.
     */
    public CutProfile getProfile(CutPoint c0, CutPoint c1) {
        return getProfile(c0, c1, 0.0);
    }

    /**
     * Retrieve the cutting profile following the line build from the given cut points.
     * @param c0 Starting point.
     * @param c1 Ending point.
     * @return Cutting profile.
     */
    public CutProfile getProfile(CutPoint c0, CutPoint c1, double gS) {
        CutProfile profile = getProfile(c0.getCoordinate(), c1.getCoordinate(), gS);

        profile.source.buildingId = c0.buildingId;
        profile.source.groundCoef = c0.groundCoef;
        profile.source.wallAlpha = c0.wallAlpha;

        profile.receiver.buildingId = c1.buildingId;
        profile.receiver.groundCoef = c1.groundCoef;
        profile.receiver.wallAlpha = c1.wallAlpha;

        return profile;
    }


    /**
     * split the segment between two points in segments of a given length maxLineLength
     * @param c0
     * @param c1
     * @param maxLineLength
     * @return
     */
    public static List<LineSegment> splitSegment(Coordinate c0, Coordinate c1, double maxLineLength) {
        List<LineSegment> lines = new ArrayList<>();
        LineSegment fullLine = new LineSegment(c0, c1);
        double l = c0.distance(c1);
        //If the line length if greater than the MAX_LINE_LENGTH value, split it into multiple lines
        if(l < maxLineLength) {
            lines.add(fullLine);
        }
        else {
            double frac = maxLineLength /l;
            for(int i = 0; i<l/ maxLineLength; i++) {
                Coordinate p0 = fullLine.pointAlong(i*frac);
                p0.z = c0.z + (c1.z - c0.z) * i*frac;
                Coordinate p1 = fullLine.pointAlong(Math.min((i+1)*frac, 1.0));
                p1.z = c0.z + (c1.z - c0.z) * Math.min((i+1)*frac, 1.0);
                lines.add(new LineSegment(p0, p1));
            }
        }
        return lines;
    }

    /**
     * Retrieve the cutting profile following the line build from the given coordinates.
     * @param c0 Starting point.
     * @param c1 Ending point.
     * @return Cutting profile.
     */
    public CutProfile getProfile(Coordinate c0, Coordinate c1, double gS) {
        CutProfile profile = new CutProfile();
        //Topography
        if(topoTree != null) {
            addTopoCutPts(c0, c1, profile);
            for (CutPoint cut : profile.getCutPoints()){
                List<Integer> groundEffectsResult = (List<Integer>) groundEffectsRtree.query(new Envelope(cut.coordinate));
                if (groundEffectsResult.size() >0) {
                    cut.groundCoef = groundAbsorptions.get(groundEffectsResult.get(groundEffectsResult.size() - 1)).getCoefficient();
                }
                else {
                    cut.groundCoef = gS;
                }
            }
        }
        // Split line into segments for structures based on RTree in order to limit the number of queries
        // (for large area of the line segment envelope)
        LineSegment fullLine = new LineSegment(c0, c1);
        List<LineSegment> lines = splitSegment(c0, c1, maxLineLength);

        //Buildings and Ground effect
        if(rtree != null) {
            addGroundBuildingCutPts(lines, fullLine, profile);
        }

        //Sort all the cut point in order to set the ground coefficients.
        profile.sort(c0, c1);
        //Add base cut for buildings
        addBuildingBaseCutPts(profile, c0, c1);
        /*profile.addSource(c0);
        profile.addReceiver(c1);*/
        List<Integer> groundEffectsResult = (List<Integer>) groundEffectsRtree.query(new Envelope(profile.pts.get(0).coordinate));
        if (groundEffectsResult.size() >0) {
            profile.pts.get(0).groundCoef = groundAbsorptions.get(groundEffectsResult.get(groundEffectsResult.size() - 1)).getCoefficient();
        }
        else {
            profile.pts.get(0).groundCoef = gS;
        }
        List<Integer> groundEffectsResults = (List<Integer>) groundEffectsRtree.query(new Envelope(profile.pts.get(profile.pts.size()-1).coordinate));
        if (groundEffectsResults.size() >0) {
            profile.pts.get(profile.pts.size() - 1).groundCoef = groundAbsorptions.get(groundEffectsResults.get(groundEffectsResults.size() - 1)).getCoefficient();
        }
        else {
            profile.pts.get(profile.pts.size() - 1).groundCoef = gS;
        }
        //If ordering puts source at last position, reverse the list
        if(profile.pts.get(0) != profile.source) {
            if(profile.pts.get(profile.pts.size()-1) != profile.source && profile.pts.get(0) != profile.source) {
                LOGGER.error("The source have to be first or last cut point");
            }
            if(profile.pts.get(profile.pts.size()-1) != profile.receiver && profile.pts.get(0) != profile.receiver) {
                LOGGER.error("The receiver have to be first or last cut point");
            }
            profile.reverse();
        }
        // Remove all
        int k = 0;
        for (int i = 0; i<profile.pts.size()-2; i++) {
            if (profile.pts.get(i).getType().equals(profile.pts.get(i + 1).getType()) && !profile.pts.get(i).getType().equals(BUILDING)  && profile.pts.get(i).coordinate.equals(profile.pts.get(i + 1).coordinate)) {
                for (int j = i + 1; j < profile.pts.size() - 1; j++) {
                    if (profile.pts.get(i).getType().equals(profile.pts.get(j).getType()) && profile.pts.get(i).coordinate.equals(profile.pts.get(j).coordinate) && profile.pts.get(i).groundCoef == profile.pts.get(j).getGroundCoef()) {
                        profile.pts.remove(j);
                        j--;
                    }
                }

            }
        }
        for (int i = 0; i<profile.pts.size()-2; i++){
            if(profile.pts.get(i).getType().equals(profile.pts.get(i+1).getType())  && !profile.pts.get(i).getType().equals(BUILDING) && profile.pts.get(i).coordinate.equals(profile.pts.get(i+1).coordinate) && profile.pts.get(i).groundCoef!= profile.pts.get(i+1).getGroundCoef()){
                k++;
                if (k%2 != 0.0 ){ // la division entière
                    if (profile.pts.get(i).groundCoef == 0.0 ) {
                        profile.pts.remove(i+1);
                        i--;
                    }
                    else {
                        profile.pts.remove(i);
                    }
                }
                else {
                    if (profile.pts.get(i).groundCoef == 0.0 ) {
                        profile.pts.remove(i);
                    }
                    else {
                        profile.pts.remove(i+1);
                        i--;
                    }
                }

            }
        }

        return profile;
    }

    /**
     *
     * @param profile
     * @param c0
     * @param c1
     */
    private void addBuildingBaseCutPts(CutProfile profile, Coordinate c0, Coordinate c1) {
        ArrayList<CutPoint> pts = new ArrayList<>(profile.pts.size());
        int buildId = -1;
        CutPoint lastBuild = null;
        for(int i=0; i<profile.pts.size(); i++) {
            CutPoint cut = profile.pts.get(i);
            if(cut.getType().equals(BUILDING)) {
                if (buildId == -1) {
                    buildId = cut.getId();
                    CutPoint grd = new CutPoint(cut);
                    grd.getCoordinate().z = getZGround(cut);
                    pts.add(grd);
                    pts.add(cut);
                }
                else if(buildId == cut.getId()) {
                    pts.add(cut);
                }
                else {
                    CutPoint grd0 = new CutPoint(lastBuild);
                    grd0.getCoordinate().z = getZGround(grd0);
                    pts.add(pts.indexOf(lastBuild)+1, grd0);
                    CutPoint grd1 = new CutPoint(cut);
                    grd1.getCoordinate().z = getZGround(grd1);
                    pts.add(grd1);
                    pts.add(cut);
                    buildId = cut.getId();
                }
                lastBuild = cut;
            }
            else if(cut.getType().equals(RECEIVER)) {
                if(buildId != -1) {
                    buildId = -1;
                    CutPoint grd0 = new CutPoint(pts.get(pts.size()-1));
                    grd0.getCoordinate().z = getZGround(grd0);
                    pts.add(grd0);
                }
                pts.add(cut);
            }
            else {
                pts.add(cut);
            }
        }
        if(buildId != -1) {
            CutPoint grd0 = new CutPoint(lastBuild);
            grd0.getCoordinate().z = getZGround(grd0);
            pts.add(pts.indexOf(lastBuild)+1, grd0);
        }
        profile.pts = pts;
        profile.addSource(c0);
        profile.addReceiver(c1);
    }


    /**
     *
     * @param lines
     * @param fullLine
     * @param profile
     */
    private void addGroundBuildingCutPts(List<LineSegment> lines, LineSegment fullLine, CutProfile profile) {
        List<Integer> indexes = new ArrayList<>();
        int k = 0;
        for (LineSegment line : lines) {
            indexes.addAll(rtree.query(new Envelope(line.p0, line.p1)));
        }
        indexes = indexes.stream().distinct().collect(Collectors.toList());
        Map<Integer, Coordinate> processedGround = new HashMap<>();
        for (int i : indexes) {
            Wall facetLine = processedWalls.get(i);
            Coordinate intersection = fullLine.intersection(facetLine.ls);
            if (intersection != null) {
                intersection = new Coordinate(intersection);
                if(!isNaN(facetLine.p0.z) && !isNaN(facetLine.p1.z)) {
                    if(facetLine.p0.z == facetLine.p1.z) {
                        intersection.z = facetLine.p0.z;
                    }
                    else {
                        intersection.z = facetLine.p0.z + ((intersection.x - facetLine.p0.x) / (facetLine.p1.x - facetLine.p0.x) * (facetLine.p1.z - facetLine.p0.z));
                    }
                }
                else if(topoTree == null) {
                    intersection.z = NaN;
                }
                else {
                    intersection.z = getZGround(intersection);
                }
                if(facetLine.type == IntersectionType.BUILDING) {
                    profile.addBuildingCutPt(intersection, facetLine.originId, i, facetLine.p0.equals(intersection)||facetLine.p1.equals(intersection));
                    k=1;

                }
                else if(facetLine.type == IntersectionType.WALL) {
                    profile.addWallCutPt(intersection, facetLine.originId, facetLine.p0.equals(intersection)||facetLine.p1.equals(intersection), facetLine.alphas);
                    List<Integer> groundEffectsResult = (List<Integer>) groundEffectsRtree.query(new Envelope(profile.pts.get(profile.pts.size()-1).coordinate));
                    List<Integer> groundEffectsResult = (List<Integer>) groundEffectsRtree.query(new Envelope(profile.pts.get(profile.pts.size()-1).coordinate));
                    if(groundEffectsResult.size()>0 && groundAbsorptions.size() > 0){
                        profile.pts.get(profile.pts.size() - 1).groundCoef = groundAbsorptions.get(groundEffectsResult.get(groundEffectsResult.size() - 1)).getCoefficient();
                    }
                    else if (groundEffectsResult.size()<=0 && groundAbsorptions.size() > 0){
                        profile.pts.get(profile.pts.size()-1).groundCoef = groundAbsorptions.get(facetLine.originId).getCoefficient();
                    }
                }
                else if(facetLine.type == GROUND_EFFECT) {
                    if(!intersection.equals(facetLine.p0) && !intersection.equals(facetLine.p1)) {
                        processedGround.put(facetLine.originId, intersection);
                        //Add cut point only if the same origin Id is for two different coordinate to avoid having
                        // more than one cutPoint with the same id on the same coordinate
                        if(processedGround.containsKey(facetLine.originId) ) {
                            if (k > 0) {
                                CutProfile cutpt = new CutProfile();
                                cutpt.addGroundCutPt(intersection, facetLine.originId);
                                cutpt.addGroundCutPt(processedGround.remove(facetLine.originId), facetLine.originId);
                                cutpt.pts.sort(CutPoint::compareTox01y01);
                                List<Integer> groundEffectsResult1 = (List<Integer>) groundEffectsRtree.query(new Envelope(cutpt.pts.get(0).coordinate));
                                List<Integer> groundEffectsResult2 = (List<Integer>) groundEffectsRtree.query(new Envelope(cutpt.pts.get(1).coordinate));
                                if (groundEffectsResult1.equals(groundEffectsResult2) && groundEffectsResult2.size() > 1){
                                    if (groundEffectsResult1.get(0) < groundEffectsResult1.get(1)){
                                        cutpt.pts.get(0).groundCoef = groundAbsorptions.get(groundEffectsResult1.get(groundEffectsResult1.size() - 2)).getCoefficient();
                                        cutpt.pts.get(1).groundCoef = groundAbsorptions.get(groundEffectsResult2.get(groundEffectsResult2.size() - 1)).getCoefficient();
                                    }
                                    else {
                                        cutpt.pts.get(0).groundCoef = groundAbsorptions.get(groundEffectsResult1.get(groundEffectsResult1.size() - 1)).getCoefficient();
                                        cutpt.pts.get(1).groundCoef = groundAbsorptions.get(groundEffectsResult2.get(groundEffectsResult2.size() - 2)).getCoefficient();
                                    }
                                }
                                else {
                                    cutpt.pts.get(0).groundCoef = groundAbsorptions.get(groundEffectsResult1.get(groundEffectsResult1.size() - 1)).getCoefficient();
                                    cutpt.pts.get(1).groundCoef = groundAbsorptions.get(groundEffectsResult2.get(groundEffectsResult2.size() - 1)).getCoefficient();
                                }
                                cutpt.pts.get(0).coordinate.z = 0.0;
                                cutpt.pts.get(1).coordinate.z = 0.0;
                                profile.addCutPt(cutpt.pts.get(0));
                                profile.addCutPt(cutpt.pts.get(1));
                                k--;
                            } else {
                                profile.addGroundCutPt(intersection, facetLine.originId);
                                List<Integer> groundEffectsResult = (List<Integer>) groundEffectsRtree.query(new Envelope(profile.pts.get(profile.pts.size() - 1).coordinate));
                                profile.pts.get(profile.pts.size() - 1).groundCoef = groundAbsorptions.get(groundEffectsResult.get(groundEffectsResult.size() - 1)).getCoefficient();
                                profile.addGroundCutPt(processedGround.remove(facetLine.originId), facetLine.originId);
                                List<Integer> groundEffectsResults = (List<Integer>) groundEffectsRtree.query(new Envelope(profile.pts.get(profile.pts.size() - 1).coordinate));
                                profile.pts.get(profile.pts.size() - 1).groundCoef = groundAbsorptions.get(groundEffectsResults.get(groundEffectsResults.size() - 1)).getCoefficient();
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Compute the next triangle index.Find the shortest intersection point of
     * triIndex segments to the p1 coordinate
     *
     * @param triIndex        Triangle index
     * @param propagationLine Propagation line
     * @return Next triangle to the specified direction, -1 if there is no
     * triangle neighbor.
     */
    private int getNextTri(final int triIndex,
                           final LineSegment propagationLine,
                           HashSet<Integer> navigationHistory, final Coordinate segmentIntersection) {
        final Triangle tri = topoTriangles.get(triIndex);
        final Triangle triNeighbors = topoNeighbors.get(triIndex);
        int nearestIntersectionSide = -1;
        int idNeighbor;

        double nearestIntersectionPtDist = Double.MAX_VALUE;
        // Find intersection pt
        final Coordinate aTri = this.vertices.get(tri.getA());
        final Coordinate bTri = this.vertices.get(tri.getB());
        final Coordinate cTri = this.vertices.get(tri.getC());
        double distline_line;
        // Intersection First Side
        idNeighbor = triNeighbors.get(2);
        if (!navigationHistory.contains(idNeighbor)) {
            LineSegment triSegment = new LineSegment(aTri, bTri);
            Coordinate[] closestPoints = propagationLine.closestPoints(triSegment);
            Coordinate intersectionTest = null;
            if(closestPoints.length == 2 && closestPoints[0].distance(closestPoints[1]) < JTSUtility.TRIANGLE_INTERSECTION_EPSILON) {
                intersectionTest = new Coordinate(closestPoints[0].x, closestPoints[0].y, Vertex.interpolateZ(closestPoints[0], triSegment.p0, triSegment.p1));
            }
            if(intersectionTest != null) {
                distline_line = propagationLine.p1.distance(intersectionTest);
                if (distline_line < nearestIntersectionPtDist) {
                    segmentIntersection.setCoordinate(intersectionTest);
                    nearestIntersectionPtDist = distline_line;
                    nearestIntersectionSide = 2;
                }
            }
        }
        // Intersection Second Side
        idNeighbor = triNeighbors.get(0);
        if (!navigationHistory.contains(idNeighbor)) {
            LineSegment triSegment = new LineSegment(bTri, cTri);
            Coordinate[] closestPoints = propagationLine.closestPoints(triSegment);
            Coordinate intersectionTest = null;
            if(closestPoints.length == 2 && closestPoints[0].distance(closestPoints[1]) < JTSUtility.TRIANGLE_INTERSECTION_EPSILON) {
                intersectionTest = new Coordinate(closestPoints[0].x, closestPoints[0].y, Vertex.interpolateZ(closestPoints[0], triSegment.p0, triSegment.p1));
            }
            if(intersectionTest != null) {
                distline_line = propagationLine.p1.distance(intersectionTest);
                if (distline_line < nearestIntersectionPtDist) {
                    segmentIntersection.setCoordinate(intersectionTest);
                    nearestIntersectionPtDist = distline_line;
                    nearestIntersectionSide = 0;
                }
            }
        }
        // Intersection Third Side
        idNeighbor = triNeighbors.get(1);
        if (!navigationHistory.contains(idNeighbor)) {
            LineSegment triSegment = new LineSegment(cTri, aTri);
            Coordinate[] closestPoints = propagationLine.closestPoints(triSegment);
            Coordinate intersectionTest = null;
            if(closestPoints.length == 2 && closestPoints[0].distance(closestPoints[1]) < JTSUtility.TRIANGLE_INTERSECTION_EPSILON) {
                intersectionTest = new Coordinate(closestPoints[0].x, closestPoints[0].y, Vertex.interpolateZ(closestPoints[0], triSegment.p0, triSegment.p1));
            }
            if(intersectionTest != null) {
                distline_line = propagationLine.p1.distance(intersectionTest);
                if (distline_line < nearestIntersectionPtDist) {
                    segmentIntersection.setCoordinate(intersectionTest);
                    nearestIntersectionSide = 1;
                }
            }
        }
        if(nearestIntersectionSide > -1) {
            return triNeighbors.get(nearestIntersectionSide);
        } else {
            return -1;
        }
    }


    /**
     * Get coordinates of triangle vertices
     * @param triIndex Index of triangle
     * @return triangle vertices
     */
    Coordinate[] getTriangle(int triIndex) {
        final Triangle tri = this.topoTriangles.get(triIndex);
        return new Coordinate[]{this.vertices.get(tri.getA()),
                this.vertices.get(tri.getB()), this.vertices.get(tri.getC())};
    }


    /**
     * Return the triangle id from a point coordinate inside the triangle
     *
     * @param pt Point test
     * @return Triangle Id, Or -1 if no triangle has been found
     */

    public int getTriangleIdByCoordinate(Coordinate pt) {
        Envelope ptEnv = new Envelope(pt);
        ptEnv.expandBy(1);
        List res = topoTree.query(new Envelope(ptEnv));
        double minDistance = Double.MAX_VALUE;
        int minDistanceTriangle = -1;
        for(Object objInd : res) {
            int triId = (Integer) objInd;
            Coordinate[] tri = getTriangle(triId);
            AtomicReference<Double> err = new AtomicReference<>(0.);
            JTSUtility.dotInTri(pt, tri[0], tri[1], tri[2], err);
            if (err.get() < minDistance) {
                minDistance = err.get();
                minDistanceTriangle = triId;
            }
        }
        return minDistanceTriangle;
    }

    /**
     *
     * @param p1
     * @param p2
     * @param profile
     */
    public void addTopoCutPts(Coordinate p1, Coordinate p2, CutProfile profile) {
        List<Coordinate> coordinates = getTopographicProfile(p1, p2);
        // Remove unnecessary points
        ArrayList<Coordinate> retainedCoordinates = new ArrayList<>(coordinates.size());
        for(int i =0; i < coordinates.size(); i++) {
            // Always add first and last points
            Coordinate previous;
            Coordinate current = coordinates.get(i);
            Coordinate next;
            if(retainedCoordinates.isEmpty()) {
                previous = new Coordinate(p1.x, p1.y, getZGround(p1));
            } else {
                previous = retainedCoordinates.get(retainedCoordinates.size() - 1);
            }
            if(i == coordinates.size() - 1) {
                next = new Coordinate(p2.x, p2.y, getZGround(p2));
            } else {
                next = coordinates.get(i + 1);
            }
            // Do not add topographic points which are simply the linear interpolation between two points
            if(CGAlgorithms3D.distancePointSegment(current, previous, next) >= DELTA) {
                retainedCoordinates.add(coordinates.get(i));
            }
        }
        // Feed profile
        profile.reservePoints(retainedCoordinates.size());
        for(int i =0; i < retainedCoordinates.size(); i++) {
            profile.addTopoCutPt(retainedCoordinates.get(i), i);
        }
    }

    /**
     * Find closest triangle that intersects with segment
     * @param segment Segment to intersects will all triangles
     * @param intersection Found closest intersection point with p0
     * @param intersectionTriangle Found closest intersection triangle
     * @return True if at least one triangle as been found on intersection
     */
    boolean findClosestTriangleIntersection(LineSegment segment, final Coordinate intersection, AtomicInteger intersectionTriangle) {
        Envelope queryEnvelope = new Envelope(segment.p0);
        queryEnvelope.expandToInclude(segment.p1);
        if(queryEnvelope.getHeight() < 1.0 || queryEnvelope.getWidth() < 1) {
            queryEnvelope.expandBy(1.0);
        }
        List res = topoTree.query(queryEnvelope);
        double minDistance = Double.MAX_VALUE;
        int minDistanceTriangle = -1;
        GeometryFactory factory = new GeometryFactory();
        LineString lineString = factory.createLineString(new Coordinate[]{segment.p0, segment.p1});
        Coordinate intersectionPt = null;
        for(Object objInd : res) {
            int triId = (Integer) objInd;
            Coordinate[] tri = getTriangle(triId);
            Geometry triangleGeometry = factory.createPolygon(new Coordinate[]{ tri[0], tri[1], tri[2], tri[0]});
            if(triangleGeometry.intersects(lineString)) {
                Coordinate[] nearestCoordinates = DistanceOp.nearestPoints(triangleGeometry, lineString);
                for (Coordinate nearestCoordinate : nearestCoordinates) {
                    double distance = nearestCoordinate.distance(segment.p0);
                    if (distance < minDistance) {
                        minDistance = distance;
                        minDistanceTriangle = triId;
                        intersectionPt = nearestCoordinate;
                    }
                }
            }
        }
        if(minDistanceTriangle != -1) {
            Coordinate[] tri = getTriangle(minDistanceTriangle);
            // Compute interpolated Z of the intersected point on the nearest triangle
            intersectionPt.setZ(Vertex.interpolateZ(intersectionPt, tri[0], tri[1], tri[2]));
            intersection.setCoordinate(intersectionPt);
            intersectionTriangle.set(minDistanceTriangle);
            return true;
        } else {
            return false;
        }
    }

    /**
     *
     * @param p1
     * @param p2
     * @return
     */
    public List<Coordinate> getTopographicProfile(Coordinate p1, Coordinate p2) {
        if(topoTree == null) {
            return new ArrayList<>();
        }
        List<Coordinate> outputPoints = new ArrayList<>();
        //get origin triangle id
        int curTriP1 = getTriangleIdByCoordinate(p1);
        LineSegment propaLine = new LineSegment(p1, p2);
        if(curTriP1 == -1) {
            // we are outside the bounds of the triangles
            // Find the closest triangle to p1
            Coordinate intersectionPt = new Coordinate();
            AtomicInteger minDistanceTriangle = new AtomicInteger();
            if(findClosestTriangleIntersection(propaLine, intersectionPt, minDistanceTriangle)) {
                outputPoints.add(intersectionPt);
                curTriP1 = minDistanceTriangle.get();
            } else {
                return outputPoints;
            }
        }
        HashSet<Integer> navigationHistory = new HashSet<Integer>();
        int navigationTri = curTriP1;
        while (navigationTri != -1) {
            navigationHistory.add(navigationTri);
            Coordinate intersectionPt = new Coordinate();
            int propaTri = this.getNextTri(navigationTri, propaLine, navigationHistory, intersectionPt);
            // Found next triangle (if propaTri >= 0)
            // extract X,Y,Z values of intersection with triangle segment
            if(!Double.isNaN(intersectionPt.z)) {
                outputPoints.add(intersectionPt);
            }
            navigationTri = propaTri;
        }
        return outputPoints;
    }

    /**
     * Get the topographic height of a point.
     * @param c Coordinate of the point.
     * @return Topographic height of the point.
     */
    @Deprecated
    public double getZGround(Coordinate c) {
        return getZGround(new CutPoint(c, TOPOGRAPHY, -1));
    }

    /**
     * @return True if digital elevation model has been added
     */
    public boolean hasDem() {
        return topoTree != null && topoTree.size() > 0;
    }


    /**
     *
     * @param cut
     * @return
     */
    public double getZGround(CutPoint cut) {
        if(!Double.isNaN(cut.zGround)) {
            return cut.zGround;
        }
        if(topoTree == null) {
            cut.zGround = NaN;
            return 0.0;
        }
        Envelope env = new Envelope(cut.coordinate);
        List<Integer> list = (List<Integer>)topoTree.query(env);
        for (int i : list) {
            final Triangle tri = topoTriangles.get(i);
            final Coordinate p1 = vertices.get(tri.getA());
            final Coordinate p2 = vertices.get(tri.getB());
            final Coordinate p3 = vertices.get(tri.getC());
            if(JTSUtility.dotInTri(cut.coordinate, p1, p2, p3)) {
                double z = Vertex.interpolateZ(cut.coordinate, p1, p2, p3);
                cut.zGround = z;
                return z;
            }
        }
        cut.zGround = NaN;
        return 0.0;
    }

    /**
     * Different type of intersection.
     */
    public enum IntersectionType {BUILDING, WALL, TOPOGRAPHY, GROUND_EFFECT, SOURCE, RECEIVER;

        public PointPath.POINT_TYPE toPointType(PointPath.POINT_TYPE dflt) {
            if(this.equals(SOURCE)){
                return PointPath.POINT_TYPE.SRCE;
            }
            else if(this.equals(RECEIVER)){
                return PointPath.POINT_TYPE.RECV;
            }
            else {
                return dflt;
            }
        }
    }

    /**
     * Cutting profile containing all th cut points with there x,y,z position.
     */


    /**
     * Profile cutting point.
     */


    public interface Obstacle{
        Collection<? extends Wall> getWalls();
    }



    /**
     * Ground effect.
     */



    //TODO methods to check
    public static final double wideAngleTranslationEpsilon = 0.01;

    /**
     * @param build 1-n based building identifier
     * @return
     */
    public ArrayList<Coordinate> getPrecomputedWideAnglePoints(int build) {
        return buildingsWideAnglePoints.get(build);
    }

    /**
     *
     * @param build
     * @param minAngle
     * @param maxAngle
     * @return
     */
    public ArrayList<Coordinate> getWideAnglePointsByBuilding(int build, double minAngle, double maxAngle) {
        ArrayList <Coordinate> verticesBuilding = new ArrayList<>();
        Coordinate[] ring = getBuilding(build-1).getGeometry().getExteriorRing().getCoordinates().clone();
        if(!isCCW(ring)) {
            for (int i = 0; i < ring.length / 2; i++) {
                Coordinate temp = ring[i];
                ring[i] = ring[ring.length - 1 - i];
                ring[ring.length - 1 - i] = temp;
            }
        }
        for(int i=0; i < ring.length - 1; i++) {
            int i1 = i > 0 ? i-1 : ring.length - 2;
            int i3 = i + 1;
            double smallestAngle = Angle.angleBetweenOriented(ring[i1], ring[i], ring[i3]);
            double openAngle;
            if(smallestAngle >= 0) {
                // corresponds to a counterclockwise (CCW) rotation
                openAngle = smallestAngle;
            } else {
                // corresponds to a clockwise (CW) rotation
                openAngle = 2 * Math.PI + smallestAngle;
            }
            // Open Angle is the building angle in the free field area
            if(openAngle > minAngle && openAngle < maxAngle) {
                // corresponds to a counterclockwise (CCW) rotation
                double midAngle = openAngle / 2;
                double midAngleFromZero = Angle.angle(ring[i], ring[i1]) + midAngle;
                Coordinate offsetPt = new Coordinate(
                        ring[i].x + Math.cos(midAngleFromZero) * wideAngleTranslationEpsilon,
                        ring[i].y + Math.sin(midAngleFromZero) * wideAngleTranslationEpsilon,
                        buildings.get(build - 1).getGeometry().getCoordinate().z + wideAngleTranslationEpsilon);
                verticesBuilding.add(offsetPt);
            }
        }
        verticesBuilding.add(verticesBuilding.get(0));
        return verticesBuilding;
    }

    /**
     * Find all buildings (polygons) that 2D cross the line p1 to p2
     * @param p1 first point of line
     * @param p2 second point of line
     * @param visitor Iterate over found buildings
     */
    public void getBuildingsOnPath(Coordinate p1, Coordinate p2, ItemVisitor visitor) {
        try {
            List<LineSegment> lines = splitSegment(p1, p2, maxLineLength);
            for(LineSegment segment : lines) {
                Envelope pathEnv = new Envelope(segment.p0, segment.p1);
                buildingTree.query(pathEnv, visitor);
            }
        } catch (IllegalStateException ex) {
            //Ignore
        }
    }


    /**
     *
     * @param p1
     * @param p2
     * @param visitor
     */
    public void getWallsOnPath(Coordinate p1, Coordinate p2, ItemVisitor visitor) {
        Envelope pathEnv = new Envelope(p1, p2);
        try {
            wallTree.query(pathEnv, visitor);
        } catch (IllegalStateException ex) {
            //Ignore
        }
    }


    /**
     * Hold two integers. Used to store unique triangle segments
     */

}
