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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.spinnaker.cats.cache.Cache;
import com.netflix.spinnaker.cats.cache.CacheData;
import com.netflix.spinnaker.clouddriver.model.LoadBalancerProvider;
import com.netflix.spinnaker.clouddriver.model.LoadBalancerServerGroup;
import com.netflix.spinnaker.clouddriver.security.AccountCredentialsProvider;
import com.netflix.spinnaker.clouddriver.yandex.YandexCloudProvider;
import com.netflix.spinnaker.clouddriver.yandex.model.YandexCloudLoadBalancer;
import com.netflix.spinnaker.clouddriver.yandex.model.YandexCloudServerGroup;
import com.netflix.spinnaker.clouddriver.yandex.provider.Keys;
import java.util.*;
import java.util.stream.Collectors;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Data
public class YandexLoadBalancerProvider implements LoadBalancerProvider<YandexCloudLoadBalancer> {
  private final String cloudProvider = YandexCloudProvider.ID;
  private Cache cacheView;
  private ObjectMapper objectMapper;
  private AccountCredentialsProvider accountCredentialsProvider;

  @Autowired
  public YandexLoadBalancerProvider(
      Cache cacheView,
      AccountCredentialsProvider accountCredentialsProvider,
      ObjectMapper objectMapper) {
    this.cacheView = cacheView;
    this.accountCredentialsProvider = accountCredentialsProvider;
    this.objectMapper = objectMapper;
  }

  @Override
  public Set<YandexCloudLoadBalancer> getApplicationLoadBalancers(final String application) {
    return Collections.emptySet();
    //    Collection<String> identifiers =
    // cacheView.filterIdentifiers(Keys.Namespace.LOAD_BALANCERS.getNs(),
    // Keys.getLoadBalancerKey("*"));
    //
    //    if (!Strings.isNullOrEmpty(application)) {
    //      Collection<CacheData> applicationServerGroups = cacheView.getAll(
    //        Keys.Namespace.SERVER_GROUPS.getNs(),
    //        cacheView.filterIdentifiers(Keys.Namespace.SERVER_GROUPS.getNs(),
    // Keys.getServerGroupKey(, "*", "*"))
    //      );
    //      applicationServerGroups.forEach(serverGroup -> {
    //        Collection<String> relatedLoadBalancers = serverGroup.getRelationships() == null ?
    //          Collections.emptySet() :
    //          serverGroup.getRelationships().getOrDefault(Keys.Namespace.LOAD_BALANCERS.getNs(),
    // Collections.emptySet());
    //
    //        identifiers.addAll(relatedLoadBalancers);
    //      });
    //    }
    //
    //
    //    return cacheView.getAll(
    //      Keys.Namespace.LOAD_BALANCERS.getNs(),
    //      identifiers,
    //      RelationshipCacheFilter.include(Keys.Namespace.SERVER_GROUPS.getNs(),
    // Keys.Namespace.INSTANCES.getNs())
    //    ).stream()
    //      .map(loadBalancerCacheData -> {
    //        Collection<String> instances = loadBalancerCacheData.getRelationships() == null ?
    //          Collections.emptySet() :
    //
    // loadBalancerCacheData.getRelationships().getOrDefault(Keys.Namespace.INSTANCES.getNs(),
    // Collections.emptySet());
    //        return loadBalancersFromCacheData(loadBalancerCacheData, instances);
    //      })
    //      .collect(Collectors.toSet());
  }

