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

package com.netflix.spinnaker.clouddriver.yandex.model;

import com.netflix.spinnaker.clouddriver.model.HealthState;
import com.netflix.spinnaker.clouddriver.model.Instance;
import com.netflix.spinnaker.clouddriver.yandex.YandexCloudProvider;
import com.netflix.spinnaker.clouddriver.yandex.model.health.YandexInstanceHealth;
import com.netflix.spinnaker.clouddriver.yandex.model.health.YandexLoadBalancerHealth;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import yandex.cloud.api.compute.v1.InstanceOuterClass;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static yandex.cloud.api.compute.v1.InstanceOuterClass.NetworkInterface;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class YandexCloudInstance implements Instance {
  private String id;
  private String name;
  private Long launchTime;
  private String zone;
  private String providerType;
  private String cloudProvider;
  private YandexInstanceHealth instanceHealth;
  private List<YandexLoadBalancerHealth> loadBalancerHealths;
  private Map<String, String> labels;
  private Map<String, List<String>> addressesInSubnets;

  public static YandexCloudInstance createFromProto(InstanceOuterClass.Instance instance) {
    YandexInstanceHealth instanceHealth = createInstanceHealth(instance);
    return YandexCloudInstance.builder()
      .id(instance.getId())
      .name(instance.getName())
      .launchTime(calculateInstanceTimestamp(instance))
      .zone(instance.getZoneId())
      .providerType(YandexCloudProvider.ID)
      .cloudProvider(YandexCloudProvider.ID)
      .instanceHealth(instanceHealth)
      .labels(instance.getLabelsMap())
      .addressesInSubnets(instance.getNetworkInterfacesList().stream()
        .collect(Collectors.toMap(
          NetworkInterface::getSubnetId,
          ni -> Stream.of(
            ni.hasPrimaryV4Address() ? ni.getPrimaryV4Address().getAddress() : null,
            ni.hasPrimaryV4Address() ? ni.getPrimaryV4Address().getOneToOneNat().getAddress() : null,
            ni.hasPrimaryV6Address() ? ni.getPrimaryV6Address().getAddress() : null
          ).filter(Objects::nonNull).collect(Collectors.toList())
        ))
      )
      .build();
  }


  @Override
  public List<Map<String, Object>> getHealth() {
    List<Map<String, Object>> health = new ArrayList<>();

    Map<String, Object> instanceHealthState = new HashMap<>();
    instanceHealthState.put("type", "Yandex");
    instanceHealthState.put("healthClass", "platform");
    instanceHealthState.put("state", instanceHealth.getState());

    health.add(instanceHealthState);

    if (loadBalancerHealths != null) {
      loadBalancerHealths.stream()
        .map(lbHealth -> {
          HashMap<String, Object> healthState = new HashMap<>();
          instanceHealthState.put("type", "LoadBalancer");
          instanceHealthState.put("state", lbHealth.getState());
          return healthState;
        })
        .forEach(health::add);
    }
    return health;

  }

  @Override
  public HealthState getHealthState() {
    return instanceHealth.getState() != HealthState.Unknown ?
      instanceHealth.getState() :
      loadBalancerHealths == null ?
        HealthState.Unknown :
        loadBalancerHealths.stream().allMatch(health -> health.getState() == HealthState.Up) ?
          HealthState.Up :
          HealthState.Down;
  }


  private static Long calculateInstanceTimestamp(InstanceOuterClass.Instance instance) {
    return instance.getCreatedAt() != null
      ? instance.getCreatedAt().getSeconds() * 1000
      : Long.MAX_VALUE;
  }

  private static YandexInstanceHealth createInstanceHealth(InstanceOuterClass.Instance instance) {
    YandexInstanceHealth health = new YandexInstanceHealth();
    health.setStatus(YandexInstanceHealth.Status.valueOf(instance.getStatus().name()));
    return health;
  }
}
