# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Yu-RPC is a high-performance RPC framework built from scratch using Java + Etcd + Vert.x. The project demonstrates building a production-grade RPC framework with pluggable components, distributed service discovery, and fault tolerance mechanisms.

## Build and Development Commands

This is a Maven multi-module project. All modules must be built from their respective directories.

### Building the Project

```bash
# Build core RPC framework
cd yu-rpc-core && mvn clean install

# Build easy version (simplified for learning)
cd yu-rpc-easy && mvn clean install

# Build Spring Boot starter
cd yu-rpc-spring-boot-starter && mvn clean install

# Build example modules (requires core to be installed first)
cd example-common && mvn clean install
cd example-provider && mvn clean install
cd example-consumer && mvn clean install
```

### Running Tests

```bash
# Run all tests in core module
cd yu-rpc-core && mvn test

# Run specific test class
cd yu-rpc-core && mvn test -Dtest=RegistryTest

# Run specific test method
cd yu-rpc-core && mvn test -Dtest=LoadBalancerTest#testRoundRobin
```

### Running Examples

**Non-Spring Boot Examples:**

```bash
# Run provider (starts TCP server on port 8080)
cd example-provider
mvn exec:java -Dexec.mainClass="com.yupi.example.provider.ProviderExample"

# Run consumer (in separate terminal)
cd example-consumer
mvn exec:java -Dexec.mainClass="com.yupi.example.consumer.ConsumerExample"
```

**Spring Boot Examples:**

```bash
# Run Spring Boot provider
cd example-springboot-provider
mvn spring-boot:run

# Run Spring Boot consumer (in separate terminal)
cd example-springboot-consumer
mvn spring-boot:run
```

## High-Level Architecture

### Module Structure

- **yu-rpc-core**: Full-featured RPC framework with all capabilities
- **yu-rpc-easy**: Simplified version for learning (basic Vert.x server/client only)
- **yu-rpc-spring-boot-starter**: Annotation-driven Spring Boot integration
- **example-common**: Shared service interfaces for examples
- **example-provider/consumer**: Programmatic API examples
- **example-springboot-provider/consumer**: Spring Boot annotation-driven examples

### Core Framework Architecture (yu-rpc-core)

The framework follows a layered architecture with pluggable components:

**1. Bootstrap Layer** (`bootstrap` package)
- `ProviderBootstrap`: Initializes service providers - registers services to local and distributed registries, starts TCP server
- `ConsumerBootstrap`: Initializes service consumers - loads configuration, initializes registry client

**2. Proxy Layer** (`proxy` package)
- Uses JDK dynamic proxy to intercept method calls
- `ServiceProxy`: Implements `InvocationHandler`, converts method calls to RPC requests
- `ServiceProxyFactory`: Creates proxy instances with optional mock mode
- Handles the entire RPC invocation flow: service discovery → load balancing → retry → network call → fault tolerance

**3. Protocol Layer** (`protocol` package)
- Custom binary protocol with fixed-length header (17 bytes) + variable-length body
- Header: magic(1) + version(1) + serializer(1) + type(1) + status(1) + requestId(8) + bodyLength(4)
- `ProtocolMessageEncoder/Decoder`: Handles encoding/decoding with Vert.x Buffer
- Supports request/response/heartbeat message types

**4. Network Layer** (`server.tcp` package)
- Built on Vert.x for async, non-blocking I/O
- `VertxTcpServer`: Listens for incoming connections, delegates to `TcpServerHandler`
- `VertxTcpClient`: Sends RPC requests over TCP
- `TcpServerHandler`: Decodes requests, invokes local services via reflection, encodes responses

**5. Registry Layer** (`registry` package)
- Abstracted `Registry` interface for service registration and discovery
- Implementations: `LocalRegistry` (in-memory), `EtcdRegistry`, `ZooKeeperRegistry`
- `RegistryServiceCache`: Caches discovered services to reduce registry queries
- Supports service heartbeat and watch mechanisms

**6. Serialization Layer** (`serializer` package)
- Pluggable serializers via SPI: JDK, Hessian, JSON, Kryo
- `SerializerFactory` loads implementations from `META-INF/rpc/system/` or `META-INF/rpc/custom/`

**7. Load Balancing** (`loadbalancer` package)
- Strategies: Round Robin, Random, Consistent Hash
- Selected via configuration, loaded through SPI

**8. Fault Tolerance** (`fault` package)
- **Retry strategies** (`fault.retry`): NoRetry, FixedInterval (uses Guava Retrying)
- **Tolerant strategies** (`fault.tolerant`): FailFast, FailSafe, FailOver, FailBack
- Applied in `ServiceProxy` during RPC invocation

**9. SPI System** (`spi` package)
- Custom Service Provider Interface implementation
- Loads implementations from `META-INF/rpc/system/` (built-in) and `META-INF/rpc/custom/` (user-defined)
- Format: `key=fully.qualified.ClassName`
- Supports singleton caching and lazy loading

### Request Flow

