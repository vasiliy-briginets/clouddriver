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

import com.netflix.frigga.Names;
import com.netflix.spinnaker.clouddriver.security.resources.ApplicationNameable;
import com.netflix.spinnaker.clouddriver.yandex.security.YandexCloudCredentials;
import lombok.Data;

import java.util.Collection;
import java.util.Collections;

@Data
public class DeleteYandexLoadBalancerDescription implements CredentialsChangeable, ApplicationNameable {
  private YandexCloudCredentials credentials;
  private String loadBalancerName;

  @Override
  public Collection<String> getApplications() {
    return Collections.singletonList(Names.parseName(loadBalancerName).getApp());
  }

  public void setCredentials(YandexCloudCredentials credentials) {
    this.credentials = credentials;
  }
}
