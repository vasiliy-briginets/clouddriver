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

import static yandex.cloud.api.compute.v1.instancegroup.InstanceGroupOuterClass.InstanceGroup;
import static yandex.cloud.api.compute.v1.instancegroup.InstanceGroupServiceOuterClass.*;
import static yandex.cloud.api.operation.OperationOuterClass.Operation;
import static yandex.cloud.api.operation.OperationServiceGrpc.OperationServiceBlockingStub;
import static yandex.cloud.api.operation.OperationServiceOuterClass.GetOperationRequest;

import com.netflix.spinnaker.clouddriver.data.task.Task;
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository;
import com.netflix.spinnaker.clouddriver.helpers.OperationPoller;
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperation;
import com.netflix.spinnaker.clouddriver.yandex.deploy.description.YandexConverter;
import com.netflix.spinnaker.clouddriver.yandex.deploy.description.YandexDeployGroupDescription;
import com.netflix.spinnaker.clouddriver.yandex.security.YandexCloudCredentials;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;

public class YandexModifyInstanceGroupOperation implements AtomicOperation<Void> {
  private static final String BASE_PHASE = "MODIFY_ASG_LAUNCH_CONFIGURATION";
  private final YandexDeployGroupDescription description;

  @Autowired private OperationPoller operationPoller;

  private static Task getTask() {
    return TaskRepository.threadLocalTask.get();
  }

  public YandexModifyInstanceGroupOperation(YandexDeployGroupDescription description) {
    this.description = description;
  }

  @Override
  public Void operate(List priorOutputs) {
    getTask().updateStatus(BASE_PHASE, "Initializing operation...");
    YandexCloudCredentials credentials = description.getCredentials();

    description.saturateLabels();
    getTask()
        .updateStatus(
            BASE_PHASE, "Resolving server group identifier  " + description.getName() + "...");

    ListInstanceGroupsRequest listRequest =
        ListInstanceGroupsRequest.newBuilder()
            .setFolderId(credentials.getFolder())
            .setFilter("name='" + description.getName() + "'")
            .setView(InstanceGroupView.FULL)
            .build();

    List<InstanceGroup> instanceGroups =
        credentials.instanceGroupService().list(listRequest).getInstanceGroupsList();
    if (instanceGroups.size() != 1) {
      String message = "Found more that one server group " + description.getName() + ".";
      getTask().updateStatus(BASE_PHASE, message);
      throw new IllegalStateException(message);
    }

    String instanceGroupId = instanceGroups.get(0).getId();
    getTask().updateStatus(BASE_PHASE, "Composing server group " + description.getName() + "...");
    UpdateInstanceGroupRequest request =
        YandexConverter.mapToUpdateRequest(description, instanceGroupId);

    Operation operation = credentials.instanceGroupService().update(request);
    waitDeployEnd(instanceGroupId, operation, credentials.operationService());
    getTask().updateStatus(BASE_PHASE, "Done updating server group " + description.getName() + ".");
    return null;
  }

  private void waitDeployEnd(
      String igID, Operation operation, OperationServiceBlockingStub operationService) {
    operationPoller.waitForOperation(
        () ->
            operationService.get(
                GetOperationRequest.newBuilder().setOperationId(operation.getId()).build()),
        Operation::getDone,
        Duration.ofMinutes(60).getSeconds(), // todo
        getTask(),
        "instance group " + igID,
        BASE_PHASE);
  }
}
