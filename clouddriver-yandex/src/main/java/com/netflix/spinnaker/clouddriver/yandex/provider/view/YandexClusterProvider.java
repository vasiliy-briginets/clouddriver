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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.spinnaker.cats.cache.Cache;
import com.netflix.spinnaker.cats.cache.CacheData;
import com.netflix.spinnaker.cats.cache.RelationshipCacheFilter;
import com.netflix.spinnaker.clouddriver.model.ClusterProvider;
import com.netflix.spinnaker.clouddriver.security.AccountCredentials;
import com.netflix.spinnaker.clouddriver.security.AccountCredentialsProvider;
import com.netflix.spinnaker.clouddriver.yandex.YandexCloudProvider;
import com.netflix.spinnaker.clouddriver.yandex.model.*;
import com.netflix.spinnaker.clouddriver.yandex.provider.Keys;
import com.netflix.spinnaker.clouddriver.yandex.security.YandexCloudCredentials;
import java.util.*;
import java.util.stream.Collectors;
import lombok.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Value
public class YandexClusterProvider implements ClusterProvider<YandexCloudCluster> {
  private final Cache cacheView;
  private final ObjectMapper objectMapper;
  private final YandexInstanceProvider instanceProvider;
  private final YandexApplicationProvider applicationProvider;
  private final String cloudProviderId = YandexCloudProvider.ID;
  private AccountCredentialsProvider accountCredentialsProvider;

  @Autowired
  public YandexClusterProvider(
      Cache cacheView,
      ObjectMapper objectMapper,
      YandexInstanceProvider instanceProvider,
      YandexApplicationProvider applicationProvider,
      AccountCredentialsProvider accountCredentialsProvider) {
    this.cacheView = cacheView;
    this.objectMapper = objectMapper;
    this.instanceProvider = instanceProvider;
    this.applicationProvider = applicationProvider;
    this.accountCredentialsProvider = accountCredentialsProvider;
  }

  @Override
  public Map<String, Set<YandexCloudCluster>> getClusters() {
    return cacheView.getAll(Keys.Namespace.CLUSTERS.getNs()).stream()
        .map(o -> objectMapper.convertValue(o, YandexCloudCluster.class))
        .collect(Collectors.groupingBy(YandexCloudCluster::getAccountName, Collectors.toSet()));
  }

  @Override
  public Map<String, Set<YandexCloudCluster>> getClusterDetails(String applicationName) {
    return getClusters(applicationName, true);
  }

  @Override
  public Map<String, Set<YandexCloudCluster>> getClusterSummaries(String applicationName) {
    return getClusters(applicationName, false);
  }

  @Override
  public Set<YandexCloudCluster> getClusters(String applicationName, String account) {
    return getClusterDetails(applicationName).get(account);
  }

  @Override
  public YandexCloudCluster getCluster(
      String application, String account, String name, boolean includeDetails) {
    CacheData clusterData =
        cacheView.get(
            Keys.Namespace.CLUSTERS.getNs(),
            Keys.getClusterKey(account, application, name),
            RelationshipCacheFilter.include(
                Keys.Namespace.SERVER_GROUPS.getNs(), Keys.Namespace.INSTANCES.getNs()));

    if (clusterData == null) {
      return null;
    }

    Set<String> instances =
        !includeDetails || clusterData.getRelationships() == null
            ? null
            : new HashSet<>(clusterData.getRelationships().get(Keys.Namespace.INSTANCES.getNs()));

    return clusterFromCacheData(clusterData, instanceProvider.getInstanceCacheData(instances));
  }

  @Override
  public YandexCloudCluster getCluster(
      String applicationName, String accountName, String clusterName) {
    return getCluster(applicationName, accountName, clusterName, true);
  }