**Consumer → Provider:**
1. Consumer calls proxy method
2. `ServiceProxy.invoke()` intercepts call
3. Constructs `RpcRequest` with method metadata
4. Queries registry for service instances (`Registry.serviceDiscovery()`)
5. Selects instance via load balancer (`LoadBalancer.select()`)
6. Wraps in `ProtocolMessage` and encodes with serializer
7. `RetryStrategy.doRetry()` wraps `VertxTcpClient.doRequest()`
8. Sends over TCP connection
9. On failure, applies `TolerantStrategy.doTolerant()`

**Provider Processing:**
1. `VertxTcpServer` receives connection
2. `TcpServerHandler` decodes `ProtocolMessage`
3. Extracts `RpcRequest` from message body
4. Looks up service in `LocalRegistry`
5. Invokes method via reflection
6. Constructs `RpcResponse` with result or exception
7. Encodes response and sends back

### Configuration

Configuration is loaded from `application.properties` or `application.yml` with prefix `rpc.`:

```properties
rpc.name=yurpc
rpc.version=2.0
rpc.serverHost=localhost
rpc.serverPort=8080
rpc.mock=false
rpc.serializer=jdk  # or hessian, json, kryo
rpc.registryConfig.registry=etcd  # or zookeeper
rpc.registryConfig.address=http://localhost:2380
rpc.loadBalancer=roundRobin  # or random, consistentHash
rpc.retryStrategy=fixedInterval
rpc.tolerantStrategy=failFast
```

### Spring Boot Integration

The `yu-rpc-spring-boot-starter` provides annotation-driven development:

- `@EnableRpc`: Enables RPC framework on Spring Boot application
- `@RpcService`: Marks a class as RPC service provider (auto-registers)
- `@RpcReference`: Injects RPC service proxy into consumer fields

**Initialization Flow:**
1. `@EnableRpc` imports `RpcInitBootstrap`
2. `RpcInitBootstrap` implements `InitializingBean`, calls `RpcApplication.init()`
3. `RpcProviderBootstrap` scans for `@RpcService` beans, registers them
4. `RpcConsumerBootstrap` scans for `@RpcReference` fields, injects proxies

### Key Design Patterns

1. **Factory Pattern**: All pluggable components use factories (`SerializerFactory`, `RegistryFactory`, `LoadBalancerFactory`, etc.)
2. **Strategy Pattern**: Multiple implementations for serialization, load balancing, retry, fault tolerance
3. **Proxy Pattern**: JDK dynamic proxy for transparent RPC invocation
4. **SPI Pattern**: Custom implementation for plugin architecture
5. **Singleton Pattern**: `RpcApplication` uses double-checked locking for global config

### Important Implementation Notes

**When modifying serializers:**
- Add implementation in `serializer` package
- Register in `META-INF/rpc/system/com.yupi.yurpc.serializer.Serializer`
- Format: `key=com.yupi.yurpc.serializer.YourSerializer`

**When adding load balancers:**
- Implement `LoadBalancer` interface
- Register in `META-INF/rpc/system/com.yupi.yurpc.loadbalancer.LoadBalancer`

**When adding registry implementations:**
- Implement `Registry` interface (register, unregister, serviceDiscovery, heartBeat, watch, destroy)
- Register in `META-INF/rpc/system/com.yupi.yurpc.registry.Registry`

**Protocol modifications:**
- Header structure is fixed at 17 bytes - changing it breaks compatibility
- Magic number (0xA1) and version (0x01) are in `ProtocolConstant`
- Body is serialized `RpcRequest` or `RpcResponse`

**Reflection and service invocation:**
- `LocalRegistry` stores `serviceName → Class` mapping
- `TcpServerHandler` uses `Method.invoke()` to call actual service methods
- Service names must match exactly between provider and consumer

### Testing Infrastructure

Tests are located in `yu-rpc-core/src/test/java/`:
- `RegistryTest`: Tests Etcd/ZooKeeper registry operations
- `LoadBalancerTest`: Tests load balancing algorithms
- `ProtocolMessageTest`: Tests protocol encoding/decoding
- `RetryStrategyTest`: Tests retry mechanisms

### Dependencies

- **Vert.x 4.5.1**: Async networking framework
- **Etcd (jetcd 0.7.7)**: Distributed registry
- **ZooKeeper (curator 5.6.0)**: Alternative registry
- **Guava Retrying 2.0.0**: Retry mechanism
- **Hutool 5.8.16**: Utility library
- **Logback 1.3.12**: Logging
- **Lombok 1.18.30**: Boilerplate reduction
- **Java 8**: Target version

### Common Pitfalls

1. **Module build order**: Must build `yu-rpc-core` before example modules
2. **Registry requirement**: Etcd or ZooKeeper must be running for distributed examples
3. **Port conflicts**: Default server port is 8080, configurable via `rpc.serverPort`
4. **Serialization compatibility**: Consumer and provider must use same serializer
5. **Service name matching**: Service interface fully qualified name must match exactly
