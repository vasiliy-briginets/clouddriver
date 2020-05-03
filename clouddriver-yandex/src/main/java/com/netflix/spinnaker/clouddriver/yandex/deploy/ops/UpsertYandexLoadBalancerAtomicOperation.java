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
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperation;
import com.netflix.spinnaker.clouddriver.yandex.deploy.YandexOperationPoller;
import com.netflix.spinnaker.clouddriver.yandex.deploy.description.UpsertYandexLoadBalancerDescription;
import com.netflix.spinnaker.clouddriver.yandex.deploy.description.YandexLoadBalancerConverter;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static java.util.Collections.singletonMap;
import static yandex.cloud.api.loadbalancer.v1.NetworkLoadBalancerServiceOuterClass.*;
import static yandex.cloud.api.operation.OperationOuterClass.Operation;

@SuppressWarnings("rawtypes")
public class UpsertYandexLoadBalancerAtomicOperation implements AtomicOperation<Map> {
  private static final String BASE_PHASE = "UPSERT_LOAD_BALANCER";

  private static Task getTask() {
    return TaskRepository.threadLocalTask.get();
  }

  @Autowired
  private YandexOperationPoller operationPoller;
  private final UpsertYandexLoadBalancerDescription description;

  public UpsertYandexLoadBalancerAtomicOperation(UpsertYandexLoadBalancerDescription description) {
    this.description = description;
  }

  @Override
  public Map operate(List priorOutputs) {
    getTask().updateStatus(BASE_PHASE, "Initializing upsert of load balancer " + description.getName() + "...");

    ListNetworkLoadBalancersRequest request = ListNetworkLoadBalancersRequest.newBuilder()
      .setFolderId(description.getCredentials().getFolder())
      .setFilter("name='" + description.getName() + "'")
      .build();
    ListNetworkLoadBalancersResponse response = description.getCredentials().networkLoadBalancerService().list(request);
    if (response.getNetworkLoadBalancersCount() == 0) {
      createNewLoadBalancer();
    } else if (response.getNetworkLoadBalancersCount() == 1) {
      updateLoadBalancer(response.getNetworkLoadBalancers(0).getId());
    } else {
      String message = "Found more than one load balancer with name '" + description.getName() + "'.";
      getTask().updateStatus(BASE_PHASE, message);
      throw new IllegalStateException(message);
    }


    getTask().updateStatus(BASE_PHASE, "Done upserting load balancer " + description.getName() + ".");
    return singletonMap("loadBalancers", singletonMap("ru-central1", singletonMap("name", description.getName())));
  }

  public void createNewLoadBalancer() {
    getTask().updateStatus(BASE_PHASE, "Creating load balancer " + description.getName() + "...");
    CreateNetworkLoadBalancerRequest request = YandexLoadBalancerConverter.mapToCreateRequest(description);
    Operation operation = description.getCredentials().networkLoadBalancerService().create(request);
    operationPoller.waitDone(description.getCredentials(), operation, BASE_PHASE);
    getTask().updateStatus(BASE_PHASE, "Done creating load balancer " + description.getName() + ".");
  }

  public void updateLoadBalancer(String id) {
    getTask().updateStatus(BASE_PHASE, "Updating load balancer " + description.getName() + "...");
    UpdateNetworkLoadBalancerRequest request = YandexLoadBalancerConverter.mapToUpdateRequest(id, description);
    Operation operation = description.getCredentials().networkLoadBalancerService().update(request);
    operationPoller.waitDone(description.getCredentials(), operation, BASE_PHASE);
    getTask().updateStatus(BASE_PHASE, "Done updating load balancer " + description.getName() + ".");
  }
}
