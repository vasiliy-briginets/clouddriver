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

package com.netflix.spinnaker.clouddriver.yandex.deploy

import com.netflix.spinnaker.clouddriver.helpers.AbstractServerGroupNameResolver
import com.netflix.spinnaker.clouddriver.names.NamerRegistry
import com.netflix.spinnaker.clouddriver.yandex.YandexCloudProvider
import com.netflix.spinnaker.clouddriver.yandex.model.YandexCloudServerGroup
import com.netflix.spinnaker.clouddriver.yandex.security.YandexCloudCredentials
import com.netflix.spinnaker.moniker.Moniker
import com.netflix.spinnaker.moniker.Namer
import yandex.cloud.api.compute.v1.instancegroup.InstanceGroupServiceOuterClass

import java.time.Instant
import java.util.stream.Collectors

class YandexServerGroupNameResolver extends AbstractServerGroupNameResolver {
  private static final String PHASE = "YANDEX_DEPLOY"

  private final YandexCloudCredentials credentials
  private final Namer naming

  YandexServerGroupNameResolver(YandexCloudCredentials credentials) {
    this.credentials = credentials
    naming =
      NamerRegistry.lookup()
        .withProvider(YandexCloudProvider.ID)
        .withAccount(credentials.getName())
        .withResource(YandexCloudServerGroup.class)
  }

  @Override
  public String combineAppStackDetail(String appName, String stack, String detail) {
    return super.combineAppStackDetail(appName, stack, detail)
  }

  @Override
  String getPhase() {
    return PHASE
  }

  @Override
  String getRegion() {
    return "ru-central1"
  }

  @Override
  List<TakenSlot> getTakenSlots(String clusterName) {
    return credentials.instanceGroupService().list(InstanceGroupServiceOuterClass.ListInstanceGroupsRequest.newBuilder()
    .setFolderId(credentials.getFolder())
    .build())
    .getInstanceGroupsList().stream()
    .map({ group ->
      Moniker moniker = naming.deriveMoniker(group)
      return new TakenSlot(
        group.getName(),
        moniker.sequence,
        Instant.ofEpochSecond(group.getCreatedAt().getSeconds()).toDate()
      )
    })
    .collect(Collectors.toList());

  }
}
