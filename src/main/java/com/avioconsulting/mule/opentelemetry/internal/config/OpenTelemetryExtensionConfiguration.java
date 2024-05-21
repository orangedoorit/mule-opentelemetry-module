package com.avioconsulting.mule.opentelemetry.internal.config;

import com.avioconsulting.mule.opentelemetry.api.AppIdentifier;
import com.avioconsulting.mule.opentelemetry.api.config.ExporterConfiguration;
import com.avioconsulting.mule.opentelemetry.api.config.OpenTelemetryResource;
import com.avioconsulting.mule.opentelemetry.api.config.SpanProcessorConfiguration;
import com.avioconsulting.mule.opentelemetry.api.config.TraceLevelConfiguration;
import com.avioconsulting.mule.opentelemetry.api.notifications.MetricBaseNotificationData;
import com.avioconsulting.mule.opentelemetry.api.providers.OpenTelemetryMetricsConfigProvider;
import com.avioconsulting.mule.opentelemetry.api.providers.OpenTelemetryMetricsConfigSupplier;
import com.avioconsulting.mule.opentelemetry.internal.OpenTelemetryOperations;
import com.avioconsulting.mule.opentelemetry.internal.connection.OpenTelemetryConnection;
import com.avioconsulting.mule.opentelemetry.internal.connection.OpenTelemetryConnectionProvider;
import com.avioconsulting.mule.opentelemetry.internal.notifications.listeners.AsyncMessageNotificationListener;
import com.avioconsulting.mule.opentelemetry.internal.notifications.listeners.MetricEventNotificationListener;
import com.avioconsulting.mule.opentelemetry.internal.notifications.listeners.MuleMessageProcessorNotificationListener;
import com.avioconsulting.mule.opentelemetry.internal.notifications.listeners.MulePipelineMessageNotificationListener;
import com.avioconsulting.mule.opentelemetry.internal.processor.MuleNotificationProcessor;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.lifecycle.Startable;
import org.mule.runtime.api.lifecycle.Stoppable;
import org.mule.runtime.api.meta.ExpressionSupport;
import org.mule.runtime.api.metadata.DataType;
import org.mule.runtime.api.notification.NotificationListenerRegistry;
import org.mule.runtime.core.api.el.ExpressionManager;
import org.mule.runtime.extension.api.annotation.Configuration;
import org.mule.runtime.extension.api.annotation.Expression;
import org.mule.runtime.extension.api.annotation.Operations;
import org.mule.runtime.extension.api.annotation.connectivity.ConnectionProviders;
import org.mule.runtime.extension.api.annotation.param.Optional;
import org.mule.runtime.extension.api.annotation.param.Parameter;
import org.mule.runtime.extension.api.annotation.param.ParameterGroup;
import org.mule.runtime.extension.api.annotation.param.RefName;
import org.mule.runtime.extension.api.annotation.param.display.DisplayName;
import org.mule.runtime.extension.api.annotation.param.display.Placement;
import org.mule.runtime.extension.api.annotation.param.display.Summary;
import org.mule.runtime.http.api.HttpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

