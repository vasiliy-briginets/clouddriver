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

package com.netflix.spinnaker.clouddriver.yandex.model;

import com.netflix.spinnaker.clouddriver.model.Image;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import yandex.cloud.api.compute.v1.ImageOuterClass;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class YandexCloudImage implements Image {
  private String id;
  private String name;
  private String region;

  public static YandexCloudImage createFromProto(ImageOuterClass.Image image) {
    return YandexCloudImage.builder()
        .id(image.getId())
        .name(image.getName())
        .region("ru-central1")
        .build();
  }
}
