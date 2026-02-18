# Yu-RPC-Core 自定义 TCP 序列化协议设计详解

## 一、协议整体结构

协议采用 **固定长度消息头 + 可变长度消息体** 的设计:

```
+---------------------------------------------------------------+
|  Header (17 bytes)                    |  Body (variable)     |
+---------------------------------------------------------------+
```

### 1.1 消息头结构 (17字节固定)

从 `ProtocolMessage.java:31-66` 可以看到完整的消息头定义:

```
+------+--------+------------+------+--------+-----------+------------+
| magic| version| serializer | type | status | requestId | bodyLength |
| 1B   | 1B     | 1B         | 1B   | 1B     | 8B        | 4B         |
+------+--------+------------+------+--------+-----------+------------+
```

各字段含义:

| 字段 | 长度 | 偏移位置 | 说明 |
|------|------|----------|------|
| **magic** | 1字节 | 0 | 魔数 `0x1`,用于协议识别和安全校验 |
| **version** | 1字节 | 1 | 协议版本号 `0x1`,支持协议演进 |
| **serializer** | 1字节 | 2 | 序列化器类型 (0=JDK, 1=JSON, 2=Kryo, 3=Hessian) |
| **type** | 1字节 | 3 | 消息类型 (0=REQUEST, 1=RESPONSE, 2=HEART_BEAT, 3=OTHERS) |
| **status** | 1字节 | 4 | 状态码 (20=OK, 40=BAD_REQUEST, 50=BAD_RESPONSE) |
| **requestId** | 8字节 | 5-12 | 请求唯一标识,用于请求响应匹配 |
| **bodyLength** | 4字节 | 13-16 | 消息体长度,用于解决粘包问题 |

**核心常量定义** (`ProtocolConstant.java`):

```java
public interface ProtocolConstant {
    int MESSAGE_HEADER_LENGTH = 17;      // 消息头固定长度
    byte PROTOCOL_MAGIC = 0x1;           // 协议魔数
    byte PROTOCOL_VERSION = 0x1;         // 协议版本号
}
```

### 1.2 消息体结构 (可变长度)

消息体是序列化后的 `RpcRequest` 或 `RpcResponse` 对象,长度由 header 中的 `bodyLength` 指定。

**消息体内容**:
- **请求消息**: 序列化的 `RpcRequest` 对象 (包含服务名、方法名、参数类型、参数值等)
- **响应消息**: 序列化的 `RpcResponse` 对象 (包含返回值、异常信息等)

## 二、编码器设计

### 2.1 编码流程

从 `ProtocolMessageEncoder.java:23-47` 可以看到完整的编码实现:

```java
public static Buffer encode(ProtocolMessage<?> protocolMessage) throws IOException {
    if (protocolMessage == null || protocolMessage.getHeader() == null) {
        return Buffer.buffer();
    }
    ProtocolMessage.Header header = protocolMessage.getHeader();

    // 1. 创建 Vert.x Buffer
    Buffer buffer = Buffer.buffer();

    // 2. 按顺序写入消息头各字段
    buffer.appendByte(header.getMagic());        // 偏移0
    buffer.appendByte(header.getVersion());      // 偏移1
    buffer.appendByte(header.getSerializer());   // 偏移2
    buffer.appendByte(header.getType());         // 偏移3
    buffer.appendByte(header.getStatus());       // 偏移4
    buffer.appendLong(header.getRequestId());    // 偏移5-12

    // 3. 根据序列化器类型序列化消息体
    ProtocolMessageSerializerEnum serializerEnum =
        ProtocolMessageSerializerEnum.getEnumByKey(header.getSerializer());
    if (serializerEnum == null) {
        throw new RuntimeException("序列化协议不存在");
    }
    Serializer serializer = SerializerFactory.getInstance(serializerEnum.getValue());
    byte[] bodyBytes = serializer.serialize(protocolMessage.getBody());

    // 4. 写入消息体长度和数据
    buffer.appendInt(bodyBytes.length);          // 偏移13-16
    buffer.appendBytes(bodyBytes);               // 偏移17+

    return buffer;
}
```

### 2.2 编码设计亮点

1. **使用 Vert.x Buffer**: 高效的字节操作,避免频繁的数组拷贝
2. **插拔式序列化器**: 通过 SPI 机制加载不同的序列化实现
3. **精确长度计算**: 先序列化 body 获取长度,再写入 header,保证 bodyLength 准确
4. **顺序写入**: 严格按照协议定义的字段顺序写入,保证跨语言兼容性

### 2.3 序列化器枚举

从 `ProtocolMessageSerializerEnum.java` 可以看到支持的序列化器:

