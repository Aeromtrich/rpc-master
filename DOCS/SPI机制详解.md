# Yu-RPC SPI 机制详解

## 一、概述

Yu-RPC 框架实现了一套自定义的 SPI（Service Provider Interface）机制，用于实现框架的插件化扩展。相比 Java 原生 SPI，该机制提供了更灵活的键值对映射、懒加载、单例缓存等特性。

## 二、核心设计

### 2.1 核心类：SpiLoader

`SpiLoader` 是整个 SPI 机制的核心实现类，位于 `com.yupi.yurpc.spi.SpiLoader`。

**关键数据结构**：

```java
// 双层 Map：接口全限定名 -> (key -> 实现类Class)
private static final Map<String, Map<String, Class<?>>> loaderMap = new ConcurrentHashMap<>();

// 单例缓存：类全限定名 -> 实例对象
private static final Map<String, Object> instanceCache = new ConcurrentHashMap<>();
```

**双层缓存机制**：
- `loaderMap`：存储类加载信息，避免重复扫描配置文件
- `instanceCache`：存储实例对象，实现单例模式，避免重复创建

### 2.2 配置文件扫描路径

```java
private static final String RPC_SYSTEM_SPI_DIR = "META-INF/rpc/system/";
private static final String RPC_CUSTOM_SPI_DIR = "META-INF/rpc/custom/";
private static final String[] SCAN_DIRS = new String[]{RPC_SYSTEM_SPI_DIR, RPC_CUSTOM_SPI_DIR};
```

**双目录设计**：
- `META-INF/rpc/system/`：框架内置实现
- `META-INF/rpc/custom/`：用户自定义实现
- 扫描顺序：先 system 后 custom，**用户自定义 SPI 可以覆盖系统 SPI**

### 2.3 配置文件格式

配置文件采用 **key-value** 格式，文件名为接口的全限定名。

**示例**：`META-INF/rpc/system/com.yupi.yurpc.serializer.Serializer`

```properties
jdk=com.yupi.yurpc.serializer.JdkSerializer
hessian=com.yupi.yurpc.serializer.HessianSerializer
json=com.yupi.yurpc.serializer.JsonSerializer
kryo=com.yupi.yurpc.serializer.KryoSerializer
```

**格式说明**：
- 文件名 = 接口全限定名
- 文件内容 = `key=实现类全限定名`
- 每行一个映射关系
- key 用于在配置中指定使用哪个实现

## 三、核心流程

### 3.1 加载流程

**load() 方法**（SpiLoader.java:104-131）：

```java
public static Map<String, Class<?>> load(Class<?> loadClass) {
    log.info("加载类型为 {} 的 SPI", loadClass.getName());
    Map<String, Class<?>> keyClassMap = new HashMap<>();

    // 遍历扫描目录
    for (String scanDir : SCAN_DIRS) {
        // 1. 扫描配置文件：scanDir + 接口全限定名
        List<URL> resources = ResourceUtil.getResources(scanDir + loadClass.getName());

        // 2. 读取每个资源文件
        for (URL resource : resources) {
            try {
                InputStreamReader inputStreamReader = new InputStreamReader(resource.openStream());
                BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
                String line;

                // 3. 逐行解析配置
                while ((line = bufferedReader.readLine()) != null) {
                    String[] strArray = line.split("=");
                    if (strArray.length > 1) {
                        String key = strArray[0];
                        String className = strArray[1];
                        // 4. 加载 Class 对象（不实例化）
                        keyClassMap.put(key, Class.forName(className));
                    }
                }
            } catch (Exception e) {
                log.error("spi resource load error", e);
            }
        }
    }

    // 5. 缓存到 loaderMap
    loaderMap.put(loadClass.getName(), keyClassMap);
    return keyClassMap;
}
```

**流程图**：

```
开始
  ↓
遍历扫描目录 [system, custom]
  ↓
扫描配置文件 (META-INF/rpc/{dir}/{接口全限定名})
  ↓
逐行读取配置文件
  ↓
解析 key=className
  ↓
Class.forName(className) 加载类
  ↓
存入 keyClassMap
  ↓
缓存到 loaderMap
  ↓
结束
```

