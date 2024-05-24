package org.noise_planet.nmtutorial01;

import org.cts.crs.CRSException;
import org.cts.op.CoordinateOperationException;
import org.h2.value.ValueBoolean;
import org.h2gis.api.EmptyProgressVisitor;
import org.h2gis.api.ProgressVisitor;
import org.h2gis.functions.io.geojson.GeoJsonRead;
import org.h2gis.functions.io.shp.SHPWrite;
import org.h2gis.utilities.GeometryMetaData;
import org.h2gis.utilities.GeometryTableUtilities;
import org.h2gis.utilities.JDBCUtilities;
import org.h2gis.utilities.TableLocation;
import org.h2gis.utilities.dbtypes.DBTypes;
import org.noise_planet.noisemodelling.jdbc.utils.IsoSurface;
import org.noise_planet.noisemodelling.jdbc.utils.CellIndex;
import org.noise_planet.noisemodelling.jdbc.NoiseMapParameters;
import org.noise_planet.noisemodelling.jdbc.NoiseMapMaker;
import org.noise_planet.noisemodelling.jdbc.NoiseMapByReceiverMaker;
import org.noise_planet.noisemodelling.jdbc.DelaunayReceiversMaker;
import org.noise_planet.noisemodelling.pathfinder.IComputePathsOut;
import org.noise_planet.noisemodelling.pathfinder.delaunay.LayerDelaunayError;
import org.noise_planet.noisemodelling.pathfinder.profilebuilder.ProfileBuilder;
import org.noise_planet.noisemodelling.pathfinder.utils.profiler.RootProgressVisitor;
import org.noise_planet.noisemodelling.pathfinder.utils.profiler.JVMMemoryMetric;
import org.noise_planet.noisemodelling.pathfinder.utils.documents.KMLDocument;
import org.noise_planet.noisemodelling.pathfinder.utils.profiler.ProfilerThread;
import org.noise_planet.noisemodelling.pathfinder.utils.profiler.ProgressMetric;
import org.noise_planet.noisemodelling.pathfinder.utils.profiler.ReceiverStatsMetric;
import org.noise_planet.noisemodelling.propagation.Attenuation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLStreamException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

class Main {
    public final static int MAX_OUTPUT_PROPAGATION_PATHS = 50000;

    public static void main(String[] args) throws SQLException, IOException, LayerDelaunayError {
        // Init output logger
        Logger logger = LoggerFactory.getLogger(Main.class);

        // Read working directory argument
        String workingDir = "target/";
        if (args.length > 0) {
            workingDir = args[0];
        }
        File workingDirPath = new File(workingDir).getAbsoluteFile();
        if(!workingDirPath.exists()) {
            if(!workingDirPath.mkdirs()) {
                logger.error(String.format("Cannot create working directory %s", workingDir));
                return;
            }
        }

        logger.info(String.format("Working directory is %s", workingDirPath.getAbsolutePath()));

        // Create spatial database named to current time
        DateFormat df = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.getDefault());

        // Open connection to database
        String dbName = new File(workingDir + "db_" + df.format(new Date())).toURI().toString();
        Connection connection = JDBCUtilities.wrapConnection(DbUtilities.createSpatialDataBase(dbName, true));
        Statement sql = connection.createStatement();

        // Import BUILDINGS

        logger.info("Import buildings");

        GeoJsonRead.importTable(connection, Main.class.getResource("buildings.geojson").getFile(), "BUILDINGS",
                ValueBoolean.TRUE);

        // Import noise source

        logger.info("Import noise source");

        GeoJsonRead.importTable(connection, Main.class.getResource("lw_roads.geojson").getFile(), "LW_ROADS",
                ValueBoolean.TRUE);
        // Set primary key
        sql.execute("ALTER TABLE LW_ROADS ALTER COLUMN PK INTEGER NOT NULL");
        sql.execute("ALTER TABLE LW_ROADS ADD PRIMARY KEY (PK)");
        sql.execute("DELETE FROM LW_ROADS WHERE PK != 102");

        // Import BUILDINGS

        logger.info("Generate receivers grid for noise map rendering");

        DelaunayReceiversMaker noiseMap = new DelaunayReceiversMaker("BUILDINGS", "LW_ROADS");