  private YandexCloudLoadBalancer loadBalancersFromCacheData(
      CacheData loadBalancerCacheData, Collection<String> allApplicationInstanceKeys) {
    YandexCloudLoadBalancer loadBalancer =
        objectMapper.convertValue(
            loadBalancerCacheData.getAttributes(), YandexCloudLoadBalancer.class);

    Collection<String> serverGroupKeys =
        loadBalancerCacheData.getRelationships() == null
            ? Collections.emptySet()
            : loadBalancerCacheData.getRelationships().get(Keys.Namespace.SERVER_GROUPS.getNs());
    if (serverGroupKeys.isEmpty()) {
      return loadBalancer;
    }

    cacheView
        .getAll(Keys.Namespace.SERVER_GROUPS.getNs(), serverGroupKeys)
        .forEach(
            serverGroupCacheData -> {
              YandexCloudServerGroup serverGroup =
                  objectMapper.convertValue(
                      serverGroupCacheData.getAttributes(), YandexCloudServerGroup.class);

              LoadBalancerServerGroup loadBalancerServerGroup = new LoadBalancerServerGroup();

              loadBalancerServerGroup.setName(serverGroup.getName());
              loadBalancerServerGroup.setRegion(serverGroup.getRegion());
              loadBalancerServerGroup.setIsDisabled(serverGroup.isDisabled());
              loadBalancerServerGroup.setDetachedInstances(Collections.emptySet());
              loadBalancerServerGroup.setInstances(new HashSet<>());
              loadBalancerServerGroup.setCloudProvider(YandexCloudProvider.ID);

              //      allApplicationInstanceKeys.stream()
              //        .filter(it ->
              // serverGroup.getInstances().stream().map(YandexCloudInstance::getId).anyMatch(it::equals))
              //        .map(it->{
              //          final Object parse = getProperty("Keys").invokeMethod("parse", new
              // Object[]{it});
              //          return (parse == null ? null : parse.name);
              //        });

              //      DefaultGroovyMethods.each(loadBalancer.get().healths, new
              // Closure<Set<LoadBalancerInstance>>(YandexLoadBalancerProvider.this,
              // YandexLoadBalancerProvider.this) {
              //        public Set<LoadBalancerInstance> doCall(GoogleLoadBalancerHealth
              // googleLoadBalancerHealth) {
              //          if
              // (!DefaultGroovyMethods.asBoolean(DefaultGroovyMethods.invokeMethod(instanceNames,
              // "remove", new Object[]{googleLoadBalancerHealth.instanceName}))) {
              //            return;
              //
              //          }
              //
              //
              //          LoadBalancerInstance instance = new LoadBalancerInstance();
              //
              //
              //          LinkedHashMap<String, String> map = new LinkedHashMap<String, String>(2);
              //          map.put("state",
              // DefaultGroovyMethods.asType(DefaultGroovyMethods.getAt(googleLoadBalancerHealth.lbHealthSummaries, 0).state, String.class));
              //          map.put("description",
              // DefaultGroovyMethods.getAt(googleLoadBalancerHealth.lbHealthSummaries,
              // 0).description);
              //
              //          return
              // DefaultGroovyMethods.leftShift(loadBalancerServerGroup.getInstances(),
              // instance.setId(googleLoadBalancerHealth.instanceName)instance.setZone(googleLoadBalancerHealth.instanceZone)instance.setHealth(map));
              //        }
              //
              //      });

              loadBalancer.getServerGroups().add(loadBalancerServerGroup);
            });

    return loadBalancer;
  }

  public List<YandexLoadBalancerAccountRegionSummary> list() {
    Map<String, List<YandexCloudLoadBalancer>> loadBalancerMap =
        getApplicationLoadBalancers("").stream()
            .collect(Collectors.groupingBy(YandexCloudLoadBalancer::getName));

    return loadBalancerMap.entrySet().stream()
        .map(
            e -> {
              YandexLoadBalancerAccountRegionSummary summary =
                  new YandexLoadBalancerAccountRegionSummary();
              summary.setName(e.getKey());
              e.getValue()
                  .forEach(
                      balancer -> {
                        YandexLoadBalancerSummary s = new YandexLoadBalancerSummary();
                        s.setAccount(balancer.getAccount());
                        s.setName(balancer.getName());
                        s.setRegion(balancer.getRegion());
                        //          s.setBackendServices();
                        //          s.setUrlMapName((String) urlMapName);

                        summary
                            .getMappedAccounts()
                            .computeIfAbsent(
                                balancer.getAccount(), a -> new YandexLoadBalancerAccount())
                            .getMappedRegions()
                            .computeIfAbsent(
                                balancer.getAccount(), a -> new YandexLoadBalancerAccountRegion())
                            .getLoadBalancers()
                            .add(s);
                      });

              return summary;
            })
        .collect(Collectors.toList());
  }

  public YandexLoadBalancerAccountRegionSummary get(String name) {
    return list().stream().filter(l -> l.getName().equals(name)).findFirst().orElse(null);
  }

