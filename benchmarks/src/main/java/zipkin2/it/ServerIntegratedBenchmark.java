package zipkin2.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.Closer;
import com.linecorp.armeria.client.HttpClient;
import io.netty.handler.codec.http.QueryStringEncoder;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import org.testcontainers.utility.MountableFile;
import zipkin.server.ZipkinServer;

class ServerIntegratedBenchmark {

  static final Logger LOGGER = LoggerFactory.getLogger(ServerIntegratedBenchmark.class);

  static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Nullable static final String RELEASED_ZIPKIN_VERSION = System.getenv("ZIPKIN_VERSION");

  static final boolean WAIT_AFTER_BENCHMARK = "true".equals(System.getenv("ZIPKIN_BENCHMARK_WAIT"));

  Closer closer;

  @BeforeEach void setUp() {
    closer = Closer.create();
  }

  @AfterEach void tearDown() throws Exception {
    closer.close();
  }

  @Test void inMemory() throws Exception {
    runBenchmark(null);
  }

  @Test void elasticsearch() throws Exception {
    GenericContainer<?> elasticsearch = new GenericContainer<>("openzipkin/zipkin-elasticsearch7")
      .withNetwork(Network.SHARED)
      .withNetworkAliases("elasticsearch")
      .withLabel("name", "elasticsearch")
      .withExposedPorts(9200)
      .waitingFor(new HttpWaitStrategy().forPath("/_cluster/health"));
    startContainer(elasticsearch);

    runBenchmark(elasticsearch);
  }

  @Test void cassandra() throws Exception {
    GenericContainer<?> cassandra = new GenericContainer<>("openzipkin/zipkin-cassandra")
      .withNetwork(Network.SHARED)
      .withNetworkAliases("cassandra3")
      .withLabel("name", "cassandra3")
      .withExposedPorts(9042)
      .waitingFor(new LogMessageWaitStrategy().withRegEx(".*Starting listening for CQL clients.*"));
    startContainer(cassandra);

    runBenchmark(cassandra);
  }

  @Test void mysql() throws Exception {
    GenericContainer<?> mysql = new GenericContainer<>("openzipkin/zipkin-mysql")
      .withNetwork(Network.SHARED)
      .withNetworkAliases("mysql")
      .withLabel("name", "mysql")
      .withExposedPorts(3306);
    startContainer(mysql);

    runBenchmark(mysql);
  }

  void runBenchmark(@Nullable GenericContainer<?> storage) throws Exception {
    GenericContainer<?> zipkin = startZipkin(storage);

    GenericContainer<?> backend = new GenericContainer<>("openzipkin/example-sleuth-webmvc")
      .withNetwork(Network.SHARED)
      .withNetworkAliases("backend")
      .withCommand("backend")
      .withExposedPorts(9000)
      .waitingFor(new HttpWaitStrategy().forPath("/actuator/health"));

    GenericContainer<?> frontend = new GenericContainer<>("openzipkin/example-sleuth-webmvc")
      .withNetwork(Network.SHARED)
      .withNetworkAliases("frontend")
      .withCommand("frontend")
      .withExposedPorts(8081)
      .waitingFor(new HttpWaitStrategy().forPath("/actuator/health"));

    if (RELEASED_ZIPKIN_VERSION == null) {
      Testcontainers.exposeHostPorts(9411);
      String zipkinArg = "-Dspring.zipkin.baseUrl=http://host.testcontainers.internal:9411";
      backend.withEnv("JAVA_OPTS", zipkinArg);
      frontend.withEnv("JAVA_OPTS", zipkinArg);
    }

    startContainer(backend);
    startContainer(frontend);

    String prometheusResource = RELEASED_ZIPKIN_VERSION == null
      ? "prometheus-local.yml" : "prometheus-container.yml";
    GenericContainer<?> prometheus = new GenericContainer<>("prom/prometheus")
      .withNetwork(Network.SHARED)
      .withNetworkAliases("prometheus")
      .withExposedPorts(9090)
      .withCopyFileToContainer(
        MountableFile.forClasspathResource(prometheusResource), "/etc/prometheus/prometheus.yml");
    startContainer(prometheus);

    GenericContainer<?> grafana = new GenericContainer<>("grafana/grafana")
      .withNetwork(Network.SHARED)
      .withNetworkAliases("grafana")
      .withExposedPorts(3000)
      .withEnv("GF_AUTH_ANONYMOUS_ENABLED", "true")
      .withEnv("GF_AUTH_ANONYMOUS_ORG_ROLE", "Admin");
    startContainer(grafana);

    startContainer(new GenericContainer<>("appropriate/curl")
      .withNetwork(Network.SHARED)
      .withCommand("/create.sh")
      .withCopyFileToContainer(
        MountableFile.forClasspathResource("create-datasource-and-dashboard.sh"), "/create.sh"));

    GenericContainer<?> wrk = new GenericContainer<>("skandyla/wrk")
      .withNetwork(Network.SHARED)
      .withCommand("-t4 -c128 -d100s http://frontend:8081 --latency");
    startContainer(wrk);

    LOGGER.info("Benchmark started.");
    if (zipkin != null) {
      printContainerMapping(zipkin);
    }
    if (storage != null) {{
      printContainerMapping(storage);
    }}
    printContainerMapping(backend);
    printContainerMapping(frontend);
    printContainerMapping(prometheus);
    printContainerMapping(grafana);

    while (wrk.isRunning()) {
      Thread.sleep(1000);
    }

    // Wait for prometheus to do a final scrape.
    Thread.sleep(5000);

    LOGGER.info("Benchmark complete, wrk output:\n{}", wrk.getLogs());

    HttpClient prometheusClient = HttpClient.of(
      "h1c://" + prometheus.getContainerIpAddress() + ":" + prometheus.getFirstMappedPort());

    LOGGER.info("Messages received: {}", prometheusValue(
      prometheusClient, "sum(zipkin_collector_messages_total)"));
    LOGGER.info("Spans received: {}", prometheusValue(
      prometheusClient, "sum(zipkin_collector_spans_total)"));
    LOGGER.info("Spans dropped: {}", prometheusValue(
      prometheusClient, "sum(zipkin_collector_spans_dropped_total)"));

    LOGGER.info("Memory quantiles:");
    printQuartiles(prometheusClient, "jvm_memory_used_bytes{area=\"heap\"}");
    printQuartiles(prometheusClient, "jvm_memory_used_bytes{area=\"nonheap\"}");

    LOGGER.info("Total GC time (s): {}",
      prometheusValue(prometheusClient, "sum(jvm_gc_pause_seconds_sum)"));
    LOGGER.info("Number of GCs: {}",
      prometheusValue(prometheusClient, "sum(jvm_gc_pause_seconds_count)"));

    LOGGER.info("POST Spans latency (s)");
    printHistogram(prometheusClient, "http_server_requests_seconds_bucket{"
      + "method=\"POST\",status=\"202\",uri=\"/api/v2/spans\"}");

    if (WAIT_AFTER_BENCHMARK) {
      LOGGER.info("Keeping containers running until explicit termination. "
        + "Feel free to poke around in grafana.");
      Thread.sleep(Long.MAX_VALUE);
    }
  }

