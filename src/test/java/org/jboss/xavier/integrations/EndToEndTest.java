package org.jboss.xavier.integrations;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.util.Base64;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.builder.AdviceWithRouteBuilder;
import org.apache.camel.component.aws.s3.S3Constants;
import org.apache.camel.test.spring.CamelSpringBootRunner;
import org.apache.camel.test.spring.UseAdviceWith;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.assertj.core.api.SoftAssertions;
import org.awaitility.Duration;
import org.jboss.xavier.Application;
import org.jboss.xavier.analytics.pojo.output.InitialSavingsEstimationReportModel;
import org.jboss.xavier.analytics.pojo.output.workload.inventory.WorkloadInventoryReportModel;
import org.jboss.xavier.analytics.pojo.output.workload.summary.ScanRunModel;
import org.jboss.xavier.analytics.pojo.output.workload.summary.SummaryModel;
import org.jboss.xavier.analytics.pojo.output.workload.summary.WorkloadSummaryReportModel;
import org.jboss.xavier.analytics.pojo.output.workload.summary.WorkloadsDetectedOSTypeModel;
import org.jboss.xavier.integrations.jpa.repository.AnalysisRepository;
import org.jboss.xavier.integrations.jpa.repository.InitialSavingsEstimationReportRepository;
import org.jboss.xavier.integrations.jpa.service.InitialSavingsEstimationReportService;
import org.jboss.xavier.integrations.route.model.notification.FilePersistedNotification;
import org.jboss.xavier.integrations.route.model.user.User;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.EnvironmentTestUtils;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.hateoas.PagedResources;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;

import javax.inject.Inject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.S3;

