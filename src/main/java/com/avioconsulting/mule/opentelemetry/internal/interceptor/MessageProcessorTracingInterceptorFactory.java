package com.avioconsulting.mule.opentelemetry.internal.interceptor;

import com.avioconsulting.mule.opentelemetry.api.config.MuleComponent;
import com.avioconsulting.mule.opentelemetry.internal.config.OpenTelemetryExtensionConfiguration;
import com.avioconsulting.mule.opentelemetry.internal.processor.MuleNotificationProcessor;
import org.mule.runtime.api.component.ComponentIdentifier;
import org.mule.runtime.api.component.TypedComponentIdentifier;
import org.mule.runtime.api.component.location.ComponentLocation;
import org.mule.runtime.api.interception.ProcessorInterceptor;
import org.mule.runtime.api.interception.ProcessorInterceptorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * ProcessorInterceptorFactory can intercept processors. This is injected
 * registry for auto-configuration.
 *
 * Disable interceptor processing by setting
 * "mule.otel.interceptor.processor.enable" to `true`.
 *
 * See registry-bootstrap.properties.
 */
@Component
public class MessageProcessorTracingInterceptorFactory implements ProcessorInterceptorFactory {

  private static final Logger LOGGER = LoggerFactory.getLogger(MessageProcessorTracingInterceptorFactory.class);
  public static final String MULE_OTEL_INTERCEPTOR_PROCESSOR_ENABLE_PROPERTY_NAME = "mule.otel.interceptor.processor.enable";
  private final boolean interceptorEnabled = Boolean
      .parseBoolean(System.getProperty(MULE_OTEL_INTERCEPTOR_PROCESSOR_ENABLE_PROPERTY_NAME, "true"));

  /**
   * {@link MuleNotificationProcessor} instance for getting opentelemetry
   * connection supplier by processor.
   */
  private final ProcessorTracingInterceptor processorTracingInterceptor;
  private final MuleNotificationProcessor muleNotificationProcessor;;

  private final List<MuleComponent> interceptExclusions = new ArrayList<>();

  private final List<MuleComponent> interceptInclusions = new ArrayList<>();

  @Inject
  public MessageProcessorTracingInterceptorFactory(MuleNotificationProcessor muleNotificationProcessor) {
    processorTracingInterceptor = new ProcessorTracingInterceptor(muleNotificationProcessor);
    this.muleNotificationProcessor = muleNotificationProcessor;
    setupInterceptableComponents(muleNotificationProcessor);
  }

  /**
   * Exclude following {@link MuleComponent}s -
   * 
   * <pre>
   * - {@code ee:* } - All components such as cache, transform component, dynamic evaluate from ee namespace
   * - {@code mule:*} - All components from mule namespace except from {@link #interceptInclusions}
   *
   * </pre>
   */
  private void setupInterceptableComponents(MuleNotificationProcessor muleNotificationProcessor) {
    interceptExclusions.add(new MuleComponent("ee", "*"));
    interceptExclusions.add(new MuleComponent("mule", "*"));

    interceptInclusions.add(new MuleComponent("mule", "flow-ref"));
    if (muleNotificationProcessor.getTraceLevelConfiguration() != null) {
      if (muleNotificationProcessor.getTraceLevelConfiguration().getInterceptionDisabledComponents() != null)
        interceptExclusions
            .addAll(muleNotificationProcessor.getTraceLevelConfiguration()
                .getInterceptionDisabledComponents());
      if (muleNotificationProcessor.getTraceLevelConfiguration().getInterceptionEnabledComponents() != null)
        interceptInclusions
            .addAll(muleNotificationProcessor.getTraceLevelConfiguration()
                .getInterceptionEnabledComponents());
    }
  }

  @Override
  public ProcessorInterceptor get() {
    return processorTracingInterceptor;
  }

  public List<MuleComponent> getInterceptExclusions() {
    return interceptExclusions;
  }

  public List<MuleComponent> getInterceptInclusions() {
    return interceptInclusions;
  }

  /**
   * This intercepts the first processor of root container which can be a flow or
   * sub-flow.
   *
   * This will not intercept if "mule.otel.interceptor.processor.enable" is set to
   * `true` Or {@link MuleNotificationProcessor} does not have a valid connection
   * due to disabled tracing. See
   * {@link OpenTelemetryExtensionConfiguration#start()}.
   *
   * @param location
   *            {@link ComponentLocation}
   * @return true if intercept
   */
  @Override
  public boolean intercept(ComponentLocation location) {
    boolean intercept = false;
    if (interceptorEnabled &&
        muleNotificationProcessor.hasConnection()) {
      String interceptPath = String.format("%s/processors/0", location.getRootContainerName());
      Optional<TypedComponentIdentifier> flowAsContainer = location.getParts().get(0).getPartIdentifier()
          .filter(c -> TypedComponentIdentifier.ComponentType.FLOW.equals(c.getType()));

      // Intercept the first processor of the flow OR
      // included processor/namespaces OR
      // any processor/namespaces that are not excluded
      ComponentIdentifier identifier = location.getComponentIdentifier().getIdentifier();
      intercept = (flowAsContainer.isPresent()
          && interceptPath.equalsIgnoreCase(location.getLocation()))
          || interceptInclusions.stream()
              .anyMatch(mc -> mc.getNamespace().equalsIgnoreCase(identifier.getNamespace())
                  & (mc.getName().equalsIgnoreCase(identifier.getName())
                      || "*".equalsIgnoreCase(mc.getName())))
          || interceptExclusions.stream()
              .noneMatch(mc -> mc.getNamespace().equalsIgnoreCase(identifier.getNamespace())
                  & (mc.getName().equalsIgnoreCase(identifier.getName())
                      || "*".equalsIgnoreCase(mc.getName())));
    }
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("Will Intercept '{}'?: {}", location, intercept);
    }
    return intercept;
  }
}