**关键点**：
- 使用 Hutool 的 `ResourceUtil.getResources()` 扫描 classpath
- 通过 `Class.forName()` 加载类，但**不实例化**（懒加载）
- 后扫描的目录会覆盖前面的（custom 覆盖 system）
- 只加载 Class 对象，不创建实例，节省内存

### 3.2 实例获取流程

**getInstance() 方法**（SpiLoader.java:74-96）：

```java
public static <T> T getInstance(Class<?> tClass, String key) {
    String tClassName = tClass.getName();

    // 1. 从 loaderMap 获取接口的实现类映射
    Map<String, Class<?>> keyClassMap = loaderMap.get(tClassName);
    if (keyClassMap == null) {
        throw new RuntimeException(String.format("SpiLoader 未加载 %s 类型", tClassName));
    }

    // 2. 根据 key 获取具体实现类
    if (!keyClassMap.containsKey(key)) {
        throw new RuntimeException(String.format("SpiLoader 的 %s 不存在 key=%s 的类型", tClassName, key));
    }
    Class<?> implClass = keyClassMap.get(key);

    // 3. 检查实例缓存
    String implClassName = implClass.getName();
    if (!instanceCache.containsKey(implClassName)) {
        try {
            // 4. 首次创建实例并缓存（单例）
            instanceCache.put(implClassName, implClass.newInstance());
        } catch (InstantiationException | IllegalAccessException e) {
            String errorMsg = String.format("%s 类实例化失败", implClassName);
            throw new RuntimeException(errorMsg, e);
        }
    }

    // 5. 返回缓存的实例
    return (T) instanceCache.get(implClassName);
}
```

**流程图**：

```
开始
  ↓
从 loaderMap 获取接口映射
  ↓
根据 key 获取实现类 Class
  ↓
检查 instanceCache 是否存在实例？
  ↓ 否
创建实例 (newInstance)
  ↓
存入 instanceCache
  ↓ 是
从 instanceCache 获取实例
  ↓
返回实例
  ↓
结束
```

**单例模式**：
- 每个实现类只会被实例化一次
- 使用 `ConcurrentHashMap` 保证线程安全
- 懒加载：只在首次调用 `getInstance()` 时才创建实例

## 四、工厂类集成

框架中的各个可插拔组件都通过工厂类来使用 SPI 机制。

### 4.1 序列化器工厂

**SerializerFactory**（SerializerFactory.java:10-31）：

```java
public class SerializerFactory {

    // 静态代码块：类加载时触发 SPI 扫描
    static {
        SpiLoader.load(Serializer.class);
    }

    private static final Serializer DEFAULT_SERIALIZER = new JdkSerializer();

    // 获取实例的便捷方法
    public static Serializer getInstance(String key) {
        return SpiLoader.getInstance(Serializer.class, key);
    }
}
```

### 4.2 负载均衡器工厂

**LoadBalancerFactory**（LoadBalancerFactory.java:10-31）：

```java
public class LoadBalancerFactory {

    static {
        SpiLoader.load(LoadBalancer.class);
    }

    private static final LoadBalancer DEFAULT_LOAD_BALANCER = new RoundRobinLoadBalancer();

    public static LoadBalancer getInstance(String key) {
        return SpiLoader.getInstance(LoadBalancer.class, key);
    }
}
```

### 4.3 注册中心工厂

**RegistryFactory**（RegistryFactory.java:10-32）：

```java
public class RegistryFactory {

    static {
        SpiLoader.load(Registry.class);
    }

    private static final Registry DEFAULT_REGISTRY = new EtcdRegistry();

    public static Registry getInstance(String key) {
        return SpiLoader.getInstance(Registry.class, key);
    }
}
```

**工厂类设计模式**：
- 静态代码块在类加载时自动触发 SPI 扫描
- 提供 `getInstance(String key)` 方法简化调用
- 提供默认实现作为 fallback

