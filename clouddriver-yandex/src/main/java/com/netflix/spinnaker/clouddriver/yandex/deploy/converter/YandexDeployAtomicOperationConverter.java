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

package com.netflix.spinnaker.clouddriver.yandex.deploy.converter;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.netflix.spinnaker.clouddriver.deploy.DeployAtomicOperation;
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperation;
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperations;
import com.netflix.spinnaker.clouddriver.security.AbstractAtomicOperationsCredentialsSupport;
import com.netflix.spinnaker.clouddriver.security.ProviderVersion;
import com.netflix.spinnaker.clouddriver.yandex.YandexCloudOperation;
import com.netflix.spinnaker.clouddriver.yandex.deploy.description.YandexDeployGroupDescription;
import com.netflix.spinnaker.clouddriver.yandex.security.YandexCloudCredentials;
import groovy.util.logging.Slf4j;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.springframework.stereotype.Component;

@YandexCloudOperation(AtomicOperations.CREATE_SERVER_GROUP)
@Component
@Slf4j
@NoArgsConstructor
@SuppressWarnings({"unchecked", "rawtypes"})
public class YandexDeployAtomicOperationConverter
    extends AbstractAtomicOperationsCredentialsSupport {
  @Override
  public AtomicOperation convertOperation(Map input) {
    return new DeployAtomicOperation(convertDescription(input));
  }

  @Override
  public YandexDeployGroupDescription convertDescription(Map input) {
    return convertDescription(input, this, YandexDeployGroupDescription.class);
  }

  @Override
  public boolean acceptsVersion(ProviderVersion version) {
    return version == ProviderVersion.v1;
  }

  public static YandexDeployGroupDescription convertDescription(
      Map input,
      AbstractAtomicOperationsCredentialsSupport credentialsSupport,
      Class<YandexDeployGroupDescription> targetDescriptionType) {
    if (!input.containsKey("accountName")) {
      input.put("accountName", input.get("credentials"));
    }

    if (DefaultGroovyMethods.asBoolean(input.get("accountName"))) {
      input.put(
          "credentials",
          credentialsSupport.getCredentialsObject((String.valueOf(input.get("accountName")))));
    }

    // Save these to re-assign after ObjectMapper does its work.
    Object credentials = input.remove("credentials");

    YandexDeployGroupDescription t =
        credentialsSupport
            .getObjectMapper()
            .copy()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .convertValue(input, targetDescriptionType);
    if (credentials instanceof YandexCloudCredentials) {
      t.setCredentials((YandexCloudCredentials) credentials);
    }
    return t;
  }
}