        AtomicInteger pk = new AtomicInteger(0);
        noiseMap.initialize(connection, new EmptyProgressVisitor());
        noiseMap.setGridDim(1);
        noiseMap.setMaximumArea(0);
        noiseMap.setIsoSurfaceInBuildings(false);

        for (int i = 0; i < noiseMap.getGridDim(); i++) {
            for (int j = 0; j < noiseMap.getGridDim(); j++) {
                logger.info("Compute cell " + (i * noiseMap.getGridDim() + j + 1) + " of " + noiseMap.getGridDim() * noiseMap.getGridDim());
                noiseMap.generateReceivers(connection, i, j, "RECEIVERS", "TRIANGLES", pk);
            }
        }
        // Import MNT

        logger.info("Import digital elevation model");

        GeoJsonRead.importTable(connection, Main.class.getResource("dem_lorient.geojson").getFile(), "DEM",
                ValueBoolean.TRUE);

        // Init NoiseModelling
        NoiseMapByReceiverMaker noiseMapByReceiverMaker = new NoiseMapByReceiverMaker("BUILDINGS", "LW_ROADS", "RECEIVERS");

        noiseMapByReceiverMaker.setMaximumPropagationDistance(100.0);
        noiseMapByReceiverMaker.setSoundReflectionOrder(0);
        noiseMapByReceiverMaker.setThreadCount(1);
        noiseMapByReceiverMaker.setComputeHorizontalDiffraction(false);
        noiseMapByReceiverMaker.setComputeVerticalDiffraction(true);
        // Building height field name
        noiseMapByReceiverMaker.setHeightField("HEIGHT");
        // Point cloud height above sea level POINT(X Y Z)
        noiseMapByReceiverMaker.setDemTable("DEM");

        // Init custom input in order to compute more than just attenuation
        // LW_ROADS contain Day Evening Night emission spectrum
        NoiseMapParameters noiseMapParameters = new NoiseMapParameters(NoiseMapParameters.INPUT_MODE.INPUT_MODE_LW_DEN);

        noiseMapParameters.setComputeLDay(true);
        noiseMapParameters.setComputeLEvening(true);
        noiseMapParameters.setComputeLNight(true);
        noiseMapParameters.setComputeLDEN(true);
        noiseMapParameters.setExportRaysMethod(org.noise_planet.noisemodelling.jdbc.NoiseMapParameters.ExportRaysMethods.TO_MEMORY);
        noiseMapParameters.setExportAttenuationMatrix(true);

        NoiseMapMaker tableWriter = new NoiseMapMaker(connection, noiseMapParameters);

        noiseMapByReceiverMaker.setPropagationProcessDataFactory(tableWriter);
        noiseMapByReceiverMaker.setComputeRaysOutFactory(tableWriter);

        RootProgressVisitor progressLogger = new RootProgressVisitor(1, true, 1);

        noiseMapByReceiverMaker.initialize(connection, new EmptyProgressVisitor());

        noiseMapParameters.getPropagationProcessPathData(NoiseMapParameters.TIME_PERIOD.DAY).setTemperature(20);
        noiseMapParameters.getPropagationProcessPathData(NoiseMapParameters.TIME_PERIOD.EVENING).setTemperature(16);
        noiseMapParameters.getPropagationProcessPathData(NoiseMapParameters.TIME_PERIOD.NIGHT).setTemperature(10);
        noiseMapParameters.setMaximumRaysOutputCount(MAX_OUTPUT_PROPAGATION_PATHS); // do not export more than this number of rays per computation area

        noiseMapByReceiverMaker.setGridDim(1);

        LocalDateTime now = LocalDateTime.now();
        ProfilerThread profilerThread = new ProfilerThread(new File(String.format("profile_%d_%d_%d_%dh%d.csv",
                now.getYear(), now.getMonthValue(), now.getDayOfMonth(), now.getHour(), now.getMinute())));
        profilerThread.addMetric(tableWriter);
        profilerThread.addMetric(new ProgressMetric(progressLogger));
        profilerThread.addMetric(new JVMMemoryMetric());
        profilerThread.addMetric(new ReceiverStatsMetric());
        profilerThread.setWriteInterval(60);
        profilerThread.setFlushInterval(60);
        noiseMapByReceiverMaker.setProfilerThread(profilerThread);
        // Set of already processed receivers
        Set<Long> receivers = new HashSet<>();

