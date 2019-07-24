/*
 * Copyright 2015-2019 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin2.elasticsearch.integration;

import com.linecorp.armeria.client.ClientOptionsBuilder;
import com.linecorp.armeria.client.logging.LoggingClientBuilder;
import com.linecorp.armeria.common.logging.LogLevel;
import java.util.Arrays;
import java.util.Collections;
import java.util.function.Consumer;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import zipkin2.CheckResult;
import zipkin2.elasticsearch.ElasticsearchStorage;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ElasticsearchStorageExtension implements BeforeAllCallback, AfterAllCallback {
  static final Logger LOGGER = LoggerFactory.getLogger(ElasticsearchStorageExtension.class);
  static final int ELASTICSEARCH_PORT = 9200;
  final String image;
  GenericContainer container;

  ElasticsearchStorageExtension(String image) {
    this.image = image;
  }

  @Override public void beforeAll(ExtensionContext context) {
    if (context.getRequiredTestClass().getEnclosingClass() != null) {
      // Only run once in outermost scope.
      return;
    }

    if (!"true".equals(System.getProperty("docker.skip"))) {
      try {
        LOGGER.info("Starting docker image " + image);
        container =
          new GenericContainer(image)
            .withExposedPorts(ELASTICSEARCH_PORT)
            .waitingFor(new HttpWaitStrategy().forPath("/"));
        container.start();
        if (Boolean.valueOf(System.getenv("ES_DEBUG"))) {
          container.followOutput(new Slf4jLogConsumer(LoggerFactory.getLogger(image)));
        }
        LOGGER.info("Starting docker image " + image);
      } catch (RuntimeException e) {
        LOGGER.warn("Couldn't start docker image " + image + ": " + e.getMessage(), e);
      }
    } else {
      LOGGER.info("Skipping startup of docker " + image);
    }

    try {
      tryToInitializeSession();
    } catch (RuntimeException | Error e) {
      if (container == null) throw e;
      LOGGER.warn("Couldn't connect to docker image " + image + ": " + e.getMessage(), e);
      container.stop();
      container = null; // try with local connection instead
      tryToInitializeSession();
    }
  }

  @Override public void afterAll(ExtensionContext context) {
    if (context.getRequiredTestClass().getEnclosingClass() != null) {
      // Only run once in outermost scope.
      return;
    }

   if (container != null) {
     LOGGER.info("Stopping docker image " + image);
     container.stop();
   }
  }

  void tryToInitializeSession() {
    try (ElasticsearchStorage result = computeStorageBuilder().build()) {
      CheckResult check = result.check();
      assumeTrue(check.ok(), () -> "Could not connect to storage, skipping test: "
        + check.error().getMessage());
    }
  }

  ElasticsearchStorage.Builder computeStorageBuilder() {
    ElasticsearchStorage.Builder builder = ElasticsearchStorage.newBuilder()
      .index("zipkin-test")
      .flushOnWrites(true)
      .hosts(Arrays.asList(baseUrl()));

    if (Boolean.valueOf(System.getenv("ES_DEBUG"))) {
      builder.httpLogging(ElasticsearchStorage.HttpLoggingLevel.BODY);
    }
  }

  String baseUrl() {
    if (container != null && container.isRunning()) {
      return String.format(
          "http://%s:%d",
          container.getContainerIpAddress(), container.getMappedPort(ELASTICSEARCH_PORT));
    } else {
      // Use localhost if we failed to start a container (i.e. Docker is not available)
      return "http://localhost:" + ELASTICSEARCH_PORT;
    }
  }

  static String index(TestInfo testInfo) {
    String result;
    if (testInfo.getTestMethod().isPresent()) {
      result = testInfo.getTestMethod().get().getName();
    } else {
      assert testInfo.getTestClass().isPresent();
      result = testInfo.getTestClass().get().getSimpleName();
    }
    result = result.toLowerCase();
    return result.length() <= 48 ? result : result.substring(result.length() - 48);
  }
}
