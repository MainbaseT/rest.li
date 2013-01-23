package com.linkedin.d2.balancer.strategies.degrader;


import com.linkedin.d2.balancer.properties.PropertyKeys;
import com.linkedin.util.clock.Clock;
import com.linkedin.util.clock.SystemClock;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.fail;


/**
 * TestsS for DegraderLoadBalancerStrategyConfig
 *
 * @author Oby Sumampouw (osumampouw@linkedin.com)
 */
public class DegraderLoadBalancerStrategyConfigTest
{
  @Test
  public void testConfigFromMap()
  {
    Map<String,Object> properties = new HashMap<String, Object>();
    long updateIntervalMs = 4662;
    double maxClusterLatencyWithoutDegrading = 325.4;
    double defaultSuccessfulTransmissionWeight = 0.62;
    int pointsPerWeight = 222;
    String hashMethod = "md5";
    Clock clock = SystemClock.instance();
    double initialRecoveryLevel = 0.03;
    double ringRampFactor = 1.37;
    double highWaterMark = 2999.2;
    double lowWaterMark = 420.3;
    double globalStepUp = 0.15;
    double globalStepDown = 0.22;

    properties.put(PropertyKeys.LB_STRATEGY_PROPERTIES_UPDATE_INTERVAL_MS, updateIntervalMs);
    properties.put(PropertyKeys.LB_STRATEGY_PROPERTIES_MAX_CLUSTER_LATENCY_WITHOUT_DEGRADING,
                   maxClusterLatencyWithoutDegrading);
    properties.put(PropertyKeys.LB_STRATEGY_PROPERTIES_DEFAULT_SUCCESSFUL_TRANSMISSION_WEIGHT,
                   defaultSuccessfulTransmissionWeight);
    properties.put(PropertyKeys.LB_STRATEGY_PROPERTIES_POINTS_PER_WEIGHT,
                   pointsPerWeight);
    properties.put(PropertyKeys.LB_HASH_METHOD, hashMethod);
    properties.put(PropertyKeys.CLOCK, clock);
    properties.put(PropertyKeys.LB_INITIAL_RECOVERY_LEVEL, initialRecoveryLevel);
    properties.put(PropertyKeys.LB_RING_RAMP_FACTOR, ringRampFactor);
    properties.put(PropertyKeys.LB_HIGH_WATER_MARK, highWaterMark);
    properties.put(PropertyKeys.LB_LOW_WATER_MARK, lowWaterMark);
    properties.put(PropertyKeys.LB_GLOBAL_STEP_DOWN, globalStepDown);
    properties.put(PropertyKeys.LB_GLOBAL_STEP_UP, globalStepUp);

    DegraderLoadBalancerStrategyConfig config =
        DegraderLoadBalancerStrategyConfig.configFromMap(properties);

    assertEquals(config.getUpdateIntervalMs(), updateIntervalMs);
    assertEquals(config.getMaxClusterLatencyWithoutDegrading(), maxClusterLatencyWithoutDegrading);
    assertEquals(config.getDefaultSuccessfulTransmissionWeight(), defaultSuccessfulTransmissionWeight);
    assertEquals(config.getPointsPerWeight(), pointsPerWeight);
    assertEquals(config.getHashMethod(), hashMethod);
    assertEquals(config.getClock(), clock);
    assertEquals(config.getInitialRecoveryLevel(), initialRecoveryLevel);
    assertEquals(config.getRingRampFactor(), ringRampFactor);
    assertEquals(config.getHighWaterMark(), highWaterMark);
    assertEquals(config.getLowWaterMark(), lowWaterMark);
    assertEquals(config.getGlobalStepDown(), globalStepDown);
    assertEquals(config.getGlobalStepUp(), globalStepUp);
  }