@RunWith(CamelSpringBootRunner.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@UseAdviceWith // Disables automatic start of Camel context
@SpringBootTest(classes = {Application.class}, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ContextConfiguration(initializers = EndToEndTest.Initializer.class)
@Import(TestConfigurationS3.class)
@ActiveProfiles("test")
public class EndToEndTest {
    private static Logger logger = LoggerFactory.getLogger(EndToEndTest.class);

    @ClassRule
    public static GenericContainer activemq = new GenericContainer<>("vromero/activemq-artemis")
            .withExposedPorts(61616, 8161)
            .withLogConsumer(new Slf4jLogConsumer(logger).withPrefix("AMQ-LOG"))
            .withEnv("DISABLE_SECURITY", "true")
            .withEnv("BROKER_CONFIG_GLOBAL_MAX_SIZE", "50000")
            .withEnv("BROKER_CONFIG_MAX_SIZE_BYTES", "50000")
            .withEnv("BROKER_CONFIG_MAX_DISK_USAGE", "100");

    @ClassRule
    public static GenericContainer kie_server = new GenericContainer<>("jboss/kie-server-showcase:7.18.0.Final")
            .withNetworkAliases("kie-server")
            .withExposedPorts(8080)
            .withLogConsumer(new Slf4jLogConsumer(logger).withPrefix("KIE-LOG"))
            .withEnv("KIE_SERVER_ID", "analytics-kieserver")
            .withEnv("KIE_ADMIN_USER", "kieserver")
            .withEnv("KIE_ADMIN_PWD", "kieserver1!")
            .withEnv("KIE_SERVER_MODE", "DEVELOPMENT")
            .withEnv("KIE_MAVEN_REPO", "https://oss.sonatype.org/content/repositories/snapshots")
            .withEnv("KIE_REPOSITORY","https://repository.jboss.org/nexus/content/groups/public-jboss")
            .withEnv("KIE_SERVER_CONTROLLER_PWD","admin")
            .withEnv("KIE_SERVER_CONTROLLER_USER","admin")
            .withEnv("KIE_SERVER_LOCATION","http://kie-server:8080/kie-server/services/rest/server")
            .withEnv("KIE_SERVER_PWD","kieserver1!")
            .withEnv("KIE_SERVER_USER","kieserver");

    @ClassRule
    public static PostgreSQLContainer postgreSQL = new PostgreSQLContainer()
            .withDatabaseName("sampledb")
            .withUsername("admin")
            .withPassword("redhat");

    @ClassRule
    public static LocalStackContainer localstack = new LocalStackContainer()
            .withLogConsumer(new Slf4jLogConsumer(logger).withPrefix("AWS-LOG"))
            .withServices(S3);

    private static String ingressCommitHash = "3ea33a8d793c2154f7cfa12057ca005c5f6031fa"; // 2019-11-11

    @Inject
    private InitialSavingsEstimationReportService initialSavingsEstimationReportService;

    @Autowired
    private InitialSavingsEstimationReportRepository initialSavingsEstimationReportRepository;

    @Inject
    private AnalysisRepository analysisRepository;

    @Value("${S3_BUCKET}")
    private String bucket;

    @Value("${server.port:8080}")
    private String serverPort;

    @Value("${test.timeout.performance:60000}") // 1 minute
    private int timeoutMilliseconds_PerformaceTest;

    @Value("${test.timeout.ics:10000}") // 10 seconds
    private int timeoutMilliseconds_InitialCostSavingsReport;

    @Value("${minio.host}") // Set in the Initializer
    private String minio_host;

    @Value("${test.timeout.ultraperformance:1200000}") // 20 minutes
    private int timeoutMilliseconds_UltraPerformaceTest;

    @Value("${test.timeout.smallfilesummaryreport:10000}") // 10 seconds
    private int timeoutMilliseconds_SmallFileSummaryReport;

    @Value("${test.bigfile.vms_expected:5254}")
    private int numberVMsExpected_InBigFile;

    public static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext configurableApplicationContext) {
            try {
                cloneIngressRepoAndUnzip();

                Network network = Network.newNetwork();

                GenericContainer minio = new GenericContainer<>("minio/minio")
                        .withCommand("server /data")
                        .withExposedPorts(9000)
                        .withNetworkAliases("minio")
                        .withNetwork(network)
                        .withLogConsumer(new Slf4jLogConsumer(logger).withPrefix("MINIO-LOG"))
                        .withEnv("MINIO_ACCESS_KEY", "BQA2GEXO711FVBVXDWKM")
                        .withEnv("MINIO_SECRET_KEY", "uvgz3LCwWM3e400cDkQIH/y1Y4xgU4iV91CwFSPC");
                minio.start();

                Thread.sleep(5000);
                GenericContainer createbuckets = new GenericContainer<>("minio/mc")
                        .dependsOn(minio)
                        .withNetwork(network)
                        .withLogConsumer(new Slf4jLogConsumer(logger).withPrefix("MINIO-MC-LOG"))
                        .withCopyFileToContainer(MountableFile.forClasspathResource("minio-bucket-creation-commands.sh"), "/")
                        .withCreateContainerCmdModifier(createContainerCmd -> createContainerCmd.withEntrypoint("sh", "/minio-bucket-creation-commands.sh", "minio:9000"));
                createbuckets.start();

                KafkaContainer kafka = new KafkaContainer()
                        .withLogConsumer(new Slf4jLogConsumer(logger).withPrefix("KAFKA-LOG"))
                        .withNetworkAliases("kafka")
                        .withNetwork(network);
                kafka.start();

                GenericContainer ingress = new GenericContainer(new ImageFromDockerfile()
                        .withDockerfile(Paths.get("src/test/resources/insights-ingress-go/Dockerfile")))
                        .withExposedPorts(3000)
                        .withNetwork(network)
                        .withLogConsumer(new Slf4jLogConsumer(logger).withPrefix("INGRESS-LOG"))
                        .withEnv("AWS_ACCESS_KEY_ID","BQA2GEXO711FVBVXDWKM")
                        .withEnv("AWS_SECRET_ACCESS_KEY","uvgz3LCwWM3e400cDkQIH/y1Y4xgU4iV91CwFSPC")
                        .withEnv("AWS_REGION","us-east-1")
                        .withEnv("INGRESS_STAGEBUCKET","insights-upload-perma")
                        .withEnv("INGRESS_REJECTBUCKET","insights-upload-rejected")
                        .withEnv("INGRESS_INVENTORYURL","http://inventory:8080/api/inventory/v1/hosts")
                        .withEnv("INGRESS_VALIDTOPICS","xavier,testareno,advisortestareno,advisor")
                        .withEnv("OPENSHIFT_BUILD_COMMIT","woopwoop")
                        .withEnv("INGRESS_MINIODEV","true")
                        .withEnv("INGRESS_MINIOACCESSKEY","BQA2GEXO711FVBVXDWKM")
                        .withEnv("INGRESS_MINIOSECRETKEY","uvgz3LCwWM3e400cDkQIH/y1Y4xgU4iV91CwFSPC")
                        .withEnv("INGRESS_MINIOENDPOINT", "minio:9000")
                        .withEnv("INGRESS_KAFKABROKERS", "kafka:9092");
                ingress.start();

                importProjectIntoKIE();

                EnvironmentTestUtils.addEnvironment("environment", configurableApplicationContext.getEnvironment(),
                        "amq.server=" + activemq.getContainerIpAddress(),
                        "amq.port=" + activemq.getMappedPort(61616),
                        "minio.host=" + getContainerHost(minio, 9000),
                        "insights.upload.host=" + getContainerHost(ingress),
                        "insights.properties=yearOverYearGrowthRatePercentage,percentageOfHypervisorsMigratedOnYear1,percentageOfHypervisorsMigratedOnYear2,percentageOfHypervisorsMigratedOnYear3,reportName,reportDescription",
                        "camel.component.servlet.mapping.context-path=/api/xavier/*",
                        "insights.kafka.host=" + kafka.getBootstrapServers(),
                        "postgresql.service.name=" + postgreSQL.getContainerIpAddress(),
                        "postgresql.service.port=" + postgreSQL.getFirstMappedPort(),
                        "spring.datasource.username=" + postgreSQL.getUsername(),
                        "spring.datasource.password=" + postgreSQL.getPassword(),
                        "S3_HOST=" + localstack.getEndpointConfiguration(S3).getServiceEndpoint(),
                        "S3_REGION="+ localstack.getEndpointConfiguration(S3).getSigningRegion(),
                        "kieserver.devel-service=" + getHostForKie() + "/kie-server",
                        "spring.datasource.url = jdbc:postgresql://" + getContainerHost(postgreSQL) + "/sampledb" ,
                        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQL9Dialect" ,
                        "thread.concurrentConsumers=3");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @NotNull
        private String getContainerHost(GenericContainer container, Integer port) {
            return container.getContainerIpAddress() + ":" + container.getMappedPort(port);
        }

        @NotNull
        private static String getContainerHost(GenericContainer container) {
            return container.getContainerIpAddress() + ":" + container.getFirstMappedPort();
        }
    }

    @Inject
    CamelContext camelContext;

    @Inject
    AmazonS3 amazonS3;

    private static void cloneIngressRepoAndUnzip() throws IOException {
        // downloading, unzipping, renaming
        String ingressRepoZipURL = "https://github.com/RedHatInsights/insights-ingress-go/archive/" + ingressCommitHash + ".zip";
        File compressedFile = new File("src/test/resources/ingressRepo.zip");
        FileUtils.copyURLToFile(new URL(ingressRepoZipURL), compressedFile, 1000, 10000);
        unzipFile(compressedFile, "src/test/resources");

        // we rename the directory because we had issues with Docker and the long folder
        FileUtils.moveDirectory(new File("src/test/resources/insights-ingress-go-" + ingressCommitHash), new File("src/test/resources/insights-ingress-go"));
    }

    private static void unzipFile(File file, String outputDir) throws IOException {
        java.util.zip.ZipFile zipFile = new ZipFile(file);
        try {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                File entryDestination = new File(outputDir, entry.getName());
                if (entry.isDirectory()) {
                    entryDestination.mkdirs();
                } else {
                    entryDestination.getParentFile().mkdirs();
                    InputStream in = zipFile.getInputStream(entry);
                    OutputStream out = new FileOutputStream(entryDestination);
                    IOUtils.copy(in, out);
                    in.close();
                    out.close();
                }
            }
        } finally {
            zipFile.close();
        }
    }

    private static String getHostForKie() {
        return kie_server.getContainerIpAddress() + ":" + kie_server.getFirstMappedPort();
    }

    private static void importProjectIntoKIE() throws InterruptedException, IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setCacheControl("no-cache");
        headers.set("Authorization", "Basic YWRtaW46YWRtaW4="); // admin:admin

        String kieRestURL = "http://" + getHostForKie() + "/kie-server/services/rest/";

        // KIE Container Creation
        HttpHeaders kieheaders = new HttpHeaders();
        kieheaders.setContentType(MediaType.APPLICATION_JSON);
        kieheaders.set("Authorization", "Basic a2llc2VydmVyOmtpZXNlcnZlcjEh");
        kieheaders.setCacheControl("no-cache");
        String kieContainerBody = "{\"container-id\" : \"xavier-analytics_0.0.1-SNAPSHOT\",\"release-id\" : {\"group-id\" : \"org.jboss.xavier\",\"artifact-id\" : \"xavier-analytics\",\"version\" : \"0.0.1-SNAPSHOT\" } }";
        try {
            new RestTemplate().exchange(kieRestURL + "server/containers/xavier-analytics_0.0.1-SNAPSHOT", HttpMethod.PUT, new HttpEntity<>(kieContainerBody, kieheaders), String.class);
        } catch (RestClientException e) {
            e.printStackTrace();
        }
    }

    @After
    public void cleanUp() throws IOException {
        // cleaning downloadable files/directories
        FileUtils.deleteDirectory(new File("src/test/resources/insights-ingress-go"));
        FileUtils.deleteQuietly(new File("src/test/resources/ingressRepo.zip"));
    }

    private List<String> getS3Objects(String bucket) {
        ListObjectsV2Request req = new ListObjectsV2Request().withBucketName(bucket).withMaxKeys(2);

        return amazonS3.listObjectsV2(req).getObjectSummaries().stream().map(e -> e.getKey()).collect(Collectors.toList());
    }

    @Test
    public void end2endTest() throws Exception {
        Thread.sleep(2000);

        // given
        camelContext.getGlobalOptions().put(Exchange.LOG_DEBUG_BODY_MAX_CHARS, "5000");
        camelContext.start();

        camelContext.getRouteDefinition("store-in-s3").adviceWith(camelContext, new AdviceWithRouteBuilder() {
            @Override
            public void configure() {
                weaveById("set-s3-key")
                        .replace().process(e -> e.getIn().setHeader(S3Constants.KEY, "S3KEY123" + UUID.randomUUID().toString()));
            }
        });

        camelContext.getRouteDefinition("download-file").adviceWith(camelContext, new AdviceWithRouteBuilder() {
            @Override
            public void configure() {
                weaveById("setHttpUri")
                        .replace()
                        .process(e -> {
                            String url = e.getIn().getBody(FilePersistedNotification.class).getUrl();
                            url = url.replace("minio:9000", minio_host);
                            e.getIn().setHeader("httpUriReplaced", url);
                        })
                        .setHeader("Exchange.HTTP_URI", header("httpUriReplaced"))
                        .setHeader("Host", constant("minio:9000"));

                weaveById("toOldHost")
                        .replace()
                        .to("http4:oldhost?preserveHostHeader=true");
            }
        });

        // 1. Check user has firstTime
        ResponseEntity<User> userEntity = new RestTemplate().exchange("http://localhost:" + serverPort + "/api/xavier/user", HttpMethod.GET, getRequestEntity(), new ParameterizedTypeReference<User>() {});
        assertThat(userEntity.getBody().isFirstTimeCreatingReports()).isTrue();

        // Start the camel route as if the UI was sending the file to the Camel Rest Upload route
        int analysisNum = 0;

        logger.info("+++++++  Regular Test ++++++");
        analysisNum++;
        new RestTemplate().postForEntity("http://localhost:" + serverPort + "/api/xavier/upload", getRequestEntityForUploadRESTCall("cfme_inventory-20190912-demolab_withSSA.tar.gz", "application/zip"), String.class);

        // then
        await()
            .atMost(timeoutMilliseconds_InitialCostSavingsReport, TimeUnit.MILLISECONDS)
            .with().pollInterval(Duration.ONE_HUNDRED_MILLISECONDS)
            .until( () -> {
                // Check database for the ICSR to be created
                List<InitialSavingsEstimationReportModel> all = initialSavingsEstimationReportRepository.findAll();
                return all != null && !all.isEmpty();
            });

        // Check S3
        assertThat(getS3Objects(bucket).stream().allMatch(e -> e.startsWith("S3KEY123"))).isTrue();
        assertThat(getS3Objects(bucket).size()).isEqualTo(1);

        // Check DB for initialCostSavingsReport with concrete values
        InitialSavingsEstimationReportModel initialCostSavingsReportDB = initialSavingsEstimationReportService.findByAnalysisOwnerAndAnalysisId("dummy@redhat.com", 1L);
        assertThat(initialCostSavingsReportDB.getEnvironmentModel().getHypervisors() == 2);
        assertThat(initialCostSavingsReportDB.getSourceCostsModel().getYear1Server() == 42);

        // Call initialCostSavingsReport
        ResponseEntity<InitialSavingsEstimationReportModel> initialCostSavingsReport = new RestTemplate().exchange("http://localhost:" + serverPort + String.format("/api/xavier/report/%d/initial-saving-estimation", analysisNum), HttpMethod.GET, getRequestEntity(), new ParameterizedTypeReference<InitialSavingsEstimationReportModel>() {});

        // Call workloadInventoryReport
        ResponseEntity<PagedResources<WorkloadInventoryReportModel>> workloadInventoryReport = new RestTemplate().exchange("http://localhost:" + serverPort + String.format("/api/xavier/report/%d/workload-inventory?size=100", analysisNum), HttpMethod.GET, getRequestEntity(), new ParameterizedTypeReference<PagedResources<WorkloadInventoryReportModel>>() {});

        // Call workloadSummaryReport
        ResponseEntity<WorkloadSummaryReportModel> workloadSummaryReport = new RestTemplate().exchange("http://localhost:" + serverPort + String.format("/api/xavier/report/%d/workload-summary", analysisNum), HttpMethod.GET, getRequestEntity(), new ParameterizedTypeReference<WorkloadSummaryReportModel>() {});

        // Checks on Initial Savings Report
        InitialSavingsEstimationReportModel initialSavingsEstimationReport_Expected = new ObjectMapper().readValue(IOUtils.resourceToString("cfme_inventory-20190912-demolab-withssa-initial-cost-savings-report.json", StandardCharsets.UTF_8, EndToEndTest.class.getClassLoader()), InitialSavingsEstimationReportModel.class);
        SoftAssertions.assertSoftly(softly -> softly.assertThat(initialCostSavingsReport.getBody())
                .usingRecursiveComparison()
                .ignoringFieldsMatchingRegexes(".*id.*", ".*creationDate.*", ".*report.*")
                .isEqualTo(initialSavingsEstimationReport_Expected));

        // Checks on Workload Inventory Report
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(workloadInventoryReport.getBody().getContent().size()).isEqualTo(14);
            softly.assertThat(workloadInventoryReport.getBody().getContent().stream().flatMap(e -> e.getWorkloads().stream()).distinct().count()).isEqualTo(7);
            softly.assertThat(workloadInventoryReport.getBody().getContent().stream().filter(e -> e.getWorkloads().contains("Red Hat JBoss EAP")).count()).isEqualTo(2);
            softly.assertThat(workloadInventoryReport.getBody().getContent().stream().map(WorkloadInventoryReportModel::getOsName).distinct().count()).isEqualTo(4);
            softly.assertThat(workloadInventoryReport.getBody().getContent().stream().filter(e -> e.getOsName().contains("CentOS 7 (64-bit)")).count()).isEqualTo(2);
            softly.assertThat(workloadInventoryReport.getBody().getContent().stream().map(WorkloadInventoryReportModel::getComplexity).distinct().count()).isEqualTo(4);
            softly.assertThat(workloadInventoryReport.getBody().getContent().stream().filter(e -> e.getComplexity().contains("Unknown")).count()).isEqualTo(0);
            softly.assertThat(workloadInventoryReport.getBody().getContent().stream().filter(e -> e.getComplexity().contains("Unsupported")).count()).isEqualTo(1);
            softly.assertThat(workloadInventoryReport.getBody().getContent().stream().flatMap(e -> e.getRecommendedTargetsIMS().stream()).distinct().count()).isEqualTo(4);
            softly.assertThat(workloadInventoryReport.getBody().getContent().stream().filter(e -> e.getRecommendedTargetsIMS().contains("OSP")).count()).isEqualTo(11);
            softly.assertThat(workloadInventoryReport.getBody().getContent().stream().filter(e -> e.getRecommendedTargetsIMS().contains("RHEL")).count()).isEqualTo(4);
            softly.assertThat(workloadInventoryReport.getBody().getContent().stream().filter(e -> e.getRecommendedTargetsIMS().contains("None")).count()).isEqualTo(1);
            softly.assertThat(workloadInventoryReport.getBody().getContent().stream().flatMap(e -> e.getFlagsIMS().stream()).distinct().count()).isEqualTo(2);
            softly.assertThat(workloadInventoryReport.getBody().getContent().stream().filter(e -> e.getFlagsIMS().contains("Shared Disk")).count()).isEqualTo(2);
            softly.assertThat(workloadInventoryReport.getBody().getContent().stream().filter(e -> e.getOsName().contains("ServerNT") && e.getWorkloads().contains("Microsoft SQL Server")).count()).isEqualTo(1);
        });

        WorkloadInventoryReportModel[] workloadInventoryReportModelExpected = new ObjectMapper().readValue(IOUtils.resourceToString("cfme_inventory-20190912-demolab-withssa-workload-inventory-report.json", StandardCharsets.UTF_8, EndToEndTest.class.getClassLoader()), WorkloadInventoryReportModel[].class);
        assertThat(workloadInventoryReport.getBody().getContent().toArray())
                .usingRecursiveComparison()
                .ignoringFieldsMatchingRegexes(".*id.*", ".*creationDate.*")
                .isEqualTo(workloadInventoryReportModelExpected);

        // Checks on Workload Summary Report
        WorkloadSummaryReportModel workloadSummaryReport_Expected = new ObjectMapper().readValue(IOUtils.resourceToString("cfme_inventory-20190912-demolab-withssa-workload-summary-report.json", StandardCharsets.UTF_8, EndToEndTest.class.getClassLoader()), WorkloadSummaryReportModel.class);

        assertThat(workloadSummaryReport.getBody())
                .usingRecursiveComparison()
                .ignoringFieldsMatchingRegexes(".*id.*", ".*creationDate.*",  ".*report.*", ".*workloadsDetectedOSTypeModels.*", ".*scanRunModels.*")
                .isEqualTo(workloadSummaryReport_Expected);

        // WLSR.ScanRunModels
        TreeSet<ScanRunModel> wks_scanrunmodel_expected = getWks_scanrunmodel(workloadSummaryReport_Expected.getScanRunModels());
        TreeSet<ScanRunModel> wks_scanrunmodel_actual = getWks_scanrunmodel(workloadSummaryReport.getBody().getScanRunModels());

        // WLSR.WorkloadsDetectedOSTypeModel
        TreeSet<WorkloadsDetectedOSTypeModel> wks_ostypemodel_expected = getWks_ostypemodel(workloadSummaryReport_Expected.getWorkloadsDetectedOSTypeModels());
        TreeSet<WorkloadsDetectedOSTypeModel> wks_ostypemodel_actual = getWks_ostypemodel(workloadSummaryReport.getBody().getWorkloadsDetectedOSTypeModels());

        SoftAssertions.assertSoftly(softly -> {
                    softly.assertThat(wks_scanrunmodel_actual).isEqualTo(wks_scanrunmodel_expected);
                    softly.assertThat(wks_ostypemodel_actual).isEqualTo(wks_ostypemodel_expected);
        });

        // Performance test
        logger.info("+++++++  Performance Test ++++++");

        new RestTemplate().postForEntity("http://localhost:" + serverPort + "/api/xavier/upload", getRequestEntityForUploadRESTCall("cfme_inventory-20190829-16128-uq17dx.tar.gz", "application/zip"), String.class);
        assertThat(callSummaryReportAndCheckVMs(String.format("/api/xavier/report/%d/workload-summary", ++analysisNum), timeoutMilliseconds_PerformaceTest)).isEqualTo(142);

        // Test with a file with VM without Host
        logger.info("+++++++  Test with a file with VM without Host ++++++");

        new RestTemplate().postForEntity("http://localhost:" + serverPort + "/api/xavier/upload", getRequestEntityForUploadRESTCall("cloudforms-export-v1_0_0-vm_without_host.json", "application/json"), String.class);
        assertThat(callSummaryReportAndCheckVMs(String.format("/api/xavier/report/%d/workload-summary", ++analysisNum), timeoutMilliseconds_InitialCostSavingsReport)).isEqualTo(8);

        ResponseEntity<PagedResources<WorkloadInventoryReportModel>> workloadInventoryReport_file_vm_without_host = new RestTemplate().exchange("http://localhost:" + serverPort + String.format("/api/xavier/report/%d/workload-inventory?size=100", analysisNum), HttpMethod.GET, getRequestEntity(), new ParameterizedTypeReference<PagedResources<WorkloadInventoryReportModel>>() {});
        assertThat(workloadInventoryReport_file_vm_without_host.getBody().getContent().size()).isEqualTo(8);
        assertThat(workloadInventoryReport_file_vm_without_host.getBody().getContent().stream().filter(e -> e.getDatacenter().equalsIgnoreCase("No datacenter defined") && e.getCluster().equalsIgnoreCase("No cluster defined")).count()).isEqualTo(2);
        assertThat(workloadInventoryReport_file_vm_without_host.getBody().getContent().stream().filter(e -> !e.getDatacenter().equalsIgnoreCase("No datacenter defined") && !e.getCluster().equalsIgnoreCase("No cluster defined")).count()).isEqualTo(6);

        // Test with a file with Host without Cluster
        logger.info("+++++++  Test with a file with Host without Cluster ++++++");
        new RestTemplate().postForEntity("http://localhost:" + serverPort + "/api/xavier/upload", getRequestEntityForUploadRESTCall("cloudforms-export-v1_0_0-host_without_cluster.json", "application/json"), String.class);
        assertThat(callSummaryReportAndCheckVMs(String.format("/api/xavier/report/%d/workload-summary", ++analysisNum), timeoutMilliseconds_InitialCostSavingsReport)).isEqualTo(8);

        ResponseEntity<PagedResources<WorkloadInventoryReportModel>> workloadInventoryReport_file_host_without_cluster = new RestTemplate().exchange("http://localhost:" + serverPort + String.format("/api/xavier/report/%d/workload-inventory?size=100", analysisNum), HttpMethod.GET, getRequestEntity(), new ParameterizedTypeReference<PagedResources<WorkloadInventoryReportModel>>() {});
        // Total VMs
        assertThat(workloadInventoryReport_file_host_without_cluster.getBody().getContent().size()).isEqualTo(8);
        // Wrong VMs
        assertThat(workloadInventoryReport_file_host_without_cluster.getBody().getContent().stream().filter(e -> e.getDatacenter().equalsIgnoreCase("No datacenter defined") && e.getCluster().equalsIgnoreCase("No cluster defined")).count()).isEqualTo(3);
        // Right VMs
        assertThat(workloadInventoryReport_file_host_without_cluster.getBody().getContent().stream().filter(e -> !e.getDatacenter().equalsIgnoreCase("No datacenter defined") && !e.getCluster().equalsIgnoreCase("No cluster defined")).count()).isEqualTo(5);

        // Test with a file with Wrong CPU cores per socket
        logger.info("+++++++  Test with a file with Wrong CPU cores per socket ++++++");
        new RestTemplate().postForEntity("http://localhost:" + serverPort + "/api/xavier/upload", getRequestEntityForUploadRESTCall("cloudforms-export-v1_0_0-wrong_cpu_cores_per_socket.json", "application/json"), String.class);
        assertThat(callSummaryReportAndCheckVMs(String.format("/api/xavier/report/%d/workload-summary", ++analysisNum), timeoutMilliseconds_InitialCostSavingsReport)).isEqualTo(5);

        ResponseEntity<InitialSavingsEstimationReportModel> initialCostSavingsReport_wrong_cpu_cores = new RestTemplate().exchange("http://localhost:" + serverPort + String.format("/api/xavier/report/%d/initial-saving-estimation", analysisNum), HttpMethod.GET, getRequestEntity(), new ParameterizedTypeReference<InitialSavingsEstimationReportModel>() {});
        assertThat(initialCostSavingsReport_wrong_cpu_cores.getBody().getEnvironmentModel().getHypervisors()).isEqualTo(2);

        ResponseEntity<PagedResources<WorkloadInventoryReportModel>> workloadInventoryReport_file_wrong_cpu_cores = new RestTemplate().exchange("http://localhost:" + serverPort + String.format("/api/xavier/report/%d/workload-inventory?size=100", analysisNum), HttpMethod.GET, getRequestEntity(), new ParameterizedTypeReference<PagedResources<WorkloadInventoryReportModel>>() {});
        assertThat(workloadInventoryReport_file_wrong_cpu_cores.getBody().getContent().stream().filter(e -> e.getCpuCores() == null).count()).isEqualTo(0);
        assertThat(workloadInventoryReport_file_wrong_cpu_cores.getBody().getContent().stream().filter(e -> e.getCpuCores() != null).count()).isEqualTo(5);
        assertThat(workloadInventoryReport_file_wrong_cpu_cores.getBody().getContent().size()).isEqualTo(5);

        // Test with a file with 0 CPU cores per socket
        logger.info("+++++++  Test with a file with 0 CPU cores per socket ++++++");
        new RestTemplate().postForEntity("http://localhost:" + serverPort + "/api/xavier/upload", getRequestEntityForUploadRESTCall("cloudforms-export-v1_0_0-vm_with_0_cores.json", "application/json"), String.class);
        assertThat(callSummaryReportAndCheckVMs(String.format("/api/xavier/report/%d/workload-summary", ++analysisNum), timeoutMilliseconds_InitialCostSavingsReport)).isEqualTo(8);

        ResponseEntity<InitialSavingsEstimationReportModel> initialCostSavingsReport_zero_cpu_cores = new RestTemplate().exchange("http://localhost:" + serverPort + String.format("/api/xavier/report/%d/initial-saving-estimation", analysisNum), HttpMethod.GET, getRequestEntity(), new ParameterizedTypeReference<InitialSavingsEstimationReportModel>() {});
        assertThat(initialCostSavingsReport_zero_cpu_cores.getBody().getEnvironmentModel().getHypervisors()).isEqualTo(2);

        ResponseEntity<PagedResources<WorkloadInventoryReportModel>> workloadInventoryReport_file_zero_cpu_cores = new RestTemplate().exchange("http://localhost:" + serverPort + String.format("/api/xavier/report/%d/workload-inventory?size=100", analysisNum), HttpMethod.GET, getRequestEntity(), new ParameterizedTypeReference<PagedResources<WorkloadInventoryReportModel>>() {});
        assertThat(workloadInventoryReport_file_zero_cpu_cores.getBody().getContent().stream().filter(e -> e.getCpuCores() == null).count()).isEqualTo(0);
        assertThat(workloadInventoryReport_file_zero_cpu_cores.getBody().getContent().stream().filter(e -> e.getCpuCores() != null).count()).isEqualTo(8);
        assertThat(workloadInventoryReport_file_zero_cpu_cores.getBody().getContent().size()).isEqualTo(8);


        // Ultra Performance test
        logger.info("+++++++  Ultra Performance Test ++++++");
        new RestTemplate().postForEntity("http://localhost:" + serverPort + "/api/xavier/upload", getRequestEntityForUploadRESTCall("cfme_inventory20190807-32152-jimd0q_large_dataset_5254_vms.tar.gz", "application/zip"), String.class);
        assertThat(callSummaryReportAndCheckVMs(String.format("/api/xavier/report/%d/workload-summary", ++analysisNum), timeoutMilliseconds_UltraPerformaceTest)).isEqualTo(numberVMsExpected_InBigFile);

        // Stress test
        // We load 3 times a BIG file ( 8 Mb ) and 2 times a small file ( 316 Kb )
        // More or less 7 minutes each bunch of threads of Big Files
        // 1 bunch of threads for 2 big files and 1 small file, while 1 big file and 1 small file wait in the queue
        // We have 3 consumers
        // To process the first small file it should take 10 seconds
        // To process the second small file it should take 7 minutes of the first bunch of big files plus 10 seconds of the small file
        logger.info("+++++++  Stress Test ++++++");
        new RestTemplate().postForEntity("http://localhost:" + serverPort + "/api/xavier/upload", getRequestEntityForUploadRESTCall("cfme_inventory20190807-32152-jimd0q_large_dataset_5254_vms.tar.gz", "application/zip"), String.class);
        new RestTemplate().postForEntity("http://localhost:" + serverPort + "/api/xavier/upload", getRequestEntityForUploadRESTCall("cloudforms-export-v1_0_0.json", "application/json"), String.class);
        new RestTemplate().postForEntity("http://localhost:" + serverPort + "/api/xavier/upload", getRequestEntityForUploadRESTCall("cfme_inventory20190807-32152-jimd0q_large_dataset_5254_vms.tar.gz", "application/zip"), String.class);
        new RestTemplate().postForEntity("http://localhost:" + serverPort + "/api/xavier/upload", getRequestEntityForUploadRESTCall("cloudforms-export-v1_0_0.json", "application/json"), String.class);

        // We will check for time we retrieve the third file uploaded to see previous ones are not affecting
        assertThat(callSummaryReportAndCheckVMs(String.format("/api/xavier/report/%d/workload-summary", analysisNum + 2), timeoutMilliseconds_SmallFileSummaryReport)).isEqualTo(8);
        assertThat(callSummaryReportAndCheckVMs(String.format("/api/xavier/report/%d/workload-summary", analysisNum + 1), timeoutMilliseconds_UltraPerformaceTest)).isEqualTo(numberVMsExpected_InBigFile);
        assertThat(callSummaryReportAndCheckVMs(String.format("/api/xavier/report/%d/workload-summary", analysisNum + 3), timeoutMilliseconds_UltraPerformaceTest)).isEqualTo( numberVMsExpected_InBigFile);

        int timeoutMilliseconds_secondSmallFile = timeoutMilliseconds_UltraPerformaceTest + timeoutMilliseconds_SmallFileSummaryReport;
        assertThat(callSummaryReportAndCheckVMs(String.format("/api/xavier/report/%d/workload-summary", analysisNum + 4), timeoutMilliseconds_secondSmallFile)).isEqualTo( 8);

        camelContext.stop();
    }

    private Integer callSummaryReportAndCheckVMs(final String reportUrl, int timeoutMilliseconds) {
        AtomicInteger numberVMsReceived = new AtomicInteger(0);
        await()
                .atMost(timeoutMilliseconds, TimeUnit.MILLISECONDS)
                .with().pollInterval(Duration.ONE_SECOND)
                .until(() -> {
                    ResponseEntity<WorkloadSummaryReportModel> workloadSummaryReport_stress_checkVMs = new RestTemplate().exchange("http://localhost:" + serverPort + reportUrl, HttpMethod.GET, getRequestEntity(), new ParameterizedTypeReference<WorkloadSummaryReportModel>() {
                    });
                    boolean success = (workloadSummaryReport_stress_checkVMs != null &&
                            workloadSummaryReport_stress_checkVMs.getStatusCodeValue() == 200 &&
                            workloadSummaryReport_stress_checkVMs.getBody() != null &&
                            workloadSummaryReport_stress_checkVMs.getBody().getSummaryModels() != null );
                    if (success) {
                       numberVMsReceived.set(workloadSummaryReport_stress_checkVMs.getBody().getSummaryModels().stream().mapToInt(SummaryModel::getVms).sum());
                    }
                    return success;
                });
        return numberVMsReceived.intValue();
    }

    @NotNull
    private TreeSet<WorkloadsDetectedOSTypeModel> getWks_ostypemodel(Set<WorkloadsDetectedOSTypeModel> elements) {
        TreeSet<WorkloadsDetectedOSTypeModel> treeset = new TreeSet<>(new WorkloadsDetectedOSTypeModelComparator());
        treeset.addAll(elements);
        return treeset;
    }

    @NotNull
    private TreeSet<ScanRunModel> getWks_scanrunmodel(Set<ScanRunModel> elements) {
        TreeSet<ScanRunModel> treeset = new TreeSet<>(new ScanRunModelComparator());
        treeset.addAll(elements);
        return treeset;
    }

    private HttpEntity getRequestEntity() {
        return new HttpEntity<String>(getHttpHeaders());
    }

    @NotNull
    private HttpEntity<MultiValueMap<String, Object>> getRequestEntityForUploadRESTCall(String filename, String content_type_header) throws IOException {
        // Headers
        HttpHeaders headers = getHttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        // Body
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

        // File Body part
        LinkedMultiValueMap<String, String> fileMap = new LinkedMultiValueMap<>();
        fileMap.add(HttpHeaders.CONTENT_DISPOSITION, "form-data; name=filex; filename=" + filename);
        fileMap.add("Content-type", content_type_header);
        body.add("file", new HttpEntity<>(IOUtils.resourceToByteArray(filename, EndToEndTest.class.getClassLoader()), fileMap));

        // params Body parts
        body.add("percentageOfHypervisorsMigratedOnYear1", "50");
        body.add("percentageOfHypervisorsMigratedOnYear2", "25");
        body.add("percentageOfHypervisorsMigratedOnYear3", "25");
        body.add("yearOverYearGrowthRatePercentage", "5");
        body.add("sourceproductindicator", "2");
        body.add("reportName", "report name test");
        body.add("reportDescription", "report desc test");
        body.add("payloadName", "payloadname");

        return new HttpEntity<>(body, headers);
    }

    @NotNull
    private HttpHeaders getHttpHeaders() {
        // Headers
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-rh-insights-request-id", UUID.randomUUID().toString());
        String rhIdentityJson = "{\"entitlements\":{\"insights\":{\"is_entitled\":true},\"openshift\":{\"is_entitled\":true},\"smart_management\":{\"is_entitled\":false},\"hybrid_cloud\":{\"is_entitled\":true}}," +
                "\"identity\":{\"internal\":{\"auth_time\":0,\"auth_type\":\"jwt-auth\",\"org_id\":\"6340056\", " +
                //"\"filename\":\"" + filename + "\"," +
                "\"origin\":\"xavier\",\"customerid\":\"CID888\"}," +
                // \"analysisId\":\"" + analysisId + "\"}," +
                "\"account_number\":\"1460290\", \"user\":{\"first_name\":\"User\",\"is_active\":true,\"is_internal\":true,\"last_name\":\"Dumy\",\"locale\":\"en_US\",\"is_org_admin\":false,\"username\":\"dummy@redhat.com\",\"email\":\"dummy+qa@redhat.com\"},\"type\":\"User\"}}";
        headers.set("x-rh-identity", Base64.encodeAsString(rhIdentityJson.getBytes()) );
        headers.set("username", "dummy@redhat.com");
        return headers;
    }

    private static class ScanRunModelComparator implements Comparator<ScanRunModel> {
        @Override
        public int compare(ScanRunModel o1, ScanRunModel o2) {
            return o1.getTarget().equals(o2.getTarget()) && o1.getType().equals(o2.getType()) ? 0 : 1;
        }
    }

    private static class WorkloadsDetectedOSTypeModelComparator implements Comparator<WorkloadsDetectedOSTypeModel> {
        @Override
        public int compare(WorkloadsDetectedOSTypeModel o1, WorkloadsDetectedOSTypeModel o2) {
            return o1.getOsName().equals(o2.getOsName()) && o1.getTotal().equals(o2.getTotal()) ? 0 : 1;
        }
    }
}
