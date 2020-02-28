/*
 * Copyright 2020 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.yandex.deploy.description;

import static java.util.stream.Collectors.toList;
import static yandex.cloud.api.compute.v1.instancegroup.InstanceGroupOuterClass.*;
import static yandex.cloud.api.compute.v1.instancegroup.InstanceGroupServiceOuterClass.CreateInstanceGroupMetadata;
import static yandex.cloud.api.compute.v1.instancegroup.InstanceGroupServiceOuterClass.CreateInstanceGroupRequest;

import com.google.protobuf.InvalidProtocolBufferException;
import com.netflix.spinnaker.clouddriver.yandex.deploy.CreateInstanceGroupFailedException;
import com.netflix.spinnaker.clouddriver.yandex.model.YandexCloudServerGroup;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import yandex.cloud.api.operation.OperationOuterClass;

public class YandexConverter {
  private static final long GB = 1024 * 1024 * 1024;

  @SuppressWarnings("Duplicates")
  @NotNull
  public static CreateInstanceGroupRequest mapToCreateRequest(
      YandexDeployGroupDescription description) {
    CreateInstanceGroupRequest.Builder builder =
        CreateInstanceGroupRequest.newBuilder()
            .setFolderId(description.getCredentials().getFolder())
            .setInstanceTemplate(mapInstanceTemplate(description.getInstanceTemplate()))
            .setScalePolicy(
                mapScalePolicy(description.getAutoScalePolicy(), description.getGroupSize()))
            .setDeployPolicy(mapDeployPolicy(description.getDeployPolicy()))
            .setAllocationPolicy(mapAllocationPolicy(description.getZones()))
            .setServiceAccountId(description.getServiceAccountId());

    if (description.getName() != null) {
      builder.setName(description.getName());
    }
    if (description.getDescription() != null) {
      builder.setDescription(description.getDescription());
    }
    if (description.getLabels() != null) {
      builder.putAllLabels(description.getLabels());
    }
    if (description.getLoadBalancerIntegration() != null
        && description.getLoadBalancerIntegration().getTargetGroupSpec() != null) {
      builder.setLoadBalancerSpec(
          mapLoadBalancerSpec(description.getLoadBalancerIntegration().getTargetGroupSpec()));
    }
    if (description.getHealthCheckSpecs() != null) {
      builder.setHealthChecksSpec(mapHealthCheckSpecs(description.getHealthCheckSpecs()));
    }
    return builder.build();
  }

  private static InstanceTemplate mapInstanceTemplate(
      YandexCloudServerGroup.InstanceTemplate instanceTemplate) {
    InstanceTemplate.Builder builder =
        InstanceTemplate.newBuilder()
            .setPlatformId(instanceTemplate.getPlatformId())
            .setResourcesSpec(
                ResourcesSpec.newBuilder()
                    .setCores(instanceTemplate.getResourcesSpec().getCores())
                    .setCoreFraction(instanceTemplate.getResourcesSpec().getCoreFraction())
                    .setGpus(instanceTemplate.getResourcesSpec().getGpus())
                    .setMemory(instanceTemplate.getResourcesSpec().getMemory() * GB))
            .setBootDiskSpec(mapAttachedDiskSpec(instanceTemplate.getBootDiskSpec()))
            .addAllNetworkInterfaceSpecs(
                instanceTemplate.getNetworkInterfaceSpecs().stream()
                    .map(YandexConverter::mapNetworkInterface)
                    .collect(toList()));

    if (instanceTemplate.getDescription() != null) {
      builder.setDescription(instanceTemplate.getDescription());
    }
    if (instanceTemplate.getLabels() != null) {
      builder.putAllLabels(instanceTemplate.getLabels());
    }
    if (instanceTemplate.getMetadata() != null) {
      builder.putAllMetadata(instanceTemplate.getMetadata());
    }
    if (instanceTemplate.getSecondaryDiskSpecs() != null) {
      builder.addAllSecondaryDiskSpecs(
          instanceTemplate.getSecondaryDiskSpecs().stream()
              .map(YandexConverter::mapAttachedDiskSpec)
              .collect(toList()));
    }
    if (instanceTemplate.getSchedulingPolicy() != null) {
      builder.setSchedulingPolicy(
          SchedulingPolicy.newBuilder()
              .setPreemptible(instanceTemplate.getSchedulingPolicy().isPreemptible()));
    }
    if (instanceTemplate.getServiceAccountId() != null) {
      builder.setServiceAccountId(instanceTemplate.getServiceAccountId());
    }

    return builder.build();
  }

  private static NetworkInterfaceSpec mapNetworkInterface(
      YandexCloudServerGroup.NetworkInterfaceSpec spec) {
    NetworkInterfaceSpec.Builder builder = NetworkInterfaceSpec.newBuilder();
    if (spec.getNetworkId() != null) {
      builder.setNetworkId(spec.getNetworkId());
    }
    if (spec.getSubnetIds() != null) {
      builder.addAllSubnetIds(spec.getSubnetIds());
    }
    if (spec.getPrimaryV4AddressSpec() != null) {
      builder.setPrimaryV4AddressSpec(
          mapAddressSpec(spec.getPrimaryV4AddressSpec(), IpVersion.IPV4));
    }
    if (spec.getPrimaryV6AddressSpec() != null) {
      builder.setPrimaryV6AddressSpec(
          mapAddressSpec(spec.getPrimaryV6AddressSpec(), IpVersion.IPV6));
    }
    return builder.build();
  }

  @NotNull
  private static PrimaryAddressSpec mapAddressSpec(
      YandexCloudServerGroup.PrimaryAddressSpec addressSpec, IpVersion ipVersion) {
    PrimaryAddressSpec.Builder builder = PrimaryAddressSpec.newBuilder();
    if (addressSpec.isOneToOneNat()) {
      builder.setOneToOneNatSpec(OneToOneNatSpec.newBuilder().setIpVersion(ipVersion).build());
    }
    return builder.build();
  }

  private static AttachedDiskSpec mapAttachedDiskSpec(
      YandexCloudServerGroup.AttachedDiskSpec spec) {
    AttachedDiskSpec.DiskSpec.Builder diskSpec =
        AttachedDiskSpec.DiskSpec.newBuilder()
            .setTypeId(spec.getDiskSpec().getTypeId())
            .setSize(spec.getDiskSpec().getSize() * GB);
    if (spec.getDiskSpec().getDescription() != null) {
      diskSpec.setDescription(spec.getDiskSpec().getDescription());
    }
    if (spec.getDiskSpec().getImageId() != null) {
      diskSpec.setImageId(spec.getDiskSpec().getImageId());
    }
    if (spec.getDiskSpec().getSnapshotId() != null) {
      diskSpec.setSnapshotId(spec.getDiskSpec().getSnapshotId());
    }
    AttachedDiskSpec.Builder builder =
        AttachedDiskSpec.newBuilder()
            .setMode(
                spec.getMode() != null
                    ? AttachedDiskSpec.Mode.valueOf(spec.getMode().name())
                    : AttachedDiskSpec.Mode.READ_WRITE)
            .setDiskSpec(diskSpec);

    if (spec.getDeviceName() != null) {
      builder.setDeviceName(spec.getDeviceName());
    }
    return builder.build();
  }

  @SuppressWarnings("Duplicates")
  private static LoadBalancerSpec mapLoadBalancerSpec(
      YandexCloudServerGroup.TargetGroupSpec targetGroupSpec) {
    TargetGroupSpec.Builder builder = TargetGroupSpec.newBuilder();
    if (targetGroupSpec.getName() != null) {
      builder.setName(targetGroupSpec.getName());
    }
    if (targetGroupSpec.getDescription() != null) {
      builder.setDescription(targetGroupSpec.getDescription());
    }
    if (targetGroupSpec.getLabels() != null) {
      builder.putAllLabels(targetGroupSpec.getLabels());
    }
    return LoadBalancerSpec.newBuilder().setTargetGroupSpec(builder).build();
  }

  private static HealthChecksSpec mapHealthCheckSpecs(
      List<YandexCloudServerGroup.HealthCheckSpec> healthCheckSpecs) {
    return HealthChecksSpec.newBuilder()
        .addAllHealthCheckSpecs(
            healthCheckSpecs.stream().map(YandexConverter::mapHealthCheckSpec).collect(toList()))
        .build();
  }

  @NotNull
  private static HealthCheckSpec mapHealthCheckSpec(YandexCloudServerGroup.HealthCheckSpec hc) {
    HealthCheckSpec.Builder builder = HealthCheckSpec.newBuilder();
    if (hc.getType() == YandexCloudServerGroup.HealthCheckSpec.Type.HTTP) {
      builder.setHttpOptions(
          HealthCheckSpec.HttpOptions.newBuilder().setPort(hc.getPort()).setPath(hc.getPath()));
    } else {
      builder.setTcpOptions(HealthCheckSpec.TcpOptions.newBuilder().setPort(hc.getPort()));
    }
    return builder
        .setInterval(mapDuration(hc.getInterval()))
        .setTimeout(mapDuration(hc.getTimeout()))
        .setUnhealthyThreshold(hc.getUnhealthyThreshold())
        .setHealthyThreshold(hc.getHealthyThreshold())
        .build();
  }

  private static DeployPolicy mapDeployPolicy(YandexCloudServerGroup.DeployPolicy deployPolicy) {
    return DeployPolicy.newBuilder()
        .setMaxCreating(deployPolicy.getMaxCreating())
        .setMaxDeleting(deployPolicy.getMaxDeleting())
        .setMaxExpansion(deployPolicy.getMaxExpansion())
        .setMaxUnavailable(deployPolicy.getMaxUnavailable())
        .setStartupDuration(mapDuration(deployPolicy.getStartupDuration()))
        .build();
  }

  private static ScalePolicy mapScalePolicy(
      YandexCloudServerGroup.AutoScalePolicy autoScalePolicy, Long groupSize) {
    ScalePolicy.Builder builder = ScalePolicy.newBuilder();
    if (autoScalePolicy != null) {
      ScalePolicy.AutoScale.Builder asBuilder =
          ScalePolicy.AutoScale.newBuilder()
              .setInitialSize(autoScalePolicy.getInitialSize())
              .setMinZoneSize(autoScalePolicy.getMinZoneSize())
              .setMaxSize(autoScalePolicy.getMaxSize());

      asBuilder.setMeasurementDuration(mapDuration(autoScalePolicy.getMeasurementDuration()));
      asBuilder.setWarmupDuration(mapDuration(autoScalePolicy.getWarmupDuration()));
      asBuilder.setStabilizationDuration(mapDuration(autoScalePolicy.getStabilizationDuration()));
      if (autoScalePolicy.getCpuUtilizationRule() != null) {
        asBuilder.setCpuUtilizationRule(
            ScalePolicy.CpuUtilizationRule.newBuilder()
                .setUtilizationTarget(
                    autoScalePolicy.getCpuUtilizationRule().getUtilizationTarget()));
      }
      if (autoScalePolicy.getCustomRules() != null) {
        autoScalePolicy.getCustomRules().stream()
            .map(
                rule ->
                    ScalePolicy.CustomRule.newBuilder()
                        .setRuleType(
                            ScalePolicy.CustomRule.RuleType.valueOf(rule.getRuleType().name()))
                        .setMetricType(
                            ScalePolicy.CustomRule.MetricType.valueOf(rule.getMetricType().name()))
                        .setMetricName(rule.getMetricName())
                        .setTarget(rule.getTarget())
                        .build())
            .forEach(asBuilder::addCustomRules);
      }
      builder.setAutoScale(asBuilder);
    } else {
      builder.setFixedScale(
          ScalePolicy.FixedScale.newBuilder()
              .setSize(groupSize == null || groupSize < 0 ? 0 : groupSize));
    }
    return builder.build();
  }

  @NotNull
  private static com.google.protobuf.Duration.Builder mapDuration(Duration duration) {
    com.google.protobuf.Duration.Builder builder = com.google.protobuf.Duration.newBuilder();
    if (duration == null) {
      return builder;
    }
    return builder.setSeconds(duration.getSeconds());
  }

  @NotNull
  private static AllocationPolicy mapAllocationPolicy(Set<String> zones) {
    return AllocationPolicy.newBuilder()
        .addAllZones(
            zones.stream()
                .map(zone -> AllocationPolicy.Zone.newBuilder().setZoneId(zone).build())
                .collect(toList()))
        .build();
  }

  public static CreateInstanceGroupMetadata convertOperationMetadata(
      OperationOuterClass.Operation operation) {
    try {
      return operation.getMetadata().unpack(CreateInstanceGroupMetadata.class);
    } catch (InvalidProtocolBufferException e) {
      throw new CreateInstanceGroupFailedException(operation.toString());
    }
  }
}
