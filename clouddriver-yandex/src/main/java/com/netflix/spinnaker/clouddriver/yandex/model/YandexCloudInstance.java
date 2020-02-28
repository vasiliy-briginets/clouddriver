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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import yandex.cloud.api.compute.v1.InstanceOuterClass;

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
  private HealthState healthState;
  private List<Map<String, Object>> health;
  private YandexInstanceHealth instanceHealth;
  private Map<String, String> labels;

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
        .healthState(instanceHealth.getState())
        .health(
            Collections.singletonList(Collections.singletonMap("state", instanceHealth.getState())))
        .labels(instance.getLabelsMap())
        .build();
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
