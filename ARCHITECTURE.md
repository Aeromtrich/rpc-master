# yu-rpc-core 架构设计文档

## 📚 目录

- [1. 项目概述](#1-项目概述)
- [2. 整体架构](#2-整体架构)
- [3. 核心模块详解](#3-核心模块详解)
- [4. 技术选型](#4-技术选型)
- [5. 设计模式](#5-设计模式)
- [6. 核心流程](#6-核心流程)
- [7. 关键特性](#7-关键特性)

---

## 1. 项目概述

**yu-rpc-core** 是一个基于Java开发的高性能RPC框架核心模块，支持服务注册与发现、负载均衡、容错重试、多种序列化方式等特性。

### 1.1 核心功能

- ✅ 服务注册与发现（Etcd、ZooKeeper）
- ✅ 自定义网络协议（基于TCP）
- ✅ 多种负载均衡策略（轮询、随机、一致性哈希）
- ✅ 多种序列化方式（JDK、JSON、Kryo、Hessian）
- ✅ 重试机制（固定间隔重试）
- ✅ 容错机制（快速失败、静默处理、故障转移、故障恢复）
- ✅ Spring Boot 无缝集成
- ✅ SPI扩展机制

---

## 2. 整体架构

### 2.1 分层架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                    应用层 (Application Layer)                    │
│                                                                   │
│  ┌──────────────────────┐         ┌──────────────────────┐     │
│  │   服务提供者          │         │   服务消费者          │     │
│  │   @RpcService        │         │   @RpcReference      │     │
│  └──────────────────────┘         └──────────────────────┘     │
└─────────────────────────────────────────────────────────────────┘
                              ▲
                              │
┌─────────────────────────────┴───────────────────────────────────┐
│              Spring Boot集成层 (Integration Layer)              │
│                                                                   │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  yu-rpc-spring-boot-starter                              │  │
│  │  - @EnableRpc (启用注解)                                 │  │
│  │  - RpcInitBootstrap (框架初始化)                        │  │
│  │  - RpcProviderBootstrap (服务注册)                      │  │
│  │  - RpcConsumerBootstrap (代理注入)                      │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                              ▲
                              │
┌─────────────────────────────┴───────────────────────────────────┐
│                  核心层 (Core Layer - yu-rpc-core)              │
│                                                                   │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  代理层 (Proxy Layer)                                    │  │
│  │  - ServiceProxy (JDK动态代理)                           │  │
│  │  - MockServiceProxy (模拟调用)                          │  │
│  │  - ServiceProxyFactory (代理工厂)                       │  │
│  └──────────────────────────────────────────────────────────┘  │
│                              ▲                                   │
│  ┌──────────────────────────┴───────────────────────────────┐  │
│  │  容错层 (Fault Tolerance Layer)                          │  │
│  │  - RetryStrategy (重试策略: 不重试/固定间隔)            │  │
│  │  - TolerantStrategy (容错策略: 快速失败/静默/转移/降级) │  │
│  └──────────────────────────────────────────────────────────┘  │
│                              ▲                                   │
│  ┌──────────────────────────┴───────────────────────────────┐  │
│  │  负载均衡层 (Load Balance Layer)                         │  │
│  │  - RoundRobinLoadBalancer (轮询)                         │  │
│  │  - RandomLoadBalancer (随机)                             │  │
│  │  - ConsistentHashLoadBalancer (一致性哈希)              │  │
│  └──────────────────────────────────────────────────────────┘  │
│                              ▲                                   │
│  ┌──────────────────────────┴───────────────────────────────┐  │
│  │  注册中心层 (Registry Layer)                             │  │
│  │  - EtcdRegistry (租约30秒, 10秒续签, Watch监听)         │  │
│  │  - ZooKeeperRegistry (临时节点, Curator, Watch监听)     │  │
│  │  - LocalRegistry (本地内存, 单机测试)                   │  │
│  │  - RegistryServiceCache (服务列表缓存)                  │  │
│  └──────────────────────────────────────────────────────────┘  │
│                              ▲                                   │
│  ┌──────────────────────────┴───────────────────────────────┐  │
│  │  网络通信层 (Network Layer)                              │  │
│  │  - 自定义协议 (17字节Header + 变长Body)                 │  │
│  │  - ProtocolMessageEncoder/Decoder (编解码器)            │  │
│  │  - VertxTcpServer/Client (基于Vert.x)                   │  │
│  │  - TcpBufferHandlerWrapper (粘包/半包处理)              │  │
│  └──────────────────────────────────────────────────────────┘  │
│                              ▲                                   │
│  ┌──────────────────────────┴───────────────────────────────┐  │
│  │  序列化层 (Serialization Layer)                          │  │
│  │  - JdkSerializer / JsonSerializer                        │  │
│  │  - KryoSerializer / HessianSerializer                    │  │
│  │  - SerializerFactory (SPI动态加载)                      │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  配置层 (Configuration Layer)                            │  │
│  │  - RpcConfig (全局配置)                                  │  │
│  │  - RegistryConfig (注册中心配置)                        │  │
│  │  - ConfigUtils (配置加载工具)                           │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  SPI机制 (SPI Loader)                                    │  │
│  │  - 加载 META-INF/rpc/system/接口全限定名                │  │
│  │  - 动态加载实现类, 单例缓存                             │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 模块依赖关系

```
rpc-master (根项目)
│
├── example-common (公共接口)
│   └── UserService.java
│
├── yu-rpc-easy (简易版RPC)
│
├── yu-rpc-core (核心框架) ★
│   ├── config
│   ├── registry
│   ├── protocol
│   ├── server/tcp
│   ├── serializer
│   ├── proxy
│   ├── loadbalancer
│   ├── fault
│   └── spi
│
├── yu-rpc-spring-boot-starter (Spring集成) ★
│   ├── annotation
│   └── bootstrap
│
├── example-springboot-provider
└── example-springboot-consumer
```

---

## 3. 核心模块详解

### 3.1 注册中心模块 (registry)

#### 架构设计

```java
public interface Registry {
    void init(RegistryConfig registryConfig);
    void register(ServiceMetaInfo serviceMetaInfo) throws Exception;
    void unRegister(ServiceMetaInfo serviceMetaInfo);
    List<ServiceMetaInfo> serviceDiscovery(String serviceKey);
    void heartBeat();
    void watch(String serviceNodeKey);
    void destroy();
}
```

#### 实现类

**EtcdRegistry**
- **租约机制**: 创建30秒租约，10秒续签一次
- **服务注册**: Key格式 `/rpc/服务名:版本/主机:端口`
- **Watch监听**: 监听服务节点DELETE事件，自动清空缓存
- **心跳续签**: 定时任务每10秒重新注册服务

**ZooKeeperRegistry**
- **临时节点**: 使用临时节点自动过期
- **Curator框架**: 简化ZooKeeper操作
- **服务发现**: 支持Watch监听

**关键流程**

```
Provider启动
    ↓
1. RpcApplication.init()
   - 初始化Registry实例
   - 注册ShutdownHook
    ↓
2. registry.register(serviceMetaInfo)
   - 创建租约（Etcd: 30秒）
   - 写入注册中心
   - 添加到localRegisterNodeKeySet
    ↓
3. heartBeat()
   - 定时任务每10秒执行
   - 遍历localRegisterNodeKeySet
   - 重新注册服务（续签）
    ↓
Consumer调用
    ↓
4. registry.serviceDiscovery(serviceKey)
   - 优先从缓存读取
   - 缓存未命中则查询Etcd
   - 启动Watch监听
   - 写入缓存并返回
```

---

### 3.2 自定义网络协议 (protocol)

#### 协议格式

```
┌──────────────────────────────────────────────────────────┐
│  ProtocolMessage<T>                                      │
│  ┌────────────────────────────────────────────────────┐ │
│  │  Header (17字节固定长度)                           │ │
│  │  ┌────┬────┬──────┬────┬────┬────────┬────────┐  │ │
│  │  │魔数│版本│序列化│类型│状态│请求ID  │体长度  │  │ │
│  │  │0x1 │0x1 │ 0-3  │0-3 │20+ │8字节   │4字节   │  │ │
│  │  │1B  │1B  │ 1B   │1B  │1B  │8B      │4B      │  │ │
│  │  └────┴────┴──────┴────┴────┴────────┴────────┘  │ │
│  └────────────────────────────────────────────────────┘ │
│  ┌────────────────────────────────────────────────────┐ │
│  │  Body (变长)                                       │ │
│  │  RpcRequest 或 RpcResponse (序列化后的字节数组)   │ │
│  └────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────┘
```

#### 字段说明

| 字段 | 类型 | 长度 | 说明 |
|------|------|------|------|
| magic | byte | 1B | 魔数0x1，用于校验 |
| version | byte | 1B | 协议版本号0x1 |
| serializer | byte | 1B | 序列化器类型（0:JDK, 1:JSON, 2:Kryo, 3:Hessian） |
| type | byte | 1B | 消息类型（0:REQUEST, 1:RESPONSE, 2:HEART_BEAT） |
| status | byte | 1B | 消息状态（20:OK, 40:BAD_REQUEST, 50:BAD_RESPONSE） |
| requestId | long | 8B | 请求唯一ID（雪花算法生成） |
| bodyLength | int | 4B | 消息体长度 |
| body | byte[] | N | 消息体（序列化后的RpcRequest/RpcResponse） |

#### 编解码流程

**编码 (ProtocolMessageEncoder)**
```
ProtocolMessage对象
    ↓
1. 创建Buffer
    ↓
2. 依次写入Header字段 (17字节)
   buffer.appendByte(magic)
   buffer.appendByte(version)
   ...
   buffer.appendLong(requestId)
    ↓
3. 序列化Body
   byte[] bodyBytes = serializer.serialize(body)
    ↓
4. 写入Body长度和内容
   buffer.appendInt(bodyBytes.length)
   buffer.appendBytes(bodyBytes)
    ↓
返回Buffer
```

**解码 (ProtocolMessageDecoder)**
```
Buffer字节流
    ↓
1. 读取Header (17字节)
   magic = buffer.getByte(0)
   校验魔数 (magic == 0x1)
   version = buffer.getByte(1)
   ...
   bodyLength = buffer.getInt(13)
    ↓
2. 读取Body (指定长度，解决粘包)
   bodyBytes = buffer.getBytes(17, 17 + bodyLength)
    ↓
3. 根据type反序列化
   if (type == REQUEST)
       body = serializer.deserialize(bodyBytes, RpcRequest.class)
   else
       body = serializer.deserialize(bodyBytes, RpcResponse.class)
    ↓
返回ProtocolMessage对象
```

#### 粘包/半包处理

**TcpBufferHandlerWrapper** (装饰者模式)

```java
// 使用Vert.x的RecordParser分阶段解析
RecordParser parser = RecordParser.newFixed(17);  // 阶段1: 读取17字节Header

parser.setOutput(new Handler<Buffer>() {
    int size = -1;
    Buffer resultBuffer = Buffer.buffer();

    public void handle(Buffer buffer) {
        if (size == -1) {
            // 阶段1: 读取Header
            size = buffer.getInt(13);  // 获取Body长度
            parser.fixedSizeMode(size);  // 切换到阶段2
            resultBuffer.appendBuffer(buffer);
        } else {
            // 阶段2: 读取Body
            resultBuffer.appendBuffer(buffer);
            bufferHandler.handle(resultBuffer);  // 处理完整消息

            // 重置，准备处理下一条消息
            parser.fixedSizeMode(17);
            size = -1;
            resultBuffer = Buffer.buffer();
        }
    }
});
```

---

### 3.3 负载均衡模块 (loadbalancer)

#### 接口定义

```java
public interface LoadBalancer {
    ServiceMetaInfo select(Map<String, Object> requestParams,
                          List<ServiceMetaInfo> serviceMetaInfoList);
}
```

#### 实现策略

**1. 轮询负载均衡 (RoundRobinLoadBalancer) - 默认**

```java
private final AtomicInteger currentIndex = new AtomicInteger(0);

public ServiceMetaInfo select(Map<String, Object> requestParams,
                              List<ServiceMetaInfo> serviceMetaInfoList) {
    int size = serviceMetaInfoList.size();
    if (size == 1) return serviceMetaInfoList.get(0);

    int index = currentIndex.getAndIncrement() % size;
    return serviceMetaInfoList.get(index);
}
```

**特点**: 请求均匀分配，线程安全（AtomicInteger）

**2. 随机负载均衡 (RandomLoadBalancer)**

```java
private final Random random = new Random();

public ServiceMetaInfo select(Map<String, Object> requestParams,
                              List<ServiceMetaInfo> serviceMetaInfoList) {
    int size = serviceMetaInfoList.size();
    return serviceMetaInfoList.get(random.nextInt(size));
}
```

**特点**: 实现简单，理论上长期均衡

**3. 一致性哈希负载均衡 (ConsistentHashLoadBalancer)**

```java
private final TreeMap<Integer, ServiceMetaInfo> virtualNodes = new TreeMap<>();
private static final int VIRTUAL_NODE_NUM = 100;

public ServiceMetaInfo select(Map<String, Object> requestParams,
                              List<ServiceMetaInfo> serviceMetaInfoList) {
    // 1. 构建虚拟节点环
    for (ServiceMetaInfo service : serviceMetaInfoList) {
        for (int i = 0; i < VIRTUAL_NODE_NUM; i++) {
            int hash = getHash(service.getServiceAddress() + "#" + i);
            virtualNodes.put(hash, service);
        }
    }

    // 2. 计算请求hash值
    int hash = getHash(requestParams);

    // 3. 顺时针找最近的节点
    Map.Entry<Integer, ServiceMetaInfo> entry = virtualNodes.ceilingEntry(hash);
    if (entry == null) {
        entry = virtualNodes.firstEntry();  // 环状结构
    }
    return entry.getValue();
}
```

**特点**: 相同请求路由到同一服务，服务增减影响最小

---

### 3.4 重试与容错模块 (fault)

#### 重试策略 (retry)

**接口定义**

```java
public interface RetryStrategy {
    RpcResponse doRetry(Callable<RpcResponse> callable) throws Exception;
}
```

**固定间隔重试 (FixedIntervalRetryStrategy)**

```java
public RpcResponse doRetry(Callable<RpcResponse> callable)
        throws ExecutionException, RetryException {
    Retryer<RpcResponse> retryer = RetryerBuilder.<RpcResponse>newBuilder()
        .retryIfExceptionOfType(Exception.class)  // 任何异常都重试
        .withWaitStrategy(WaitStrategies.fixedWait(3L, TimeUnit.SECONDS))  // 等待3秒
        .withStopStrategy(StopStrategies.stopAfterAttempt(3))  // 最多3次
        .withRetryListener(new RetryListener() {
            public <V> void onRetry(Attempt<V> attempt) {
                log.info("重试次数 {}", attempt.getAttemptNumber());
            }
        })
        .build();
    return retryer.call(callable);
}
```

**配置**: 最多重试3次，每次间隔3秒

#### 容错策略 (tolerant)

**接口定义**

```java
public interface TolerantStrategy {
    RpcResponse doTolerant(Map<String, Object> context, Exception e);
}
```

**四种策略**

1. **快速失败 (FailFastTolerantStrategy)** - 默认
   ```java
   public RpcResponse doTolerant(Map<String, Object> context, Exception e) {
       throw new RuntimeException("服务报错", e);
   }
   ```
   **场景**: 核心业务，必须感知失败

2. **静默处理 (FailSafeTolerantStrategy)**
   ```java
   public RpcResponse doTolerant(Map<String, Object> context, Exception e) {
       log.info("静默处理异常", e);
       return new RpcResponse();
   }
   ```
   **场景**: 非核心业务，允许降级

3. **故障转移 (FailOverTolerantStrategy)**
   ```java
   public RpcResponse doTolerant(Map<String, Object> context, Exception e) {
       // TODO: 切换到其他服务节点重试
       return null;
   }
   ```
   **场景**: 集群部署，多节点容错

4. **故障恢复 (FailBackTolerantStrategy)**
   ```java
   public RpcResponse doTolerant(Map<String, Object> context, Exception e) {
       // TODO: 调用降级服务
       return null;
   }
   ```
   **场景**: 服务降级，返回默认值

---

### 3.5 序列化模块 (serializer)

#### 支持的序列化方式

| 序列化器 | 优点 | 缺点 | 适用场景 |
|---------|------|------|---------|
| JDK | 无需依赖 | 性能较低，体积大 | 简单对象 |
| JSON | 可读性好 | 性能一般 | 调试、跨语言 |
| Kryo | 高性能，体积小 | 需要注册类 | 高性能场景 |
| Hessian | 跨语言支持 | 性能中等 | 跨语言调用 |

#### 序列化器工厂

```java
public class SerializerFactory {
    static {
        SpiLoader.load(Serializer.class);
    }

    public static Serializer getInstance(String key) {
        return SpiLoader.getInstance(Serializer.class, key);
    }
}
```

**SPI配置** (`META-INF/rpc/system/com.yupi.yurpc.serializer.Serializer`)
```
jdk=com.yupi.yurpc.serializer.JdkSerializer
json=com.yupi.yurpc.serializer.JsonSerializer
kryo=com.yupi.yurpc.serializer.KryoSerializer
hessian=com.yupi.yurpc.serializer.HessianSerializer
```

---

### 3.6 代理模块 (proxy)

#### ServiceProxy (JDK动态代理)

```java
public class ServiceProxy implements InvocationHandler {

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // 1. 构造RPC请求
        RpcRequest rpcRequest = RpcRequest.builder()
            .serviceName(method.getDeclaringClass().getName())
            .methodName(method.getName())
            .parameterTypes(method.getParameterTypes())
            .args(args)
            .build();

        // 2. 从注册中心获取服务列表
        Registry registry = RegistryFactory.getInstance(...);
        List<ServiceMetaInfo> serviceList = registry.serviceDiscovery(serviceKey);

        // 3. 负载均衡选择服务
        LoadBalancer loadBalancer = LoadBalancerFactory.getInstance(...);
        ServiceMetaInfo selectedService = loadBalancer.select(requestParams, serviceList);

        // 4. 重试机制
        RpcResponse rpcResponse;
        try {
            RetryStrategy retryStrategy = RetryStrategyFactory.getInstance(...);
            rpcResponse = retryStrategy.doRetry(() ->
                VertxTcpClient.doRequest(rpcRequest, selectedService)
            );
        } catch (Exception e) {
            // 5. 容错机制
            TolerantStrategy tolerantStrategy = TolerantStrategyFactory.getInstance(...);
            rpcResponse = tolerantStrategy.doTolerant(null, e);
        }

        return rpcResponse.getData();
    }
}
```

#### ServiceProxyFactory

```java
public class ServiceProxyFactory {
    public static <T> T getProxy(Class<T> serviceClass) {
        return (T) Proxy.newProxyInstance(
            serviceClass.getClassLoader(),
            new Class[]{serviceClass},
            new ServiceProxy()
        );
    }
}
```

---

## 4. 技术选型

### 4.1 核心依赖

```xml
<!-- 网络通信 -->
<dependency>
    <groupId>io.vertx</groupId>
    <artifactId>vertx-core</artifactId>
    <version>4.5.1</version>
</dependency>

<!-- 注册中心 - Etcd -->
<dependency>
    <groupId>io.etcd</groupId>
    <artifactId>jetcd-core</artifactId>
    <version>0.7.5</version>
</dependency>

<!-- 注册中心 - ZooKeeper -->
<dependency>
    <groupId>org.apache.curator</groupId>
    <artifactId>curator-x-discovery</artifactId>
    <version>5.1.0</version>
</dependency>

<!-- 重试机制 -->
<dependency>
    <groupId>com.github.rholder</groupId>
    <artifactId>guava-retrying</artifactId>
    <version>2.0.0</version>
</dependency>

<!-- 序列化 - Kryo -->
<dependency>
    <groupId>com.esotericsoftware</groupId>
    <artifactId>kryo</artifactId>
    <version>5.5.0</version>
</dependency>

<!-- 序列化 - Hessian -->
<dependency>
    <groupId>com.caucho</groupId>
    <artifactId>hessian</artifactId>
    <version>4.0.66</version>
</dependency>

<!-- 工具库 -->
<dependency>
    <groupId>cn.hutool</groupId>
    <artifactId>hutool-all</artifactId>
    <version>5.8.16</version>
</dependency>
```

### 4.2 技术选型理由

| 技术 | 选型理由 |
|------|---------|
| **Vert.x** | 高性能异步IO框架，支持TCP/HTTP，事件驱动 |
| **Etcd** | 强一致性KV存储，Watch机制，适合服务注册 |
| **ZooKeeper** | 成熟的分布式协调服务，临时节点自动过期 |
| **guava-retrying** | 灵活的重试框架，支持多种重试策略 |
| **Kryo** | 高性能序列化，体积小，速度快 |
| **Hutool** | 丰富的Java工具类，简化开发 |

---

## 5. 设计模式

### 5.1 工厂模式

**应用场景**: 创建各种策略实例

```java
// 序列化器工厂
SerializerFactory.getInstance("kryo")

// 注册中心工厂
RegistryFactory.getInstance("etcd")

// 负载均衡器工厂
LoadBalancerFactory.getInstance("roundRobin")

// 重试策略工厂
RetryStrategyFactory.getInstance("fixedInterval")

// 容错策略工厂
TolerantStrategyFactory.getInstance("failFast")

// 代理工厂
ServiceProxyFactory.getProxy(UserService.class)
```

### 5.2 策略模式

**应用场景**: 提供多种可替换的算法

```
LoadBalancer策略
├── RoundRobinLoadBalancer (轮询)
├── RandomLoadBalancer (随机)
└── ConsistentHashLoadBalancer (一致性哈希)

Serializer策略
├── JdkSerializer
├── JsonSerializer
├── KryoSerializer
└── HessianSerializer

RetryStrategy策略
├── NoRetryStrategy
└── FixedIntervalRetryStrategy

TolerantStrategy策略
├── FailFastTolerantStrategy
├── FailSafeTolerantStrategy
├── FailOverTolerantStrategy
└── FailBackTolerantStrategy
```

### 5.3 代理模式

**JDK动态代理**

```java
// 创建代理对象
UserService userService = ServiceProxyFactory.getProxy(UserService.class);

// 调用方法时，实际调用ServiceProxy.invoke()
userService.getUser(user);
```

**透明化RPC调用**，对业务代码无侵入

### 5.4 单例模式

```java
// RpcApplication - 双检锁单例
public class RpcApplication {
    private static volatile RpcConfig rpcConfig;

    public static RpcConfig getRpcConfig() {
        if (rpcConfig == null) {
            synchronized (RpcApplication.class) {
                if (rpcConfig == null) {
                    init();
                }
            }
        }
        return rpcConfig;
    }
}

// SpiLoader - 单例缓存
private static final Map<String, Object> instanceCache = new ConcurrentHashMap<>();
```

### 5.5 装饰者模式

**TcpBufferHandlerWrapper**

```java
// 增强原有的Buffer处理能力，添加粘包/半包处理
public class TcpBufferHandlerWrapper implements Handler<Buffer> {
    private final RecordParser recordParser;

    public TcpBufferHandlerWrapper(Handler<Buffer> bufferHandler) {
        recordParser = initRecordParser(bufferHandler);
    }
}
```

### 5.6 观察者模式

**Registry.watch()** - 监听服务变化

```java
// Etcd Watch监听
watchClient.watch(serviceNodeKey, response -> {
    for (WatchEvent event : response.getEvents()) {
        if (event.getEventType() == DELETE) {
            registryServiceCache.clearCache();  // 清空缓存
        }
    }
});
```

### 5.7 SPI机制

**Service Provider Interface** - 插件化扩展

```
META-INF/rpc/system/
├── com.yupi.yurpc.registry.Registry
├── com.yupi.yurpc.loadbalancer.LoadBalancer
├── com.yupi.yurpc.serializer.Serializer
├── com.yupi.yurpc.fault.retry.RetryStrategy
└── com.yupi.yurpc.fault.tolerant.TolerantStrategy
```

**优势**: 解耦实现与调用，支持动态扩展

---

## 6. 核心流程

### 6.1 Provider启动流程

```
1. 应用启动
   @SpringBootApplication
   @EnableRpc
   ↓
2. RpcInitBootstrap.registerBeanDefinitions()
   - RpcApplication.init()
     ├── 加载配置文件
     ├── 初始化Registry
     └── 注册ShutdownHook
   - 启动VertxTcpServer(8080)
   ↓
3. Spring创建@RpcService的Bean
   @Service
   @RpcService
   class UserServiceImpl implements UserService { ... }
   ↓
4. RpcProviderBootstrap.postProcessAfterInitialization()
   - 检测@RpcService注解
   - LocalRegistry.register(serviceName, beanClass)
   - Registry.register(serviceMetaInfo)
     写入Etcd: /rpc/UserService:1.0/localhost:8080
   ↓
5. 启动心跳任务
   定时任务每10秒续签
   ↓
6. 等待消费者调用
```

### 6.2 Consumer启动流程

```
1. 应用启动
   @SpringBootApplication
   @EnableRpc(needServer=false)
   ↓
2. RpcInitBootstrap.registerBeanDefinitions()
   - RpcApplication.init()
   - 不启动服务器
   ↓
3. Spring创建含@RpcReference字段的Bean
   @Service
   class OrderService {
       @RpcReference
       private UserService userService;
   }
   ↓
4. RpcConsumerBootstrap.postProcessAfterInitialization()
   - 扫描@RpcReference字段
   - ServiceProxyFactory.getProxy(UserService.class)
   - 反射注入代理对象
     field.set(bean, proxyObject)
   ↓
5. 可以调用远程服务
```

### 6.3 RPC调用完整流程

```
Consumer端                                                Provider端
   |                                                           |
   |--1. userService.getUser(user)                            |
   |   调用代理对象                                            |
   |                                                           |
   |--2. ServiceProxy.invoke()                                |
   |   拦截方法调用                                            |
   |                                                           |
   |--3. registry.serviceDiscovery("UserService:1.0")         |
   |   从Etcd获取服务列表: [localhost:8080, localhost:8081]   |
   |                                                           |
   |--4. loadBalancer.select(serviceList)                     |
   |   负载均衡选择: localhost:8080                            |
   |                                                           |
   |--5. retryStrategy.doRetry(() -> { ... })                 |
   |   重试机制包装                                            |
   |                                                           |
   |--6. VertxTcpClient.doRequest(request, serviceInfo)       |
   |   ├── 构造ProtocolMessage                                |
   |   │   Header: 魔数, 版本, 序列化器, 类型REQUEST, 请求ID  |
   |   │   Body: RpcRequest(序列化)                           |
   |   ├── encode编码为字节流                                 |
   |   └── socket.write(buffer)                               |
   |       发送TCP请求─────────────────────────────────>       |
   |                                                           |
   |                                        7. VertxTcpServer接收
   |                                           TcpServerHandler处理
   |                                                           |
   |                                        8. TcpBufferHandlerWrapper
   |                                           解决粘包/半包   |
   |                                                           |
   |                                        9. decode解码      |
   |                                           得到RpcRequest  |
   |                                                           |
   |                                        10. LocalRegistry.get()
   |                                            获取实现类     |
   |                                                           |
   |                                        11. 反射调用方法   |
   |                                            method.invoke()|
   |                                                           |
   |                                        12. 构造RpcResponse|
   |                                                           |
   |                                        13. encode并返回   |
   |   <────────────────────────────────────socket.write()    |
   |                                                           |
   |--14. 接收响应                                             |
   |   TcpBufferHandlerWrapper处理                            |
   |                                                           |
   |--15. decode解码得到RpcResponse                           |
   |                                                           |
   |--16. 重试成功,返回结果                                    |
   |                                                           |
   |<--17. 返回User对象给业务代码                              |
```

---

## 7. 关键特性

### 7.1 服务注册与发现

✅ **多注册中心支持**: Etcd、ZooKeeper、本地内存
✅ **自动心跳续签**: 10秒续签一次，保证服务在线
✅ **Watch监听机制**: 监听服务变化，自动更新缓存
✅ **服务缓存**: 减少注册中心查询压力
✅ **优雅下线**: ShutdownHook确保服务正确注销

### 7.2 自定义网络协议

✅ **固定消息头**: 17字节，便于解析
✅ **魔数校验**: 0x1，防止非法消息
✅ **长度前置**: 解决粘包问题
✅ **RecordParser**: 分阶段解析，解决半包问题
✅ **多序列化支持**: JDK/JSON/Kryo/Hessian

### 7.3 负载均衡

✅ **轮询**: 默认策略，请求均匀分配
✅ **随机**: 随机选择，实现简单
✅ **一致性哈希**: 100虚拟节点，相同请求路由到同一服务

### 7.4 容错机制

✅ **重试机制**: 固定间隔重试（3次，间隔3秒）
✅ **快速失败**: 立刻抛异常，适合核心业务
✅ **静默处理**: 返回空响应，适合非核心业务
✅ **故障转移**: 切换其他节点（待实现）
✅ **故障恢复**: 服务降级（待实现）

### 7.5 Spring Boot集成

✅ **@EnableRpc**: 一键启用RPC框架
✅ **@RpcService**: 自动注册服务
✅ **@RpcReference**: 自动注入代理对象
✅ **零配置**: 基于注解，无需XML
✅ **细粒度配置**: 每个字段可独立配置策略

### 7.6 扩展性

✅ **SPI机制**: 支持插件化扩展
✅ **工厂模式**: 解耦实现与调用
✅ **策略模式**: 多种算法可替换
✅ **配置驱动**: 通过配置文件切换实现

---

## 8. 配置说明

### 8.1 全局配置 (application.properties)

```properties
# RPC框架配置
rpc.name=yu-rpc
rpc.version=1.0
rpc.serverHost=localhost
rpc.serverPort=8080

# 注册中心配置
rpc.registryConfig.registry=etcd
rpc.registryConfig.address=http://localhost:2380
rpc.registryConfig.username=
rpc.registryConfig.password=
rpc.registryConfig.timeout=10000

# 负载均衡器
rpc.loadBalancer=roundRobin
# 可选: roundRobin, random, consistentHash

# 序列化器
rpc.serializer=kryo
# 可选: jdk, json, kryo, hessian

# 重试策略
rpc.retryStrategy=fixedInterval
# 可选: no, fixedInterval

# 容错策略
rpc.tolerantStrategy=failFast
# 可选: failFast, failSafe, failOver, failBack
```

### 8.2 注解配置

```java
// Provider端
@Service
@RpcService(
    interfaceClass = UserService.class,
    serviceVersion = "1.0"
)
public class UserServiceImpl implements UserService { ... }

// Consumer端
@Service
public class OrderService {

    @RpcReference(
        interfaceClass = UserService.class,
        serviceVersion = "1.0",
        loadBalancer = LoadBalancerKeys.CONSISTENT_HASH,
        retryStrategy = RetryStrategyKeys.FIXED_INTERVAL,
        tolerantStrategy = TolerantStrategyKeys.FAIL_SAFE,
        mock = false
    )
    private UserService userService;
}
```

---

## 9. 性能优化

### 9.1 已实现的优化

✅ **异步IO**: 使用Vert.x异步事件驱动
✅ **二进制协议**: 相比HTTP减少传输开销
✅ **服务缓存**: 减少注册中心查询
✅ **连接复用**: TCP长连接
✅ **高效序列化**: Kryo性能优于JDK

### 9.2 可优化的方向

🔄 **连接池**: 管理TCP连接
🔄 **异步调用**: 支持Future/CompletableFuture
🔄 **批量请求**: 合并多个请求
🔄 **压缩**: 支持Gzip/Snappy压缩
🔄 **流量控制**: 限流、熔断

---

## 10. 未来规划

### 10.1 功能扩展

- [ ] 完善FailOver故障转移实现
- [ ] 完善FailBack故障恢复实现
- [ ] 支持指数退避重试策略
- [ ] 支持熔断器模式
- [ ] 支持限流功能
- [ ] 支持链路追踪
- [ ] 支持监控指标采集

### 10.2 性能优化

- [ ] 连接池管理
- [ ] 异步调用支持
- [ ] 批量请求支持
- [ ] 数据压缩
- [ ] 零拷贝优化

### 10.3 易用性

- [ ] 完善文档和示例
- [ ] 提供Spring Boot Starter自动配置
- [ ] 可视化管理界面
- [ ] 性能测试报告

---

## 11. 参考资料

- [Vert.x官方文档](https://vertx.io/docs/)
- [Etcd官方文档](https://etcd.io/docs/)
- [Apache ZooKeeper](https://zookeeper.apache.org/)
- [guava-retrying](https://github.com/rholder/guava-retrying)
- [Kryo序列化](https://github.com/EsotericSoftware/kryo)

---

## 附录：项目结构

```
yu-rpc-core/
├── src/
│   ├── main/
│   │   ├── java/com/yupi/yurpc/
│   │   │   ├── config/              # 配置模块
│   │   │   ├── constant/            # 常量定义
│   │   │   ├── fault/               # 容错模块
│   │   │   │   ├── retry/          # 重试策略
│   │   │   │   └── tolerant/       # 容错策略
│   │   │   ├── loadbalancer/        # 负载均衡
│   │   │   ├── model/               # 数据模型
│   │   │   ├── protocol/            # 自定义协议
│   │   │   ├── proxy/               # 代理模块
│   │   │   ├── registry/            # 注册中心
│   │   │   ├── serializer/          # 序列化器
│   │   │   ├── server/              # 服务器
│   │   │   │   └── tcp/            # TCP服务器
│   │   │   ├── spi/                 # SPI机制
│   │   │   ├── utils/               # 工具类
│   │   │   ├── bootstrap/           # 引导类
│   │   │   └── RpcApplication.java  # 框架入口
│   │   └── resources/
│   │       ├── META-INF/rpc/system/ # SPI配置
│   │       └── application.properties
│   └── test/                        # 单元测试
├── pom.xml
└── ARCHITECTURE.md                  # 本文档
```

---

**文档版本**: v1.0
**最后更新**: 2024年
**维护者**: Aeromtrich