  @Override
  public YandexCloudServerGroup getServerGroup(
      String account, String region, String name, boolean includeDetails) {
    AccountCredentials credentials = accountCredentialsProvider.getCredentials(account);
    if (!(credentials instanceof YandexCloudCredentials)) {
      return null;
    }

    CacheData cacheData =
        searchCacheForServerGroup(
            Keys.getServerGroupKey("*", ((YandexCloudCredentials) credentials).getFolder(), name));
    if (cacheData == null) {
      return null;
    }

    return serverGroupFromCacheData(
        cacheData,
        account,
        instanceProvider.getInstances(
            cacheData.getRelationships().get(Keys.Namespace.INSTANCES.getNs())),
        loadBalancersFromKeys(
            cacheData.getRelationships().get(Keys.Namespace.LOAD_BALANCERS.getNs())));
  }

  @Override
  public YandexCloudServerGroup getServerGroup(String account, String region, String name) {
    return getServerGroup(account, region, name, true);
  }

  @Override
  public boolean supportsMinimalClusters() {
    return false;
  }

  private Map<String, Set<YandexCloudCluster>> getClusters(
      String applicationName, boolean includeInstanceDetails) {
    YandexApplication application = applicationProvider.getApplication(applicationName);

    if (application == null) {
      return new HashMap<>();
    }

    Set<String> clusterIdentifiers =
        application.getClusterNames().values().stream()
            .flatMap(Collection::stream)
            .map(clusterName -> Keys.getClusterKey("*", applicationName, clusterName))
            .flatMap(
                key -> cacheView.filterIdentifiers(Keys.Namespace.CLUSTERS.getNs(), key).stream())
            .collect(Collectors.toSet());

    Set<String> instanceIdentifiers =
        application.getInstances().stream()
            .map(o -> Keys.getInstanceKey(o.get("id"), o.get("folder"), o.get("name")))
            .collect(Collectors.toSet());

    Collection<CacheData> instanceCacheData =
        includeInstanceDetails
            ? instanceProvider.getInstanceCacheData(instanceIdentifiers)
            : Collections.emptySet();

    Collection<CacheData> clusterCacheData =
        cacheView.getAll(
            Keys.Namespace.CLUSTERS.getNs(),
            clusterIdentifiers,
            RelationshipCacheFilter.include(Keys.Namespace.SERVER_GROUPS.getNs()));

    return clusterCacheData.stream()
        .map(cacheData -> clusterFromCacheData(cacheData, instanceCacheData))
        .collect(Collectors.groupingBy(YandexCloudCluster::getAccountName, Collectors.toSet()));
  }

  private CacheData searchCacheForServerGroup(String pattern) {
    Collection<CacheData> cacheDataResults =
        cacheView.getAll(
            Keys.Namespace.SERVER_GROUPS.getNs(),
            cacheView.filterIdentifiers(Keys.Namespace.SERVER_GROUPS.getNs(), pattern),
            RelationshipCacheFilter.include(
                Keys.Namespace.LOAD_BALANCERS.getNs(), Keys.Namespace.INSTANCES.getNs()));
    return cacheDataResults.stream().findFirst().orElse(null);
  }