        logger.info("start");
        long start = System.currentTimeMillis();

        // Iterate over computation areas
        try {
            tableWriter.start();
            new Thread(profilerThread).start();
            // Fetch cell identifiers with receivers
            Map<CellIndex, Integer> cells = noiseMapByReceiverMaker.searchPopulatedCells(connection);
            ProgressVisitor progressVisitor = progressLogger.subProcess(cells.size());
            for(CellIndex cellIndex : new TreeSet<>(cells.keySet())) {
                // Run ray propagation
                IComputePathsOut out = noiseMapByReceiverMaker.evaluateCell(connection, cellIndex.getLatitudeIndex(), cellIndex.getLongitudeIndex(), progressVisitor, receivers);
                // Export as a Google Earth 3d scene
                if (out instanceof Attenuation) {
                    Attenuation cellStorage = (Attenuation) out;
                    exportScene(String.format(Locale.ROOT,"target/scene_%d_%d.kml", cellIndex.getLatitudeIndex(), cellIndex.getLongitudeIndex()), cellStorage.inputData.profileBuilder, cellStorage);
                }
            }
        } finally {
            profilerThread.stop();
            tableWriter.stop();
        }
        long computationTime = System.currentTimeMillis() - start;
        logger.info(String.format(Locale.ROOT, "Computed in %d ms, %.2f ms per receiver", computationTime,computationTime / (double)receivers.size()));


        logger.info("Create iso contours");
        int srid = GeometryTableUtilities.getSRID(connection, TableLocation.parse("LW_ROADS", DBTypes.H2GIS));
        List<Double> isoLevels = IsoSurface.NF31_133_ISO; // default values
        GeometryMetaData m = GeometryTableUtilities.getMetaData(connection, "RECEIVERS", "THE_GEOM");
        sql.execute("ALTER TABLE " + noiseMapParameters.getlDenTable() +
                " ADD COLUMN THE_GEOM "+m.getSQL());
        sql.execute(" UPDATE "+ noiseMapParameters.getlDenTable()+" SET THE_GEOM = (SELECT THE_GEOM FROM RECEIVERS R " +
                "WHERE R.PK = " + noiseMapParameters.getlDenTable() + ".IDRECEIVER)");
        IsoSurface isoSurface = new IsoSurface(isoLevels, srid);
        isoSurface.setSmoothCoefficient(0.5);
        isoSurface.setPointTable(noiseMapParameters.getlDenTable());
        isoSurface.createTable(connection);
        logger.info("Export iso contours");
        SHPWrite.exportTable(connection, "target/"+ isoSurface.getOutputTable()+".shp", isoSurface.getOutputTable(), ValueBoolean.TRUE);
        if(JDBCUtilities.tableExists(connection,  noiseMapParameters.getRaysTable())) {
            SHPWrite.exportTable(connection, "target/" + noiseMapParameters.getRaysTable() + ".shp", noiseMapParameters.getRaysTable(), ValueBoolean.TRUE);
        }
    }

    public static void exportScene(String name, ProfileBuilder builder, Attenuation result) throws IOException {
        try {
            FileOutputStream outData = new FileOutputStream(name);
            KMLDocument kmlDocument = new KMLDocument(outData);
            kmlDocument.setInputCRS("EPSG:2154");
            kmlDocument.writeHeader();
            if(builder != null) {
                kmlDocument.writeTopographic(builder.getTriangles(), builder.getVertices());
            }
            if(result != null) {
                kmlDocument.writeRays(result.getPropagationPaths());
            }
            if(builder != null) {
                kmlDocument.writeBuildings(builder);
                if(result != null && !result.getInputData().sourceGeometries.isEmpty() && !result.getInputData().receivers.isEmpty()) {
                    String layerName = "S:"+result.getInputData().sourcesPk.get(0)+" R:" + result.getInputData().receiversPk.get(0);
                    kmlDocument.writeProfile(layerName, builder.getProfile(result.getInputData().
                            sourceGeometries.get(0).getCoordinate(),result.getInputData().receivers.get(0)));
                }
            }

            kmlDocument.writeFooter();
        } catch (XMLStreamException | CoordinateOperationException | CRSException ex) {
            throw new IOException(ex);
        }
    }

}