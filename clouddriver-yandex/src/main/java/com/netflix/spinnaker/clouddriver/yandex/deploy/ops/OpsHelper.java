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

package com.netflix.spinnaker.clouddriver.yandex.deploy.ops;

import com.netflix.spinnaker.clouddriver.data.task.Task;
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository;
import com.netflix.spinnaker.clouddriver.yandex.deploy.YandexOperationPoller;
import com.netflix.spinnaker.clouddriver.yandex.deploy.description.YandexInstanceGroupConverter;
import com.netflix.spinnaker.clouddriver.yandex.model.YandexCloudServerGroup;
import com.netflix.spinnaker.clouddriver.yandex.security.YandexCloudCredentials;
import yandex.cloud.api.operation.OperationOuterClass;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static yandex.cloud.api.loadbalancer.v1.HealthCheckOuterClass.HealthCheck;
import static yandex.cloud.api.loadbalancer.v1.NetworkLoadBalancerOuterClass.AttachedTargetGroup;
import static yandex.cloud.api.loadbalancer.v1.NetworkLoadBalancerOuterClass.NetworkLoadBalancer;
import static yandex.cloud.api.loadbalancer.v1.NetworkLoadBalancerServiceOuterClass.*;

public class OpsHelper {
  public static Task getTask() {
    return TaskRepository.threadLocalTask.get();
  }

  public static void enableInstanceGroup(
    YandexOperationPoller operationPoller,
    String phaseName,
    YandexCloudCredentials credentials,
    String targetGroupId,
    Map<String, YandexCloudServerGroup.HealthCheckSpec> loadBalancersSpecs) {
    if (targetGroupId == null) {
      return;
    }
    getTask().updateStatus(phaseName, "Registering instances with network load balancers...");
    getTask().updateStatus(phaseName, "Retrieving load balancers...");
    List<NetworkLoadBalancer> balancers = loadBalancersSpecs.keySet().stream()
      .map(name -> resolverLoadBalancerByName(credentials, phaseName, name))
      .collect(Collectors.toList());

    balancers.forEach(balancer -> {
      AttachNetworkLoadBalancerTargetGroupRequest request = AttachNetworkLoadBalancerTargetGroupRequest.newBuilder()
        .setNetworkLoadBalancerId(balancer.getId())
        .setAttachedTargetGroup(AttachedTargetGroup.newBuilder()
          .setTargetGroupId(targetGroupId)
          .addHealthChecks(mapHealthCheckSpec(loadBalancersSpecs.get(balancer.getName()))))
        .build();

      getTask().updateStatus(phaseName, "Registering server group with load balancer " + balancer.getName() + "...");
      OperationOuterClass.Operation operation = credentials.networkLoadBalancerService().attachTargetGroup(request);
      operationPoller.waitDone(credentials, operation, phaseName);
      getTask().updateStatus(phaseName, "Done registering server group with load balancer " + balancer.getName() + ".");
    });

  }

  private static HealthCheck mapHealthCheckSpec(YandexCloudServerGroup.HealthCheckSpec hc) {
    HealthCheck.Builder builder = HealthCheck.newBuilder();
    if (hc.getType() == YandexCloudServerGroup.HealthCheckSpec.Type.HTTP) {
      builder.setHttpOptions(HealthCheck.HttpOptions.newBuilder()
        .setPort(hc.getPort())
        .setPath(hc.getPath()));
    } else {
      builder.setTcpOptions(HealthCheck.TcpOptions.newBuilder().setPort(hc.getPort()));
    }
    return builder
      .setInterval(YandexInstanceGroupConverter.mapDuration(hc.getInterval()))
      .setTimeout(YandexInstanceGroupConverter.mapDuration(hc.getTimeout()))
      .setUnhealthyThreshold(hc.getUnhealthyThreshold())
      .setHealthyThreshold(hc.getHealthyThreshold())
      .build();
  }

  private static NetworkLoadBalancer resolverLoadBalancerByName(YandexCloudCredentials credentials, String phaseName, String name) {
    ListNetworkLoadBalancersRequest request = ListNetworkLoadBalancersRequest.newBuilder()
      .setFolderId(credentials.getFolder())
      .setFilter("name='" + name + "'")
      .build();
    ListNetworkLoadBalancersResponse response = credentials.networkLoadBalancerService().list(request);
    if (response.getNetworkLoadBalancersCount() != 1) {
      String message = "Found none or more than one load balancer with name '" + name + "'.";
      getTask().updateStatus(phaseName, message);
      throw new IllegalStateException(message);
    }
    return response.getNetworkLoadBalancers(0);
  }
}