  @Test
  public void testCreateHttpConfigFromMap()
  {
    Map<String,Object> properties = new HashMap<String, Object>();
    long updateIntervalMs = 4662;
    double maxClusterLatencyWithoutDegrading = 325.4;
    double defaultSuccessfulTransmissionWeight = 0.62;
    int pointsPerWeight = 222;
    String hashMethod = "md5";
    Clock clock = SystemClock.instance();
    double initialRecoveryLevel = 0.03;
    double ringRampFactor = 1.37;
    double highWaterMark = 2999.2;
    double lowWaterMark = 420.3;
    double globalStepUp = 0.15;
    double globalStepDown = 0.22;
    Map<String,Object> hashConfig = new HashMap<String,Object>();
    List<String> regexes = new LinkedList<String>();
    regexes.add("hashToken=(\\d+)");
    hashConfig.put("regexes", regexes);

    properties.put(PropertyKeys.LB_HASH_CONFIG, hashConfig);
    properties.put(PropertyKeys.LB_STRATEGY_PROPERTIES_UPDATE_INTERVAL_MS, updateIntervalMs);
    properties.put(PropertyKeys.LB_STRATEGY_PROPERTIES_MAX_CLUSTER_LATENCY_WITHOUT_DEGRADING,
                   maxClusterLatencyWithoutDegrading);
    properties.put(PropertyKeys.LB_STRATEGY_PROPERTIES_DEFAULT_SUCCESSFUL_TRANSMISSION_WEIGHT,
                   defaultSuccessfulTransmissionWeight);
    properties.put(PropertyKeys.LB_STRATEGY_PROPERTIES_POINTS_PER_WEIGHT,
                   pointsPerWeight);
    properties.put(PropertyKeys.LB_HASH_METHOD, hashMethod);
    properties.put(PropertyKeys.CLOCK, clock);
    properties.put(PropertyKeys.LB_INITIAL_RECOVERY_LEVEL, initialRecoveryLevel);
    properties.put(PropertyKeys.LB_RING_RAMP_FACTOR, ringRampFactor);
    properties.put(PropertyKeys.LB_HIGH_WATER_MARK, highWaterMark);
    properties.put(PropertyKeys.LB_LOW_WATER_MARK, lowWaterMark);
    properties.put(PropertyKeys.LB_GLOBAL_STEP_DOWN, globalStepDown);
    properties.put(PropertyKeys.LB_GLOBAL_STEP_UP, globalStepUp);

    //test without http specific config listed
    DegraderLoadBalancerStrategyConfig config =
        DegraderLoadBalancerStrategyConfig.createHttpConfigFromMap(properties);

    assertEquals(config.getUpdateIntervalMs(), updateIntervalMs);
    assertEquals(config.getMaxClusterLatencyWithoutDegrading(), maxClusterLatencyWithoutDegrading);
    assertEquals(config.getDefaultSuccessfulTransmissionWeight(), defaultSuccessfulTransmissionWeight);
    assertEquals(config.getPointsPerWeight(), pointsPerWeight);
    assertEquals(config.getHashMethod(), hashMethod);
    assertEquals(config.getClock(), clock);
    assertEquals(config.getInitialRecoveryLevel(), initialRecoveryLevel);
    assertEquals(config.getRingRampFactor(), ringRampFactor);
    assertEquals(config.getHighWaterMark(), highWaterMark);
    assertEquals(config.getLowWaterMark(), lowWaterMark);
    assertEquals(config.getGlobalStepDown(), globalStepDown);
    assertEquals(config.getGlobalStepUp(), globalStepUp);
    assertEquals(config.getHashConfig(), hashConfig);

    long httpUpdateIntervalMs = 5231;
    double httpMaxClusterLatencyWithoutDegrading = 139.6;
    double httpDefaultSuccessfulTransmissionWeight = 0.88;
    int httpPointsPerWeight = 202;
    String httpHashMethod = "sha1";
    double httpInitialRecoveryLevel = 0.06;
    double httpRingRampFactor = 1.67;
    double httpHighWaterMark = 1866.2;
    double httpLowWaterMark = 555.5;
    double httpGlobalStepUp = 0.17;
    double httpGlobalStepDown = 0.21;
    Map<String,Object> httpHashConfig = new HashMap<String,Object>();
    List<String> httpRegexes = new LinkedList<String>();
    httpRegexes.add("httphashToken=(\\d+)");
    httpHashConfig.put("regexes", httpRegexes);

    properties.put(PropertyKeys.HTTP_LB_HASH_CONFIG, httpHashConfig);
    properties.put(PropertyKeys.HTTP_LB_STRATEGY_PROPERTIES_UPDATE_INTERVAL_MS,
                   httpUpdateIntervalMs);
    properties.put(PropertyKeys.HTTP_LB_STRATEGY_PROPERTIES_MAX_CLUSTER_LATENCY_WITHOUT_DEGRADING,
                   httpMaxClusterLatencyWithoutDegrading);
    properties.put(PropertyKeys.HTTP_LB_STRATEGY_PROPERTIES_DEFAULT_SUCCESSFUL_TRANSMISSION_WEIGHT,
                   httpDefaultSuccessfulTransmissionWeight);
    properties.put(PropertyKeys.HTTP_LB_STRATEGY_PROPERTIES_POINTS_PER_WEIGHT,
                   httpPointsPerWeight);
    properties.put(PropertyKeys.HTTP_LB_HASH_METHOD, httpHashMethod);
    properties.put(PropertyKeys.HTTP_LB_INITIAL_RECOVERY_LEVEL, httpInitialRecoveryLevel);
    properties.put(PropertyKeys.HTTP_LB_RING_RAMP_FACTOR, httpRingRampFactor);
    properties.put(PropertyKeys.HTTP_LB_HIGH_WATER_MARK, httpHighWaterMark);
    properties.put(PropertyKeys.HTTP_LB_LOW_WATER_MARK, httpLowWaterMark);
    properties.put(PropertyKeys.HTTP_LB_GLOBAL_STEP_DOWN, httpGlobalStepDown);
    properties.put(PropertyKeys.HTTP_LB_GLOBAL_STEP_UP, httpGlobalStepUp);

    //now test if there's http, then http config should take more priority
    config = DegraderLoadBalancerStrategyConfig.createHttpConfigFromMap(properties);

    assertEquals(config.getUpdateIntervalMs(), httpUpdateIntervalMs);
    assertEquals(config.getMaxClusterLatencyWithoutDegrading(), httpMaxClusterLatencyWithoutDegrading);
    assertEquals(config.getDefaultSuccessfulTransmissionWeight(), httpDefaultSuccessfulTransmissionWeight);
    assertEquals(config.getPointsPerWeight(), httpPointsPerWeight);
    assertEquals(config.getHashMethod(), httpHashMethod);
    assertEquals(config.getInitialRecoveryLevel(), httpInitialRecoveryLevel);
    assertEquals(config.getRingRampFactor(), httpRingRampFactor);
    assertEquals(config.getHighWaterMark(), httpHighWaterMark);
    assertEquals(config.getLowWaterMark(), httpLowWaterMark);
    assertEquals(config.getGlobalStepDown(), httpGlobalStepDown);
    assertEquals(config.getGlobalStepUp(), httpGlobalStepUp);
    assertEquals(config.getHashConfig(), httpHashConfig);

    //test if there is no old config will the http config appropriately located
    properties.clear();
    properties.put(PropertyKeys.HTTP_LB_HASH_CONFIG, httpHashConfig);
    properties.put(PropertyKeys.HTTP_LB_STRATEGY_PROPERTIES_UPDATE_INTERVAL_MS,
                   httpUpdateIntervalMs);
    properties.put(PropertyKeys.HTTP_LB_STRATEGY_PROPERTIES_MAX_CLUSTER_LATENCY_WITHOUT_DEGRADING,
                   httpMaxClusterLatencyWithoutDegrading);
    properties.put(PropertyKeys.HTTP_LB_STRATEGY_PROPERTIES_DEFAULT_SUCCESSFUL_TRANSMISSION_WEIGHT,
                   httpDefaultSuccessfulTransmissionWeight);
    properties.put(PropertyKeys.HTTP_LB_STRATEGY_PROPERTIES_POINTS_PER_WEIGHT,
                   httpPointsPerWeight);
    properties.put(PropertyKeys.HTTP_LB_HASH_METHOD, httpHashMethod);
    properties.put(PropertyKeys.HTTP_LB_INITIAL_RECOVERY_LEVEL, httpInitialRecoveryLevel);
    properties.put(PropertyKeys.HTTP_LB_RING_RAMP_FACTOR, httpRingRampFactor);
    properties.put(PropertyKeys.HTTP_LB_HIGH_WATER_MARK, httpHighWaterMark);
    properties.put(PropertyKeys.HTTP_LB_LOW_WATER_MARK, httpLowWaterMark);
    properties.put(PropertyKeys.HTTP_LB_GLOBAL_STEP_DOWN, httpGlobalStepDown);
    properties.put(PropertyKeys.HTTP_LB_GLOBAL_STEP_UP, httpGlobalStepUp);
    config = DegraderLoadBalancerStrategyConfig.createHttpConfigFromMap(properties);

    assertEquals(config.getUpdateIntervalMs(), httpUpdateIntervalMs);
    assertEquals(config.getMaxClusterLatencyWithoutDegrading(), httpMaxClusterLatencyWithoutDegrading);
    assertEquals(config.getDefaultSuccessfulTransmissionWeight(), httpDefaultSuccessfulTransmissionWeight);
    assertEquals(config.getPointsPerWeight(), httpPointsPerWeight);
    assertEquals(config.getHashMethod(), httpHashMethod);
    assertEquals(config.getInitialRecoveryLevel(), httpInitialRecoveryLevel);
    assertEquals(config.getRingRampFactor(), httpRingRampFactor);
    assertEquals(config.getHighWaterMark(), httpHighWaterMark);
    assertEquals(config.getLowWaterMark(), httpLowWaterMark);
    assertEquals(config.getGlobalStepDown(), httpGlobalStepDown);
    assertEquals(config.getGlobalStepUp(), httpGlobalStepUp);
    assertEquals(config.getHashConfig(), httpHashConfig);

    //test if there's no config, will the default config value set
    properties.clear();
    config = DegraderLoadBalancerStrategyConfig.createHttpConfigFromMap(properties);
    assertEquals(config.getUpdateIntervalMs(), DegraderLoadBalancerStrategyConfig.DEFAULT_UPDATE_INTERVAL_MS);
    assertEquals(config.getMaxClusterLatencyWithoutDegrading(),
                 DegraderLoadBalancerStrategyConfig.DEFAULT_MAX_CLUSTER_LATENCY_WITHOUT_DEGRADING);
    assertEquals(config.getDefaultSuccessfulTransmissionWeight(),
                 DegraderLoadBalancerStrategyConfig.DEFAULT_SUCCESSFUL_TRANSMISSION_WEIGHT);
    assertEquals(config.getPointsPerWeight(),
                 DegraderLoadBalancerStrategyConfig.DEFAULT_POINTS_PER_WEIGHT);
    assertNull(config.getHashMethod());
    assertEquals(config.getInitialRecoveryLevel(), DegraderLoadBalancerStrategyConfig.DEFAULT_INITIAL_RECOVERY_LEVEL);
    assertEquals(config.getRingRampFactor(), DegraderLoadBalancerStrategyConfig.DEFAULT_RAMP_FACTOR);
    assertEquals(config.getHighWaterMark(), DegraderLoadBalancerStrategyConfig.DEFAULT_HIGH_WATER_MARK);
    assertEquals(config.getLowWaterMark(), DegraderLoadBalancerStrategyConfig.DEFAULT_LOW_WATER_MARK);
    assertEquals(config.getGlobalStepDown(), DegraderLoadBalancerStrategyConfig.DEFAULT_GLOBAL_STEP_DOWN);
    assertEquals(config.getGlobalStepUp(), DegraderLoadBalancerStrategyConfig.DEFAULT_GLOBAL_STEP_UP);
    assertEquals(config.getHashConfig(), Collections.emptyMap());
  }
}
