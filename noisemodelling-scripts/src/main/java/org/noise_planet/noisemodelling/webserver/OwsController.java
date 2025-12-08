package org.noise_planet.noisemodelling.webserver;

import io.javalin.http.Context;
import net.opengis.wps10.ExecuteType;
import org.geotools.wps.WPSConfiguration;
import org.geotools.xsd.Parser;
import org.h2gis.functions.factory.H2GISFunctions;
import org.locationtech.jts.geom.Geometry;
import org.noise_planet.noisemodelling.scripts.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.h2.server.web.PageParser.escapeHtml;

/**
 * The OwsController class handles requests for OGC Web Services (OWS), including
 * WPS (Web Processing Service), WFS (Web Feature Service), and WCS (Web Coverage Service).
 * It provides functionalities for GET and POST requests, depending on the OWS service and
 * operation type.
 */
public class OwsController {

    /**
     * Represents the root directory where the Web Processing Service (WPS) scripts are stored.
     * This path serves as the base location for loading, managing, and executing the scripts
     * utilized by the OwsController for various WPS operations.
     *
     * The scripts within this directory are expected to adhere to a structured format, allowing
     * for proper organization and processing. The directory serves as a critical configuration
     * point for initializing and reloading scripts in the OwsController.
     */
    Path scriptsRoot;
    /**
     * Manages the database operations and configurations required for the web server.
     *
     * This instance is responsible for handling interactions with the database, such as
     * connecting to the database, initializing GIS-specific functions, and maintaining
     * the current database name and directory path.
     *
     * It ensures the proper setup and creation of directories for database storage during
     * its initialization. The {@code DataBaseManager} also facilitates the retrieval of
     * active database information, including the name and directory path, and supports
     * concurrent database access using H2's auto-server mode.
     */
    DataBaseManager dataBaseManager = new DataBaseManager();
    /**
     * A static collection of {@link ScriptWrapper} objects representing the
     * scripts available for the Web Processing Service (WPS). Each script is wrapped
     * in a {@link ScriptWrapper}, which encapsulates its metadata, inputs, outputs,
     * and logic to facilitate execution.
     *
     * This list serves as a central repository of scripts that can be dynamically
     * reloaded and used to process WPS requests. It plays a crucial role in
     * handling WPS operations by mapping process identifiers to their corresponding
     * Groovy script implementations.
     */
    static List<ScriptWrapper> wpsScripts;
    /**
     * An instance of WpsScriptWrapper used to manage the execution of WPS (Web Processing Service) scripts.
     * This wrapper facilitates the interaction between the application and the underlying scripting engine
     * to execute processes defined in scripts. It is responsible for script execution and handling inputs
     * and outputs for WPS processes.
     */
    WpsScriptWrapper wpsScriptWrapper;
    private static Logger LOGGER = null;
    private final ExecutorService scriptExecutor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService monitorExecutor = Executors.newSingleThreadScheduledExecutor();

    /**
     * Constructs a new instance of the OwsController class, initializing the environment
     * with the provided script directory and setting up the necessary components for
     * handling Web Processing Service (WPS) operations. The constructor also establishes
     * a database connection to load spatial functions required for operations.
     *
     * @param scriptsDir the directory path containing the WPS scripts. This is used to load
     *                   and organize scripts for processing various WPS requests.
     * @throws IOException if an I/O error occurs while initializing or loading scripts.
     * @throws SQLException if a database access error occurs while loading spatial functions.
     */
    public OwsController( Path scriptsDir,Logger logger) throws IOException, SQLException {
        LOGGER=logger;
        this.scriptsRoot = scriptsDir;
        wpsScriptWrapper = new WpsScriptWrapper(scriptsRoot);
        Map<String, List<File>> groupedScripts = wpsScriptWrapper.loadScripts();
        wpsScripts = WpsScriptWrapper.buildScriptWrappers(groupedScripts);
        try (Connection conn = dataBaseManager.openDatabaseConnection()) {
            H2GISFunctions.load(conn);
        }
    }
    /**
     * Reloads the WPS (Web Processing Service) scripts by reloading them from the file system
     * and rebuilding the corresponding script wrappers.
     *
     * This method uses {@link WpsScriptWrapper#loadScripts()} to scan and organize script files into
     * groups. The results are then processed by {@link WpsScriptWrapper#buildScriptWrappers(Map)} to
     * create a new set of script wrappers, which replace the existing ones.
     *
     * @throws IOException if an error occurs while loading or rebuilding the scripts.
     */
    public void reloadScripts() throws IOException {
        Map<String, List<File>> groupedScripts = wpsScriptWrapper.loadScripts();
        wpsScripts = WpsScriptWrapper.buildScriptWrappers(groupedScripts);
    }

