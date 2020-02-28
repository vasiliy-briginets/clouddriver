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

package com.netflix.spinnaker.clouddriver.yandex.security;

import static yandex.cloud.api.iam.v1.IamTokenServiceOuterClass.CreateIamTokenRequest;
import static yandex.cloud.api.iam.v1.IamTokenServiceOuterClass.CreateIamTokenResponse;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import java.io.IOException;
import java.time.Duration;
import lombok.Value;
import yandex.cloud.api.iam.v1.IamTokenServiceGrpc;
import yandex.cloud.sdk.ServiceFactory;
import yandex.cloud.sdk.auth.IamToken;
import yandex.cloud.sdk.auth.InvalidJsonKeyException;
import yandex.cloud.sdk.auth.jwt.JwtConfig;
import yandex.cloud.sdk.auth.jwt.JwtCreator;
import yandex.cloud.sdk.auth.jwt.ServiceAccountKey;

@Value
public class ConfigurableIamToken extends IamToken {
  private final ServiceFactory serviceFactory;
  private final JwtCreator jwtCreator;
  private final ServiceAccountKey serviceAccountKey;
  // todo: cache

  public ConfigurableIamToken(ServiceFactory serviceFactory, String iamEndpoint, String jsonKey) {
    super(null);
    this.serviceFactory = serviceFactory;
    this.jwtCreator =
        Strings.isNullOrEmpty(iamEndpoint)
            ? new JwtCreator()
            : new JwtCreator(new JwtConfig(iamEndpoint, Duration.ZERO));

    try {
      this.serviceAccountKey = new ObjectMapper().readValue(jsonKey, ServiceAccountKey.class);
    } catch (JsonParseException | JsonMappingException e) {
      throw new InvalidJsonKeyException(e);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public String getToken() {
    IamTokenServiceGrpc.IamTokenServiceBlockingStub stub =
        serviceFactory.create(
            IamTokenServiceGrpc.IamTokenServiceBlockingStub.class,
            IamTokenServiceGrpc::newBlockingStub);

    String jwtToken = jwtCreator.generateJwt(serviceAccountKey).getToken();
    CreateIamTokenResponse response =
        stub.create(CreateIamTokenRequest.newBuilder().setJwt(jwtToken).build());
    return response.getIamToken();
  }
}