```java
public enum ProtocolMessageSerializerEnum {
    JDK(0, "jdk"),
    JSON(1, "json"),
    KRYO(2, "kryo"),
    HESSIAN(3, "hessian");

    private final int key;      // 协议中的标识
    private final String value; // 序列化器名称
}
```

## 三、解码器设计

### 3.1 解码流程

从 `ProtocolMessageDecoder.java:25-64` 可以看到完整的解码实现:

```java
public static ProtocolMessage<?> decode(Buffer buffer) throws IOException {
    // 1. 按固定偏移读取消息头
    ProtocolMessage.Header header = new ProtocolMessage.Header();
    byte magic = buffer.getByte(0);

    // 校验魔数
    if (magic != ProtocolConstant.PROTOCOL_MAGIC) {
        throw new RuntimeException("消息 magic 非法");
    }

    header.setMagic(magic);
    header.setVersion(buffer.getByte(1));
    header.setSerializer(buffer.getByte(2));
    header.setType(buffer.getByte(3));
    header.setStatus(buffer.getByte(4));
    header.setRequestId(buffer.getLong(5));
    header.setBodyLength(buffer.getInt(13));

    // 2. 根据 bodyLength 读取指定长度的消息体 (解决粘包)
    byte[] bodyBytes = buffer.getBytes(17, 17 + header.getBodyLength());

    // 3. 获取序列化器
    ProtocolMessageSerializerEnum serializerEnum =
        ProtocolMessageSerializerEnum.getEnumByKey(header.getSerializer());
    if (serializerEnum == null) {
        throw new RuntimeException("序列化消息的协议不存在");
    }
    Serializer serializer = SerializerFactory.getInstance(serializerEnum.getValue());

    // 4. 根据消息类型反序列化
    ProtocolMessageTypeEnum messageTypeEnum =
        ProtocolMessageTypeEnum.getEnumByKey(header.getType());
    if (messageTypeEnum == null) {
        throw new RuntimeException("序列化消息的类型不存在");
    }

    switch (messageTypeEnum) {
        case REQUEST:
            RpcRequest request = serializer.deserialize(bodyBytes, RpcRequest.class);
            return new ProtocolMessage<>(header, request);
        case RESPONSE:
            RpcResponse response = serializer.deserialize(bodyBytes, RpcResponse.class);
            return new ProtocolMessage<>(header, response);
        case HEART_BEAT:
        case OTHERS:
        default:
            throw new RuntimeException("暂不支持该消息类型");
    }
}
```

### 3.2 解码设计亮点

1. **魔数校验**: 第一步就校验魔数,防止非法消息进入系统
2. **精确读取**: 根据 `bodyLength` 精确读取消息体,避免粘包问题
3. **类型安全**: 根据 type 字段动态反序列化为不同类型,保证类型安全
4. **异常处理**: 对非法的序列化器和消息类型进行校验和异常抛出

### 3.3 消息类型枚举

从 `ProtocolMessageTypeEnum.java` 可以看到支持的消息类型:

```java
public enum ProtocolMessageTypeEnum {
    REQUEST(0),      // RPC 请求
    RESPONSE(1),     // RPC 响应
    HEART_BEAT(2),   // 心跳消息
    OTHERS(3);       // 其他类型

    private final int key;
}
```

### 3.4 状态码枚举

从 `ProtocolMessageStatusEnum.java` 可以看到支持的状态码:

```java
public enum ProtocolMessageStatusEnum {
    OK("ok", 20),                      // 成功
    BAD_REQUEST("badRequest", 40),     // 请求错误
    BAD_RESPONSE("badResponse", 50);   // 响应错误

    private final String text;
    private final int value;
}
```

## 四、粘包/半包处理

### 4.1 TCP 粘包/半包问题

TCP 是流式协议,存在以下问题:
- **粘包**: 多个消息粘在一起,无法区分边界
- **半包**: 一个消息被拆分成多个 TCP 包传输

### 4.2 解决方案

通过 `TcpBufferHandlerWrapper.java:36-70` 实现两阶段读取:

```java
private RecordParser initRecordParser(Handler<Buffer> bufferHandler) {
    // 1. 创建固定长度解析器,先读取 17 字节的消息头
    RecordParser parser = RecordParser.newFixed(ProtocolConstant.MESSAGE_HEADER_LENGTH);

    parser.setOutput(new Handler<Buffer>() {
        // 初始化状态
        int size = -1;  // -1 表示正在读取消息头
        Buffer resultBuffer = Buffer.buffer();

        @Override
        public void handle(Buffer buffer) {
            if (-1 == size) {
                // 阶段1: 读取消息头
                // 从偏移13处获取消息体长度
                size = buffer.getInt(13);
                // 切换为读取 size 字节的消息体
                parser.fixedSizeMode(size);
                // 保存消息头
                resultBuffer.appendBuffer(buffer);
            } else {
                // 阶段2: 读取消息体
                resultBuffer.appendBuffer(buffer);

                // 完整消息读取完毕,交给业务处理器
                bufferHandler.handle(resultBuffer);

                // 重置状态,准备读取下一条消息
                parser.fixedSizeMode(ProtocolConstant.MESSAGE_HEADER_LENGTH);
                size = -1;
                resultBuffer = Buffer.buffer();
            }
        }
    });

    return parser;
}
```
   必须先接受17个字节 
   RecordPaser凑够17个字节 才开始读消息头 然后得到再读几个字节 - 处理完消息重制

### 4.3 两阶段读取流程

```
┌─────────────────────────────────────────────────────────────┐
│                     TCP 字节流                               │
│  [Header1][Body1][Header2][Body2][Header3][Body3]...        │
└─────────────────────────────────────────────────────────────┘
                          ↓
         ┌────────────────────────────────────┐
         │   RecordParser (固定长度模式)       │
         └────────────────────────────────────┘
                          ↓
    ┌─────────────────────────────────────────────┐
    │  阶段1: 读取 17 字节消息头                   │
    │  - 解析 bodyLength 字段                     │
    │  - 切换为读取 bodyLength 字节               │
    └─────────────────────────────────────────────┘
                          ↓
    ┌─────────────────────────────────────────────┐
    │  阶段2: 读取 bodyLength 字节消息体           │
    │  - 拼接完整消息 (Header + Body)             │
    │  - 交给业务处理器                           │
    │  - 重置状态,继续读取下一条消息               │
    └─────────────────────────────────────────────┘
```

### 4.4 设计优势

1. **完美解决粘包**: 通过固定长度头 + 长度字段精确分割消息边界
2. **完美解决半包**: RecordParser 会自动缓存不完整的数据,等待后续数据到达
3. **状态机设计**: 通过 `size` 变量维护读取状态,清晰简洁
4. **零拷贝**: 使用 Vert.x Buffer,避免不必要的数据拷贝

## 五、实际应用流程

### 5.1 服务端处理流程

从 `TcpServerHandler.java:28-68` 可以看到完整的服务端处理:

```java
public void handle(NetSocket socket) {
    // 使用包装器处理粘包/半包
    TcpBufferHandlerWrapper bufferHandlerWrapper = new TcpBufferHandlerWrapper(buffer -> {
        // 1. 解码请求
        ProtocolMessage<RpcRequest> protocolMessage;
        try {
            protocolMessage = (ProtocolMessage<RpcRequest>) ProtocolMessageDecoder.decode(buffer);
        } catch (IOException e) {
            throw new RuntimeException("协议消息解码错误");
        }
        RpcRequest rpcRequest = protocolMessage.getBody();
        ProtocolMessage.Header header = protocolMessage.getHeader();

        // 2. 处理请求 - 构造响应结果对象
        RpcResponse rpcResponse = new RpcResponse();
        try {
            // 获取要调用的服务实现类,通过反射调用
            Class<?> implClass = LocalRegistry.get(rpcRequest.getServiceName());
            Method method = implClass.getMethod(
                rpcRequest.getMethodName(),
                rpcRequest.getParameterTypes()
            );
            Object result = method.invoke(implClass.newInstance(), rpcRequest.getArgs());

            // 封装返回结果
            rpcResponse.setData(result);
            rpcResponse.setDataType(method.getReturnType());
            rpcResponse.setMessage("ok");
        } catch (Exception e) {
            e.printStackTrace();
            rpcResponse.setMessage(e.getMessage());
            rpcResponse.setException(e);
        }

        // 3. 发送响应 - 编码
        header.setType((byte) ProtocolMessageTypeEnum.RESPONSE.getKey());
        header.setStatus((byte) ProtocolMessageStatusEnum.OK.getValue());
        ProtocolMessage<RpcResponse> responseProtocolMessage =
            new ProtocolMessage<>(header, rpcResponse);
        try {
            Buffer encode = ProtocolMessageEncoder.encode(responseProtocolMessage);
            socket.write(encode);
        } catch (IOException e) {
            throw new RuntimeException("协议消息编码错误");
        }
    });

    // 设置 socket 处理器
    socket.handler(bufferHandlerWrapper);
}
```

### 5.2 完整的请求-响应流程

