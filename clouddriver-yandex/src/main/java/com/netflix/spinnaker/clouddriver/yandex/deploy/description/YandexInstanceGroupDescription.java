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

import com.netflix.spinnaker.clouddriver.security.resources.CredentialsNameable;
import com.netflix.spinnaker.clouddriver.yandex.model.YandexCloudServerGroup;
import com.netflix.spinnaker.clouddriver.yandex.security.YandexCloudCredentials;
import com.netflix.spinnaker.kork.artifacts.model.Artifact;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Data;

@Data
public class YandexInstanceGroupDescription implements CredentialsNameable, Cloneable {
  private YandexCloudCredentials credentials;

  private String name;
  private String description;
  private Set<String> zones;
  private Map<String, String> labels;
  private Long groupSize;
  private YandexCloudServerGroup.AutoScalePolicy autoScalePolicy;
  private YandexCloudServerGroup.DeployPolicy deployPolicy;
  private YandexCloudServerGroup.LoadBalancerIntegration loadBalancerIntegration;
  private List<YandexCloudServerGroup.HealthCheckSpec> healthCheckSpecs;
  private YandexCloudServerGroup.InstanceTemplate instanceTemplate;
  private String serviceAccountId;
  private Artifact imageArtifact;
}