## 五、使用示例

### 5.1 使用系统内置实现

**配置文件**（application.properties）：

```properties
rpc.serializer=json
rpc.loadBalancer=roundRobin
rpc.registryConfig.registry=etcd
```

**代码使用**：

```java
// 框架内部会根据配置自动加载
Serializer serializer = SerializerFactory.getInstance("json");
LoadBalancer loadBalancer = LoadBalancerFactory.getInstance("roundRobin");
Registry registry = RegistryFactory.getInstance("etcd");
```

### 5.2 自定义扩展实现

**步骤 1：实现接口**

```java
package com.example.custom;

public class MyCustomSerializer implements Serializer {
    @Override
    public <T> byte[] serialize(T object) throws IOException {
        // 自定义序列化逻辑
    }

    @Override
    public <T> T deserialize(byte[] bytes, Class<T> type) throws IOException {
        // 自定义反序列化逻辑
    }
}
```

**步骤 2：创建配置文件**

创建文件：`src/main/resources/META-INF/rpc/custom/com.yupi.yurpc.serializer.Serializer`

```properties
myCustom=com.example.custom.MyCustomSerializer
```

**步骤 3：使用自定义实现**

```properties
rpc.serializer=myCustom
```

### 5.3 覆盖系统实现

如果想用自己的实现替换系统内置的 `json` 序列化器：

**配置文件**：`META-INF/rpc/custom/com.yupi.yurpc.serializer.Serializer`

```properties
json=com.example.custom.MyBetterJsonSerializer
```

由于 custom 目录后扫描，会覆盖 system 目录中的 `json` 映射。

## 六、支持的可插拔组件

框架中通过 SPI 机制实现了以下组件的可插拔：

### 6.1 序列化器（Serializer）

**配置文件**：`META-INF/rpc/system/com.yupi.yurpc.serializer.Serializer`

```properties
jdk=com.yupi.yurpc.serializer.JdkSerializer
hessian=com.yupi.yurpc.serializer.HessianSerializer
json=com.yupi.yurpc.serializer.JsonSerializer
kryo=com.yupi.yurpc.serializer.KryoSerializer
```

### 6.2 负载均衡器（LoadBalancer）

**配置文件**：`META-INF/rpc/system/com.yupi.yurpc.loadbalancer.LoadBalancer`

```properties
roundRobin=com.yupi.yurpc.loadbalancer.RoundRobinLoadBalancer
random=com.yupi.yurpc.loadbalancer.RandomLoadBalancer
consistentHash=com.yupi.yurpc.loadbalancer.ConsistentHashLoadBalancer
```

### 6.3 注册中心（Registry）

**配置文件**：`META-INF/rpc/system/com.yupi.yurpc.registry.Registry`

```properties
etcd=com.yupi.yurpc.registry.EtcdRegistry
zookeeper=com.yupi.yurpc.registry.ZooKeeperRegistry
```

### 6.4 重试策略（RetryStrategy）

**配置文件**：`META-INF/rpc/system/com.yupi.yurpc.fault.retry.RetryStrategy`

```properties
no=com.yupi.yurpc.fault.retry.NoRetryStrategy
fixedInterval=com.yupi.yurpc.fault.retry.FixedIntervalRetryStrategy
```

### 6.5 容错策略（TolerantStrategy）

**配置文件**：`META-INF/rpc/system/com.yupi.yurpc.fault.tolerant.TolerantStrategy`

```properties
failFast=com.yupi.yurpc.fault.tolerant.FailFastTolerantStrategy
failSafe=com.yupi.yurpc.fault.tolerant.FailSafeTolerantStrategy
failOver=com.yupi.yurpc.fault.tolerant.FailOverTolerantStrategy
failBack=com.yupi.yurpc.fault.tolerant.FailBackTolerantStrategy
```

## 七、设计优势

### 7.1 与 Java 原生 SPI 对比

