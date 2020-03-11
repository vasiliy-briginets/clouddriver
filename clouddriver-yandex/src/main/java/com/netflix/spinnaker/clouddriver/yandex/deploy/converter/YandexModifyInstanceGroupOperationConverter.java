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
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperation;
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperations;
import com.netflix.spinnaker.clouddriver.security.AbstractAtomicOperationsCredentialsSupport;
import com.netflix.spinnaker.clouddriver.security.ProviderVersion;
import com.netflix.spinnaker.clouddriver.yandex.YandexCloudOperation;
import com.netflix.spinnaker.clouddriver.yandex.deploy.description.YandexDeployGroupDescription;
import com.netflix.spinnaker.clouddriver.yandex.deploy.ops.YandexModifyInstanceGroupOperation;
import com.netflix.spinnaker.clouddriver.yandex.security.YandexCloudCredentials;
import java.util.Map;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.springframework.stereotype.Component;

@YandexCloudOperation(AtomicOperations.UPDATE_LAUNCH_CONFIG)
@Component
@SuppressWarnings({"unchecked", "rawtypes"})
public class YandexModifyInstanceGroupOperationConverter
    extends AbstractAtomicOperationsCredentialsSupport {

  @Override
  public AtomicOperation convertOperation(Map input) {
    return new YandexModifyInstanceGroupOperation(convertDescription(input));
  }

  @Override
  public YandexDeployGroupDescription convertDescription(Map input) {
    if (!input.containsKey("accountName")) {
      input.put("accountName", input.get("credentials"));
    }

    if (DefaultGroovyMethods.asBoolean(input.get("accountName"))) {
      input.put(
          "credentials", this.getCredentialsObject((String.valueOf(input.get("accountName")))));
    }

    // Save these to re-assign after ObjectMapper does its work.
    Object credentials = input.remove("credentials");

    YandexDeployGroupDescription t =
        this.getObjectMapper()
            .copy()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .convertValue(input, YandexDeployGroupDescription.class);
    if (credentials instanceof YandexCloudCredentials) {
      t.setCredentials((YandexCloudCredentials) credentials);
    }
    return t;
  }

  public boolean acceptsVersion(ProviderVersion version) {
    return version == ProviderVersion.v1;
  }
}