  public List<YandexLoadBalancerDetails> byAccountAndRegionAndName(
      final String account, final String region, String name) {
    throw new UnsupportedOperationException();
    //    final YandexCloudLoadBalancer view = (YandexCloudLoadBalancer)
    // DefaultGroovyMethods.find((Collection<T>) getApplicationLoadBalancers(name), new
    // Closure<Boolean>(this, this) {
    //      public Boolean doCall(Object view) {
    //        return view.account.equals(account) && view.region.equals(region);
    //      }
    //
    //    });
    //
    //    if (!view.asBoolean()) {
    //      return new ArrayList<YandexLoadBalancerDetails>();
    //    }
    //
    //
    //    final LinkedHashMap<Object, Object> backendServiceHealthChecks = new LinkedHashMap<Object,
    // Object>();
    //    if (view.loadBalancerType.equals(getProperty("GoogleLoadBalancerType").HTTP)) {
    //      View httpView = (View) view;
    //      List<GoogleBackendService> backendServices =
    // getProperty("Utils").invokeMethod("getBackendServicesFromHttpLoadBalancerView", new
    // Object[]{httpView});
    //      DefaultGroovyMethods.each(backendServices, new Closure<Object>(this, this) {
    //        public Object doCall(GoogleBackendService backendService) {
    //          return putAt0(backendServiceHealthChecks, backendService.name,
    // backendService.healthCheck.view);
    //        }
    //
    //      });
    //    }
    //
    //
    //    String instancePort;
    //    String loadBalancerPort;
    //    String sessionAffinity;
    //    switch (view.loadBalancerType) {
    //      case getProperty("GoogleLoadBalancerType").NETWORK:
    //        View nlbView = (View) view;
    //        sessionAffinity = ((String) (nlbView.sessionAffinity));
    //        instancePort = ((String) (getProperty("Utils").invokeMethod("derivePortOrPortRange",
    // new Object[]{view.portRange})));
    //        loadBalancerPort = ((String)
    // (getProperty("Utils").invokeMethod("derivePortOrPortRange", new Object[]{view.portRange})));
    //        break;
    //      case getProperty("GoogleLoadBalancerType").HTTP:
    //        instancePort = "http";
    //        loadBalancerPort = ((String)
    // (getProperty("Utils").invokeMethod("derivePortOrPortRange", new Object[]{view.portRange})));
    //        break;
    //      case getProperty("GoogleLoadBalancerType").INTERNAL:
    //        View ilbView = (View) view;
    //        Object portString = ilbView.ports.invokeMethod("join", new Object[]{","});
    //        instancePort = portString;
    //        loadBalancerPort = portString;
    //        break;
    //      case getProperty("GoogleLoadBalancerType").SSL:
    //        instancePort = ((String) (getProperty("Utils").invokeMethod("derivePortOrPortRange",
    // new Object[]{view.portRange})));
    //        loadBalancerPort = ((String)
    // (getProperty("Utils").invokeMethod("derivePortOrPortRange", new Object[]{view.portRange})));
    //        break;
    //      case getProperty("GoogleLoadBalancerType").TCP:
    //        instancePort = ((String) (getProperty("Utils").invokeMethod("derivePortOrPortRange",
    // new Object[]{view.portRange})));
    //        loadBalancerPort = ((String)
    // (getProperty("Utils").invokeMethod("derivePortOrPortRange", new Object[]{view.portRange})));
    //        break;
    //      default:
    //        throw new IllegalStateException("Load balancer " +
    // DefaultGroovyMethods.invokeMethod(String.class, "valueOf", new Object[]{view.name}) + " is an
    // unknown load balancer type.");
    //        break;
    //    }
    //    YandexLoadBalancerDetails details = new YandexLoadBalancerDetails();
    //
    //
    //    LinkedHashMap<String, ListenerDescription> map = new LinkedHashMap<String,
    // ListenerDescription>(1);
    //    ListenerDescription description = new ListenerDescription();
    //
    //
    //    map.put("listener",
    // description.setInstancePort(instancePort)description.setLoadBalancerPort(loadBalancerPort)description.setInstanceProtocol(view.ipProtocol)description.setProtocol(view.ipProtocol));
    //
    //    return new
    // ArrayList<YandexLoadBalancerDetails>(Arrays.asList(details.setLoadBalancerName(view.name)details.setLoadBalancerType(view.loadBalancerType)details.setCreatedTime(view.createdTime)details.setDnsname(view.ipAddress)details.setIpAddress(view.ipAddress)details.setSessionAffinity(sessionAffinity)details.setHealthCheck((view.invokeMethod("hasProperty", new Object[]{"healthCheck"}) && view.healthCheck) ? view.healthCheck : null)details.setBackendServiceHealthChecks((Map<String, View>) DefaultGroovyMethods.asBoolean(backendServiceHealthChecks) ? backendServiceHealthChecks : null)details.setListenerDescriptions(new ArrayList<LinkedHashMap<String, ListenerDescription>>(Arrays.asList(map)))));
  }

  @Data
  public static class YandexLoadBalancerAccountRegionSummary implements Item {
    private String name;

    @JsonIgnore private Map<String, YandexLoadBalancerAccount> mappedAccounts = new HashMap<>();

    @JsonProperty("accounts")
    public List<YandexLoadBalancerAccount> getByAccounts() {
      return new ArrayList<>(mappedAccounts.values());
    }
  }

  @Data
  public static class YandexLoadBalancerAccount implements ByAccount {
    private String name;

    @JsonIgnore
    private Map<String, YandexLoadBalancerAccountRegion> mappedRegions = new HashMap<>();

    @JsonProperty("regions")
    public List<YandexLoadBalancerAccountRegion> getByRegions() {
      return new ArrayList<>(mappedRegions.values());
    }
  }

  @Data
  private static class YandexLoadBalancerAccountRegion implements ByRegion {
    private String name;
    private List<YandexLoadBalancerSummary> loadBalancers = new ArrayList<>();
  }

  @Data
  private static class YandexLoadBalancerSummary implements Details {
    private String account;
    private String region;
    private String name;
    private String type = YandexCloudProvider.ID;
    private List<String> backendServices;
    private String urlMapName;
  }

  @Data
  private static class YandexLoadBalancerDetails implements Details {
    private Long createdTime;
    private String dnsname;
    private String ipAddress;
    private String loadBalancerName;
    //    private View healthCheck;
    private String sessionAffinity;
    //    private Map<String, View> backendServiceHealthChecks = new LinkedHashMap<String, View>();
    private List<Map<String, ListenerDescription>> listenerDescriptions = new ArrayList<>();
  }

  @Data
  private static class ListenerDescription {
    private String instancePort;
    private String instanceProtocol;
    private String loadBalancerPort;
    private String protocol;
  }
}