| 特性 | Java SPI | Yu-RPC SPI |
|------|----------|------------|
| 配置格式 | 只有实现类列表 | key=实现类（支持命名） |
| 加载时机 | 调用 `ServiceLoader.load()` 时全部加载 | 懒加载，需要时才实例化 |
| 实例管理 | 每次迭代创建新实例 | 单例缓存 |
| 扩展性 | 只能追加 | 支持覆盖（custom 覆盖 system） |
| 选择实现 | 需要遍历所有实现 | 通过 key 直接获取 |
| 线程安全 | 需要自行处理 | 使用 ConcurrentHashMap 保证 |

### 7.2 核心优势

1. **键值对映射**：通过 key 可以精确指定使用哪个实现，无需遍历
2. **双目录机制**：支持系统默认实现和用户自定义扩展，且支持覆盖
3. **懒加载**：只在需要时才实例化对象，节省资源
4. **单例缓存**：避免重复创建对象，提高性能
5. **线程安全**：使用 `ConcurrentHashMap` 保证并发安全
6. **灵活扩展**：用户可以轻松添加自定义实现，无需修改框架代码

## 八、实现细节

### 8.1 线程安全

```java
// 使用 ConcurrentHashMap 保证线程安全
private static final Map<String, Map<String, Class<?>>> loaderMap = new ConcurrentHashMap<>();
private static final Map<String, Object> instanceCache = new ConcurrentHashMap<>();
```

- 多线程环境下，多个线程可能同时调用 `getInstance()`
- `ConcurrentHashMap` 保证了 `put` 和 `get` 操作的线程安全
- 但实例化过程可能存在竞态条件（两个线程同时发现缓存中没有实例）
- 实际影响较小，因为创建的是无状态对象，重复创建不会导致错误

### 8.2 类加载时机

```java
public class SerializerFactory {
    static {
        SpiLoader.load(Serializer.class);  // 类加载时执行
    }
}
```

- 静态代码块在类首次加载时执行
- 首次调用 `SerializerFactory.getInstance()` 时触发类加载
- 此时会扫描配置文件并加载所有实现类的 Class 对象
- 但不会创建实例，实例在首次 `getInstance(key)` 时创建

### 8.3 资源扫描

```java
List<URL> resources = ResourceUtil.getResources(scanDir + loadClass.getName());
```

- 使用 Hutool 的 `ResourceUtil` 扫描 classpath
- 可以扫描到 jar 包内的资源文件
- 支持多个同名文件（例如多个 jar 包都提供了同一个接口的实现）

## 九、最佳实践

### 9.1 添加新的可插拔组件

如果要为框架添加新的可插拔组件类型：

1. 定义接口（例如 `Compressor`）
2. 创建配置文件 `META-INF/rpc/system/com.yupi.yurpc.compressor.Compressor`
3. 创建工厂类：

```java
public class CompressorFactory {
    static {
        SpiLoader.load(Compressor.class);
    }

    public static Compressor getInstance(String key) {
        return SpiLoader.getInstance(Compressor.class, key);
    }
}
```

### 9.2 实现类要求

- 必须有无参构造函数（`newInstance()` 需要）
- 建议实现类是无状态的（因为使用单例模式）
- 如果需要有状态，应该使用线程安全的方式管理状态

### 9.3 配置文件命名

- 文件名必须是接口的**全限定名**
- key 建议使用小驼峰命名（例如 `roundRobin`）
- 实现类必须使用**全限定名**

## 十、总结

Yu-RPC 的 SPI 机制是一个轻量级、高性能的插件化实现方案，核心特点包括：

1. **简单易用**：通过 key-value 配置即可扩展
2. **高性能**：懒加载 + 单例缓存
3. **灵活扩展**：支持系统实现和用户自定义实现
4. **线程安全**：使用 ConcurrentHashMap 保证并发安全
5. **可覆盖**：用户自定义实现可以覆盖系统实现

这种设计非常适合 RPC 框架的插件化需求，使得框架具有良好的扩展性和可维护性。
