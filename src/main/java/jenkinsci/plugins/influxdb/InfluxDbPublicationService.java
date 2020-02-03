package jenkinsci.plugins.influxdb;

import hudson.EnvVars;
import hudson.ProxyConfiguration;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import jenkinsci.plugins.influxdb.generators.*;
import jenkinsci.plugins.influxdb.models.Target;
import jenkinsci.plugins.influxdb.renderer.MeasurementRenderer;
import jenkinsci.plugins.influxdb.renderer.ProjectNameRenderer;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import org.apache.commons.lang3.StringUtils;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDB.ConsistencyLevel;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.BatchPoints;
import org.influxdb.dto.Point;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class InfluxDbPublicationService {

    /**
     * The logger.
     **/
    private static final Logger logger = Logger.getLogger(InfluxDbPublicationService.class.getName());

    /**
     * Shared HTTP client which can make use of connection and thread pooling.
     */
    private static final OkHttpClient httpClient = new OkHttpClient();

    /**
     * Targets to write to.
     */
    private final List<Target> selectedTargets;

    /**
     * Custom project name, overrides the project name with the specified value.
     */
    private final String customProjectName;

    /**
     * Custom prefix, for example in multi-branch pipelines, where every build is named
     * after the branch built and thus you have different builds called 'master' that report
     * different metrics.
     */
    private final String customPrefix;

    /**
     * Custom data fields, especially in pipelines, where additional information is calculated
     * or retrieved by Groovy functions which should be sent to InfluxDB.
     * <p>
     * Example for a pipeline script:
     * <pre>{@code
     * def myFields = [:]
     * myFields['field_a'] = 11
     * myFields['field_b'] = 12
     * influxDbPublisher(target: 'my-target', customData: myFields)
     * }</pre>
     */
    private final Map<String, Object> customData;

    /**
     * Custom data tags, especially in pipelines, where additional information is calculated
     * or retrieved by Groovy functions which should be sent to InfluxDB.
     * <p>
     * Example for a pipeline script:
     * <pre>{@code
     * def myTags = [:]
     * myTags['tag_1'] = 'foo'
     * myTags['tag_2'] = 'bar'
     * influxDbPublisher(target: 'my-target', customData: ..., customDataTags: myTags)
     * }</pre>
     */
    private final Map<String, String> customDataTags;

    /**
     * Custom data maps for fields, especially in pipelines, where additional information is calculated
     * or retrieved by Groovy functions which should be sent to InfluxDB.
     * <p>
     * This goes beyond {@code customData} since it allows to define multiple {@code customData} measurements
     * where the name of the measurement is defined as the key of the {@code customDataMap}.
     * <p>
     * Example for a pipeline script:
     * <pre>{@code
     * def myFields1 = [:]
     * def myFields2 = [:]
     * def myCustomMeasurementFields = [:]
     * myFields1['field_a'] = 11
     * myFields1['field_b'] = 12
     * myFields2['field_c'] = 21
     * myFields2['field_d'] = 22
     * myCustomMeasurementFields['series_1'] = myFields1
     * myCustomMeasurementFields['series_2'] = myFields2
     * influxDbPublisher(target: 'my-target', customDataMap: myCustomMeasurementFields)
     * }</pre>
     */
    private final Map<String, Map<String, Object>> customDataMap;

    /**
     * Custom data maps for tags, especially in pipelines, where additional information is calculated
     * or retrieved by Groovy functions which should be sent to InfluxDB.
     * <p>
     * Custom tags that are sent to respective measurements defined in {@code customDataMap}.
     * <p>
     * Example for a pipeline script:
     * <pre>{@code
     * def myTags = [:]
     * def myCustomMeasurementTags = [:]
     * myTags['buildResult'] = currentBuild.result
     * myTags['NODE_LABELS'] = env.NODE_LABELS
     * myCustomMeasurementTags['series_1'] = myTags
     * influxDbPublisher(target: 'my-target', customDataMap: ..., customDataMapTags: myCustomMeasurementTags)
     * }</pre>
     */
    private final Map<String, Map<String, String>> customDataMapTags;

    /**
     * Jenkins parameter(s) which will be added as field set to measurement 'jenkins_data'.
     * If parameter value has a $-prefix, it will be resolved from current Jenkins job environment properties.
     */
    private final String jenkinsEnvParameterField;

    /**
     * Jenkins parameter(s) which will be added as tag set to  measurement 'jenkins_data'.
     * If parameter value has a $-prefix, it will be resolved from current Jenkins job environment properties.
     */
    private final String jenkinsEnvParameterTag;

    /**
     * Custom measurement name used for all measurement types,
     * overrides the default measurement names.
     * Default value is "jenkins_data"
     * <p>
     * For custom data, prepends "custom_", i.e. "some_measurement"
     * becomes "custom_some_measurement".
     * Default custom name remains "jenkins_custom_data".
     */
    private final String measurementName;

    private final long timestamp;

    public InfluxDbPublicationService(List<Target> selectedTargets, String customProjectName, String customPrefix, Map<String, Object> customData, Map<String, String> customDataTags, Map<String, Map<String, String>> customDataMapTags, Map<String, Map<String, Object>> customDataMap, long timestamp, String jenkinsEnvParameterField, String jenkinsEnvParameterTag, String measurementName) {
        this.selectedTargets = selectedTargets;
        this.customProjectName = customProjectName;
        this.customPrefix = customPrefix;
        this.customData = customData;
        this.customDataTags = customDataTags;
        this.customDataMap = customDataMap;
        this.customDataMapTags = customDataMapTags;
        this.timestamp = timestamp;
        this.jenkinsEnvParameterField = jenkinsEnvParameterField;
        this.jenkinsEnvParameterTag = jenkinsEnvParameterTag;
        this.measurementName = measurementName;
    }

    public void perform(Run<?, ?> build, TaskListener listener, EnvVars env) {
        // Logging
        listener.getLogger().println("[InfluxDB Plugin] Collecting data...");

        // Renderer to use for the metrics
        MeasurementRenderer<Run<?, ?>> measurementRenderer = new ProjectNameRenderer(customPrefix, customProjectName);

        // Points to write
        List<Point> pointsToWrite = new ArrayList<>();

        generateJenkinsBaseData(build, listener, measurementRenderer, pointsToWrite, env);
        generateCustomData1(build, listener, measurementRenderer, pointsToWrite);
        generateCustomData2(build, listener, measurementRenderer, pointsToWrite);
        generateCoberturaData(build, listener, measurementRenderer, pointsToWrite);
        generateRobotFrameworkData(build, listener, measurementRenderer, pointsToWrite);
        generateJacocoData(build, listener, measurementRenderer, pointsToWrite);
        generatePerformanceData(build, listener, measurementRenderer, pointsToWrite);
        generateSonarQubeData(build, listener, measurementRenderer, pointsToWrite, env);
        generateChangeLogData(build, listener, measurementRenderer, pointsToWrite);
        generatePerfPublisherData(build, listener, measurementRenderer, pointsToWrite);

        for (Target target : selectedTargets) {
            String logMessage = "[InfluxDB Plugin] Publishing data to: " + target;
            logger.log(Level.FINE, logMessage);
            listener.getLogger().println(logMessage);

            URL url;
            try {
                url = new URL(target.getUrl());
            } catch (MalformedURLException e) {
                listener.getLogger().println("[InfluxDB Plugin] Skipping target due to invalid URL: " + target.getUrl());
                continue;
            }

            OkHttpClient.Builder httpClient = createHttpClient(url, target.isUsingJenkinsProxy());
            InfluxDB influxDB = StringUtils.isEmpty(target.getUsername()) ?
                    InfluxDBFactory.connect(target.getUrl(), httpClient) :
                    InfluxDBFactory.connect(target.getUrl(), target.getUsername(), Secret.toString(target.getPassword()), httpClient);
            writeToInflux(target, influxDB, pointsToWrite);
        }

        listener.getLogger().println("[InfluxDB Plugin] Completed.");
    }

    private void generateJenkinsBaseData(Run<?, ?> build, TaskListener listener, MeasurementRenderer<Run<?, ?>> measurementRenderer, List<Point> pointsToWrite, EnvVars env) {
        JenkinsBasePointGenerator jGen = new JenkinsBasePointGenerator(measurementRenderer, customPrefix, build, timestamp, listener, jenkinsEnvParameterField, jenkinsEnvParameterTag, measurementName, env);
        addPoints(pointsToWrite, jGen, listener);
    }

    private void generateCustomData1(Run<?, ?> build, TaskListener listener, MeasurementRenderer<Run<?, ?>> measurementRenderer, List<Point> pointsToWrite) {
        CustomDataPointGenerator cdGen = new CustomDataPointGenerator(measurementRenderer, customPrefix, build, timestamp, customData, customDataTags, measurementName);
        if (cdGen.hasReport()) {
            listener.getLogger().println("[InfluxDB Plugin] Custom data found. Writing to InfluxDB...");
            addPoints(pointsToWrite, cdGen, listener);
        } else {
            logger.log(Level.FINE, "Data source empty: Custom Data");
        }
    }

    private void generateCustomData2(Run<?, ?> build, TaskListener listener, MeasurementRenderer<Run<?, ?>> measurementRenderer, List<Point> pointsToWrite) {
        CustomDataMapPointGenerator cdmGen = new CustomDataMapPointGenerator(measurementRenderer, customPrefix, build, timestamp, customDataMap, customDataMapTags);
        if (cdmGen.hasReport()) {
            listener.getLogger().println("[InfluxDB Plugin] Custom data map found. Writing to InfluxDB...");
            addPoints(pointsToWrite, cdmGen, listener);
        } else {
            logger.log(Level.FINE, "Data source empty: Custom Data Map");
        }
    }

    private void generateCoberturaData(Run<?, ?> build, TaskListener listener, MeasurementRenderer<Run<?, ?>> measurementRenderer, List<Point> pointsToWrite) {
        try {
            CoberturaPointGenerator cGen = new CoberturaPointGenerator(measurementRenderer, customPrefix, build, timestamp);
            if (cGen.hasReport()) {
                listener.getLogger().println("[InfluxDB Plugin] Cobertura data found. Writing to InfluxDB...");
                addPoints(pointsToWrite, cGen, listener);
            }
        } catch (NoClassDefFoundError ignore) {
            logger.log(Level.FINE, "Plugin skipped: Cobertura");
        }
    }

    private void generateRobotFrameworkData(Run<?, ?> build, TaskListener listener, MeasurementRenderer<Run<?, ?>> measurementRenderer, List<Point> pointsToWrite) {
        try {
            RobotFrameworkPointGenerator rfGen = new RobotFrameworkPointGenerator(measurementRenderer, customPrefix, build, timestamp);
            if (rfGen.hasReport()) {
                listener.getLogger().println("[InfluxDB Plugin] Robot Framework data found. Writing to InfluxDB...");
                addPoints(pointsToWrite, rfGen, listener);
            }
        } catch (NoClassDefFoundError ignore) {
            logger.log(Level.FINE, "Plugin skipped: Robot Framework");
        }
    }

    private void generateJacocoData(Run<?, ?> build, TaskListener listener, MeasurementRenderer<Run<?, ?>> measurementRenderer, List<Point> pointsToWrite) {
        try {
            JacocoPointGenerator jacoGen = new JacocoPointGenerator(measurementRenderer, customPrefix, build, timestamp);
            if (jacoGen.hasReport()) {
                listener.getLogger().println("[InfluxDB Plugin] JaCoCo data found. Writing to InfluxDB...");
                addPoints(pointsToWrite, jacoGen, listener);
            }
        } catch (NoClassDefFoundError ignore) {
            logger.log(Level.FINE, "Plugin skipped: JaCoCo");
        }
    }

    private void generatePerformanceData(Run<?, ?> build, TaskListener listener, MeasurementRenderer<Run<?, ?>> measurementRenderer, List<Point> pointsToWrite) {
        try {
            PerformancePointGenerator perfGen = new PerformancePointGenerator(measurementRenderer, customPrefix, build, timestamp);
            if (perfGen.hasReport()) {
                listener.getLogger().println("[InfluxDB Plugin] Performance data found. Writing to InfluxDB...");
                addPoints(pointsToWrite, perfGen, listener);
            }
        } catch (NoClassDefFoundError ignore) {
            logger.log(Level.FINE, "Plugin skipped: Performance");
        }
    }

    private void generateSonarQubeData(Run<?, ?> build, TaskListener listener, MeasurementRenderer<Run<?, ?>> measurementRenderer, List<Point> pointsToWrite, EnvVars env) {
        SonarQubePointGenerator sonarGen = new SonarQubePointGenerator(measurementRenderer, customPrefix, build, timestamp, listener);
        if (sonarGen.hasReport()) {
            listener.getLogger().println("[InfluxDB Plugin] SonarQube data found. Writing to InfluxDB...");
            sonarGen.setEnv(env);
            addPoints(pointsToWrite, sonarGen, listener);
        } else {
            logger.log(Level.FINE, "Plugin skipped: SonarQube");
        }
    }

    private void generateChangeLogData(Run<?, ?> build, TaskListener listener, MeasurementRenderer<Run<?, ?>> measurementRenderer, List<Point> pointsToWrite) {
        ChangeLogPointGenerator changeLogGen = new ChangeLogPointGenerator(measurementRenderer, customPrefix, build, timestamp);
        if (changeLogGen.hasReport()) {
            listener.getLogger().println("[InfluxDB Plugin] Change Log data found. Writing to InfluxDB...");
            addPoints(pointsToWrite, changeLogGen, listener);
        } else {
            logger.log(Level.FINE, "Data source empty: Change Log");
        }
    }

    private void generatePerfPublisherData(Run<?, ?> build, TaskListener listener, MeasurementRenderer<Run<?, ?>> measurementRenderer, List<Point> pointsToWrite) {
        try {
            PerfPublisherPointGenerator perfPublisherGen = new PerfPublisherPointGenerator(measurementRenderer, customPrefix, build, timestamp);
            if (perfPublisherGen.hasReport()) {
                listener.getLogger().println("[InfluxDB Plugin] Performance Publisher data found. Writing to InfluxDB...");
                addPoints(pointsToWrite, perfPublisherGen, listener);
            }
        } catch (NoClassDefFoundError ignore) {
            logger.log(Level.FINE, "Plugin skipped: Performance Publisher");
        }

        for (Target target : selectedTargets) {
            URL url;
            try {
                url = new URL(target.getUrl());
            } catch (MalformedURLException e) {
                String logMessage = String.format("[InfluxDB Plugin] Skipping target '%s' due to invalid URL '%s'",
                        target.getDescription(),
                        target.getUrl());
                logger.log(Level.WARNING, logMessage);
                listener.getLogger().println(logMessage);
                continue;
            }

            String logMessage = String.format("[InfluxDB Plugin] Publishing data to target '%s' (url='%s', database='%s')",
                    target.getDescription(),
                    target.getUrl(),
                    target.getDatabase());
            logger.log(Level.FINE, logMessage);
            listener.getLogger().println(logMessage);

            OkHttpClient.Builder httpClient = createHttpClient(url, target.isUsingJenkinsProxy());
            InfluxDB influxDB = StringUtils.isEmpty(target.getUsername()) ?
                    InfluxDBFactory.connect(target.getUrl(), httpClient) :
                    InfluxDBFactory.connect(target.getUrl(), target.getUsername(), target.getPassword().getPlainText(), httpClient);
            writeToInflux(target, influxDB, pointsToWrite);

        }

        listener.getLogger().println("[InfluxDB Plugin] Completed.");
    }

    private void addPoints(List<Point> pointsToWrite, PointGenerator generator, TaskListener listener) {
        try {
            pointsToWrite.addAll(Arrays.asList(generator.generate()));
        } catch (Exception e) {
            listener.getLogger().println("[InfluxDB Plugin] Failed to collect data. Ignoring Exception:" + e);
        }
    }

    private OkHttpClient.Builder createHttpClient(URL url, boolean useProxy) {
        OkHttpClient.Builder builder = httpClient.newBuilder();
        ProxyConfiguration proxyConfig = Jenkins.getInstance().proxy;
        if (useProxy && proxyConfig != null) {
            builder.proxy(proxyConfig.createProxy(url.getHost()));
            if (proxyConfig.getUserName() != null) {
                builder.proxyAuthenticator((route, response) -> {
                    if (response.request().header("Proxy-Authorization") != null) {
                        return null; // Give up, we've already failed to authenticate.
                    }

                    String credential = Credentials.basic(proxyConfig.getUserName(), proxyConfig.getPassword());
                    return response.request().newBuilder().header("Proxy-Authorization", credential).build();
                });
            }
        }
        return builder;
    }

    private void writeToInflux(Target target, InfluxDB influxDB, List<Point> pointsToWrite) {
        /*
         * build batchpoints for a single write.
         */
        try {
            BatchPoints batchPoints = BatchPoints
                    .database(target.getDatabase())
                    .points(pointsToWrite)
                    .retentionPolicy(target.getRetentionPolicy())
                    .consistency(ConsistencyLevel.ANY)
                    .build();
            influxDB.write(batchPoints);
        } catch (Exception e) {
            if (target.isExposeExceptions()) {
                throw new InfluxReportException(e);
            } else {
                //Exceptions not exposed by configuration. Just log and ignore.
                logger.log(Level.WARNING, "Could not report to InfluxDB. Ignoring Exception.", e);
            }
        }
    }
}