  private YandexCloudCluster clusterFromCacheData(
      CacheData clusterCacheData, Collection<CacheData> instanceCacheDataSuperSet) {
    YandexCloudCluster cluster =
        objectMapper.convertValue(clusterCacheData.getAttributes(), YandexCloudCluster.class);

    Collection<String> serverGroupKeys =
        clusterCacheData.getRelationships().get(Keys.Namespace.SERVER_GROUPS.getNs());
    if (!serverGroupKeys.isEmpty()) {
      Collection<CacheData> serverGroupData =
          cacheView.getAll(
              Keys.Namespace.SERVER_GROUPS.getNs(),
              serverGroupKeys,
              RelationshipCacheFilter.include(Keys.Namespace.LOAD_BALANCERS.getNs()));

      List<YandexCloudInstance> instances =
          instanceCacheDataSuperSet.stream()
              .filter(
                  cacheData ->
                      cacheData.getRelationships().get(Keys.Namespace.CLUSTERS.getNs()).stream()
                          .map(Keys::parse)
                          .filter(Objects::nonNull)
                          .map(m -> m.get("cluster"))
                          .filter(Objects::nonNull)
                          .anyMatch(cluster.getName()::equals))
              .map(instanceProvider::instanceFromCacheData)
              .collect(Collectors.toList());

      List<String> loadBalancerKeys =
          serverGroupData.stream()
              .map(
                  serverGroup ->
                      serverGroup.getRelationships().get(Keys.Namespace.LOAD_BALANCERS.getNs()))
              .flatMap(Collection::stream)
              .collect(Collectors.toList());

      Set<YandexCloudLoadBalancer> loadBalancers = loadBalancersFromKeys(loadBalancerKeys);

      serverGroupData.forEach(
          serverGroupCacheData -> {
            YandexCloudServerGroup serverGroup =
                serverGroupFromCacheData(
                    serverGroupCacheData, cluster.getAccountName(), instances, loadBalancers);
            cluster.getServerGroups().add(serverGroup);
            if (serverGroup.getLoadBalancerIntegration() != null) {
              cluster
                  .getLoadBalancers()
                  .addAll(serverGroup.getLoadBalancerIntegration().getBalancers());
            }
          });
    }

    return cluster;
  }

  private Set<YandexCloudLoadBalancer> loadBalancersFromKeys(Collection<String> loadBalancerKeys) {
    return cacheView.getAll(Keys.Namespace.LOAD_BALANCERS.getNs(), loadBalancerKeys).stream()
        .map(cd -> objectMapper.convertValue(cd.getAttributes(), YandexCloudLoadBalancer.class))
        .collect(Collectors.toSet());
  }

