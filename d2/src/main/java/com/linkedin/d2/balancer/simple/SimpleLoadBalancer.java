/*
   Copyright (c) 2012 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package com.linkedin.d2.balancer.simple;

import com.google.common.annotations.VisibleForTesting;
import com.linkedin.common.callback.Callback;
import com.linkedin.common.callback.Callbacks;
import com.linkedin.common.callback.FutureCallback;
import com.linkedin.common.callback.SuccessCallback;
import com.linkedin.common.util.None;
import com.linkedin.d2.DarkClusterConfigMap;
import com.linkedin.d2.balancer.KeyMapper;
import com.linkedin.d2.balancer.LoadBalancer;
import com.linkedin.d2.balancer.LoadBalancerClusterListener;
import com.linkedin.d2.balancer.LoadBalancerState;
import com.linkedin.d2.balancer.LoadBalancerState.LoadBalancerStateListenerCallback;
import com.linkedin.d2.balancer.LoadBalancerState.NullStateListenerCallback;
import com.linkedin.d2.balancer.LoadBalancerStateItem;
import com.linkedin.d2.balancer.ServiceUnavailableException;
import com.linkedin.d2.balancer.WarmUpService;
import com.linkedin.d2.balancer.clients.RewriteLoadBalancerClient;
import com.linkedin.d2.balancer.clients.TrackerClient;
import com.linkedin.d2.balancer.clusterfailout.FailoutConfig;
import com.linkedin.d2.balancer.clusterfailout.FailoutConfigProvider;
import com.linkedin.d2.balancer.clusterfailout.FailoutConfigProviderFactory;
import com.linkedin.d2.balancer.properties.ClusterProperties;
import com.linkedin.d2.balancer.properties.PartitionData;
import com.linkedin.d2.balancer.properties.ServiceProperties;
import com.linkedin.d2.balancer.properties.UriProperties;
import com.linkedin.d2.balancer.strategies.LoadBalancerStrategy;
import com.linkedin.d2.balancer.subsetting.SubsettingState;
import com.linkedin.d2.balancer.util.ClientFactoryProvider;
import com.linkedin.d2.balancer.util.ClusterInfoProvider;
import com.linkedin.d2.balancer.util.CustomAffinityRoutingURIProvider;
import com.linkedin.d2.balancer.util.D2ExecutorThreadFactory;
import com.linkedin.d2.balancer.util.HostOverrideList;
import com.linkedin.d2.balancer.util.HostToKeyMapper;
import com.linkedin.d2.balancer.util.KeysAndHosts;
import com.linkedin.d2.balancer.util.LoadBalancerUtil;
import com.linkedin.d2.balancer.util.MapKeyResult;
import com.linkedin.d2.balancer.util.hashing.HashFunction;
import com.linkedin.d2.balancer.util.hashing.HashRingProvider;
import com.linkedin.d2.balancer.util.hashing.Ring;
import com.linkedin.d2.balancer.util.partitions.DefaultPartitionAccessor;
import com.linkedin.d2.balancer.util.partitions.PartitionAccessException;
import com.linkedin.d2.balancer.util.partitions.PartitionAccessor;
import com.linkedin.d2.balancer.util.partitions.PartitionInfoProvider;
import com.linkedin.d2.discovery.event.PropertyEventThread.PropertyEventShutdownCallback;
import com.linkedin.d2.discovery.util.Stats;
import com.linkedin.r2.message.Request;
import com.linkedin.r2.message.RequestContext;
import com.linkedin.r2.transport.common.TransportClientFactory;
import com.linkedin.r2.transport.common.bridge.client.TransportClient;
import com.linkedin.r2.transport.http.client.TimeoutCallback;
import com.linkedin.r2.util.NamedThreadFactory;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.linkedin.d2.discovery.util.LogUtil.*;

public class SimpleLoadBalancer implements LoadBalancer, HashRingProvider, ClientFactoryProvider, PartitionInfoProvider, WarmUpService,
                                           ClusterInfoProvider
{
  private static final String HOST_OVERRIDE_LIST = "HOST_OVERRIDE_LIST";
  private static final Logger     _log =
                                           LoggerFactory.getLogger(SimpleLoadBalancer.class);
  private static final String     D2_SCHEME_NAME = "d2";

  private final LoadBalancerState _state;
  private final Stats _serviceUnavailableStats;
  private final Stats _serviceAvailableStats;
  private final Stats _serviceNotFoundStats; // service is not present in service discovery system
  private final Stats _clusterNotFoundStats; // cluster is not present in service discovery system
  private final long              _timeout;
  private final TimeUnit          _unit;
  private final ScheduledExecutorService _executor;
  private final Random            _random = new Random();
  private final FailoutConfigProvider _failoutConfigProvider;
  private final ExecutorService _d2CallbackExecutorService;

  public SimpleLoadBalancer(LoadBalancerState state, ScheduledExecutorService executorService)
  {
    this(state, new Stats(1000), new Stats(1000), 0, TimeUnit.SECONDS, executorService, null);
  }

  public SimpleLoadBalancer(LoadBalancerState state, long timeout, TimeUnit unit, ScheduledExecutorService executor)
  {
    this(state, new Stats(1000), new Stats(1000), timeout, unit, executor, null);
  }

  public SimpleLoadBalancer(LoadBalancerState state, long timeout, TimeUnit unit, ScheduledExecutorService executor,
                            FailoutConfigProviderFactory failoutConfigProviderFactory)
  {
    this(state, new Stats(1000), new Stats(1000), timeout, unit, executor, failoutConfigProviderFactory);
  }


  public SimpleLoadBalancer(LoadBalancerState state,
                            Stats serviceAvailableStats,
                            Stats serviceUnavailableStats,
                            long timeout,
                            TimeUnit unit,
                            ScheduledExecutorService executor,
                            FailoutConfigProviderFactory failoutConfigProviderFactory)
  {
    _state = state;
    _serviceUnavailableStats = serviceUnavailableStats;
    _serviceAvailableStats = serviceAvailableStats;
    _serviceNotFoundStats = new Stats(1000);
    _clusterNotFoundStats = new Stats(1000);
    _timeout = timeout;
    _unit = unit;
    _executor = executor;
    if (failoutConfigProviderFactory != null)
    {
      _failoutConfigProvider = failoutConfigProviderFactory.create(state);
      _log.debug("Created failoutConfigProvider.");
    }
    else
    {
      _failoutConfigProvider = null;
    }
    _d2CallbackExecutorService = Executors.newCachedThreadPool(new NamedThreadFactory("D2 Callback Executor"));
  }

  public Stats getServiceNotFoundStats()
  {
    return _serviceNotFoundStats;
  }

  public Stats getClusterNotFoundStats()
  {
    return _clusterNotFoundStats;
  }

  public Stats getServiceUnavailableStats()
  {
    return _serviceUnavailableStats;
  }

  public Stats getServiceAvailableStats()
  {
    return _serviceAvailableStats;
  }

  @Override
  public void start(Callback<None> callback)
  {
    _state.start(new Callback<None>()
    {
      @Override
      public void onError(Throwable e)
      {
        callback.onError(e);
      }

      @Override
      public void onSuccess(None result)
      {
        if (_failoutConfigProvider != null)
        {
          _failoutConfigProvider.start();
          _log.info("Started failoutConfigProvider.");
        }
        callback.onSuccess(result);
      }
    });

  }

  @Override
  public void shutdown(PropertyEventShutdownCallback shutdown)
  {
    _state.shutdown(() -> {
      if (_failoutConfigProvider != null)
      {
        _failoutConfigProvider.shutdown();
      }
      shutdown.done();
    });
  }

  /**
   * Given a Request, returns a TransportClient that can handle requests for the Request.
   * The callback is given a client that can be called to retrieve data for the URN.
   *
   * @param request
   *          A request whose URI is a URL of the format "d2://&gt;servicename&lt;/optional/path".
   * @param requestContext context for this request
   * @throws ServiceUnavailableException
   *           If the load balancer can't figure out how to reach a service for the given
   *           URN, an ServiceUnavailableException will be thrown.
   */
  @Override
  public void getClient(Request request, RequestContext requestContext, Callback<TransportClient> clientCallback)
  {
    URI uri = request.getURI();
    debug(_log, "get client for uri: ", uri);

    if (!D2_SCHEME_NAME.equalsIgnoreCase(uri.getScheme()))
    {
      throw new IllegalArgumentException("Unsupported scheme in URI " + uri);
    }

    // get the service for this uri
    String extractedServiceName = LoadBalancerUtil.getServiceNameFromUri(uri);

    SuccessCallback<ServiceProperties> servicePropertiesSuccessCallback = service -> {
      String serviceName = service.getServiceName();
      String clusterName = service.getClusterName();
      try
      {
        ClusterProperties cluster = getClusterProperties(serviceName, clusterName);

        // Check if we want to override the service URL and bypass choosing among the existing
        // tracker clients. This is useful when the service we want is not announcing itself to
        // the cluster, ie a private service for a set of clients. This mechanism is deprecated;
        // use host override list instead.
        @SuppressWarnings("deprecation")
        URI targetService = LoadBalancerUtil.TargetHints.getRequestContextTargetService(requestContext);

        // Checks if we have a host override list provided in the request context. If present,
        // get the override URI available override for the current cluster and service names.
        HostOverrideList overrides = (HostOverrideList) requestContext.getLocalAttr(HOST_OVERRIDE_LIST);
        URI override = overrides == null ? null : overrides.getOverride(clusterName, serviceName);

        if (targetService == null && override == null)
        {
          LoadBalancerStateItem<UriProperties> uriItem = getUriItem(serviceName, clusterName, cluster);
          UriProperties uris = uriItem.getProperty();

          List<LoadBalancerState.SchemeStrategyPair> orderedStrategies =
              _state.getStrategiesForService(serviceName, service.getPrioritizedSchemes());

          TrackerClient trackerClient = null;

          // Use client provided by CustomURIAffinityRoutingProvider when it's enabled
          CustomAffinityRoutingURIProvider customAffinityRoutingURIProvider =
              new SafeCustomAffinityRoutingURIProviderDecorator((CustomAffinityRoutingURIProvider)
                  requestContext.getLocalAttr(CustomAffinityRoutingURIProvider.CUSTOM_AFFINITY_ROUTING_URI_PROVIDER));

          boolean enableCustomAffinityRouting = isCustomAffinityRoutingEnabled(requestContext, customAffinityRoutingURIProvider);
          if (enableCustomAffinityRouting)
          {
            trackerClient = customAffinityRoutingURIProvider.getTargetHostURI(clusterName)
                .map(targetHost -> _state.getClient(serviceName, targetHost))
                .orElse(null);
          }

          if (trackerClient == null)
          {
            trackerClient =
                chooseTrackerClient(request, requestContext, serviceName, clusterName, cluster, uriItem, uris,
                    orderedStrategies, service);

            // set host URI for the cluster. with that next time, for the same inbound request, if downstream request is
            // made to same cluster and custom affinity routing is enabled, then it will go to same box.
            if (enableCustomAffinityRouting)
            {
              customAffinityRoutingURIProvider.setTargetHostURI(clusterName, trackerClient.getUri());
            }
          }

          String clusterAndServiceUriString = trackerClient.getUri() + service.getPath();
          _serviceAvailableStats.inc();
          clientCallback.onSuccess(new RewriteLoadBalancerClient(serviceName,
              URI.create(clusterAndServiceUriString),
              trackerClient));
        }
        else
        {
          URI target = override == null ? targetService : URI.create(override + service.getPath());
          if (targetService != null && override != null)
          {
            _log.warn("Both TargetHints and HostOverrideList are found. HostOverList will take precedence %s.", target);
          }

          if (_log.isDebugEnabled())
          {
            _log.debug("Rewrite URI as specified in the TargetHints/HostOverrideList {} for cluster {} and service {}.",
                target, clusterName, serviceName);
          }
          TransportClient transportClient = _state.getClient(serviceName, target.getScheme());
          if (transportClient == null)
          {
            throw new ServiceUnavailableException(serviceName, String.format(
                "PEGA_1001. Cannot find transportClient for service %s and scheme %s with URI specified in"
                    + "TargetHints/HostOverrideList %s", serviceName, target.getScheme(), target));
          }

          clientCallback.onSuccess(new RewriteLoadBalancerClient(serviceName, target, transportClient));
        }
      }
      catch (ServiceUnavailableException e)
      {
        clientCallback.onError(e);
      }
    };
    listenToServiceAndCluster(extractedServiceName, Callbacks.handle(service -> {
      if (D2ExecutorThreadFactory.isFromExecutor()) {
        /*
         * When D2 gets service and cluster data from the backend, it's thread will try to complete all the queued
         * callbacks, including those of the R2 calls. However, this isn't ideal and can also result in deadlocks.
         * At this layer, tracing and invocation context are handled as part of the request context, so executors that
         * don't propagate IC can also be safely used.
         */
        _d2CallbackExecutorService.execute(() -> servicePropertiesSuccessCallback.onSuccess(service));
      } else {
        servicePropertiesSuccessCallback.onSuccess(service);
      }
    }, clientCallback));
  }

  private boolean isCustomAffinityRoutingEnabled(RequestContext requestContext,
      @Nullable CustomAffinityRoutingURIProvider affinityRoutingURIProvider) {
    return affinityRoutingURIProvider != null && affinityRoutingURIProvider.isEnabled()
        && (KeyMapper.TargetHostHints.getRequestContextTargetHost(requestContext) == null);
  }

  @Override
  public <K> MapKeyResult<Ring<URI>, K> getRings(URI serviceUri, Iterable<K> keys) throws ServiceUnavailableException
  {
    ServiceProperties service = listenToServiceAndCluster(serviceUri);
    String serviceName = service.getServiceName();
    String clusterName = service.getClusterName();
    ClusterProperties cluster = getClusterProperties(serviceName, clusterName);
    LoadBalancerStateItem<UriProperties> uriItem = getUriItem(serviceName, clusterName, cluster);
    UriProperties uris = uriItem.getProperty();

    List<LoadBalancerState.SchemeStrategyPair> orderedStrategies =
        _state.getStrategiesForService(serviceName, service.getPrioritizedSchemes());

    if (! orderedStrategies.isEmpty())
    {
      PartitionAccessor accessor = getPartitionAccessor(serviceName, clusterName);

      // first distribute keys to partitions
      Map<Integer, Set<K>> partitionSet = new HashMap<>();
      List<MapKeyResult.UnmappedKey<K>> unmappedKeys = new ArrayList<>();
      for (final K key : keys)
      {
        int partitionId;
        try
        {
          partitionId = accessor.getPartitionId(key.toString());
        }
        catch (PartitionAccessException e)
        {
          unmappedKeys.add(new MapKeyResult.UnmappedKey<>(key, MapKeyResult.ErrorType.FAIL_TO_FIND_PARTITION));
          continue;
        }

        Set<K> set = partitionSet.computeIfAbsent(partitionId, k -> new HashSet<>());
        set.add(key);
      }

      // then we find the ring for each partition and create a map of Ring<URI> to Set<K>
      final Map<Ring<URI>, Collection<K>> ringMap = new HashMap<>(partitionSet.size() * 2);
      for (Map.Entry<Integer, Set<K>> entry : partitionSet.entrySet())
      {
        int partitionId = entry.getKey();
        Ring<URI> ring = null;
        for (LoadBalancerState.SchemeStrategyPair pair : orderedStrategies)
        {
          TrackerClientSubsetItem subsetItem = getPotentialClients(serviceName, service,
              cluster, uris, pair.getScheme(), partitionId, uriItem.getVersion());
          ring = pair.getStrategy().getRing(uriItem.getVersion(), partitionId, subsetItem.getWeightedSubset(),
              subsetItem.shouldForceUpdate());

          if (!ring.isEmpty())
          {
            // don't fallback to the next strategy if there are already hosts in the current one
            break;
          }
        }
        // make sure the same ring is not used in other partition
        ringMap.put(ring, entry.getValue());
      }

      return new MapKeyResult<>(ringMap, unmappedKeys);
    }
    else
    {
      throw new ServiceUnavailableException(serviceName, "PEGA_1002. Unable to find a load balancer strategy. " +
        "Server Schemes: [" + String.join(", ", service.getPrioritizedSchemes()) + ']');
    }
  }

  @Override
  public TransportClientFactory getClientFactory(String scheme)
  {
    return ((ClientFactoryProvider)_state).getClientFactory(scheme);
  }

  public Map<Integer, Ring<URI>> getRings(URI serviceUri) throws ServiceUnavailableException
  {
    ServiceProperties service = listenToServiceAndCluster(serviceUri);
    String serviceName = service.getServiceName();
    String clusterName = service.getClusterName();
    ClusterProperties cluster = getClusterProperties(serviceName, clusterName);

    LoadBalancerStateItem<UriProperties> uriItem = getUriItem(serviceName, clusterName, cluster);
    UriProperties uris = uriItem.getProperty();

    List<LoadBalancerState.SchemeStrategyPair> orderedStrategies =
      _state.getStrategiesForService(serviceName, service.getPrioritizedSchemes());

    if (! orderedStrategies.isEmpty())
    {
      final PartitionAccessor accessor = getPartitionAccessor(serviceName, clusterName);
      int maxPartitionId = accessor.getMaxPartitionId();
      Map<Integer, Ring<URI>> ringMap = new HashMap<>((maxPartitionId + 1) * 2);

      for (int partitionId = 0; partitionId <= maxPartitionId; partitionId++)
      {
        for (LoadBalancerState.SchemeStrategyPair pair : orderedStrategies)
        {
          TrackerClientSubsetItem subsetItem = getPotentialClients(serviceName, service, cluster, uris,
              pair.getScheme(), partitionId, uriItem.getVersion());
          Ring<URI> ring = pair.getStrategy().getRing(uriItem.getVersion(), partitionId, subsetItem.getWeightedSubset(),
              subsetItem.shouldForceUpdate());
          // ring will never be null; it can be empty
          ringMap.put(partitionId, ring);

          if (!ring.isEmpty())
          {
            // don't fallback to the next strategy if there are already hosts in the current one
            break;
          }
        }
      }
      return ringMap;
    }
    else
    {
      throw new ServiceUnavailableException(serviceName, "PEGA_1003. Unable to find a load balancer strategy" +
        "Server Schemes: [" + String.join(", ", service.getPrioritizedSchemes()) + ']');
    }
  }

  @Override
  public HashFunction<Request> getRequestHashFunction(String serviceName) throws ServiceUnavailableException
  {
    ServiceProperties service = listenToServiceAndCluster(serviceName);
    List<LoadBalancerState.SchemeStrategyPair> orderedStrategies =
        _state.getStrategiesForService(serviceName, service.getPrioritizedSchemes());
    if (!orderedStrategies.isEmpty())
    {
      return orderedStrategies.get(0).getStrategy().getHashFunction();
    }
    else
    {
      throw new ServiceUnavailableException(serviceName, "PEGA_1017. Unable to find a load balancer strategy" +
        "Server Schemes: [" + String.join(", ", service.getPrioritizedSchemes()) + ']');
    }
  }

  @VisibleForTesting
  void listenToServiceAndCluster(String serviceName, Callback<ServiceProperties> callback)
  {

    boolean waitForUpdatedValue = _timeout > 0;

    // if timeout is 0, we must not add the timeout callback, otherwise it would trigger immediately
    if (waitForUpdatedValue)
    {
      Callback<ServiceProperties> finalCallback = callback;
      try
      {
        callback = new TimeoutCallback<>(_executor, _timeout, _unit, new Callback<ServiceProperties>()
        {
          @Override
          public void onError(Throwable e)
          {
            if (e instanceof TimeoutException)
            {
              // if timed out, should try to fetch the service properties from the cache
              handleTimeoutFromGetServiceProperties(serviceName, finalCallback);
            }
            else
            {
              finalCallback.onError(new ServiceUnavailableException(serviceName, "PEGA_1004. " + e.getMessage(), e));
            }
          }

          @Override
          public void onSuccess(ServiceProperties result)
          {
            finalCallback.onSuccess(result);
          }
        }, "Timeout while fetching service");
      }
      catch (RejectedExecutionException e)
      {
        _log.debug("Executor rejected new tasks. It has shut down or its queue size has reached max limit");
      }
    }
    listenToServiceAndCluster(serviceName, waitForUpdatedValue, callback);
  }

  private ServiceProperties listenToServiceAndCluster(URI uri)
          throws ServiceUnavailableException
  {
    if (!D2_SCHEME_NAME.equalsIgnoreCase(uri.getScheme()))
    {
      throw new IllegalArgumentException("Unsupported scheme in URI " + uri);
    }

    // get the service for this uri
    String serviceName = LoadBalancerUtil.getServiceNameFromUri(uri);

    return listenToServiceAndCluster(serviceName);
  }

  private ServiceProperties listenToServiceAndCluster(String serviceName)
    throws ServiceUnavailableException
  {
    FutureCallback<ServiceProperties> servicePropertiesFutureCallback = new FutureCallback<>();
    boolean waitForUpdatedValue = _timeout > 0;
    listenToServiceAndCluster(serviceName, waitForUpdatedValue, servicePropertiesFutureCallback);
    try
    {
      return servicePropertiesFutureCallback.get(_timeout, _unit);
    }
    catch (TimeoutException e)
    {
      ServiceProperties serviceProperties = getServicePropertiesFromCache(serviceName, servicePropertiesFutureCallback);
      if (serviceProperties != null)
      {
        return serviceProperties;
      }
      throw new ServiceUnavailableException(serviceName, "PEGA_1005. Timeout occurred while fetching property. Timeout:" + _timeout, e);
    }
    catch (Exception e)
    {
      throw new ServiceUnavailableException(serviceName, "PEGA_1006. Exception while fetching property. Message:" + e.getMessage(), e);
    }
  }

  private void listenToServiceAndCluster(String serviceName, boolean waitForUpdatedValue, Callback<ServiceProperties> callback)
  {
    getLoadBalancedServiceProperties(serviceName, waitForUpdatedValue, Callbacks.handle(service ->
      {
        String clusterName = service.getClusterName();
        listenToCluster(clusterName, waitForUpdatedValue, (type, name) -> callback.onSuccess(service));
      }, callback));
  }

  public void listenToCluster(String clusterName, boolean waitForUpdatedValue, LoadBalancerStateListenerCallback callback)
  {
    if (waitForUpdatedValue)
    {
      _state.listenToCluster(clusterName, callback);
    }
    else
    {
      _state.listenToCluster(clusterName, new NullStateListenerCallback());
      callback.done(0, null);
    }
  }

  @Override
  public void warmUpService(String serviceName, Callback<None> callback)
  {
    listenToServiceAndCluster(serviceName, true,
      Callbacks.handle(service -> callback.onSuccess(None.none()), callback));
  }

  private LoadBalancerStateItem<UriProperties> getUriItem(String serviceName,
                                                          String clusterName,
                                                          ClusterProperties cluster)
          throws ServiceUnavailableException
  {
    // get the uris for this uri
    LoadBalancerStateItem<UriProperties> uriItem = _state.getUriProperties(clusterName);

    if (uriItem == null || uriItem.getProperty() == null)
    {
      warn(_log, "unable to find uris: ", clusterName);

      die(serviceName, "PEGA_1007. no uri properties in lb state. Check your service being announced correctly to ZK");
    }

    debug(_log, "got uris: ", cluster);
    return uriItem;
  }

  private ClusterProperties getClusterProperties(String serviceName,
                                                 String clusterName)
          throws ServiceUnavailableException
  {
    LoadBalancerStateItem<ClusterProperties> clusterItem =
        _state.getClusterProperties(clusterName);

    if (clusterItem == null || clusterItem.getProperty() == null)
    {
      warn(_log, "unable to find cluster: ", clusterName);

      die(serviceName, "PEGA_1008. no cluster properties in lb state for cluster: " + clusterName);
    }

    return clusterItem.getProperty();
  }

  /**
   * If given a collection of keys, the method will maps keys to partitions and
   * return the servers that belongs to that partition up to limitHostPerPartition.
   *
   * If no keys are specified, the method will return hosts in all partitions
   *
   * @param serviceUri for example d2://articles
   * @param keys all the keys we want to find the partition for
   * @param limitHostPerPartition the number of hosts that we should return for this partition. Must be larger than 0.
   * @param hash this will be used to create Iterator for the hosts in the hash ring
   * @return Number of hosts in requested partitions. See {@link com.linkedin.d2.balancer.util.HostToKeyMapper} for more details.
   * @throws ServiceUnavailableException
   */
  @Override
  public <K> HostToKeyMapper<K> getPartitionInformation(URI serviceUri, Collection<K> keys,
                                                        int limitHostPerPartition,
                                                        int hash)
          throws ServiceUnavailableException
  {
    if (limitHostPerPartition <= 0)
    {
      throw new IllegalArgumentException("limitHostPartition cannot be 0 or less");
    }
    ServiceProperties service = listenToServiceAndCluster(serviceUri);
    String serviceName = service.getServiceName();
    String clusterName = service.getClusterName();
    ClusterProperties cluster = getClusterProperties(serviceName, clusterName);

    LoadBalancerStateItem<UriProperties> uriItem = getUriItem(serviceName, clusterName, cluster);
    UriProperties uris = uriItem.getProperty();

    List<LoadBalancerState.SchemeStrategyPair> orderedStrategies =
            _state.getStrategiesForService(serviceName, service.getPrioritizedSchemes());
    Map<Integer, Integer> partitionWithoutEnoughHost = new HashMap<>();

    if (! orderedStrategies.isEmpty())
    {
      // get the partitionId -> keys mapping
      final PartitionAccessor accessor = getPartitionAccessor(serviceName, clusterName);
      int maxPartitionId = accessor.getMaxPartitionId();
      List<K> unmappedKeys = new ArrayList<>();
      Map<Integer, Set<K>> partitionSet = getPartitionSet(keys, accessor, unmappedKeys);

      // get the partitionId -> host URIs list
      Map<Integer, KeysAndHosts<K>> partitionDataMap = new HashMap<>();
      for (Integer partitionId : partitionSet.keySet())
      {
        for (LoadBalancerState.SchemeStrategyPair pair : orderedStrategies)
        {
          TrackerClientSubsetItem subsetItem = getPotentialClients(serviceName, service, cluster, uris,
              pair.getScheme(), partitionId, uriItem.getVersion());
          Map<URI, TrackerClient> trackerClients = subsetItem.getWeightedSubset();
          int size = Math.min(trackerClients.size(), limitHostPerPartition);
          List<URI> rankedUri = new ArrayList<>(size);

          Ring<URI> ring = pair.getStrategy().getRing(uriItem.getVersion(), partitionId, trackerClients,
              subsetItem.shouldForceUpdate());
          Iterator<URI> iterator = ring.getIterator(hash);

          while (iterator.hasNext() && rankedUri.size() < size)
          {
            URI uri = iterator.next();
            if (!rankedUri.contains(uri))
            {
              rankedUri.add(uri);
            }
          }
          if (rankedUri.size() < limitHostPerPartition)
          {
            partitionWithoutEnoughHost.put(partitionId, limitHostPerPartition - rankedUri.size());
          }

          KeysAndHosts<K> keysAndHosts = new KeysAndHosts<>(partitionSet.get(partitionId), rankedUri);
          partitionDataMap.put(partitionId, keysAndHosts);
          if (!rankedUri.isEmpty())
          {
            // don't go to the next strategy if there are already hosts in the current one
            break;
          }
        }
      }

      return new HostToKeyMapper<>(unmappedKeys, partitionDataMap, limitHostPerPartition, maxPartitionId + 1, partitionWithoutEnoughHost);
    }
    else
    {
      throw new ServiceUnavailableException(serviceName, "PEGA_1009. Unable to find a load balancer strategy" +
        "Server Schemes: [" + String.join(", ", service.getPrioritizedSchemes()) + ']');
    }
  }

  private <K> Map<Integer, Set<K>> getPartitionSet(Collection<K> keys, PartitionAccessor accessor, Collection<K> unmappedKeys)
  {
    Map<Integer, Set<K>> partitionSet = new TreeMap<>();
    if (keys == null)
    {
      for (int i = 0; i <= accessor.getMaxPartitionId(); i++)
      {
        partitionSet.put(i, new HashSet<>());
      }
    }
    else
    {
      for (final K key : keys)
      {
        int partitionId;
        try
        {
          partitionId = accessor.getPartitionId(key.toString());
        }
        catch (PartitionAccessException e)
        {
          unmappedKeys.add(key);
          continue;
        }

        Set<K> set = partitionSet.get(partitionId);
        if (set == null)
        {
          set = new HashSet<>();
          partitionSet.put(partitionId, set);
        }
        set.add(key);
      }
    }
    return partitionSet;
  }

  @Override
  public PartitionAccessor getPartitionAccessor(String serviceName)
          throws ServiceUnavailableException
  {
    ServiceProperties service = listenToServiceAndCluster(serviceName);
    String clusterName = service.getClusterName();
    return getPartitionAccessor(serviceName, clusterName);
  }

  private PartitionAccessor getPartitionAccessor(String serviceName, String clusterName)
      throws ServiceUnavailableException
  {
    LoadBalancerStateItem<PartitionAccessor> partitionAccessorItem =
        _state.getPartitionAccessor(clusterName);
    if (partitionAccessorItem == null || partitionAccessorItem.getProperty() == null)
    {
      warn(_log, "unable to find partition accessor for cluster: ", clusterName);
      die(serviceName, "PEGA_1010. No partition accessor available for cluster: " + clusterName);
    }

    return partitionAccessorItem.getProperty();
  }

  @Override
  public void getLoadBalancedServiceProperties(String serviceName, Callback<ServiceProperties> callback)
  {
    boolean waitForUpdatedValue = _timeout > 0;
    // if timeout is 0, we must not add the timeout callback, otherwise it would trigger immediately
    if (waitForUpdatedValue)
    {
      Callback<ServiceProperties> finalCallback = callback;
      try
      {
        callback = new TimeoutCallback<>(_executor, _timeout, _unit, new Callback<ServiceProperties>()
        {
          @Override
          public void onError(Throwable e)
          {
            if (e instanceof TimeoutException)
            {
              handleTimeoutFromGetServiceProperties(serviceName, finalCallback);
            }
            else
            {
              _serviceNotFoundStats.inc();
              finalCallback.onError(new ServiceUnavailableException(serviceName, "PEGA_1011. " + e.getMessage(), e));
            }
          }

          @Override
          public void onSuccess(ServiceProperties result)
          {
            finalCallback.onSuccess(result);
          }
        }, "Timeout while fetching service");
      }
      catch (RejectedExecutionException e)
      {
        _log.debug("Executor rejected new tasks. It has shut down or its queue size has reached max limit");
      }
    }
    getLoadBalancedServiceProperties(serviceName, waitForUpdatedValue, callback);
  }

  public void getLoadBalancedServiceProperties(String serviceName, boolean waitForUpdatedValue, Callback<ServiceProperties> servicePropertiesCallback)
  {
    Runnable callback = () ->
    {
      ServiceProperties serviceProperties = getServicePropertiesFromCache(serviceName, servicePropertiesCallback);
      if (serviceProperties != null)
      {
        servicePropertiesCallback.onSuccess(serviceProperties);
      }
    };

    if (waitForUpdatedValue)
    {
      _state.listenToService(serviceName, (type, name) -> callback.run());
    }
    else
    {
      _log.debug("No timeout for service {}", serviceName);
      _state.listenToService(serviceName, new NullStateListenerCallback());
      callback.run();
    }
  }

  public void handleTimeoutFromGetServiceProperties(String serviceName,
      Callback<ServiceProperties> servicePropertiesCallback)
  {
    ServiceProperties properties = getServicePropertiesFromCache(serviceName, servicePropertiesCallback);
    if (properties != null)
    {
      _log.info("getServiceProperties for {} timed out, used cached value instead.", serviceName);
      servicePropertiesCallback.onSuccess(properties);
    }
    else
    {
      _log.warn("getServiceProperties for {} timed out, but no value in cache!", serviceName);
    }
  }

  public ServiceProperties getServicePropertiesFromCache(String serviceName,
      Callback<ServiceProperties> servicePropertiesCallback)
  {
    LoadBalancerStateItem<ServiceProperties> serviceItem = _state.getServiceProperties(serviceName);

    if (serviceItem == null || serviceItem.getProperty() == null)
    {
      warn(_log, "unable to find service: ", serviceName);
      _serviceNotFoundStats.inc();
      die(servicePropertiesCallback, serviceName, "PEGA_1012. no service properties in lb state");
      return null;
    }

    debug(_log, "got service: ", serviceItem);
    return serviceItem.getProperty();
  }

  @Override
  @VisibleForTesting
  public void getLoadBalancedClusterAndUriProperties(String clusterName,
      Callback<Pair<ClusterProperties, UriProperties>> callback)
  {
    boolean waitForUpdatedValue = _timeout > 0;
    // if timeout is 0, we must not add the timeout callback, otherwise it would trigger immediately
    if (waitForUpdatedValue)
    {
      Callback<Pair<ClusterProperties, UriProperties>> finalCallback = callback;
      try
      {
        callback =
            new TimeoutCallback<>(_executor, _timeout, _unit, new Callback<Pair<ClusterProperties, UriProperties>>()
            {
              @Override
              public void onError(Throwable e)
              {
                if (e instanceof TimeoutException)
                {
                  handleTimeoutFromGetClusterAndUriProperties(clusterName, finalCallback);
                }
                else
                {
                  finalCallback.onError(
                      new ServiceUnavailableException(clusterName, "PEGA_1011. " + e.getMessage(), e));
                }
              }

              @Override
              public void onSuccess(Pair<ClusterProperties, UriProperties> result)
              {
                finalCallback.onSuccess(result);
              }
            }, "Timeout while fetching cluster");
      }
      catch (RejectedExecutionException e)
      {
        _log.debug("Executor rejected new tasks. It has shut down or its queue size has reached max limit");
      }
    }
    getLoadBalancedClusterAndUriProperties(clusterName, waitForUpdatedValue, callback);
  }

  public void getLoadBalancedClusterAndUriProperties(String clusterName, boolean waitForUpdatedValue,
      Callback<Pair<ClusterProperties, UriProperties>> pairCallback)
  {
    Runnable callback = () ->
    {
      Pair<ClusterProperties, UriProperties> pair = getClusterAndUriPropertiesFromCache(clusterName, pairCallback);
      if (pair != null)
      {
        pairCallback.onSuccess(pair);
      }
    };

    if (waitForUpdatedValue)
    {
      _state.listenToCluster(clusterName, (type, name) -> callback.run());
    }
    else
    {
      _log.debug("No timeout for cluster {}", clusterName);
      _state.listenToCluster(clusterName, new NullStateListenerCallback());
      callback.run();
    }
  }

  @VisibleForTesting
  public void handleTimeoutFromGetClusterAndUriProperties(String clusterName,
      Callback<Pair<ClusterProperties, UriProperties>> clusterAndUriPropertiesCallback)
  {
    Pair<ClusterProperties, UriProperties> pair =
        getClusterAndUriPropertiesFromCache(clusterName, clusterAndUriPropertiesCallback);
    if (pair != null)
    {
      _log.info("getClusterAndUriProperties for {} timed out, used cached value instead.", clusterName);
      clusterAndUriPropertiesCallback.onSuccess(pair);
    }
    else
    {
      _log.warn("getClusterAndUriProperties for {} timed out, but no value in cache!", clusterName);
    }
  }

  /**
   * Gets the cluster and uri properties from the cache
   * If the properties are not found, call the callback with an error.
   */
  private Pair<ClusterProperties, UriProperties> getClusterAndUriPropertiesFromCache(String clusterName,
      Callback<Pair<ClusterProperties, UriProperties>> clusterPropertiesCallback)
  {
    LoadBalancerStateItem<ClusterProperties> clusterItem = _state.getClusterProperties(clusterName);

    LoadBalancerStateItem<UriProperties> uriItem = _state.getUriProperties(clusterName);

    if (clusterItem == null || clusterItem.getProperty() == null || uriItem == null || uriItem.getProperty() == null)
    {
      warn(_log, "unable to find cluster: ", clusterName);

      _clusterNotFoundStats.inc();
      die(clusterPropertiesCallback, clusterName, "PEGA_1012. no cluster properties in lb state");
      return null;
    }
    return Pair.of(clusterItem.getProperty(), uriItem.getProperty());
  }

  // supports partitioning
  private TrackerClientSubsetItem getPotentialClients(String serviceName,
                                                  ServiceProperties serviceProperties,
                                                  ClusterProperties clusterProperties,
                                                  UriProperties uris,
                                                  String scheme,
                                                  int partitionId,
                                                  long version)
  {
    Set<URI> possibleUris = uris.getUriBySchemeAndPartition(scheme, partitionId);
    Map<URI, TrackerClient> clientsToBalance = Collections.emptyMap();
    boolean shouldForceUpdate = false;

    if (possibleUris != null)
    {
      if (!serviceProperties.isEnableClusterSubsetting())
      {
        clientsToBalance = getPotentialClientsNotSubsetting(serviceName, serviceProperties, clusterProperties, possibleUris);
      }
      else
      {
        Map<URI, Double> weightedUris = new HashMap<>(possibleUris.size());
        for (URI possibleUri : possibleUris)
        {
           weightedUris.put(possibleUri, uris.getPartitionDataMap(possibleUri).get(partitionId).getWeight());
        }

        SubsettingState.SubsetItem subsetItem = _state.getClientsSubset(serviceName,
            serviceProperties.getMinClusterSubsetSize(), partitionId, weightedUris, version);

        clientsToBalance = getPotentialClientsSubsetting(serviceName, serviceProperties,
            clusterProperties, possibleUris, partitionId, subsetItem);

        shouldForceUpdate = subsetItem.shouldForceUpdate();
      }
    }

    debug(_log,
        "got clients to load balance for ",
        serviceName,
        ": ",
        clientsToBalance);

    if (clientsToBalance.isEmpty())
    {
      info(_log, "Can not find a host for service: ", serviceName, ", scheme: ", scheme, ", partition: ", partitionId);
    }
    return new TrackerClientSubsetItem(shouldForceUpdate, clientsToBalance);
  }

  private Map<URI, TrackerClient> getPotentialClientsSubsetting(String serviceName,
                                                  ServiceProperties serviceProperties,
                                                  ClusterProperties clusterProperties,
                                                  Set<URI> possibleUris,
                                                  int partitionId,
                                                  SubsettingState.SubsetItem subsetItem)
  {
    Map<URI, Double> weightedSubset = subsetItem.getWeightedUriSubset();;
    Set<URI> doNotSlowStartUris = subsetItem.getDoNotSlowStartUris();

    return getPotentialClients(serviceProperties, clusterProperties, possibleUris,
        possibleUri ->
        {
          // ignore if URI is not in the subset
          if (weightedSubset.containsKey(possibleUri))
          {
            TrackerClient possibleTrackerClient = _state.getClient(serviceName, possibleUri);

            if (possibleTrackerClient != null)
            {
              if (doNotSlowStartUris.contains(possibleUri))
              {
                possibleTrackerClient.setDoNotSlowStart(true);
              }
              // Only update subset weight if the subset item is a weighted subset
              if (subsetItem.isWeightedSubset())
              {
                possibleTrackerClient.setSubsetWeight(partitionId, weightedSubset.get(possibleUri));
              }
              return possibleTrackerClient;
            }
          }
          return null;
        });
  }

  private Map<URI, TrackerClient> getPotentialClientsNotSubsetting(String serviceName,
                                                               ServiceProperties serviceProperties,
                                                               ClusterProperties clusterProperties,
                                                               Set<URI> possibleUris) {
    return getPotentialClients(serviceProperties, clusterProperties, possibleUris,
        possibleUri -> _state.getClient(serviceName, possibleUri));
  }

  private Map<URI, TrackerClient> getPotentialClients(ServiceProperties serviceProperties,
                                                  ClusterProperties clusterProperties,
                                                  Set<URI> possibleUris,
                                                  Function<URI, TrackerClient> trackerClientFinder)
  {
    Map<URI, TrackerClient> clientsToLoadBalance = new HashMap<>(possibleUris.size());
    for (URI possibleUri : possibleUris)
    {
      // don't pay attention to this uri if it's banned
      if (!serviceProperties.isBanned(possibleUri) && !clusterProperties.isBanned(possibleUri))
      {
        TrackerClient trackerClient = trackerClientFinder.apply(possibleUri);
        if (trackerClient != null)
        {
          clientsToLoadBalance.put(possibleUri, trackerClient);
        }
      }
      else
      {
        warn(_log, "skipping banned uri: ", possibleUri);
      }
    }

    return clientsToLoadBalance;
  }

  private TrackerClient chooseTrackerClient(Request request, RequestContext requestContext,
                                            String serviceName, String clusterName,
                                            ClusterProperties cluster,
                                            LoadBalancerStateItem<UriProperties> uriItem,
                                            UriProperties uris,
                                            List<LoadBalancerState.SchemeStrategyPair> orderedStrategies,
                                            ServiceProperties serviceProperties)
          throws ServiceUnavailableException
  {
    // now try and find a tracker client for the uri
    TrackerClient trackerClient = null;
    URI targetHost = KeyMapper.TargetHostHints.getRequestContextTargetHost(requestContext);
    int partitionId = -1;
    URI requestUri = request.getURI();

    if (targetHost == null)
    {
      PartitionAccessor accessor = getPartitionAccessor(serviceName, clusterName);
      try
      {
        partitionId = accessor.getPartitionId(requestUri);
      }
      catch (PartitionAccessException e)
      {
        debug(_log,
            "PEGA_1013. Mapped URI to default partition as there was error in finding the partition for URI: "
                + requestUri + ", in cluster: " + clusterName + ", " + e.getMessage());
        partitionId = DefaultPartitionAccessor.DEFAULT_PARTITION_ID;
      }
    }
    else
    {
      // This is the case of scatter/gather or search, where the target host may be chosen to be responsible for
      // more than one partitions (The target host was picked from a consistent hash ring, so load balancing is already in effect).

      // we randomly pick one partition to check for the call dropping
      // This is done for two reasons:
      // 1. Currently there is no way to know for which subset of partitions the target host is chosen for
      //    if it is serving more than one partitions. This can be added, but it requires the change of public interfaces (KeyMapper) so that
      //    more hints can be added to the request context for the concerned the partitions
      // 2. More importantly, there is no good way to check for call dropping even if the above problem is solved.
      //    For example, if a target host is chosen for partition 1, 5, 7, with call drop rates of 0, 0.2, 0.4 respectively
      //    A reasonable way to proceed would be use the highest drop rate and do the check once for the target host,
      //    but currently the check can only be done for each partition and only with boolean result (no access to drop rate)

      // The partition to check is picked at random to be conservative.
      // E.g. in the above example, we don't want to always use the drop rate of partition 1.

      Map<Integer, PartitionData> partitionDataMap = uris.getPartitionDataMap(targetHost);
      if (partitionDataMap == null || partitionDataMap.isEmpty())
      {
        die(serviceName, "PEGA_1014. There is no partition data for server host: " + targetHost + ". URI: " + requestUri);
      }

      Set<Integer> partitions = partitionDataMap.keySet();
      Iterator<Integer> iterator = partitions.iterator();
      int index = _random.nextInt(partitions.size());
      for (int i = 0; i <= index; i++)
      {
        partitionId = iterator.next();
      }
    }

    Map<URI, TrackerClient> clientsToLoadBalance = null;

    for (LoadBalancerState.SchemeStrategyPair pair : orderedStrategies)
    {
      LoadBalancerStrategy strategy = pair.getStrategy();
      String scheme = pair.getScheme();

      TrackerClientSubsetItem subsetItem = getPotentialClients(serviceName, serviceProperties, cluster,
          uris, scheme, partitionId, uriItem.getVersion());
      clientsToLoadBalance = subsetItem.getWeightedSubset();

      trackerClient =
          strategy.getTrackerClient(request, requestContext, uriItem.getVersion(), partitionId, clientsToLoadBalance,
              subsetItem.shouldForceUpdate());

      debug(_log,
            "load balancer strategy for ",
            serviceName,
            " returned: ",
            trackerClient);

      // break as soon as we find an available cluster client
      if (trackerClient != null)
      {
        break;
      }
    }

    if (trackerClient == null)
    {
      if (clientsToLoadBalance == null || clientsToLoadBalance.isEmpty())
      {
        String requestedSchemes = orderedStrategies.stream()
          .map(LoadBalancerState.SchemeStrategyPair::getScheme).collect(Collectors.joining(","));

        die(serviceName, "PEGA_1015. Service: " + serviceName + " unable to find a host to route the request"
          + " in partition: " + partitionId + " cluster: " + clusterName + " scheme: [" + requestedSchemes + "]," +
          " total hosts in cluster: " + uris.Uris().size() + "."
          + " Check what cluster and scheme your servers are announcing to.");
      }
      else
      {
        die(serviceName, "PEGA_1016. Service: " + serviceName + " is in a bad state (high latency/high error). "
            + "Dropping request. Cluster: " + clusterName + ", partitionId:" + partitionId
          + " (choosable: " + clientsToLoadBalance.size() + " hosts, total in cluster: " + uris.Uris().size() + ")");
      }
    }

    return trackerClient;
  }

  private void die(String serviceName, String message) throws ServiceUnavailableException
  {
    _serviceUnavailableStats.inc();
    throw new ServiceUnavailableException(serviceName, message);
  }

  private void die(Callback<?> callback, String serviceName, String message)
  {
    _serviceUnavailableStats.inc();
    callback.onError(new ServiceUnavailableException(serviceName, message));
  }

  @Override
  public int getClusterCount(String clusterName, String scheme, int partitionId) throws ServiceUnavailableException
  {
    FutureCallback<Integer> clusterCountFutureCallback = new FutureCallback<>();

    _state.listenToCluster(clusterName, (type, name) ->
    {
      if (_state.getUriProperties(clusterName).getProperty() != null)
      {
        Set<URI> uris =
            _state.getUriProperties(clusterName).getProperty().getUriBySchemeAndPartition(scheme, partitionId);

        clusterCountFutureCallback.onSuccess((uris != null) ? uris.size() : 0);
      }
      else
      {
        // there won't be a UriProperties if there are no Uris announced for this scheme and/or partition. Return zero in this case.
        clusterCountFutureCallback.onSuccess(0);
      }
    });

    try
    {
      return clusterCountFutureCallback.get(_timeout, _unit);
    }
    catch (ExecutionException | TimeoutException | IllegalStateException | InterruptedException e)
    {
      if (e instanceof TimeoutException || e.getCause() instanceof TimeoutException)
      {
        int clusterCount = getClusterCountFromCache(clusterName, scheme, partitionId);
        if (clusterCount >= 0)
        {
          return clusterCount;
        }
      }
      die("ClusterInfo",
          "PEGA_1017, unable to retrieve cluster count for cluster: " + clusterName + ", scheme: " + scheme
              + ", partition: " + partitionId + ", exception: " + e);
      return -1;
    }
  }

  /**
   * Get cluster count from cache
   * @return -1 if the cluster count is not found in cache
   */
  @VisibleForTesting
  int getClusterCountFromCache(String clusterName, String scheme, int partitionId)
  {
    if (_state.getUriProperties(clusterName) != null && _state.getUriProperties(clusterName).getProperty() != null)
    {
      Set<URI> uris =
          _state.getUriProperties(clusterName).getProperty().getUriBySchemeAndPartition(scheme, partitionId);
      if (uris != null)
      {
        return uris.size();
      }
    }
    return -1;
  }

  @Override
  public DarkClusterConfigMap getDarkClusterConfigMap(String clusterName) throws ServiceUnavailableException
  {
    FutureCallback<DarkClusterConfigMap> darkClusterConfigMapFutureCallback = new FutureCallback<>();
    getDarkClusterConfigMap(clusterName, darkClusterConfigMapFutureCallback);

    try
    {
      return darkClusterConfigMapFutureCallback.get(_timeout, _unit);
    }
    catch (ExecutionException | TimeoutException | IllegalStateException | InterruptedException e)
    {
      if (e instanceof TimeoutException || e.getCause() instanceof TimeoutException)
      {
        DarkClusterConfigMap darkClusterConfigMap = getDarkClusterConfigMapFromCache(clusterName);
        if (darkClusterConfigMap != null)
        {
          _log.info("Got dark cluster config map for {} timed out, used cached value instead.", clusterName);
          return darkClusterConfigMap;
        }
      }
      die("ClusterInfo",
          "PEGA_1018, unable to retrieve dark cluster info for cluster: " + clusterName + ", exception" + ": " + e);
      return new DarkClusterConfigMap();
    }
  }

  @Override
  public void getDarkClusterConfigMap(String clusterName, Callback<DarkClusterConfigMap> callback)
  {
    try
    {
      Callback<DarkClusterConfigMap> wrappedCallback = new TimeoutCallback<>(_executor, _timeout, _unit, callback);
      _state.listenToCluster(clusterName, (type, name) ->
      {
        ClusterProperties clusterProperties = _state.getClusterProperties(clusterName).getProperty();
        DarkClusterConfigMap darkClusterConfigMap = clusterProperties != null ?
            clusterProperties.accessDarkClusters() : new DarkClusterConfigMap();
        wrappedCallback.onSuccess(darkClusterConfigMap);
      });
    }
    catch (RejectedExecutionException e)
    {
      _log.debug("Executor rejected new tasks. It has shut down or its queue size has reached max limit");
    }
  }

  /**
   * Get dark cluster config map from cache
   * @return empty DarkClusterConfigMap if the dark cluster config map is not found in cache
   */
  @VisibleForTesting
  DarkClusterConfigMap getDarkClusterConfigMapFromCache(String clusterName)
  {
    if (_state.getClusterProperties(clusterName) != null
        && _state.getClusterProperties(clusterName).getProperty() != null)
    {
      ClusterProperties clusterProperties = _state.getClusterProperties(clusterName).getProperty();
      return clusterProperties != null ? clusterProperties.accessDarkClusters() : new DarkClusterConfigMap();
    }
    return new DarkClusterConfigMap();
  }

  @Override
  public FailoutConfig getFailoutConfig(String clusterName)
  {
    return _failoutConfigProvider != null ? _failoutConfigProvider.getFailoutConfig(clusterName) : null;
  }

  @Override
  public void registerClusterListener(LoadBalancerClusterListener clusterListener)
  {
    _state.registerClusterListener(clusterListener);
  }

  @Override
  public void unregisterClusterListener(LoadBalancerClusterListener clusterListener)
  {
    _state.unregisterClusterListener(clusterListener);
  }

  public static class SimpleLoadBalancerCountDownCallback implements
    LoadBalancerStateListenerCallback
  {
    private CountDownLatch _latch;

    public SimpleLoadBalancerCountDownCallback(CountDownLatch latch)
    {
      _latch = latch;
    }

    @Override
    public void done(int type, String name)
    {
      _latch.countDown();
    }
  }

  public static class TrackerClientSubsetItem
  {
    private final boolean _shouldForceUpdate;
    private final Map<URI, TrackerClient> _trackerClientMap;

    public TrackerClientSubsetItem(boolean shouldForceUpdate, Map<URI, TrackerClient> trackerClientMap)
    {
      _shouldForceUpdate = shouldForceUpdate;
      _trackerClientMap = trackerClientMap;
    }

    public boolean shouldForceUpdate() {
      return _shouldForceUpdate;
    }

    public Map<URI, TrackerClient> getWeightedSubset() {
      return _trackerClientMap;
    }
  }
}
