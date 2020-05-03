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
import com.netflix.spinnaker.clouddriver.yandex.model.health.YandexLoadBalancerHealth;
import com.netflix.spinnaker.clouddriver.yandex.provider.Keys;
import com.netflix.spinnaker.clouddriver.yandex.security.YandexCloudCredentials;
import lombok.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.netflix.spinnaker.clouddriver.yandex.provider.Keys.Namespace.*;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.stream.Collectors.*;

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
    Collection<String> identifiers = cacheView.filterIdentifiers(CLUSTERS.getNs(), Keys.getClusterKey("*", "*", "*"));
    return cacheView.getAll(CLUSTERS.getNs(), identifiers).stream()
      //todo: раньше был data.getAttributes().get("resource") - почему?
      .map(data -> objectMapper.convertValue(data.getAttributes(), YandexCloudCluster.class))
      .collect(groupingBy(YandexCloudCluster::getAccountName, toSet()));
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
    CacheData clusterData = cacheView.get(
      CLUSTERS.getNs(),
      Keys.getClusterKey(account, application, name),
      RelationshipCacheFilter.include(SERVER_GROUPS.getNs(), INSTANCES.getNs())
    );

    if (clusterData == null) {
      return null;
    }

    Collection<CacheData> instances = !includeDetails || clusterData.getRelationships() == null
      ? emptyList()
      : instanceProvider.getInstanceCacheData(clusterData.getRelationships().get(INSTANCES.getNs()));

    return clusterFromCacheData(clusterData, instances);
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

    String pattern = Keys.getServerGroupKey(account, "*", ((YandexCloudCredentials) credentials).getFolder(), name);
    Collection<CacheData> cacheDataResults = cacheView.getAll(
      SERVER_GROUPS.getNs(),
      cacheView.filterIdentifiers(SERVER_GROUPS.getNs(), pattern),
      RelationshipCacheFilter.include(LOAD_BALANCERS.getNs(), INSTANCES.getNs())
    );
    if (cacheDataResults.isEmpty()) {
      return null;
    }
    CacheData cacheData = cacheDataResults.stream().findFirst().orElse(null);
    return serverGroupFromCacheData(
      cacheData,
      instanceProvider.getInstances(cacheData.getRelationships().get(INSTANCES.getNs())),
      loadBalancersFromKeys(cacheData.getRelationships().get(LOAD_BALANCERS.getNs()))
    );
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
        .map(cluster -> Keys.getClusterKey("*", applicationName, cluster))
        .map(key -> cacheView.filterIdentifiers(CLUSTERS.getNs(), key))
        .flatMap(Collection::stream)
        .collect(toSet());

    Set<String> instanceIdentifiers =
      application.getInstances().stream()
        .map(o -> Keys.getInstanceKey(o.get("account"), o.get("id"), o.get("folder"), o.get("name")))
        .collect(toSet());

    Collection<CacheData> instanceCacheData =
      includeInstanceDetails
        ? instanceProvider.getInstanceCacheData(instanceIdentifiers)
        : emptySet();

    Collection<CacheData> clusterCacheData =
      cacheView.getAll(
        CLUSTERS.getNs(),
        clusterIdentifiers,
        RelationshipCacheFilter.include(SERVER_GROUPS.getNs()));

    return clusterCacheData.stream()
      .map(cacheData -> clusterFromCacheData(cacheData, instanceCacheData))
      .collect(groupingBy(YandexCloudCluster::getAccountName, toSet()));
  }

  private YandexCloudCluster clusterFromCacheData(
    CacheData clusterCacheData, Collection<CacheData> instanceCacheDataSuperSet) {
    YandexCloudCluster cluster =
      objectMapper.convertValue(clusterCacheData.getAttributes(), YandexCloudCluster.class);

    Collection<String> serverGroupKeys =
      clusterCacheData.getRelationships().get(SERVER_GROUPS.getNs());
    if (serverGroupKeys.isEmpty()) {
      return cluster;
    }

    Collection<CacheData> serverGroupData =
      cacheView.getAll(
        SERVER_GROUPS.getNs(),
        serverGroupKeys,
        RelationshipCacheFilter.include(LOAD_BALANCERS.getNs()));

    List<YandexCloudInstance> instances =
      instanceCacheDataSuperSet.stream()
        .filter(
          cacheData ->
            cacheData.getRelationships().get(CLUSTERS.getNs()).stream()
              .map(Keys::parse)
              .filter(Objects::nonNull)
              .map(m -> m.get("cluster"))
              .filter(Objects::nonNull)
              .anyMatch(cluster.getName()::equals))
        .map(instanceProvider::instanceFromCacheData)
        .collect(toList());

    List<String> loadBalancerKeys =
      serverGroupData.stream()
        .map(sg -> sg.getRelationships().get(LOAD_BALANCERS.getNs()))
        .flatMap(Collection::stream)
        .collect(toList());

    Set<YandexCloudLoadBalancer> loadBalancers = loadBalancersFromKeys(loadBalancerKeys);

    serverGroupData.forEach(serverGroupCacheData -> {
      YandexCloudServerGroup serverGroup = serverGroupFromCacheData(
        serverGroupCacheData,
        instances,
        loadBalancers
      );
      cluster.getServerGroups().add(serverGroup);
      if (serverGroup.getLoadBalancerIntegration() != null) {
        cluster.getLoadBalancers().addAll(serverGroup.getLoadBalancerIntegration().getBalancers());
      }
    });

    return cluster;
  }

  private Set<YandexCloudLoadBalancer> loadBalancersFromKeys(Collection<String> loadBalancerKeys) {
    return cacheView.getAll(LOAD_BALANCERS.getNs(), loadBalancerKeys).stream()
      .map(cd -> objectMapper.convertValue(cd.getAttributes(), YandexCloudLoadBalancer.class))
      .collect(toSet());
  }

  private YandexCloudServerGroup serverGroupFromCacheData(
    CacheData cacheData,
    List<YandexCloudInstance> instances,
    Set<YandexCloudLoadBalancer> loadBalancers) {

    YandexCloudServerGroup serverGroup =
      objectMapper.convertValue(cacheData.getAttributes(), YandexCloudServerGroup.class);


    if (!instances.isEmpty()) {
      Set<String> instanceIds = cacheData.getRelationships().get(INSTANCES.getNs()).stream()
        .map(Keys::parse)
        .filter(Objects::nonNull)
        .map(key -> key.get("id"))
        .collect(toSet());
      serverGroup.setInstances(instances.stream()
        .filter(instance -> instanceIds.contains(instance.getId()))
        .collect(toSet()));
    }
    if (serverGroup.getLoadBalancerIntegration() != null) {
      Set<String> loadBalancerIds = cacheData.getRelationships().get(LOAD_BALANCERS.getNs()).stream()
        .map(Keys::parse)
        .filter(Objects::nonNull)
        .map(key -> key.get("id"))
        .collect(toSet());
      Set<YandexCloudLoadBalancer> balancers = loadBalancers.stream()
        .filter(loadBalancer -> loadBalancerIds.contains(loadBalancer.getId()))
        .collect(toSet());
      serverGroup.getLoadBalancerIntegration().setBalancers(balancers);

      String targetGroupId = serverGroup.getLoadBalancerIntegration().getTargetGroupId();
      serverGroup.getInstances().forEach(instance -> {
        List<YandexLoadBalancerHealth> healths = loadBalancers.stream()
          .map(balancer -> balancer.getHealths().getOrDefault(targetGroupId, emptyList()))
          .flatMap(Collection::stream)
          .filter(health -> instance.getAddressesInSubnets()
            .getOrDefault(health.getSubnetId(), emptyList())
            .contains(health.getAddress()))
          .collect(toList());
//        instance.setHealth();
        //          return instance.loadBalancerHealths = foundHealths;
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

    //    Boolean isDisabled = true;
    //    // TODO: Extend this for future load balancers that calculate disabled state after
    // caching.
    //    Boolean anyDisabledStates =  tcpDisabledStates;
    //    Boolean disabledStatesSizeMatch = tcpDisabledStates.size() ==  set.get().size();
    //    Boolean excludesNetwork = anyDisabledStates && disabledStatesSizeMatch;
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
    //    serverGroup.disabled = ;
//    serverGroup.setDisabled(excludesNetwork ? isDisabled : isDisabled && serverGroup.getDisabled());

    return serverGroup;
  }
}
