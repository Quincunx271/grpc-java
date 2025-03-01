/*
 * Copyright 2022 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.gcp.observability.logging;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.cloud.MonitoredResource;
import com.google.cloud.logging.LogEntry;
import com.google.cloud.logging.Logging;
import com.google.cloud.logging.LoggingOptions;
import com.google.cloud.logging.Payload.JsonPayload;
import com.google.cloud.logging.Severity;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.util.JsonFormat;
import io.grpc.Internal;
import io.grpc.internal.JsonParser;
import io.grpc.observabilitylog.v1.GrpcLogRecord;
import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sink for Google Cloud Logging.
 */
@Internal
public class GcpLogSink implements Sink {
  private final Logger logger = Logger.getLogger(GcpLogSink.class.getName());

  private static final String DEFAULT_LOG_NAME =
      "microservices.googleapis.com%2Fobservability%2Fgrpc";
  private static final Severity DEFAULT_LOG_LEVEL = Severity.DEBUG;
  private static final String K8S_MONITORED_RESOURCE_TYPE = "k8s_container";
  private static final Set<String> kubernetesResourceLabelSet
      = ImmutableSet.of("project_id", "location", "cluster_name", "namespace_name",
      "pod_name", "container_name");
  private final String projectId;
  private final Map<String, String> customTags;
  private final MonitoredResource kubernetesResource;
  /** Lazily initialize cloud logging client to avoid circular initialization. Because cloud
   * logging APIs also uses gRPC. */
  private volatile Logging gcpLoggingClient;
  private final Collection<String> servicesToExclude;

  @VisibleForTesting
  GcpLogSink(Logging loggingClient, String projectId, Map<String, String> locationTags,
      Map<String, String> customTags, Collection<String> servicesToExclude) {
    this(projectId, locationTags, customTags, servicesToExclude);
    this.gcpLoggingClient = loggingClient;
  }

  /**
   * Retrieves a single instance of GcpLogSink.
   *
   * @param projectId GCP project id to write logs
   * @param servicesToExclude service names for which log entries should not be generated
   */
  public GcpLogSink(String projectId, Map<String, String> locationTags,
      Map<String, String> customTags, Collection<String> servicesToExclude) {
    this.projectId = projectId;
    this.customTags = getCustomTags(customTags, locationTags, projectId);
    this.kubernetesResource = getResource(locationTags);
    this.servicesToExclude = checkNotNull(servicesToExclude, "servicesToExclude");
  }

  /**
   * Writes logs to GCP Cloud Logging.
   *
   * @param logProto gRPC logging proto containing the message to be logged
   */
  @Override
  public void write(GrpcLogRecord logProto) {
    if (gcpLoggingClient == null) {
      synchronized (this) {
        if (gcpLoggingClient == null) {
          gcpLoggingClient = createLoggingClient();
        }
      }
    }
    if (servicesToExclude.contains(logProto.getServiceName())) {
      return;
    }
    try {
      GrpcLogRecord.EventType eventType = logProto.getType();
      // TODO(DNVindhya): make sure all (int, long) values are not displayed as double
      // For now, every value is being converted as string because of JsonFormat.printer().print
      Map<String, Object> logProtoMap = protoToMapConverter(logProto);
      LogEntry.Builder grpcLogEntryBuilder =
          LogEntry.newBuilder(JsonPayload.of(logProtoMap))
              .setSeverity(DEFAULT_LOG_LEVEL)
              .setLogName(DEFAULT_LOG_NAME)
              .setResource(kubernetesResource)
              .setTimestamp(Instant.now());

      if (!customTags.isEmpty()) {
        grpcLogEntryBuilder.setLabels(customTags);
      }
      LogEntry grpcLogEntry = grpcLogEntryBuilder.build();
      synchronized (this) {
        logger.log(Level.FINEST, "Writing gRPC event : {0} to Cloud Logging", eventType);
        gcpLoggingClient.write(Collections.singleton(grpcLogEntry));
      }
    } catch (Exception e) {
      logger.log(Level.SEVERE, "Caught exception while writing to Cloud Logging", e);
    }
  }

  Logging createLoggingClient() {
    LoggingOptions.Builder builder = LoggingOptions.newBuilder();
    if (!Strings.isNullOrEmpty(projectId)) {
      builder.setProjectId(projectId);
    }
    return builder.build().getService();
  }

  @VisibleForTesting
  static Map<String, String> getCustomTags(Map<String, String> customTags,
      Map<String, String> locationTags, String projectId) {
    ImmutableMap.Builder<String, String> tagsBuilder = ImmutableMap.builder();
    String sourceProjectId = locationTags.get("project_id");
    if (!Strings.isNullOrEmpty(projectId)
        && !Strings.isNullOrEmpty(sourceProjectId)
        && !Objects.equals(sourceProjectId, projectId)) {
      tagsBuilder.put("source_project_id", sourceProjectId);
    }
    if (customTags != null) {
      tagsBuilder.putAll(customTags);
    }
    return tagsBuilder.buildOrThrow();
  }

  @VisibleForTesting
  static MonitoredResource getResource(Map<String, String> resourceTags) {
    MonitoredResource.Builder builder = MonitoredResource.newBuilder(K8S_MONITORED_RESOURCE_TYPE);
    if ((resourceTags != null) && !resourceTags.isEmpty()) {
      for (Map.Entry<String, String> entry : resourceTags.entrySet()) {
        String resourceKey = entry.getKey();
        if (kubernetesResourceLabelSet.contains(resourceKey)) {
          builder.addLabel(resourceKey, entry.getValue());
        }
      }
    }
    return builder.build();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> protoToMapConverter(GrpcLogRecord logProto)
      throws IOException {
    JsonFormat.Printer printer = JsonFormat.printer();
    String recordJson = printer.print(logProto);
    return (Map<String, Object>) JsonParser.parse(recordJson);
  }

  /**
   * Closes Cloud Logging Client.
   */
  @Override
  public synchronized void close() {
    if (gcpLoggingClient == null) {
      logger.log(Level.WARNING, "Attempt to close after GcpLogSink is closed.");
      return;
    }
    try {
      gcpLoggingClient.close();
    } catch (Exception e) {
      logger.log(Level.SEVERE, "Caught exception while closing", e);
    }
  }
}
