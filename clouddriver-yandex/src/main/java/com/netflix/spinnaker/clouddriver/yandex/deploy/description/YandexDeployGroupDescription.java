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

import com.google.common.base.Strings;
import com.netflix.frigga.Names;
import com.netflix.spinnaker.clouddriver.deploy.DeployDescription;
import com.netflix.spinnaker.clouddriver.security.resources.ApplicationNameable;
import com.netflix.spinnaker.clouddriver.yandex.deploy.YandexServerGroupNameResolver;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import lombok.Data;
import lombok.ToString;
import org.codehaus.groovy.runtime.StringGroovyMethods;

@Data
@ToString(callSuper = true)
public class YandexDeployGroupDescription extends YandexInstanceGroupDescription
    implements DeployDescription, ApplicationNameable {
  private String application;
  private String stack;

  @Override
  public Collection<String> getApplications() {
    if (StringGroovyMethods.asBoolean(application)) {
      return Collections.singletonList(application);
    }

    if (!Strings.isNullOrEmpty(getName())) {
      return Collections.singletonList(Names.parseName(getName()).getApp());
    }

    return null;
  }

  public void produceServerGroupName() {
    YandexServerGroupNameResolver serverGroupNameResolver =
        new YandexServerGroupNameResolver(getCredentials());
    this.setName(
        serverGroupNameResolver.resolveNextServerGroupName(
            getApplication(), getStack(), getFreeFormDetails(), false));
  }

  public void saturateLabels() {
    if (getLabels() == null) {
      setLabels(new HashMap<>());
    }
    if (getInstanceTemplate().getLabels() == null) {
      getInstanceTemplate().setLabels(new HashMap<>());
    }

    Integer sequence = Names.parseName(getName()).getSequence();
    String clusterName =
        new YandexServerGroupNameResolver(getCredentials())
            .combineAppStackDetail(getApplication(), getStack(), getFreeFormDetails());

    saturateLabels(getLabels(), this, sequence, clusterName);
    saturateLabels(getInstanceTemplate().getLabels(), this, sequence, clusterName);
  }

  private void saturateLabels(
      Map<String, String> labels,
      YandexDeployGroupDescription description,
      Integer sequence,
      String clusterName) {
    labels.putIfAbsent("spinnaker-server-group", description.getName());
    labels.putIfAbsent("spinnaker-moniker-application", description.getApplication());
    labels.putIfAbsent("spinnaker-moniker-cluster", clusterName);
    labels.putIfAbsent("spinnaker-moniker-stack", description.getStack());
    labels.put("spinnaker-moniker-sequence", sequence == null ? null : sequence.toString());
  }
}
