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

package com.netflix.spinnaker.clouddriver.yandex.model.health;

import com.netflix.spinnaker.clouddriver.model.Health;
import com.netflix.spinnaker.clouddriver.model.HealthState;
import java.util.List;
import lombok.Data;

@Data
public class YandexLoadBalancerHealth implements Health {
  private String instanceName;
  private String instanceZone;
  private List<LBHealthSummary> loadBalancers;
  private PlatformStatus status;

  @Override
  public HealthState getState() {
    return status.toHeathState();
  }

  public enum PlatformStatus {
    HEALTHY,
    UNHEALTHY;

    public HealthState toHeathState() {
      switch (this) {
        case HEALTHY:
          return HealthState.Up;
        default:
          return HealthState.Down;
      }
    }

    public LBHealthSummary.ServiceStatus toServiceStatus() {
      switch (this) {
        case HEALTHY:
          return LBHealthSummary.ServiceStatus.InService;
        default:
          return LBHealthSummary.ServiceStatus.OutOfService;
      }
    }
  }

  @Data
  public static class LBHealthSummary {
    private String loadBalancerName;
    private String instanceId;
    private ServiceStatus state;

    public enum ServiceStatus {
      InService,
      OutOfService;
    }
  }
}
