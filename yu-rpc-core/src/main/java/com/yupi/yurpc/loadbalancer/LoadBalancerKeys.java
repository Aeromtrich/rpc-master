package com.yupi.yurpc.loadbalancer;

/**
 * 负载均衡器键名常量
 *
 * @author Aeromtrich
 */
public interface LoadBalancerKeys {

    /**
     * 轮询
     */
    String ROUND_ROBIN = "roundRobin";

    String RANDOM = "random";

    String CONSISTENT_HASH = "consistentHash";

}
