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

package com.netflix.spinnaker.clouddriver.yandex.provider.agent;

import static com.netflix.spinnaker.cats.agent.AgentDataType.Authority.AUTHORITATIVE;
import static com.netflix.spinnaker.cats.agent.AgentDataType.Authority.INFORMATIVE;
import static com.netflix.spinnaker.clouddriver.yandex.provider.Keys.Namespace.*;
import static java.util.Collections.*;
import static java.util.stream.Collectors.toList;
import static yandex.cloud.api.loadbalancer.v1.NetworkLoadBalancerOuterClass.NetworkLoadBalancer;
import static yandex.cloud.api.loadbalancer.v1.NetworkLoadBalancerServiceOuterClass.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import com.netflix.spectator.api.Registry;
import com.netflix.spinnaker.cats.agent.AgentDataType;
import com.netflix.spinnaker.cats.agent.CacheResult;
import com.netflix.spinnaker.cats.cache.CacheData;
import com.netflix.spinnaker.cats.cache.DefaultCacheData;
import com.netflix.spinnaker.cats.provider.ProviderCache;
import com.netflix.spinnaker.clouddriver.cache.OnDemandAgent;
import com.netflix.spinnaker.clouddriver.cache.OnDemandMetricsSupport;
import com.netflix.spinnaker.clouddriver.yandex.CacheResultBuilder;
import com.netflix.spinnaker.clouddriver.yandex.YandexCloudProvider;
import com.netflix.spinnaker.clouddriver.yandex.model.YandexCloudLoadBalancer;
import com.netflix.spinnaker.clouddriver.yandex.provider.Keys;
import com.netflix.spinnaker.clouddriver.yandex.security.YandexCloudCredentials;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.Data;

