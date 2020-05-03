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

import com.google.protobuf.FieldMask;
import com.netflix.spinnaker.clouddriver.yandex.model.YandexCloudLoadBalancer;

import static yandex.cloud.api.loadbalancer.v1.NetworkLoadBalancerOuterClass.*;
import static yandex.cloud.api.loadbalancer.v1.NetworkLoadBalancerServiceOuterClass.*;

public class YandexLoadBalancerConverter {
  @SuppressWarnings("DuplicatedCode")
  public static CreateNetworkLoadBalancerRequest mapToCreateRequest(
    UpsertYandexLoadBalancerDescription description) {
    CreateNetworkLoadBalancerRequest.Builder builder =
      CreateNetworkLoadBalancerRequest.newBuilder()
        .setFolderId(description.getCredentials().getFolder())
        .setRegionId("ru-central1")
        .setType(NetworkLoadBalancer.Type.valueOf(description.getType().name()));

    if (description.getName() != null) {
      builder.setName(description.getName());
    }
    if (description.getDescription() != null) {
      builder.setDescription(description.getDescription());
    }
    if (description.getLabels() != null) {
      builder.putAllLabels(description.getLabels());
    }
    if (description.getListeners() != null) {
      description.getListeners().forEach(listener -> {
        ListenerSpec.Builder spec = ListenerSpec.newBuilder()
          .setName(listener.getName())
          .setPort(listener.getPort())
          .setTargetPort(listener.getTargetPort())
          .setProtocol(Listener.Protocol.valueOf(listener.getProtocol().name()));
        if (description.getType() == YandexCloudLoadBalancer.BalancerType.INTERNAL) {
          spec.setInternalAddressSpec(InternalAddressSpec.newBuilder()
            .setSubnetId(listener.getSubnetId())
            .setAddress(listener.getAddress())
            .setIpVersion(IpVersion.valueOf(listener.getIpVersion().name()))
          );
        } else {
          spec.setExternalAddressSpec(ExternalAddressSpec.newBuilder()
            .setAddress(listener.getAddress())
            .setIpVersion(IpVersion.valueOf(listener.getIpVersion().name()))
          );
        }
        builder.addListenerSpecs(spec);
      });
    }

    return builder.build();
  }

  @SuppressWarnings("DuplicatedCode")
  public static UpdateNetworkLoadBalancerRequest mapToUpdateRequest(String networkLoadBalancerId, UpsertYandexLoadBalancerDescription description) {
    FieldMask.Builder updateMask = FieldMask.newBuilder();
    UpdateNetworkLoadBalancerRequest.Builder builder =
      UpdateNetworkLoadBalancerRequest.newBuilder().setNetworkLoadBalancerId(networkLoadBalancerId);
    if (description.getName() != null) {
      updateMask.addPaths("name");
      builder.setName(description.getName());
    }
    if (description.getDescription() != null) {
      updateMask.addPaths("description");
      builder.setDescription(description.getDescription());
    }
    if (description.getLabels() != null) {
      updateMask.addPaths("labels");
      builder.putAllLabels(description.getLabels());
    }
    if (description.getListeners() != null) {
      updateMask.addPaths("listeners");
      description.getListeners().forEach(listener -> {
        ListenerSpec.Builder spec = ListenerSpec.newBuilder()
          .setName(listener.getName())
          .setPort(listener.getPort())
          .setTargetPort(listener.getTargetPort())
          .setProtocol(Listener.Protocol.valueOf(listener.getProtocol().name()));
        if (description.getType() == YandexCloudLoadBalancer.BalancerType.INTERNAL) {
          spec.setInternalAddressSpec(InternalAddressSpec.newBuilder()
            .setSubnetId(listener.getSubnetId())
            .setAddress(listener.getAddress())
            .setIpVersion(IpVersion.valueOf(listener.getIpVersion().name()))
          );
        } else {
          spec.setExternalAddressSpec(ExternalAddressSpec.newBuilder()
            .setAddress(listener.getAddress())
            .setIpVersion(IpVersion.valueOf(listener.getIpVersion().name()))
          );
        }
        builder.addListenerSpecs(spec);
      });
    }
    return builder.setUpdateMask(updateMask).build();

  }
}