  public YandexCloudServerGroup serverGroupFromCacheData(
      CacheData cacheData,
      String account,
      List<YandexCloudInstance> instances,
      Set<YandexCloudLoadBalancer> loadBalancers) {

    YandexCloudServerGroup serverGroup =
        objectMapper.convertValue(cacheData.getAttributes(), YandexCloudServerGroup.class);

    //    Collection<String> loadBalancerKeys =
    // cacheData.getRelationships().get(Keys.Namespace.LOAD_BALANCERS.getNs()).;
    //    Set<YandexCloudLoadBalancer> balancers = loadBalancers.stream()
    //      .filter(loadBalancer ->
    // loadBalancerKeys.contains(Keys.getLoadBalancerKey(loadBalancer.getId(), "*",
    // loadBalancer.getName())))
    //      .collect(Collectors.toSet());
    //    serverGroup.setYandexLoadBalancers(balancers);
    if (!instances.isEmpty()) {
      Set<YandexCloudInstance> cloudInstances =
          instances.stream()
              .filter(
                  instance -> {
                    // todo
                    //          return instance.serverGroup.equals(serverGroup.name) &&
                    // instance.region.equals(serverGroup.region);
                    return true;
                  })
              .collect(Collectors.toSet());
      serverGroup.setInstances(cloudInstances);

      serverGroup
          .getInstances()
          .forEach(
              instance -> {
                //        List<YandexLoadBalancerHealth> foundHealths =
                // getLoadBalancerHealths(instance.getName(), loadBalancers);
                //        if (DefaultGroovyMethods.asBoolean(foundHealths)) {
                //          return instance.loadBalancerHealths = foundHealths;
                //        }
              });
    }

    // Time to aggregate health states that can't be computed during the server group fetch
    // operation.

    //    Set<GoogleLoadBalancer> tcpLoadBalancers = DefaultGroovyMethods.findAll(set.get(), new
    // Closure<Boolean>(this, this) {
    //      public Boolean doCall(GoogleLoadBalancer it) {
    //        return it.type.equals(getProperty("GoogleLoadBalancerType").TCP);
    //      }
    //
    //      public Boolean doCall() {
    //        return doCall(null);
    //      }
    //
    //    });
    //    List<Object> tcpDisabledStates = DefaultGroovyMethods.collect(tcpLoadBalancers, new
    // Closure<Object>(this, this) {
    //      public Object doCall(Object loadBalancer) {
    //        return getProperty("Utils").invokeMethod("determineTcpLoadBalancerDisabledState", new
    // Object[]{loadBalancer, serverGroup});
    //      }
    //
    //    });
    //
    //    // Health states for Consul.
    //    final List<Object> collect = DefaultGroovyMethods.collect(serverGroup.instances, new
    // Closure<Object>(this, this) {
    //      public Object doCall(Object it) {
    //        return it.consulNode;
    //      }
    //
    //      public Object doCall() {
    //        return doCall(null);
    //      }
    //
    //    });
    //    List<Object> consulNodes = DefaultGroovyMethods.asBoolean(collect) ? collect : new
    // ArrayList();
    //    Object consulDiscoverable =
    // getProperty("ConsulProviderUtils").invokeMethod("consulServerGroupDiscoverable", new
    // Object[]{consulNodes});
    //    Boolean consulDisabled = false;
    //    if (consulDiscoverable.asBoolean()) {
    //      consulDisabled = ((Boolean)
    // (getProperty("ConsulProviderUtils").invokeMethod("serverGroupDisabled", new
    // Object[]{consulNodes})));
    //    }
    //
    //
    //    Boolean isDisabled = true;
    //    // TODO: Extend this for future load balancers that calculate disabled state after
    // caching.
    //    Boolean anyDisabledStates =  tcpDisabledStates;
    //    Boolean disabledStatesSizeMatch = internalDisabledStates.size() +
    // httpDisabledStates.size() + sslDisabledStates.size() + tcpDisabledStates.size() ==
    // set.get().size();
    //    Boolean excludesNetwork = anyDisabledStates && disabledStatesSizeMatch;
    //
    //
    //    if (DefaultGroovyMethods.asBoolean(tcpDisabledStates)) {
    //      isDisabled = DefaultGroovyMethods.and(isDisabled,
    // DefaultGroovyMethods.every(tcpDisabledStates, new Closure<Object>(this, this) {
    //        public Object doCall(Object it) {
    //          return it;
    //        }
    //
    //        public Object doCall() {
    //          return doCall(null);
    //        }
    //
    //      }));
    //    }
    //
    //    serverGroup.disabled = excludesNetwork ? isDisabled : isDisabled && serverGroup.disabled;
    //
    //    // Now that disabled is set based on L7 & L4 state, we need to take Consul into account.
    //    if (consulDiscoverable.asBoolean()) {
    //      // If there are no load balancers to determine enable/disabled status we rely on Consul
    // exclusively.
    //      if (set.get().size() == 0) {
    //        serverGroup.disabled = true;
    //      }
    //
    //      // If the server group is disabled, but Consul isn't, we say the server group is
    // discoverable.
    //      // If the server group isn't disabled, but Consul is, we say the server group can be
    // reached via load balancer.
    //      // If the server group and Consul are both disabled, the server group remains disabled.
    //      // If the server group and Consul are both not disabled, the server group is not
    // disabled.
    //      serverGroup.disabled &= consulDisabled;
    //      serverGroup.discovery = true;
    //    }

    return serverGroup;
  }

  //  public static List<YandexLoadBalancerHealth> getLoadBalancerHealths(final String instanceName,
  // List<GoogleLoadBalancer> loadBalancers) {
  //    return ((List<YandexLoadBalancerHealth>)
  // (DefaultGroovyMethods.flatten(DefaultGroovyMethods.findResults(loadBalancers.healths, new
  // Closure<List<YandexLoadBalancerHealth>>(null, null) {
  //      public List<YandexLoadBalancerHealth> doCall(List<YandexLoadBalancerHealth> glbhs) {
  //        return DefaultGroovyMethods.findAll(glbhs, new Closure<Boolean>(null, null) {
  //          public Boolean doCall(YandexLoadBalancerHealth glbh) {
  //            return glbh.instanceName.equals(instanceName);
  //          }
  //
  //        });
  //      }
  //
  //    }))));
  //  }
}