@Operations(OpenTelemetryOperations.class)
@ConnectionProviders(OpenTelemetryConnectionProvider.class)
@Configuration
public class OpenTelemetryExtensionConfiguration
    implements Startable, Stoppable, OpenTelemetryConfiguration, OpenTelemetryMetricsConfigSupplier {

  public static final String PROP_MULE_OTEL_TRACING_DISABLED = "mule.otel.tracing.disabled";
  private final Logger logger = LoggerFactory.getLogger(OpenTelemetryExtensionConfiguration.class);
  private static final DataType METRIC_NOTIFICATION_DATA_TYPE = DataType.fromType(MetricBaseNotificationData.class);

  @RefName
  private String configName;

  @Inject
  private HttpService httpService;
  @Inject
  private ExpressionManager expressionManager;
  private AppIdentifier appIdentifier;

  public HttpService getHttpService() {
    return httpService;
  }

  @Parameter
  @Optional(defaultValue = "false")
  @Summary("Turn off tracing for this application.")
  private boolean turnOffTracing;

  /**
   * Open Telemetry Resource Configuration. System or Environment Variables will
   * override this configuration. See Documentation for variable details.
   */
  @ParameterGroup(name = "Resource")
  @Placement(order = 10)
  @Summary("Open Telemetry Resource Configuration. System or Environment Variables will override this configuration.")
  private OpenTelemetryResource resource;

  /**
   * Open Telemetry Exporter Configuration. System or Environment Variables will
   * override this configuration. See Documentation for variable details.
   */
  @ParameterGroup(name = "Exporter")
  @Placement(order = 20)
  @Expression(ExpressionSupport.NOT_SUPPORTED)
  private ExporterConfiguration exporterConfiguration;

  @ParameterGroup(name = "Trace Levels")
  @Placement(order = 30)
  private TraceLevelConfiguration traceLevelConfiguration;

  @ParameterGroup(name = "Span Processor")
  @Placement(order = 40, tab = "Tracer Settings")
  @Expression(ExpressionSupport.NOT_SUPPORTED)
  private SpanProcessorConfiguration spanProcessorConfiguration;

  @Parameter
  @Optional
  @Placement(order = 501, tab = "Metrics")
  @DisplayName("Metrics Provider")
  @Summary("OpenTelemetry Metrics Provider")
  private OpenTelemetryMetricsConfigProvider metricsConfigProvider;

  @Override
  public boolean isTurnOffTracing() {
    return System.getProperties().containsKey(PROP_MULE_OTEL_TRACING_DISABLED) ? Boolean
        .parseBoolean(System.getProperty(PROP_MULE_OTEL_TRACING_DISABLED)) : turnOffTracing;
  }

  // Visible for testing purpose
  OpenTelemetryExtensionConfiguration setTurnOffTracing(boolean turnOffTracing) {
    this.turnOffTracing = turnOffTracing;
    return this;
  }

  @Override
  public OpenTelemetryResource getResource() {
    return resource;
  }

  public OpenTelemetryExtensionConfiguration setResource(OpenTelemetryResource resource) {
    this.resource = resource;
    return this;
  }

  @Override
  public ExporterConfiguration getExporterConfiguration() {
    return exporterConfiguration;
  }

  public OpenTelemetryExtensionConfiguration setExporterConfiguration(ExporterConfiguration exporterConfiguration) {
    this.exporterConfiguration = exporterConfiguration;
    return this;
  }

  @Override
  public TraceLevelConfiguration getTraceLevelConfiguration() {
    return traceLevelConfiguration;
  }

  public OpenTelemetryExtensionConfiguration setTraceLevelConfiguration(
      TraceLevelConfiguration traceLevelConfiguration) {
    this.traceLevelConfiguration = traceLevelConfiguration;
    return this;
  }

  @Override
  public SpanProcessorConfiguration getSpanProcessorConfiguration() {
    return spanProcessorConfiguration;
  }

  public OpenTelemetryExtensionConfiguration setSpanProcessorConfiguration(
      SpanProcessorConfiguration spanProcessorConfiguration) {
    this.spanProcessorConfiguration = spanProcessorConfiguration;
    return this;
  }

  @Override
  public String getConfigName() {
    return configName;
  }

  @Inject
  NotificationListenerRegistry notificationListenerRegistry;

  @Inject
  MuleNotificationProcessor muleNotificationProcessor;

  @Override
  public void start() throws MuleException {
    if (disableTelemetry()) {
      logger.warn("Tracing and Metrics is disabled. OpenTelemetry will be turned off for config '{}'.",
          getConfigName());
      // Is there a better way to let runtime trigger the configuration shutdown
      // without stopping the application?
      // Raising an exception here will make runtime invoke the stop method
      // but it will kill the application as well, so can't do that here.
      // For now, let's skip the initialization of tracing related components and
      // processors.
      return;
    }
    logger.info("Initiating otel config - '{}'", getConfigName());
    appIdentifier = AppIdentifier.fromEnvironment(expressionManager);
    OpenTelemetryConnection openTelemetryConnection = OpenTelemetryConnection
        .getInstance(new OpenTelemetryConfigWrapper(this));
    muleNotificationProcessor.init(openTelemetryConnection,
        getTraceLevelConfiguration());

    notificationListenerRegistry.registerListener(
        new MuleMessageProcessorNotificationListener(muleNotificationProcessor));
    notificationListenerRegistry.registerListener(
        new MulePipelineMessageNotificationListener(muleNotificationProcessor));
    notificationListenerRegistry.registerListener(new AsyncMessageNotificationListener(muleNotificationProcessor));
    notificationListenerRegistry.registerListener(new MetricEventNotificationListener(muleNotificationProcessor),
        extensionNotification -> METRIC_NOTIFICATION_DATA_TYPE
            .isCompatibleWith(extensionNotification.getData().getDataType()));
  }

  @Override
  public AppIdentifier getAppIdentifier() {
    return appIdentifier;
  }

  @Override
  public OpenTelemetryMetricsConfigProvider getMetricsConfigProvider() {
    return metricsConfigProvider;
  }

  private boolean disableTelemetry() {
    return isTurnOffTracing() && metricsConfigProvider == null;
  }

  @Override
  public void stop() throws MuleException {
    if (isTurnOffTracing()) {
      logger.info("{} is set to true. Configuration '{}' has been stopped.", PROP_MULE_OTEL_TRACING_DISABLED,
          getConfigName());
    }
  }
}
