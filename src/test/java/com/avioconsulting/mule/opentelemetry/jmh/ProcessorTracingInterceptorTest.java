package com.avioconsulting.mule.opentelemetry.jmh;

import com.avioconsulting.mule.opentelemetry.api.config.OpenTelemetryResource;
import com.avioconsulting.mule.opentelemetry.api.config.SpanProcessorConfiguration;
import com.avioconsulting.mule.opentelemetry.api.config.exporter.LoggingExporter;
import com.avioconsulting.mule.opentelemetry.api.config.exporter.OpenTelemetryExporter;
import com.avioconsulting.mule.opentelemetry.internal.config.OpenTelemetryConfigWrapper;
import com.avioconsulting.mule.opentelemetry.internal.connection.OpenTelemetryConnection;
import com.avioconsulting.mule.opentelemetry.internal.interceptor.ProcessorTracingInterceptor;
import com.avioconsulting.mule.opentelemetry.internal.processor.MuleNotificationProcessor;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import org.mule.runtime.api.component.location.ConfigurationComponentLocator;
import org.mule.runtime.api.interception.InterceptionEvent;
import org.mule.runtime.dsl.api.component.config.DefaultComponentLocation;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
public class ProcessorTracingInterceptorTest extends AbstractJMHTest {

  public static final String TEST_1_FLOW_FLOW_REF = "/test-1-flow/flow-ref";
  public static final DefaultComponentLocation COMPONENT_LOCATION = DefaultComponentLocation
      .fromSingleComponent(TEST_1_FLOW_FLOW_REF);
  public static final String TEST_1_FLOW = "test-1-flow";
  public static final String TEST_1_TRANSACTION_ID = "test-1";
  OpenTelemetryConnection connection;
  ProcessorTracingInterceptor interceptor;
  InterceptionEvent event;

  @Setup
  public void setup() {
    OpenTelemetryResource resource = new OpenTelemetryResource();
    OpenTelemetryExporter exporter = new LoggingExporter();
    OpenTelemetryConfigWrapper wrapper = new OpenTelemetryConfigWrapper(resource, exporter,
        new SpanProcessorConfiguration());
    connection = OpenTelemetryConnection.getInstance(wrapper);

    Tracer tracer = GlobalOpenTelemetry.get().getTracer("test", "v1");
    SpanBuilder spanBuilder = tracer.spanBuilder("test-transaction")
        .setSpanKind(SpanKind.SERVER)
        .setStartTimestamp(Instant.now());
    connection.getTransactionStore().startTransaction(TEST_1_TRANSACTION_ID, TEST_1_FLOW, spanBuilder);

    connection.getTransactionStore().addProcessorSpan(TEST_1_TRANSACTION_ID, TEST_1_FLOW, TEST_1_FLOW_FLOW_REF,
        tracer.spanBuilder(TEST_1_FLOW_FLOW_REF).setSpanKind(SpanKind.INTERNAL));
    ConfigurationComponentLocator componentLocator = mock(ConfigurationComponentLocator.class);
    MuleNotificationProcessor muleNotificationProcessor = new MuleNotificationProcessor(componentLocator);
    muleNotificationProcessor.init(() -> connection, true);
    interceptor = new ProcessorTracingInterceptor(muleNotificationProcessor);
    event = new TestInterceptionEvent(TEST_1_TRANSACTION_ID);
  }

  @Benchmark
  @Measurement(iterations = 2)
  @Warmup(iterations = 3)
  public void interceptBefore(Blackhole blackhole) {
    interceptor.before(COMPONENT_LOCATION, Collections.emptyMap(), event);

    // Benchmark Mode Cnt Score Error Units
    // ProcessorTracingInterceptorTest.interceptBefore thrpt 2 6163.569 ops/ms
  }

  @Override
  public int getIterations() {
    return 2;
  }

  @Override
  public int getWarmupIterations() {
    return 3;
  }
}