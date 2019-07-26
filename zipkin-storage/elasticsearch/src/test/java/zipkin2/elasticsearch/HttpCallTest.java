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
package zipkin2.elasticsearch; // to access package-private stuff

import com.google.common.util.concurrent.SimpleTimeLimiter;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpClientBuilder;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit4.server.ServerRule;
import java.io.FileNotFoundException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import zipkin2.Call;
import zipkin2.Callback;
import zipkin2.elasticsearch.internal.client.HttpCall;
import zipkin2.elasticsearch.internal.client.NamedRequestClient;
import zipkin2.internal.Nullable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.failBecauseExceptionWasNotThrown;
import static org.awaitility.Awaitility.await;

public class HttpCallTest {

  private static final AtomicReference<AggregatedHttpResponse> MOCK_RESPONSE =
    new AtomicReference<>();
  private static final AggregatedHttpResponse SUCCESS_RESPONSE =
    AggregatedHttpResponse.of(HttpStatus.OK);

  @ClassRule public static ServerRule server = new ServerRule() {
    @Override protected void configure(ServerBuilder sb) {
      sb.service("/", ((ctx, req) -> HttpResponse.of(MOCK_RESPONSE.get())));
    }
  };

  private static final AggregatedHttpRequest REQUEST =
    AggregatedHttpRequest.of(HttpMethod.GET, "/");

  HttpCall.Factory http;

  @Before public void setUp() {
    http = new HttpCall.Factory(HttpClient.of(server.httpUri("/")));
  }

  @Test public void emptyContent() throws Exception {
    MOCK_RESPONSE.set(AggregatedHttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, ""));

    assertThat(http.newCall(REQUEST, unused -> "not me", "test").execute()).isNull();
    CompletableCallback<String> future = new CompletableCallback<>();
    http.newCall(REQUEST, unused -> "not me", "test").enqueue(future);
    assertThat(future.join()).isNull();
  }

  @Test public void propagatesOnDispatcherThreadWhenFatal() throws Exception {
    MOCK_RESPONSE.set(SUCCESS_RESPONSE);

    final LinkedBlockingQueue<Object> q = new LinkedBlockingQueue<>();
    http.newCall(REQUEST, content -> {
      throw new LinkageError();
    }, "test").enqueue(new Callback<Object>() {
      @Override public void onSuccess(@Nullable Object value) {
        q.add(value);
      }

      @Override public void onError(Throwable t) {
        q.add(t);
      }
    });

    ExecutorService cached = Executors.newCachedThreadPool();
    SimpleTimeLimiter timeLimiter = SimpleTimeLimiter.create(cached);
    try {
      timeLimiter.callWithTimeout(q::take, 100, TimeUnit.MILLISECONDS);
      failBecauseExceptionWasNotThrown(TimeoutException.class);
    } catch (TimeoutException expected) {
    } finally {
      cached.shutdownNow();
    }
  }

  @Test public void executionException_conversionException() throws Exception {
    MOCK_RESPONSE.set(SUCCESS_RESPONSE);

    Call<?> call = http.newCall(REQUEST, content -> {
      throw new IllegalArgumentException("eeek");
    }, "test");

    try {
      call.execute();
      failBecauseExceptionWasNotThrown(IllegalArgumentException.class);
    } catch (IllegalArgumentException expected) {
      assertThat(expected).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test public void cloned() throws Exception {
    MOCK_RESPONSE.set(SUCCESS_RESPONSE);

    Call<?> call = http.newCall(REQUEST, content -> null, "test");
    call.execute();

    try {
      call.execute();
      failBecauseExceptionWasNotThrown(IllegalStateException.class);
    } catch (IllegalStateException expected) {
      assertThat(expected).isInstanceOf(IllegalStateException.class);
    }

    MOCK_RESPONSE.set(SUCCESS_RESPONSE);

    call.clone().execute();
  }

  @Test public void executionException_5xx() throws Exception {
    MOCK_RESPONSE.set(AggregatedHttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR));

    Call<?> call = http.newCall(REQUEST, BodyConverters.NULL, "test");

    try {
      call.execute();
      failBecauseExceptionWasNotThrown(RuntimeException.class);
    } catch (RuntimeException expected) {
      assertThat(expected).hasMessage("response for / failed: 500 Internal Server Error");
    }
  }

  @Test public void executionException_404() throws Exception {
    MOCK_RESPONSE.set(AggregatedHttpResponse.of(HttpStatus.NOT_FOUND));

    Call<?> call = http.newCall(REQUEST, BodyConverters.NULL, "test");

    try {
      call.execute();
      failBecauseExceptionWasNotThrown(FileNotFoundException.class);
    } catch (FileNotFoundException expected) {
      assertThat(expected).hasMessage("/");
    }
  }

  @Test public void executionException_message() throws Exception {
    Map<AggregatedHttpResponse, String> responseToMessage = new LinkedHashMap<>();
    responseToMessage.put(AggregatedHttpResponse.of(
      ResponseHeaders.of(HttpStatus.UNAUTHORIZED),
      HttpData.ofUtf8("{\"message\":\"rain\"}")
    ), "rain");
    responseToMessage.put(AggregatedHttpResponse.of(
      ResponseHeaders.of(HttpStatus.FORBIDDEN),
      HttpData.ofUtf8("{\"Message\":\"snow\"}") // note: case of key is different
    ), "snow");
    responseToMessage.put(AggregatedHttpResponse.of(
      ResponseHeaders.of(HttpStatus.BAD_GATEWAY),
      HttpData.ofUtf8("Message: sleet") // note: not json
    ), "response for / failed: Message: sleet"); // In this case, we give request context

    Call<?> call = http.newCall(REQUEST, BodyConverters.NULL, "test");

    for (Map.Entry<AggregatedHttpResponse, String> entry : responseToMessage.entrySet()) {
      MOCK_RESPONSE.set(entry.getKey());

      try {
        call.clone().execute();
        failBecauseExceptionWasNotThrown(RuntimeException.class);
      } catch (RuntimeException expected) {
        assertThat(expected).hasMessage(entry.getValue());
      }
    }
  }

  @Test public void setsCustomName() throws Exception {
    MOCK_RESPONSE.set(SUCCESS_RESPONSE);

    AtomicReference<RequestLog> log = new AtomicReference<>();
    http = new HttpCall.Factory(new HttpClientBuilder(server.httpUri("/"))
      .decorator((client, ctx, req) -> {
        ctx.log().addListener(log::set, RequestLogAvailability.COMPLETE);
        return client.execute(ctx, req);
      })
      .decorator(NamedRequestClient.newDecorator())
      .build());

    http.newCall(REQUEST, BodyConverters.NULL, "custom-name").execute();

    await().untilAsserted(() -> assertThat(log).isNotNull());
    assertThat(log.get().requestContent()).isInstanceOfSatisfying(RpcRequest.class,
      req -> assertThat(req.method().endsWith("custom-name")));
  }

  // TODO(adriancole): Find a home for this generic conversion between Call and Java 8.
  static final class CompletableCallback<T> extends CompletableFuture<T> implements Callback<T> {

    @Override public void onSuccess(T value) {
      complete(value);
    }

    @Override public void onError(Throwable t) {
      completeExceptionally(t);
    }
  }
}