```
Consumer                                                    Provider
   │                                                           │
   │  1. 构造 RpcRequest                                       │
   │     - serviceName: "com.example.UserService"             │
   │     - methodName: "getUserById"                          │
   │     - parameterTypes: [Long.class]                       │
   │     - args: [123L]                                       │
   │                                                           │
   │  2. 构造 ProtocolMessage                                  │
   │     - header.magic = 0x1                                 │
   │     - header.version = 0x1                               │
   │     - header.serializer = 0 (JDK)                        │
   │     - header.type = 0 (REQUEST)                          │
   │     - header.requestId = 雪花ID                          │
   │     - body = RpcRequest                                  │
   │                                                           │
   │  3. ProtocolMessageEncoder.encode()                      │
   │     - 序列化 body 为字节数组                              │
   │     - 计算 bodyLength                                    │
   │     - 按顺序写入 header 和 body                          │
   │                                                           │
   │  4. TCP 发送                                             │
   ├──────────────────────────────────────────────────────────>│
   │                                                           │
   │                                          5. RecordParser 接收
   │                                             - 读取 17 字节 header
   │                                             - 解析 bodyLength
   │                                             - 读取 bodyLength 字节 body
   │                                                           │
   │                                          6. ProtocolMessageDecoder.decode()
   │                                             - 校验 magic
   │                                             - 解析 header
   │                                             - 反序列化 body
   │                                                           │
   │                                          7. 反射调用本地服务
   │                                             - LocalRegistry.get(serviceName)
   │                                             - method.invoke(...)
   │                                                           │
   │                                          8. 构造 RpcResponse
   │                                             - data = 返回值
   │                                             - dataType = 返回类型
   │                                                           │
   │                                          9. 编码响应
   │                                             - header.type = RESPONSE
   │                                             - header.status = OK
   │                                             - encode(response)
   │                                                           │
   │  10. TCP 接收响应                                         │
   │<──────────────────────────────────────────────────────────┤
   │                                                           │
   │  11. 解码响应                                             │
   │      - decode(buffer)                                    │
   │      - 获取 RpcResponse                                   │
   │                                                           │
   │  12. 返回结果给调用方                                      │
   │                                                           │
```


## 七、设计优势总结

### 7.1 性能优势

1. **异步非阻塞**: 基于 Vert.x 的异步 I/O,避免线程阻塞
2. **零拷贝**: 使用 Vert.x Buffer,减少数据拷贝
3. **高效序列化**: 支持 Kryo、Hessian 等高性能序列化器
4. **精确读取**: 通过长度字段精确读取,避免不必要的数据处理

### 7.2 可靠性优势

1. **魔数校验**: 防止非法消息进入系统
2. **版本控制**: 支持协议演进和兼容性
3. **完整性保证**: 通过长度字段保证消息完整性
4. **异常处理**: 完善的异常处理机制

### 7.3 可扩展性优势

1. **插拔式序列化器**: 通过 SPI 机制支持多种序列化实现
2. **消息类型扩展**: 通过枚举轻松添加新的消息类型
3. **状态码扩展**: 支持自定义状态码
4. **协议演进**: 版本号字段支持协议升级

### 7.4 易用性优势

1. **清晰的分层**: 编码器、解码器、处理器职责明确
2. **装饰者模式**: TcpBufferHandlerWrapper 优雅地解决粘包问题
3. **枚举映射**: 通过枚举实现协议字段和业务对象的映射
4. **完善的测试**: 提供完整的测试用例


## 九、最佳实践建议

### 9.1 使用建议

1. **选择合适的序列化器**:
   - JDK: 兼容性好,性能一般
   - JSON: 可读性好,跨语言支持
   - Kryo: 性能优秀,体积小
   - Hessian: 性能和兼容性平衡

2. **合理设置 requestId**: 使用雪花算法生成全局唯一 ID,便于请求追踪

3. **异常处理**: 在编解码过程中捕获异常,避免影响其他请求

### 9.2 扩展建议

1. **添加压缩支持**: 在 header 中增加压缩标识,支持 gzip、snappy 等压缩算法
2. **添加加密支持**: 在 header 中增加加密标识,支持 AES、RSA 等加密算法
3. **添加校验和**: 在 header 中增加 checksum 字段,防止数据传输错误
4. **支持流式传输**: 对于大文件传输,支持分片传输

### 9.3 注意事项

1. **协议兼容性**: 修改协议结构时要考虑向后兼容性
2. **字节序**: 当前使用大端字节序,跨语言实现时需要注意
3. **长度限制**: bodyLength 使用 int 类型,最大支持 2GB 的消息体
4. **线程安全**: 编解码器是无状态的,可以安全地在多线程环境中使用

---

**文档版本**: v1.0
**最后更新**: 2026-02-18
**作者**: Aeromtrich