    /**
     * Handles GET requests for the OWS (Web Services) endpoint. Based on the "service" query parameter,
     * it routes the request to the appropriate WPS, WFS, or WCS service handler. If the service type is unknown,
     * responds with HTTP 400 (Bad Request). Handles exceptions and responds with HTTP 500 (Internal Server Error)
     * in case of server-side errors.
     *
     * @param ctx the context of the current HTTP request, providing access to request parameters,
     *            response handling, and the ability to set content type and status codes
     */
    public void handleGet(Context ctx) {
        ctx.contentType("text/xml; charset=UTF-8");
        String service = ctx.queryParam("service");

        try {
            if ("WPS".equalsIgnoreCase(service)) {
                handleWPSGet(ctx);
            } else if ("WFS".equalsIgnoreCase(service)) {
                handleWFSGet(ctx);
            } else if ("WCS".equalsIgnoreCase(service)) {
                handleWCSGet(ctx);
            } else {
                ctx.status(400).result("Unknown service");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Handles HTTP GET requests for the WPS (Web Processing Service) by managing
     * the "GetCapabilities" and "DescribeProcess" operations. Based on the "request"
     * query parameter, this method retrieves WPS capabilities or details of a specific
     * process. Responds with appropriate XML content or error messages in case of invalid
     * requests or missing parameters.
     *
     * @param ctx the context of the current HTTP request, providing access to
     *            query parameters, response handling, and the ability to set
     *            content type and status codes
     */
    private void handleWPSGet(Context ctx) {
        String request = ctx.queryParam("request");
        ctx.contentType("text/xml; charset=UTF-8");

        if ("GetCapabilities".equalsIgnoreCase(request)) {
            String xml = WpsScriptWrapper.generateCapabilitiesXML(wpsScripts);
            ctx.result(xml);

        } else if ("DescribeProcess".equalsIgnoreCase(request)) {
            String identifier = ctx.queryParam("identifier");
            if (identifier == null || identifier.isEmpty()) {
                ctx.status(400).result("<ows:Exception>Missing identifier parameter</ows:Exception>");
                return;
            }

            Optional<ScriptWrapper> target = wpsScripts.stream()
                    .filter(w -> w.id.equals(identifier))
                    .findFirst();

            if (target.isPresent()) {
                ctx.result(WpsScriptWrapper.generateDescribeProcessXML(target.get()));
            } else {
                ctx.status(404).result("<ows:Exception>Process not found: " + identifier + "</ows:Exception>");
            }

        } else {
            ctx.status(400).result("<ows:Exception>Unknown WPS request: " + request + "</ows:Exception>");
        }
    }

    /**
     * Handles WFS (Web Feature Service) GET requests for the OWS (Web Services) endpoint.
     * Depending on the value of the "request" query parameter, this method determines the desired operation.
     * If the request is "GetCapabilities", the corresponding XML file is read and returned in the response.
     * For unknown or unsupported requests, it returns an HTTP 400 (Bad Request) status.
     *
     * @param ctx the context of the current HTTP request, providing access to query parameters,
     *            request and response handling, and allowing for status and body configuration
     * @throws Exception if an error occurs while reading or responding with the requested resource
     */
    private void handleWFSGet(Context ctx) throws Exception {
        String request = ctx.queryParam("request");
        if ("GetCapabilities".equalsIgnoreCase(request)) {
            try (InputStream xmlStream = Main.class.getResourceAsStream("static/xmlFiles/wfs.xml")){
                ctx.result(xmlStream.readAllBytes());
            }
        } else {
            ctx.status(400).result("Unknown WFS request");
        }
    }

    /**
     * Handles a Get request for the Web Coverage Service (WCS). The method processes
     * the incoming HTTP request by examining the "request" query parameter. If the
     * query parameter is "GetCapabilities", it responds with the contents of a WCS
     * capabilities XML file. If the request is not recognized, it responds with a
     * 400 HTTP status and an error message.
     *
     * @param ctx the context of the HTTP request, providing access to query parameters,
     *            response handling, status codes, and other request-related information
     * @throws Exception if an I/O error occurs while attempting to read the WCS capabilities
     *                   XML file or while processing the request
     */
    private void handleWCSGet(Context ctx) throws Exception {
        String request = ctx.queryParam("request");
        if ("GetCapabilities".equalsIgnoreCase(request)) {
            try (InputStream xmlStream = Main.class.getResourceAsStream("static/xmlFiles/wcs.xml")) {
                ctx.result(xmlStream.readAllBytes());
            }
        } else {
            ctx.status(400).result("Unknown WCS request");
        }
    }


    /**
     * Handles a WPS (Web Processing Service) POST request. It parses the request body to execute a
     * specified WPS process and returns the result or appropriate error messages based on the inputs
     * and execution status.
     *
     * @param ctx the context of the HTTP request, providing access to the request body, headers, response
     *            handling, and more.
     */
    public void handleWPSPost(Context ctx) {
        Future<Object> future = null;
        ScheduledFuture<?> watchdog = null;
        try {
            ExecuteType execute = null;
            Parser parser = new Parser(new WPSConfiguration());
            Object parsed = parser.parse(new ByteArrayInputStream(ctx.bodyAsBytes()));

            if (!(parsed instanceof ExecuteType)) {
                ctx.status(400).result("WPS request not valid");
                return;
            }

            execute = (ExecuteType) parsed;
            String processId = execute.getIdentifier().getValue();

            String[] parts = processId.split(":");
            if (parts.length != 2) {
                ctx.status(400).result("Invalid process ID");
                return;
            }

            String group = parts[0];
            String scriptName = parts[1];

            Optional<ScriptWrapper> wrapperOpt = wpsScripts.stream()
                    .filter(sw -> sw.id.equals(group + ":" + scriptName))
                    .findFirst();

            if (wrapperOpt.isEmpty()) {
                ctx.status(404).result("Script not found");
                return;
            }
            ScriptWrapper wrapper = wrapperOpt.get();
            Map<String, Object> inputs = ScriptWrapper.extractInputs(execute);
            // Timeout limit for watchdog = 3 minute
            final long TIMEOUT = 3 * 60 * 1000;
            final String scriptId = wrapper.id;
            // Will hold the thread actualy runing the WPS script, so the watchdog can inspect it
            AtomicReference<Thread> workerThreadRef = new AtomicReference<>();
            // Submit the script execution in a background thraed
            future = scriptExecutor.submit(() -> {
                workerThreadRef.set(Thread.currentThread());

                try (Connection conn = dataBaseManager.openDatabaseConnection()) {
                    H2GISFunctions.load(conn);
                    return wrapper.execute(conn, inputs);
                }
            });
            Future<Object> finalFuture = future;
            // Schedule a watchdog task that fire after TIMEOUT to inspect long-running processes
            watchdog = monitorExecutor.schedule(() -> {
                if (!finalFuture.isDone()) {
                    Thread t = workerThreadRef.get();
                    if (t != null) {
                        // Build a readable report of the thread’s stack trace
                        StringBuilder bt = new StringBuilder();
                        bt.append("\n  The WPS script exceeds "+TIMEOUT+" minutes: ").append(scriptId).append("\n");
                        bt.append("Stack trace of the execution thread (").append(t.getName()).append("):\n");

                        for (StackTraceElement el : t.getStackTrace()) {
                            bt.append("  at ").append(el.toString()).append("\n");
                        }
                        // Log the warning with stack information
                        LOGGER.warn(bt.toString());
                    }
                }
            }, TIMEOUT, TimeUnit.MILLISECONDS);

            Object result = future.get();

            if (result != null) {
                String textResult = result.toString();
                textResult = textResult.replaceAll("</br\\s*/?>", "\n");
                textResult = textResult.replace("&nbsp;", " ");
                if (result instanceof Geometry) {
                    ctx.contentType("application/wkt");
                    ctx.result(result.toString());
                    LOGGER.info(textResult);
                } else {
                    ctx.result(result.toString());
                    LOGGER.info(textResult);
                }
            } else {
                ctx.result("null");
            }
        }  catch (Exception e) {
            // If error occurred inside the future, unwrap the ExecutionException
            Throwable cause = e;
            if (e instanceof ExecutionException) {
                cause = e.getCause();
            }
            StringBuilder stackTrace = new StringBuilder();
            for (StackTraceElement el : cause.getStackTrace()) {
                stackTrace.append(el.toString()).append("<br>");
            }
            String errorMsg = cause.getMessage() != null ? cause.getMessage().replace("<", "&lt;") : "Unknown error";
            String html =
                    "<html>" +
                            "<head>" +
                            "    <style>" +
                            "        body { font-family: Arial; margin: 20px; }" +
                            "        .section { margin-bottom: 20px; }" +
                            "        .title { font-size: 20px; font-weight: bold; margin-bottom: 5px; color:#b30000; }" +
                            "        .box { border: 1px solid #ccc; padding: 10px; background:#fafafa; }" +
                            "        .error { color: #b30000; font-weight: bold; }" +
                            "    </style>" +
                            "</head>" +
                            "<body>" +

                            "    <div class='section'>" +
                            "        <div class='title'>Error: </div>" +
                            "        <div class='box'><span class='error'>" + errorMsg + "</span></div>" +
                            "    </div>" +

                            "    <div class='section'>" +
                            "        <div class='title'>Inputs Data</div>" +
                            "        <div class='box'>" + escapeHtml(ctx.body()) + "</div>" +
                            "    </div>" +

                            "    <div class='section'>" +
                            "        <div class='title'>Stacktrace</div>" +
                            "        <div class='box'>" + stackTrace.toString() + "</div>" +
                            "    </div>" +

                            "</body>" +
                            "</html>";

            ctx.contentType("text/html; charset=UTF-8");
            ctx.result(html);
            LOGGER.error("WPS Execution Error", cause);
        } finally {
            if (watchdog != null) {
                watchdog.cancel(false);
            }
        }
    }
}