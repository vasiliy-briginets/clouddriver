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

package com.netflix.spinnaker.clouddriver.yandex.provider.view;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.spinnaker.cats.cache.Cache;
import com.netflix.spinnaker.cats.cache.CacheData;
import com.netflix.spinnaker.cats.cache.RelationshipCacheFilter;
import com.netflix.spinnaker.clouddriver.model.ApplicationProvider;
import com.netflix.spinnaker.clouddriver.yandex.model.YandexApplication;
import com.netflix.spinnaker.clouddriver.yandex.provider.Keys;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
final class YandexApplicationProvider implements ApplicationProvider {
  private final Cache cacheView;
  private final ObjectMapper objectMapper;

  @Autowired
  YandexApplicationProvider(Cache cacheView, ObjectMapper objectMapper) {
    this.cacheView = cacheView;
    this.objectMapper = objectMapper;
  }

  @Override
  public Set<YandexApplication> getApplications(boolean expand) {
    Collection<CacheData> data =
        cacheView.getAll(
            Keys.Namespace.APPLICATIONS.getNs(),
            cacheView.filterIdentifiers(
                Keys.Namespace.APPLICATIONS.getNs(), Keys.getApplicationKey("*")),
            expand
                ? RelationshipCacheFilter.include(
                    Keys.Namespace.CLUSTERS.getNs(), Keys.Namespace.INSTANCES.getNs())
                : RelationshipCacheFilter.none());
    return data.stream().map(this::applicationFromCacheData).collect(toSet());
  }

  @Override
  public YandexApplication getApplication(String name) {
    CacheData cacheData =
        cacheView.get(
            Keys.Namespace.APPLICATIONS.getNs(),
            Keys.getApplicationKey(name),
            RelationshipCacheFilter.include(
                Keys.Namespace.CLUSTERS.getNs(), Keys.Namespace.INSTANCES.getNs()));
    return applicationFromCacheData(cacheData);
  }

  private YandexApplication applicationFromCacheData(CacheData cacheData) {
    if (cacheData == null) {
      return null;
    }
    Map<String, Object> attributes = cacheData.getAttributes();
    YandexApplication application = objectMapper.convertValue(attributes, YandexApplication.class);
    if (application == null) {
      return null;
    }

    getRelationships(cacheData, Keys.Namespace.CLUSTERS).stream()
        .map(Keys::parse)
        .filter(Objects::nonNull)
        .forEach(
            parts ->
                application
                    .getClusterNames()
                    .computeIfAbsent(parts.get("account"), s -> new HashSet<>())
                    .add(parts.get("name")));

    List<Map<String, String>> instances =
        getRelationships(cacheData, Keys.Namespace.INSTANCES).stream()
            .map(Keys::parse)
            .collect(toList());
    application.setInstances(instances);

    return application;
  }

  private Set<String> getRelationships(CacheData cacheData, Keys.Namespace namespace) {
    Collection<String> relationships = cacheData.getRelationships().get(namespace.getNs());
    return relationships == null ? Collections.emptySet() : new HashSet<>(relationships);
  }
}