  GenericContainer<?> startZipkin(@Nullable GenericContainer<?> storage) {
    Map<String, Object> properties = new HashMap<>();
    if (storage != null) {
      String name = storage.getLabels().get("name");
      final String host;
      final int port;
      if (RELEASED_ZIPKIN_VERSION == null) {
        host = storage.getContainerIpAddress();
        port = storage.getFirstMappedPort();
      } else {
        host = name;
        port = storage.getExposedPorts().get(0);
      }
      String address = host + ":" + port;

      properties.put("zipkin.storage.type", name);
      switch (name) {
        case "elasticsearch":
          properties.put("zipkin.storage.elasticsearch.hosts", "http://" + address);
          break;
        case "cassandra3":
          properties.put("zipkin.storage.cassandra3.contact-points", address);
          break;
        case "mysql":
          properties.put("zipkin.storage.mysql.host", host);
          properties.put("zipkin.storage.mysql.port", port);
          properties.put("zipkin.storage.mysql.username", "zipkin");
          properties.put("zipkin.storage.mysql.password", "zipkin");
          break;
        default:
          throw new IllegalArgumentException("Unknown storage " + name +
            ". Update startZipkin to map it to properties.");
      }
    }

    if (RELEASED_ZIPKIN_VERSION == null) {
      closer.register(
        ZipkinServer.createApp().registerShutdownHook(false).run(
          properties.entrySet().stream()
            .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
            .toArray(String[]::new)));
      return null;
    } else {
      GenericContainer<?> zipkin =
        new GenericContainer<>("openzipkin/zipkin:" + RELEASED_ZIPKIN_VERSION)
          .withNetwork(Network.SHARED)
          .withNetworkAliases("zipkin")
          .withExposedPorts(9411)
          .waitingFor(new HttpWaitStrategy().forPath("/actuator/health"));
      if (!properties.isEmpty()) {
        zipkin.withEnv("JAVA_OPTS", properties.entrySet().stream()
          .map(entry -> "-D" + entry.getKey() + "=" + entry.getValue())
          .collect(Collectors.joining(" ")));
      }
      startContainer(zipkin);
      return zipkin;
    }
  }

  void startContainer(GenericContainer<?> container) {
    container.start();
    closer.register(container::stop);
  }

  static void printContainerMapping(GenericContainer<?> container) {
    LOGGER.info(
      "Container {} ports exposed at {}",
      container.getDockerImageName(),
      container.getExposedPorts().stream()
        .map(port -> new AbstractMap.SimpleImmutableEntry<>(
          port,
          "http://" + container.getContainerIpAddress() + ":" + container.getMappedPort(port)))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
  }

  static void printQuartiles(HttpClient prometheus, String metric) throws Exception {
    for (double quantile : Arrays.asList(0.0, 0.25, 0.5, 0.75, 1.0)) {
      String value = prometheusValue(prometheus, "quantile(" + quantile + ", " + metric + ")");
      LOGGER.info("{}[{}] = {}", metric, quantile, value);
    }
  }

  static void printHistogram(HttpClient prometheus, String metric) throws Exception {
    for (double quantile : Arrays.asList(0.5, 0.9, 0.99)) {
      String value =
        prometheusValue(prometheus, "histogram_quantile(" + quantile + ", " + metric + ")");
      LOGGER.info("{}[{}] = {}", metric, quantile, value);
    }
  }

  static String prometheusValue(HttpClient prometheus, String query) throws Exception {
    QueryStringEncoder encoder = new QueryStringEncoder("/api/v1/query");
    encoder.addParam("query", query);
    String response = prometheus.get(encoder.toString()).aggregate().join().contentUtf8();
    return OBJECT_MAPPER.readTree(response).at("/data/result/0/value/1").asText();
  }
}
