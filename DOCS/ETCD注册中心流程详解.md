# ETCD注册中心流程详解

> 本文档详细介绍Yu-RPC框架中ETCD注册中心的完整实现流程

## 目录

- [一、核心组件概览](#一核心组件概览)
- [二、初始化流程](#二初始化流程)
- [三、服务注册流程](#三服务注册流程provider端)
- [四、心跳续期流程](#四心跳续期流程provider端)
- [五、服务发现流程](#五服务发现流程consumer端)
- [六、服务监听流程](#六服务监听流程consumer端)
- [七、服务注销流程](#七服务注销流程provider端)
- [八、完整调用链路图](#八完整调用链路图)
- [九、关键数据结构](#九关键数据结构)
- [十、核心机制总结](#十核心机制总结)

---

# 搭建etcd
Etcd 3.5.15
几个特性：
Lease租约 租约3s 3s过期后相关的键值对会被删除
Watch监听  监视特定键的变化 值发生变化会触发通知

可视化工具 etcdkeeper https://github.com/evildecay/etcdkeeper/
./etcdkeeper -p 8081
Chmod + x etcdkeeper 加上权限
访问localhost:8081/etcdkeeper/ 就可以看到可视化界面了

客户端工具
jetcd https://github.com/etcd-io/jetcd java版本>11

## 一、核心组件概览

### 1.1 关键类

| 类名 | 职责 | 位置 |
|------|------|------|
| **EtcdRegistry** | ETCD注册中心实现类 | `yu-rpc-core/src/main/java/com/yupi/yurpc/registry/EtcdRegistry.java` |
| **Registry** | 注册中心接口 | `yu-rpc-core/src/main/java/com/yupi/yurpc/registry/Registry.java` |
| **RegistryFactory** | 注册中心工厂（SPI加载） | `yu-rpc-core/src/main/java/com/yupi/yurpc/registry/RegistryFactory.java` |
| **ServiceMetaInfo** | 服务元信息 | `yu-rpc-core/src/main/java/com/yupi/yurpc/model/ServiceMetaInfo.java` |
| **RegistryServiceCache** | 服务缓存 | `yu-rpc-core/src/main/java/com/yupi/yurpc/registry/RegistryServiceCache.java` |

### 1.2 ETCD存储结构

```
/rpc/
  └─ {serviceName}:{version}/
      ├─ {serviceName}:{version}/{host}:{port}  → ServiceMetaInfo (JSON)
      ├─ {serviceName}:{version}/{host}:{port}  → ServiceMetaInfo (JSON)
      └─ ...
```

**存储示例：**
```
Key:   /rpc/com.yupi.example.UserService:1.0/localhost:8080
Value: {"serviceName":"com.yupi.example.UserService","serviceVersion":"1.0","serviceHost":"localhost","servicePort":8080}
```

---

## 二、初始化流程

### 2.1 流程图

```
应用启动
  ↓
RpcApplication.init()
  ↓
RegistryFactory.getInstance("etcd")  ← SPI加载
  ↓
EtcdRegistry.init(registryConfig)
  ↓
创建ETCD Client
  ├─ 连接ETCD服务器
  ├─ 创建KV客户端
  └─ 启动心跳任务 heartBeat()
```

### 2.2 代码实现

**位置：** `EtcdRegistry.java:54-61`

```java
@Override
public void init(RegistryConfig registryConfig) {
    // 1. 创建ETCD客户端
    client = Client.builder()
            .endpoints(registryConfig.getAddress())  // ETCD地址，如：http://localhost:2380
            .connectTimeout(Duration.ofMillis(registryConfig.getTimeout()))
            .build();

    // 2. 创建KV客户端（用于读写操作）
    kvClient = client.getKVClient();

    // 3. 启动心跳机制
    heartBeat();
}
```

### 2.3 配置示例

```properties
# application.properties
rpc.registryConfig.registry=etcd
rpc.registryConfig.address=http://localhost:2380
rpc.registryConfig.timeout=10000
```

---

## 三、服务注册流程（Provider端）

### 3.1 流程图

```
Provider启动
  ↓
ProviderBootstrap.init(serviceList)
  ↓
遍历每个服务
  ↓
LocalRegistry.register()  ← 本地注册（用于反射调用）
  ↓
Registry.register(serviceMetaInfo)  ← ETCD注册
  ↓
EtcdRegistry.register()
  ├─ 创建30秒租约 (Lease)
  ├─ 构造Key: /rpc/{serviceKey}/{host}:{port}
  ├─ 构造Value: ServiceMetaInfo的JSON
  ├─ 关联租约并存储到ETCD
  └─ 添加到本地缓存 localRegisterNodeKeySet
```

### 3.2 Bootstrap层调用

**位置：** `ProviderBootstrap.java:32-50`

```java
// 注册服务
    for (ServiceRegisterInfo<?> serviceRegisterInfo : serviceRegisterInfoList) {
    String serviceName = serviceRegisterInfo.getServiceName();

    // 1. 本地注册（用于反射调用）
    LocalRegistry.register(serviceName, serviceRegisterInfo.getImplClass());

    // 2. 注册到ETCD
    RegistryConfig registryConfig = rpcConfig.getRegistryConfig();
    Registry registry = RegistryFactory.getInstance(registryConfig.getRegistry());

    ServiceMetaInfo serviceMetaInfo = new ServiceMetaInfo();
    serviceMetaInfo.setServiceName(serviceName);
    serviceMetaInfo.setServiceHost(rpcConfig.getServerHost());
    serviceMetaInfo.setServicePort(rpcConfig.getServerPort());

    try {
        registry.register(serviceMetaInfo);  // ← 调用ETCD注册
    } catch (Exception e) {
        throw new RuntimeException(serviceName + " 服务注册失败", e);
    }
}
```

### 3.3 ETCD注册实现

**位置：** `EtcdRegistry.java:64-81`

```java
@Override
public void register(ServiceMetaInfo serviceMetaInfo) throws Exception {
    // 1. 创建Lease客户端
    Lease leaseClient = client.getLeaseClient();

    // 2. 创建30秒的租约（TTL=30秒）
    long leaseId = leaseClient.grant(30).get().getID();

    // 3. 构造Key和Value
    String registerKey = ETCD_ROOT_PATH + serviceMetaInfo.getServiceNodeKey();
    // Key示例: /rpc/com.yupi.UserService:1.0/localhost:8080

    ByteSequence key = ByteSequence.from(registerKey, StandardCharsets.UTF_8);
    ByteSequence value = ByteSequence.from(
        JSONUtil.toJsonStr(serviceMetaInfo),  // 序列化为JSON
        StandardCharsets.UTF_8
    );

    // 4. 关联租约并存储（30秒后自动过期）
    PutOption putOption = PutOption.builder().withLeaseId(leaseId).build();
    kvClient.put(key, value, putOption).get();

    // 5. 添加到本地缓存（用于心跳续期）
    localRegisterNodeKeySet.add(registerKey);
}
```

### 3.4 关键机制

- **租约机制（Lease）**：服务注册时创建30秒TTL的租约
- **自动过期**：如果30秒内没有续期，ETCD会自动删除该Key
- **本地缓存**：记录本机注册的所有Key，用于心跳续期

---

## 四、心跳续期流程（Provider端）

### 4.1 流程图

```
heartBeat() 启动
  ↓
定时任务：每10秒执行一次
  ↓
遍历 localRegisterNodeKeySet
  ↓
对每个Key：
  ├─ 从ETCD查询Key是否存在
  ├─ 如果已过期 → 跳过（需要重启）
  └─ 如果未过期 → 重新注册（续期）
```

### 4.2 代码实现

**位置：** `EtcdRegistry.java:129-159`

```java
@Override
public void heartBeat() {
    // 定时任务：每10秒执行一次
    CronUtil.schedule("*/10 * * * * *", new Task() {
        @Override
        public void execute() {
            // 遍历本节点注册的所有Key
            for (String key : localRegisterNodeKeySet) {
                try {
                    // 1. 查询Key是否还存在
                    List<KeyValue> keyValues = kvClient.get(
                        ByteSequence.from(key, StandardCharsets.UTF_8)
                    ).get().getKvs();

                    // 2. 如果Key已过期（被ETCD删除）
                    if (CollUtil.isEmpty(keyValues)) {
                        continue;  // 跳过，需要重启服务
                    }

                    // 3. Key未过期，重新注册（相当于续期）
                    KeyValue keyValue = keyValues.get(0);
                    String value = keyValue.getValue().toString(StandardCharsets.UTF_8);
                    ServiceMetaInfo serviceMetaInfo = JSONUtil.toBean(value, ServiceMetaInfo.class);

                    // 重新注册 = 创建新租约 + 重新put
                    register(serviceMetaInfo);

                } catch (Exception e) {
                    throw new RuntimeException(key + "续签失败", e);
                }
            }
        }
    });

    // 启动定时任务（支持秒级）
    CronUtil.setMatchSecond(true);
    CronUtil.start();
}
```

### 4.3 续期机制

| 参数 | 值 | 说明 |
|------|-----|------|
| **租约TTL** | 30秒 | 服务注册时的过期时间 |
| **续期间隔** | 10秒 | 心跳任务执行频率 |
| **续期方式** | 重新注册 | 创建新租约并重新put |
| **容错** | 3次机会 | 30秒内有3次续期机会 |

---

## 五、服务发现流程（Consumer端）

### 5.1 流程图

```
Consumer调用服务
  ↓
ServiceProxy.invoke()
  ↓
Registry.serviceDiscovery(serviceKey)
  ↓
EtcdRegistry.serviceDiscovery()
  ├─ 1. 先查缓存 registryServiceCache
  │   └─ 有缓存 → 直接返回
  ├─ 2. 无缓存 → 查询ETCD
  │   ├─ 前缀查询: /rpc/{serviceKey}/
  │   ├─ 解析所有KeyValue
  │   ├─ 对每个Key启动watch监听
  │   └─ 写入缓存
  └─ 3. 返回服务列表
```

### 5.2 Proxy层调用

**位置：** `ServiceProxy.java:55-65`

```java
// Consumer端调用
RpcConfig rpcConfig = RpcApplication.getRpcConfig();
Registry registry = RegistryFactory.getInstance(
    rpcConfig.getRegistryConfig().getRegistry()
);

ServiceMetaInfo serviceMetaInfo = new ServiceMetaInfo();
serviceMetaInfo.setServiceName(serviceName);
serviceMetaInfo.setServiceVersion(RpcConstant.DEFAULT_SERVICE_VERSION);

// 服务发现
List<ServiceMetaInfo> serviceMetaInfoList =
    registry.serviceDiscovery(serviceMetaInfo.getServiceKey());

if (CollUtil.isEmpty(serviceMetaInfoList)) {
    throw new RuntimeException("暂无服务地址");
}
```

### 5.3 服务发现实现

**位置：** `EtcdRegistry.java:92-126`

```java
@Override
public List<ServiceMetaInfo> serviceDiscovery(String serviceKey) {
    // 1. 优先从缓存获取
    List<ServiceMetaInfo> cachedServiceMetaInfoList =
        registryServiceCache.readCache();
    if (cachedServiceMetaInfoList != null) {
        return cachedServiceMetaInfoList;  // 缓存命中
    }

    // 2. 缓存未命中，查询ETCD
    // 前缀搜索: /rpc/com.yupi.UserService:1.0/
    String searchPrefix = ETCD_ROOT_PATH + serviceKey + "/";

    try {
        // 3. 前缀查询（获取该服务的所有节点）
        GetOption getOption = GetOption.builder().isPrefix(true).build();
        List<KeyValue> keyValues = kvClient.get(
            ByteSequence.from(searchPrefix, StandardCharsets.UTF_8),
            getOption
        ).get().getKvs();

        // 4. 解析服务信息
        List<ServiceMetaInfo> serviceMetaInfoList = keyValues.stream()
            .map(keyValue -> {
                String key = keyValue.getKey().toString(StandardCharsets.UTF_8);

                // 5. 对每个Key启动监听
                watch(key);

                // 6. 解析Value（JSON → ServiceMetaInfo）
                String value = keyValue.getValue().toString(StandardCharsets.UTF_8);
                return JSONUtil.toBean(value, ServiceMetaInfo.class);
            })
            .collect(Collectors.toList());

        // 7. 写入缓存
        registryServiceCache.writeCache(serviceMetaInfoList);

        return serviceMetaInfoList;

    } catch (Exception e) {
        throw new RuntimeException("获取服务列表失败", e);
    }
}
```

### 5.4 缓存机制

- **首次查询**：从ETCD获取，写入缓存
- **后续查询**：直接读缓存
- **缓存失效**：Watch监听到DELETE事件时清空

---

## 六、服务监听流程（Consumer端）

### 6.1 流程图

```
serviceDiscovery() 发现服务
  ↓
对每个服务节点Key调用 watch(key)
  ↓
EtcdRegistry.watch()
  ├─ 检查是否已监听（watchingKeySet）
  ├─ 未监听 → 启动Watch
  └─ 监听事件：
      ├─ DELETE事件 → 清空缓存
      └─ PUT事件 → 忽略
```

### 6.2 代码实现

**位置：** `EtcdRegistry.java:167-187`

```java
@Override
public void watch(String serviceNodeKey) {
    Watch watchClient = client.getWatchClient();

    // 1. 检查是否已监听（避免重复监听）
    boolean newWatch = watchingKeySet.add(serviceNodeKey);

    if (newWatch) {
        // 2. 启动监听
        watchClient.watch(
            ByteSequence.from(serviceNodeKey, StandardCharsets.UTF_8),
            response -> {
                // 3. 处理Watch事件
                for (WatchEvent event : response.getEvents()) {
                    switch (event.getEventType()) {
                        case DELETE:
                            // 服务节点被删除（下线/过期）
                            // 清空缓存，下次查询时重新从ETCD获取
                            registryServiceCache.clearCache();
                            break;
                        case PUT:
                            // 服务节点更新（暂不处理）
                        default:
                            break;
                    }
                }
            }
        );
    }
}
```

### 6.3 监听机制

| 事件类型 | 触发场景 | 处理方式 |
|---------|---------|---------|
| **DELETE** | 服务下线、租约过期 | 清空缓存 |
| **PUT** | 服务注册、更新 | 暂不处理 |

---

## 七、服务注销流程（Provider端）

### 7.1 手动注销

**位置：** `EtcdRegistry.java:84-89`

```java
@Override
public void unRegister(ServiceMetaInfo serviceMetaInfo) {
    String registerKey = ETCD_ROOT_PATH + serviceMetaInfo.getServiceNodeKey();

    // 从ETCD删除
    kvClient.delete(ByteSequence.from(registerKey, StandardCharsets.UTF_8));

    // 从本地缓存移除
    localRegisterNodeKeySet.remove(registerKey);
}
```

### 7.2 应用关闭时注销

**位置：** `EtcdRegistry.java:190-209`

```java
@Override
public void destroy() {
    System.out.println("当前节点下线");

    // 1. 删除所有注册的服务节点
    for (String key : localRegisterNodeKeySet) {
        try {
            kvClient.delete(ByteSequence.from(key, StandardCharsets.UTF_8)).get();
        } catch (Exception e) {
            throw new RuntimeException(key + "节点下线失败");
        }
    }

    // 2. 释放资源
    if (kvClient != null) {
        kvClient.close();
    }
    if (client != null) {
        client.close();
    }
}
```

---

## 八、完整调用链路图

```
┌─────────────────────────────────────────────────────────────┐
│                        ETCD Server                           │
│  /rpc/                                                       │
│    └─ com.yupi.UserService:1.0/                             │
│        ├─ localhost:8080 → {"serviceName":"...", ...}       │
│        └─ localhost:8081 → {"serviceName":"...", ...}       │
└─────────────────────────────────────────────────────────────┘
         ↑ register (30s租约)          ↓ serviceDiscovery
         │ heartBeat (10s续期)          │ watch (监听变化)
         │                              │
┌────────┴────────┐            ┌───────┴────────┐
│   Provider端     │            │   Consumer端    │
├─────────────────┤            ├────────────────┤
│ 1. init()       │            │ 1. init()      │
│    └─ ETCD连接  │            │    └─ ETCD连接 │
│                 │            │                │
│ 2. register()   │            │ 2. invoke()    │
│    ├─ 创建租约  │            │    ↓           │
│    ├─ put KV    │            │ 3. discovery() │
│    └─ 本地缓存  │            │    ├─ 查缓存   │
│                 │            │    ├─ 查ETCD   │
│ 3. heartBeat()  │            │    ├─ watch    │
│    └─ 每10s续期 │            │    └─ 写缓存   │
│                 │            │                │
│ 4. destroy()    │            │ 4. watch事件   │
│    └─ 删除Key   │            │    └─ 清缓存   │
└─────────────────┘            └────────────────┘
```

---

## 九、关键数据结构

### 9.1 ServiceMetaInfo（服务元信息）

```java
{
    "serviceName": "com.yupi.example.UserService",
    "serviceVersion": "1.0",
    "serviceHost": "localhost",
    "servicePort": 8080,
    "serviceGroup": "default"
}
```

### 9.2 Key命名规则

**位置：** `ServiceMetaInfo.java:45-58`

```java
// 服务Key（用于前缀查询）
getServiceKey() → "com.yupi.UserService:1.0"

// 服务节点Key（唯一标识一个服务实例）
getServiceNodeKey() → "com.yupi.UserService:1.0/localhost:8080"

// ETCD完整Key
ETCD_ROOT_PATH + getServiceNodeKey()
→ "/rpc/com.yupi.UserService:1.0/localhost:8080"
```

### 9.3 EtcdRegistry核心字段

```java
public class EtcdRegistry implements Registry {
    // ETCD客户端
    private Client client;
    private KV kvClient;

    // 本机注册的节点Key集合（用于心跳续期）
    private final Set<String> localRegisterNodeKeySet = new HashSet<>();

    // 服务缓存
    private final RegistryServiceCache registryServiceCache = new RegistryServiceCache();

    // 正在监听的Key集合（避免重复监听）
    private final Set<String> watchingKeySet = new ConcurrentHashSet<>();

    // 根节点
    private static final String ETCD_ROOT_PATH = "/rpc/";
}
```

---

## 十、核心机制总结

### 10.1 机制对比表

| 机制 | 实现方式 | 关键参数 | 作用 |
|------|---------|---------|------|
| **服务注册** | ETCD KV + Lease租约 | TTL=30秒 | 将服务信息存储到ETCD |
| **心跳续期** | 定时任务重新注册 | 间隔10秒 | 保持服务在线状态 |
| **服务发现** | 前缀查询 + 本地缓存 | 缓存优先 | 获取可用服务列表 |
| **变更监听** | ETCD Watch机制 | DELETE清缓存 | 感知服务上下线 |
| **服务注销** | 删除Key + 关闭连接 | destroy() | 优雅下线 |

### 10.2 时间参数

```
租约TTL：30秒
心跳间隔：10秒
容错次数：3次（30秒内有3次续期机会）

时间轴：
0s    10s   20s   30s   40s
|-----|-----|-----|-----|
注册   续期1  续期2  续期3  ...
```

### 10.3 SPI配置

**位置：** `yu-rpc-core/src/main/resources/META-INF/rpc/system/com.yupi.yurpc.registry.Registry`

```properties
etcd=com.yupi.yurpc.registry.EtcdRegistry
zookeeper=com.yupi.yurpc.registry.ZooKeeperRegistry
```

### 10.4 使用示例

```properties
# application.properties
rpc.registryConfig.registry=etcd
rpc.registryConfig.address=http://localhost:2380
rpc.registryConfig.timeout=10000
```

---

## 附录：常见问题

### Q1: 为什么心跳间隔是10秒，租约是30秒？

**A:** 这是一个经典的"3次容错"设计：
- 30秒内有3次续期机会（10s、20s、30s）
- 即使2次续期失败，第3次成功仍能保持服务在线
- 平衡了网络开销和服务可用性

### Q2: 服务下线后，Consumer多久能感知到？

**A:** 取决于两种情况：
1. **优雅下线**（调用destroy）：立即删除Key，Watch立即触发，Consumer立即感知
2. **非优雅下线**（进程崩溃）：最多30秒（租约过期），Watch触发，Consumer感知

### Q3: 为什么服务发现要启动Watch？

**A:** 实现动态服务发现：
- 服务下线时，Watch监听到DELETE事件
- 清空缓存，下次调用时重新查询ETCD
- 避免调用已下线的服务

### Q4: 缓存会不会导致数据不一致？

**A:** 不会，因为：
- Watch机制保证服务下线时立即清空缓存
- 下次调用时重新从ETCD获取最新数据
- 缓存只用于优化性能，不影响正确性

---

**文档版本：** v1.0
**最后更新：** 2026-01-27
**作者：** Claude Code
