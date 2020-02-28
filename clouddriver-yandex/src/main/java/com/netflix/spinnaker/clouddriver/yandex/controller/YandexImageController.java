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

package com.netflix.spinnaker.clouddriver.yandex.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.netflix.spinnaker.cats.cache.Cache;
import com.netflix.spinnaker.cats.cache.CacheData;
import com.netflix.spinnaker.cats.cache.RelationshipCacheFilter;
import com.netflix.spinnaker.cats.mem.InMemoryCache;
import com.netflix.spinnaker.clouddriver.yandex.model.YandexCloudImage;
import com.netflix.spinnaker.clouddriver.yandex.provider.Keys;
import groovy.util.logging.Slf4j;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/yandex/images")
public class YandexImageController {
  private final Cache cacheView;
  private final ObjectMapper objectMapper;

  @Autowired
  private YandexImageController(Cache cacheView, ObjectMapper objectMapper) {
    this.cacheView = cacheView;
    this.objectMapper = objectMapper;
  }

  @RequestMapping(value = "/find", method = RequestMethod.GET)
  public List<YandexImage> list(
      @RequestParam(required = false) String q, @RequestParam(required = false) String account) {
    return getImageCacheData(account).stream()
        .map(this::convertToYandexImage)
        .filter(getQueryFilter(q))
        .sorted(Comparator.comparing(YandexImage::getImageName))
        .collect(Collectors.toList());
  }

  private Collection<CacheData> getImageCacheData(String account) {
    String imageKey =
        Keys.getImageKey(Strings.isNullOrEmpty(account) ? "*" : account, "*", "*", "*");
    Collection<String> identifiers =
        cacheView.filterIdentifiers(Keys.Namespace.IMAGES.getNs(), imageKey);
    return cacheView.getAll(
        Keys.Namespace.IMAGES.getNs(), identifiers, RelationshipCacheFilter.none());
  }

  private YandexImage convertToYandexImage(CacheData cacheData) {
    YandexCloudImage image =
        objectMapper.convertValue(cacheData.getAttributes(), YandexCloudImage.class);
    return new YandexImage(image.getId(), image.getName());
  }

  private Predicate<YandexImage> getQueryFilter(String q) {
    if (q == null || q.trim().length() <= 0) {
      return yandexImage -> true;
    }
    String glob = q.trim();
    // Wrap in '*' if there are no glob-style characters in the query string.
    if (!glob.contains("*") && !glob.contains("?") && !glob.contains("[") && !glob.contains("\\")) {
      glob = "*" + glob + "*";
    }
    Pattern pattern = new InMemoryCache.Glob(glob).toPattern();
    return i -> pattern.matcher(i.imageName).matches();
  }

  @Data
  @AllArgsConstructor
  public static class YandexImage {
    String imageId;
    String imageName;
  }
}