@Data
public class YandexNetworkLoadBalancerCachingAgent extends AbstractYandexCachingAgent
    implements OnDemandAgent {
  private String agentType = getAccountName() + "/" + this.getClass().getSimpleName();
  private String onDemandAgentType = getAgentType() + "-OnDemand";
  private final OnDemandMetricsSupport metricsSupport;
  private Collection<AgentDataType> providedDataTypes =
      ImmutableSet.of(
          AUTHORITATIVE.forType(LOAD_BALANCERS.getNs()), INFORMATIVE.forType(INSTANCES.getNs()));

  public YandexNetworkLoadBalancerCachingAgent(
      YandexCloudCredentials credentials, ObjectMapper objectMapper, Registry registry) {
    super(credentials, objectMapper);
    this.metricsSupport =
        new OnDemandMetricsSupport(
            registry, this, YandexCloudProvider.ID + ":" + OnDemandType.LoadBalancer);
  }

  @Override
  public boolean handles(OnDemandType type, String cloudProvider) {
    return type.equals(OnDemandType.LoadBalancer) && cloudProvider.equals(YandexCloudProvider.ID);
  }

  @Override
  public OnDemandResult handle(final ProviderCache providerCache, final Map<String, ?> data) {
    if (!data.containsKey("loadBalancerId") || !getAccountName().equals(data.get("account"))) {
      return null;
    }

    YandexCloudLoadBalancer loadBalancer;
    String loadBalancerId = (String) data.get("loadBalancerId");
    try {
      loadBalancer = metricsSupport.readData(() -> getLoadBalancer(loadBalancerId));
    } catch (IllegalArgumentException e) {
      return null;
    }

    if (loadBalancer == null) {
      providerCache.evictDeletedItems(
          ON_DEMAND.getNs(), singleton(Keys.getLoadBalancerKey(loadBalancerId, "*", "*")));
      return null;
    }
    String loadBalancerKey =
        Keys.getLoadBalancerKey(loadBalancerId, getFolder(), loadBalancer.getName());

    CacheResultBuilder cacheResultBuilder = new CacheResultBuilder();
    cacheResultBuilder.setStartTime(Long.MAX_VALUE);
    CacheResult result =
        metricsSupport.transformData(
            () -> buildCacheResult(cacheResultBuilder, singletonList(loadBalancer)));

    metricsSupport.onDemandStore(
        () -> {
          Map<String, Object> attributes = new HashMap<>(4);
          attributes.put("cacheTime", System.currentTimeMillis());
          try {
            attributes.put(
                "cacheResults", getObjectMapper().writeValueAsString(result.getCacheResults()));
          } catch (JsonProcessingException ignored) {

          }
          attributes.put("processedCount", 0);
          attributes.put("processedTime", null);
          DefaultCacheData cacheData =
              new DefaultCacheData(
                  loadBalancerKey, (int) TimeUnit.MINUTES.toSeconds(10), attributes, emptyMap());
          providerCache.putCacheData(ON_DEMAND.getNs(), cacheData);
          return null;
        });

    OnDemandResult result1 = new OnDemandResult();
    result1.setSourceAgentType(getOnDemandAgentType());
    result1.setCacheResult(result);
    return result1;
  }

  @Override
  public Collection<Map> pendingOnDemandRequests(ProviderCache providerCache) {
    List<String> ownedKeys =
        providerCache.getIdentifiers(ON_DEMAND.getNs()).stream()
            .filter(this::keyOwnedByThisAgent)
            .collect(toList());

    return providerCache.getAll(ON_DEMAND.getNs(), ownedKeys).stream()
        .map(
            cacheData -> {
              Map<String, String> details = Keys.parse(cacheData.getId());
              Map<String, Object> map = new HashMap<>(5);
              map.put("details", details);
              map.put("moniker", convertOnDemandDetails(details));
              map.put("cacheTime", cacheData.getAttributes().get("cacheTime"));
              map.put("processedCount", cacheData.getAttributes().get("processedCount"));
              map.put("processedTime", cacheData.getAttributes().get("processedTime"));
              return map;
            })
        .collect(toList());
  }

  private boolean keyOwnedByThisAgent(String key) {
    Map<String, String> parsedKey = Keys.parse(key);
    return parsedKey != null && parsedKey.get("type").equals(LOAD_BALANCERS.getNs());
  }

  @Override
  public CacheResult loadData(ProviderCache providerCache) {
    final CacheResultBuilder cacheResultBuilder = new CacheResultBuilder();
    cacheResultBuilder.setStartTime(System.currentTimeMillis());

    List<YandexCloudLoadBalancer> loadBalancers = constructLoadBalancers(null);
    List<String> loadBalancerKeys =
        loadBalancers.stream()
            .map(lb -> Keys.getLoadBalancerKey(lb.getId(), getFolder(), lb.getName()))
            .collect(toList());

    providerCache
        .getAll(ON_DEMAND.getNs(), loadBalancerKeys)
        .forEach(
            cacheData -> {
              // Ensure that we don't overwrite data that was inserted by the `handle` method while
              // we retrieved the
              // load balancers. Furthermore, cache data that hasn't been moved to the proper
              // namespace needs to be
              // updated in the ON_DEMAND cache, so don't evict data without a processedCount > 0.
              CacheResultBuilder.CacheMutation onDemand = cacheResultBuilder.getOnDemand();
              if ((Long) cacheData.getAttributes().get("cacheTime")
                      < cacheResultBuilder.getStartTime()
                  && (Long) cacheData.getAttributes().get("processedCount") > 0) {
                onDemand.getToEvict().add(cacheData.getId());
              } else {
                onDemand.getToKeep().put(cacheData.getId(), cacheData);
              }
            });

    CacheResult cacheResults = buildCacheResult(cacheResultBuilder, loadBalancers);
    if (cacheResults.getCacheResults() != null) {
      cacheResults
          .getCacheResults()
          .getOrDefault(ON_DEMAND.getNs(), emptyList())
          .forEach(
              cacheData -> {
                cacheData.getAttributes().put("processedTime", System.currentTimeMillis());
                cacheData
                    .getAttributes()
                    .compute(
                        "processedCount", (key, count) -> (count != null ? (Long) count : 0) + 1);
              });
    }
    return cacheResults;
  }

  private YandexCloudLoadBalancer getLoadBalancer(String loadBalancerId) {
    List<YandexCloudLoadBalancer> loadBalancers = constructLoadBalancers(loadBalancerId);
    return loadBalancers != null && !loadBalancers.isEmpty() ? loadBalancers.get(0) : null;
  }

  private CacheResult buildCacheResult(
      final CacheResultBuilder cacheResultBuilder, List<YandexCloudLoadBalancer> loadBalancers) {
    loadBalancers.forEach(
        loadBalancer -> {
          String loadBalancerKey =
              Keys.getLoadBalancerKey(loadBalancer.getId(), getFolder(), loadBalancer.getName());

          //      List<String> instanceKeys = loadBalancer.healths.stream()
          //        .map(health -> Keys.getInstanceKey(health.instanceName))
          //        .collect(toList());
          //
          //
          //      instanceKeys.forEach(instanceKey -> cacheResultBuilder
          //        .namespace(INSTANCES.getNs())
          //        .keep(instanceKey)
          //        .getRelationships()
          //        .computeIfAbsent(LOAD_BALANCERS.getNs(), s -> new ArrayList<>())
          //        .add(loadBalancerKey));

          if (shouldUseOnDemandData(cacheResultBuilder, loadBalancerKey)) {
            try {
              moveOnDemandDataToNamespace(
                  cacheResultBuilder,
                  Keys.getLoadBalancerKey(
                      loadBalancer.getId(), getFolder(), loadBalancer.getName()));
            } catch (IOException e) {
              // CatsOnDemandCacheUpdater handles this
              throw new UncheckedIOException(e);
            }
          } else {
            CacheResultBuilder.CacheDataBuilder keep =
                cacheResultBuilder.namespace(LOAD_BALANCERS.getNs()).keep(loadBalancerKey);
            keep.setAttributes(getObjectMapper().convertValue(loadBalancer, MAP_TYPE_REFERENCE));
            //        keep.getRelationships().computeIfAbsent(INSTANCES.getNs(), s -> new
            // ArrayList<>()).addAll(instanceKeys);
          }
        });
    return cacheResultBuilder.build();
  }

  private static boolean shouldUseOnDemandData(
      CacheResultBuilder cacheResultBuilder, String loadBalancerKey) {
    CacheData cacheData = cacheResultBuilder.getOnDemand().getToKeep().get(loadBalancerKey);
    return cacheData != null
        && (Long) cacheData.getAttributes().get("cacheTime") >= cacheResultBuilder.getStartTime();
  }

  /////////////////////////////////
  // ???!!!!!!!!!!!!!!!!!!!!!!!!!!!/////////////////////////////////???!!!!!!!!!!!!!!!!!!!!!!!!!!!

  private List<YandexCloudLoadBalancer> constructLoadBalancers(String loadBalancerId) {
    List<YandexCloudLoadBalancer> loadBalancers = new ArrayList<>();
    //    List<String> failedLoadBalancers = new ArrayList<>();

    // Reset the local getHealth caches/queues each caching agent cycle.

    if (loadBalancerId != null) {
      NetworkLoadBalancer networkLoadBalancer =
          getCredentials()
              .networkLoadBalancerService()
              .get(
                  GetNetworkLoadBalancerRequest.newBuilder()
                      .setNetworkLoadBalancerId(loadBalancerId)
                      .build());
      loadBalancers.add(convertLoadBalancer(networkLoadBalancer));
    } else {
      ListNetworkLoadBalancersResponse response =
          getCredentials()
              .networkLoadBalancerService()
              .list(ListNetworkLoadBalancersRequest.newBuilder().setFolderId(getFolder()).build());
      response.getNetworkLoadBalancersList().stream()
          .map(this::convertLoadBalancer)
          .collect(Collectors.toCollection(() -> loadBalancers));
    }

    //    executeIfRequestsAreQueued(forwardingRulesRequest);
    //    executeIfRequestsAreQueued(targetPoolsRequest);
    //    executeIfRequestsAreQueued(httpHealthChecksRequest);
    //    executeIfRequestsAreQueued(instanceHealthRequest);
    //
    //    resolutions.forEach(resolution -> {
    //      getTpNameToInstanceHealthsMap().get(resolution.getTarget()).forEach(targetPoolHealth ->
    // {
    ////        YandexCloudLoadBalancer loadBalancer = resolution.getGoogleLoadBalancer();
    ////        targetPoolHealth.healthStatus?.each { HealthStatus status ->
    ////          def instanceName = Utils.getLocalName(status.instance)
    ////          def googleLBHealthStatus =
    // GoogleLoadBalancerHealth.PlatformStatus.valueOf(status.healthState)
    ////
    ////          if (loadBalancer.type == GoogleLoadBalancerType.NETWORK && loadBalancer.ipAddress
    // != status.ipAddress) {
    ////            log.debug("Skip adding health for ${instanceName} to ${loadBalancer.name}
    // (${loadBalancer.ipAddress}): ${status.healthState} ($status.ipAddress)")
    ////            return
    ////          }
    ////
    ////          loadBalancer.healths << new GoogleLoadBalancerHealth(
    ////            instanceName: instanceName,
    ////            instanceZone: Utils.getZoneFromInstanceUrl(status.instance),
    ////            status: googleLBHealthStatus,
    ////            lbHealthSummaries: [
    ////          new GoogleLoadBalancerHealth.LBHealthSummary(
    ////            loadBalancerName: loadBalancer.name,
    ////            instanceId: instanceName,
    ////            state: googleLBHealthStatus.toServiceStatus(),
    ////          )
    ////        ]
    ////      )
    ////        }
    //      });
    //    });

    return loadBalancers.stream()
        //      .filter(lb -> !failedLoadBalancers.contains(lb.getName()))
        .collect(toList());
  }

  private YandexCloudLoadBalancer convertLoadBalancer(NetworkLoadBalancer networkLoadBalancer) {
    return YandexCloudLoadBalancer.createFromNetworkLoadBalancer(
        networkLoadBalancer, getAccountName());
  }

  /**
   * Local cache of targetPoolInstanceHealth keyed by TargetPool name.
   *
   * <p>It turns out that the types in the GCE Batch callbacks aren't the actual Compute types for
   * some reason, which is why this map is String -> Object.
   */
  //  private Map<String, Object> tpNameToInstanceHealthsMap = new HashMap<String, Object>();
  //  private Set<TargetPoolHealthRequest> queuedTpHealthRequests = new
  // HashSet<TargetPoolHealthRequest>();
  //  private Set<LoadBalancerHealthResolution> resolutions = new
  // HashSet<LoadBalancerHealthResolution>();

  //  public class ForwardingRuleCallbacks {
  //    private List<YandexCloudLoadBalancer> loadBalancers;
  //    private List<String> failedLoadBalancers = new ArrayList<String>();
  //    private GoogleBatchRequest targetPoolsRequest;
  //    private GoogleBatchRequest httpHealthChecksRequest;
  //    private GoogleBatchRequest instanceHealthRequest;
  //
  //    ForwardingRuleSingletonCallback<ForwardingRule> newForwardingRuleSingletonCallback() {
  //      return new ForwardingRuleSingletonCallback<ForwardingRule>();
  //    }
  //
  //    ForwardingRuleListCallback<ForwardingRuleList> newForwardingRuleListCallback() {
  //      return new ForwardingRuleListCallback<ForwardingRuleList>();
  //    }
  //
  //    void cacheRemainderOfLoadBalancerResourceGraph(ForwardingRule forwardingRule) {
  //      Map<String, List<Object>> map = new HashMap<String, List<Object>>(8);
  //      map.put("name", forwardingRule.name);
  //      map.put("account", accountName);
  //      map.put("region", region);
  //      map.put("createdTime", Util1s.getTimeFromTimestamp(forwardingRule.creationTimestamp));
  //      map.put("ipAddress", forwardingRule.IPAddress);
  //      map.put("ipProtocol", forwardingRule.IPProtocol);
  //      map.put("portRange", forwardingRule.portRange);
  //      map.put("healths", new ArrayList());
  //      YandexCloudLoadBalancer newLoadBalancer = new YandexCloudLoadBalancer(map);
  //      loadBalancers.add(newLoadBalancer);
  //
  //      Collection forwardingRuleTokens = DefaultGroovyMethods.split(forwardingRule.target, "/");
  //
  //      if (!DefaultGroovyMethods.getAt(forwardingRuleTokens, forwardingRuleTokens.size() -
  // 2).equals("targetVpnGateways") && !DefaultGroovyMethods.getAt(forwardingRuleTokens,
  // forwardingRuleTokens.size() - 2).equals("targetInstances")) {
  //        Map<String, Object> map1 = new HashMap<String, Object>(5);
  //        map1.put("googleLoadBalancer", newLoadBalancer);
  //        map1.put("httpHealthChecksRequest", httpHealthChecksRequest);
  //        map1.put("instanceHealthRequest", instanceHealthRequest);
  //        map1.put("subject", newLoadBalancer.name);
  //        map1.put("failedSubjects", failedLoadBalancers);
  //        TargetPoolCallback targetPoolsCallback = new TargetPoolCallback(map1);
  //
  //        Object targetPoolName = Util1s.getLocalName(forwardingRule.target);
  //        targetPoolsRequest.queue(compute.targetPools().get(getFolder(), getRegion(),
  // targetPoolName), targetPoolsCallback);
  //      }
  //
  //    }
  //
  //
  //    public class ForwardingRuleSingletonCallback<ForwardingRule> extends
  // JsonBatchCallback<ForwardingRule> {
  //      @Override
  //      public void onFailure(GoogleJsonError e, Http1Headers responseHeaders) throws IOException
  // {
  //        // 404 is thrown if the forwarding rule does not exist in the given region. Any other
  // exception needs to be propagated.
  //        if (!e.code.equals(404)) {
  //          String errorJson = new
  // ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(e);
  //          log.error(errorJson);
  //        }
  //
  //      }
  //
  //      @Override
  //      public void onSuccess(ForwardingRule forwardingRule, Http1Headers responseHeaders) throws
  // IOException {
  //        if (forwardingRule.target != null) {
  //          cacheRemainderOfLoadBalancerResourceGraph(forwardingRule);
  //        } else {
  //          throw new IllegalArgumentException("Not responsible for on demand caching of load
  // balancers without target pools.");
  //        }
  //
  //      }
  //
  //    }
  //  }

  //  public class TargetPoolCallback<TargetPool> extends JsonBatchCallback<TargetPool> implements
  // FailedSubjectChronicler {
  //    @Override
  //    public void onSuccess(final TargetPool targetPool, HttpHeaders responseHeaders) throws
  // IOException {
  //      googleLoadBalancer.targetPool = (targetPool == null ? null : targetPool.selfLink);
  //      googleLoadBalancer.sessionAffinity = (targetPool == null ? null :
  // targetPool.sessionAffinity);
  //      boolean hasHealthChecks = (targetPool == null ? null : targetPool.healthChecks);
  //      DefaultGroovyMethods.each((targetPool == null ? null : targetPool.healthChecks), new
  // Closure<Object>(this, this) {
  //        public Object doCall(Object healthCheckUrl) {
  //          Object localHealthCheckName = getProperty("Utils").invokeMethod("getLocalName", new
  // Object[]{healthCheckUrl});
  //          Map<String, Object> map = new HashMap<String, Object>(5);
  //          map.put("googleLoadBalancer", getProperty("googleLoadBalancer"));
  //          map.put("targetPool", getProperty("targetPool"));
  //          map.put("instanceHealthRequest", getProperty("instanceHealthRequest"));
  //          map.put("subject", getProperty("googleLoadBalancer").name);
  //          map.put("failedSubjects", getProperty("failedSubjects"));
  //          HttpHealthCheckCallback httpHealthCheckCallback = new HttpHealthCheckCallback(map);
  //
  //          return getHttpHealthChecksRequest().invokeMethod("queue", new
  // Object[]{getProperty("compute").invokeMethod("httpHealthChecks", new
  // Object[0]).invokeMethod("get", new Object[]{getFolder(), localHealthCheckName}),
  // httpHealthCheckCallback});
  //        }
  //
  //      });
  //      if (!hasHealthChecks) {
  //        Map<String, Object> map = new HashMap<String, Object>(3);
  //        map.put("googleLoadBalancer", getProperty("googleLoadBalancer"));
  //        map.put("targetPool", getProperty("targetPool"));
  //        map.put("instanceHealthRequest", getProperty("instanceHealthRequest"));
  //        new TargetPoolInstanceHealthCallInvoker(map).doCall();
  //      }
  //
  //    }
  //
  //    public GoogleNetworkLoadBalancer getGoogleLoadBalancer() {
  //      return googleLoadBalancer;
  //    }
  //
  //    public void setGoogleLoadBalancer(GoogleNetworkLoadBalancer googleLoadBalancer) {
  //      this.googleLoadBalancer = googleLoadBalancer;
  //    }
  //
  //    public GoogleBatchRequest getHttpHealthChecksRequest() {
  //      return httpHealthChecksRequest;
  //    }
  //
  //    public void setHttpHealthChecksRequest(GoogleBatchRequest httpHealthChecksRequest) {
  //      this.httpHealthChecksRequest = httpHealthChecksRequest;
  //    }
  //
  //    public GoogleBatchRequest getInstanceHealthRequest() {
  //      return instanceHealthRequest;
  //    }
  //
  //    public void setInstanceHealthRequest(GoogleBatchRequest instanceHealthRequest) {
  //      this.instanceHealthRequest = instanceHealthRequest;
  //    }
  //
  //    private GoogleNetworkLoadBalancer googleLoadBalancer;
  //    private GoogleBatchRequest httpHealthChecksRequest;
  //    private GoogleBatchRequest instanceHealthRequest;
  //  }

  //  public class HttpHealthCheckCallback<HttpHealthCheck> extends
  // JsonBatchCallback<HttpHealthCheck> implements FailedSubjectChronicler {
  //    @Override
  //    public void onSuccess(HttpHealthCheck httpHealthCheck, HttpHeaders responseHeaders) throws
  // IOException {
  //      if (DefaultGroovyMethods.asBoolean(httpHealthCheck)) {
  //        Map<String, Object> map = new HashMap<String, Object>(8);
  //        map.put("name", getProperty("httpHealthCheck").name);
  //        map.put("healthCheckType", getProperty("GoogleHealthCheck").HealthCheckType.HTTP);
  //        map.put("port", getProperty("httpHealthCheck").port);
  //        map.put("requestPath", getProperty("httpHealthCheck").requestPath);
  //        map.put("checkIntervalSec", getProperty("httpHealthCheck").checkIntervalSec);
  //        map.put("timeoutSec", getProperty("httpHealthCheck").timeoutSec);
  //        map.put("unhealthyThreshold", getProperty("httpHealthCheck").unhealthyThreshold);
  //        map.put("healthyThreshold", getProperty("httpHealthCheck").healthyThreshold);
  //        googleLoadBalancer.healthCheck = new GoogleHealthCheck(map);
  //      }
  //
  //
  //      Map<String, Object> map = new HashMap<String, Object>(3);
  //      map.put("googleLoadBalancer", getProperty("googleLoadBalancer"));
  //      map.put("targetPool", getProperty("targetPool"));
  //      map.put("instanceHealthRequest", getProperty("instanceHealthRequest"));
  //      new TargetPoolInstanceHealthCallInvoker(map).doCall();
  //    }
  //
  //    public GoogleNetworkLoadBalancer getGoogleLoadBalancer() {
  //      return googleLoadBalancer;
  //    }
  //
  //    public void setGoogleLoadBalancer(GoogleNetworkLoadBalancer googleLoadBalancer) {
  //      this.googleLoadBalancer = googleLoadBalancer;
  //    }
  //
  //    public Object getTargetPool() {
  //      return targetPool;
  //    }
  //
  //    public void setTargetPool(Object targetPool) {
  //      this.targetPool = targetPool;
  //    }
  //
  //    public GoogleBatchRequest getInstanceHealthRequest() {
  //      return instanceHealthRequest;
  //    }
  //
  //    public void setInstanceHealthRequest(GoogleBatchRequest instanceHealthRequest) {
  //      this.instanceHealthRequest = instanceHealthRequest;
  //    }
  //
  //    private GoogleNetworkLoadBalancer googleLoadBalancer;
  //    private Object targetPool;
  //    private GoogleBatchRequest instanceHealthRequest;
  //  }

  //  public class TargetPoolInstanceHealthCallInvoker {
  //    public Object doCall() {
  //      final Object region = getProperty("Utils").invokeMethod("getLocalName", new
  // Object[]{DefaultGroovyMethods.asType(getTargetPool().region, String.class)});
  //      final String targetPoolName = DefaultGroovyMethods.asType(targetPool.name, String.class);
  //
  //      final Object pool = targetPool;
  //      return DefaultGroovyMethods.each((pool == null ? null : pool.instances), new
  // Closure<Boolean>(this, this) {
  //        public Boolean doCall(String instanceUrl) {
  //          Map<String, Object> map = new HashMap<String, Object>(1);
  //          map.put("instance", getProperty("instanceUrl"));
  //          InstanceReference instanceReference = new InstanceReference(map);
  //          Map<String, Object> map1 = new HashMap<String, Object>(1);
  //          map1.put("targetPoolName", getProperty("targetPoolName"));
  //          TargetPoolInstanceHealthCallback instanceHealthCallback = new
  // TargetPoolInstanceHealthCallback(map1);
  //
  //          // Make only the group health request calls we need to.
  //          TargetPoolHealthRequest tphr = new TargetPoolHealthRequest(getFolder(), region,
  // targetPoolName, instanceReference.invokeMethod("getInstance", new Object[0]));
  //          if (!getQueuedTpHealthRequests().contains(tphr)) {
  //            // The groupHealthCallback updates the local cache along with running
  // handleHealthObject.
  //            DefaultGroovyMethods.invokeMethod(log, "debug", new Object[]{"Queueing a batch call
  // for getHealth(): {}", tphr});
  //            getQueuedTpHealthRequests().add(tphr);
  //            getInstanceHealthRequest().invokeMethod("queue", new
  // Object[]{getProperty("compute").invokeMethod("targetPools", new
  // Object[0]).invokeMethod("getHealth", new Object[]{getFolder(), region, targetPoolName,
  // instanceReference}), instanceHealthCallback});
  //          } else {
  //            DefaultGroovyMethods.invokeMethod(log, "debug", new Object[]{"Passing, batch call
  // result cached for getHealth(): {}", tphr});
  //          }
  //
  //          return getResolutions().add(new LoadBalancerHealthResolution(getGoogleLoadBalancer(),
  // targetPoolName));
  //        }
  //
  //      });
  //    }
  //
  //    public GoogleNetworkLoadBalancer getGoogleLoadBalancer() {
  //      return googleLoadBalancer;
  //    }
  //
  //    public Object getTargetPool() {
  //      return targetPool;
  //    }
  //
  //    public GoogleBatchRequest getInstanceHealthRequest() {
  //      return instanceHealthRequest;
  //    }
  //
  //    private GoogleNetworkLoadBalancer googleLoadBalancer;
  //    private Object targetPool;
  //    private GoogleBatchRequest instanceHealthRequest;
  //  }

  //  public class TargetPoolInstanceHealthCallback<TargetPoolInstanceHealth> extends
  // JsonBatchCallback<TargetPoolInstanceHealth> {
  //    @Override
  //    public void onSuccess(TargetPoolInstanceHealth targetPoolInstanceHealth, HttpHeaders
  // responseHeaders) throws IOException {
  //      if (!getTpNameToInstanceHealthsMap().containsKey(targetPoolName)) {
  //        getTpNameToInstanceHealthsMap().put(targetPoolName, new
  // ArrayList<TargetPoolInstanceHealth>(Arrays.asList(targetPoolInstanceHealth)));
  //      } else {
  //        getTpNameToInstanceHealthsMap().get(targetPoolName) << targetPoolInstanceHealth;
  //      }
  //
  //    }
  //
  //    @Override
  //    public void onFailure(GoogleJsonError e, HttpHeaders responseHeaders) throws IOException {
  //      DefaultGroovyMethods.invokeMethod(log, "error", new Object[]{"Error while querying target
  // pool instance health: {}", e.invokeMethod("getMessage", new Object[0])});
  //    }
  //
  //    public String getTargetPoolName() {
  //      return targetPoolName;
  //    }
  //
  //    public void setTargetPoolName(String targetPoolName) {
  //      this.targetPoolName = targetPoolName;
  //    }
  //
  //    private String targetPoolName;
  //  }

}
