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

package com.netflix.spinnaker.clouddriver.yandex.deploy;

import static yandex.cloud.api.compute.v1.instancegroup.InstanceGroupOuterClass.ScalePolicy;
import static yandex.cloud.api.compute.v1.instancegroup.InstanceGroupServiceOuterClass.CreateInstanceGroupMetadata;
import static yandex.cloud.api.compute.v1.instancegroup.InstanceGroupServiceOuterClass.CreateInstanceGroupRequest;
import static yandex.cloud.api.operation.OperationOuterClass.Operation;
import static yandex.cloud.api.operation.OperationServiceOuterClass.GetOperationRequest;

import com.netflix.spinnaker.clouddriver.data.task.Task;
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository;
import com.netflix.spinnaker.clouddriver.deploy.DeployDescription;
import com.netflix.spinnaker.clouddriver.deploy.DeployHandler;
import com.netflix.spinnaker.clouddriver.deploy.DeploymentResult;
import com.netflix.spinnaker.clouddriver.helpers.OperationPoller;
import com.netflix.spinnaker.clouddriver.yandex.YandexCloudProvider;
import com.netflix.spinnaker.clouddriver.yandex.deploy.description.YandexConverter;
import com.netflix.spinnaker.clouddriver.yandex.deploy.description.YandexDeployGroupDescription;
import com.netflix.spinnaker.clouddriver.yandex.security.YandexCloudCredentials;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import yandex.cloud.api.operation.OperationServiceGrpc.OperationServiceBlockingStub;

@Component
@Slf4j
@Data
public class YandexDeployHandler implements DeployHandler<YandexDeployGroupDescription> {
  private static final String BASE_PHASE = "DEPLOY";

  private final OperationPoller operationPoller;

  @Autowired
  public YandexDeployHandler(OperationPoller yandexOperationPoller) {
    this.operationPoller = yandexOperationPoller;
  }

  private static Task getTask() {
    return TaskRepository.threadLocalTask.get();
  }

  @Override
  public boolean handles(DeployDescription description) {
    return description instanceof YandexDeployGroupDescription;
  }

  @Override
  public DeploymentResult handle(YandexDeployGroupDescription description, List priorOutputs) {
    YandexCloudCredentials credentials = description.getCredentials();

    getTask()
        .updateStatus(
            BASE_PHASE,
            "Initializing creation of server group for application '"
                + description.getApplication()
                + "' stack '"
                + description.getStack()
                + "'...");
    getTask().updateStatus(BASE_PHASE, "Looking up next sequence...");
    description.produceServerGroupName();
    getTask().updateStatus(BASE_PHASE, "Produced server group name: " + description.getName());

    description.saturateLabels();

    // todo: add into lb
    getTask().updateStatus(BASE_PHASE, "Composing server group " + description.getName() + "...");
    CreateInstanceGroupRequest request = YandexConverter.mapToCreateRequest(description);

    Operation operation = credentials.instanceGroupService().create(request);
    waitDeployEnd(operation, credentials.operationService());
    getTask().updateStatus(BASE_PHASE, "Done creating server group " + description.getName() + ".");
    return makeDeploymentResult(request, credentials);
  }

  private void waitDeployEnd(Operation operation, OperationServiceBlockingStub operationService) {
    CreateInstanceGroupMetadata operationMetadata =
        YandexConverter.convertCreateOperationMetadata(operation);
    operationPoller.waitForOperation(
        () ->
            operationService.get(
                GetOperationRequest.newBuilder().setOperationId(operation.getId()).build()),
        Operation::getDone,
        Duration.ofMinutes(60).getSeconds(), // todo
        getTask(),
        "instance group " + operationMetadata.getInstanceGroupId(),
        BASE_PHASE);
  }

  @NotNull
  private DeploymentResult makeDeploymentResult(
      CreateInstanceGroupRequest request, YandexCloudCredentials credentials) {
    DeploymentResult.Deployment deployment = new DeploymentResult.Deployment();
    deployment.setAccount(credentials.getName());
    DeploymentResult.Deployment.Capacity capacity = new DeploymentResult.Deployment.Capacity();
    if (request.getScalePolicy().hasAutoScale()) {
      ScalePolicy.AutoScale autoScale = request.getScalePolicy().getAutoScale();
      capacity.setMin(
          (int) (autoScale.getMinZoneSize() * request.getAllocationPolicy().getZonesCount()));
      capacity.setMax((int) autoScale.getMaxSize());
      capacity.setDesired((int) autoScale.getInitialSize());
    } else {
      int size = (int) request.getScalePolicy().getFixedScale().getSize();
      capacity.setMin(size);
      capacity.setMax(size);
      capacity.setDesired(size);
    }
    deployment.setCapacity(capacity);
    deployment.setCloudProvider(YandexCloudProvider.ID);
    String instanceGroupName = request.getName();
    deployment.setServerGroupName(instanceGroupName);

    DeploymentResult deploymentResult = new DeploymentResult();
    String region = "ru-central1";
    deploymentResult.setServerGroupNames(
        Collections.singletonList(region + ":" + instanceGroupName));
    deploymentResult.setServerGroupNameByRegion(
        Collections.singletonMap(region, instanceGroupName));
    deploymentResult.setDeployments(Collections.singleton(deployment));
    return deploymentResult;
  }
}
