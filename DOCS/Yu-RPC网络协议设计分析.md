我来帮你深入分析 yu-rpc-core 的网络协议设计。我会创建多个图表结合代码来帮你理解。

## 1. 协议消息结构设计

首先看协议的整体结构:

```
┌─────────────────────────────────────────────────────────────┐
│                    ProtocolMessage                          │
├─────────────────────────────────────────────────────────────┤
│  Header (17 bytes - 固定长度)                                │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ magic      (1 byte)  - 魔数: 0x1                      │  │
│  │ version    (1 byte)  - 版本: 0x1                      │  │
│  │ serializer (1 byte)  - 序列化器: 0=JDK,1=JSON,2=KRYO  │  │
│  │ type       (1 byte)  - 消息类型: 0=REQ,1=RESP,2=HB    │  │
│  │ status     (1 byte)  - 状态: 20=OK,40=BAD_REQ,50=BAD_RESP│
│  │ requestId  (8 bytes) - 雪花算法生成的全局唯一ID        │  │
│  │ bodyLength (4 bytes) - 消息体长度                     │  │
│  └───────────────────────────────────────────────────────┘  │
│                                                               │
│  Body (可变长度)                                              │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ 序列化后的 RpcRequest 或 RpcResponse                   │  │
│  │ - RpcRequest: serviceName, methodName, params...      │  │
│  │ - RpcResponse: data, dataType, message, exception     │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

关键代码在 `ProtocolMessage.java:31-67`:

```java
public static class Header {
    private byte magic;        // 魔数保证安全性
    private byte version;      // 协议版本
    private byte serializer;   // 序列化器类型
    private byte type;         // 消息类型
    private byte status;       // 状态码
    private long requestId;    // 请求ID (8字节)
    private int bodyLength;    // 消息体长度 (4字节)
}
```

## 2. 编码流程详解

编码器 (`ProtocolMessageEncoder.java:23-47`) 的工作流程:

```
RpcRequest/RpcResponse
        ↓
   构建 Header
        ↓
┌──────────────────────┐
│  1. 写入 Header      │
│  - appendByte(magic) │ → Buffer 位置 0
│  - appendByte(ver)   │ → Buffer 位置 1
│  - appendByte(ser)   │ → Buffer 位置 2
│  - appendByte(type)  │ → Buffer 位置 3
│  - appendByte(status)│ → Buffer 位置 4
│  - appendLong(reqId) │ → Buffer 位置 5-12
└──────────────────────┘
        ↓
┌──────────────────────┐
│  2. 序列化 Body      │
│  - 根据 serializer   │
│    类型获取序列化器  │
│  - serialize(body)   │
│    → byte[]          │
└──────────────────────┘
        ↓
┌──────────────────────┐
│  3. 写入 Body        │
│  - appendInt(length) │ → Buffer 位置 13-16
│  - appendBytes(body) │ → Buffer 位置 17+
└──────────────────────┘
        ↓
    完整的 Buffer
```

核心代码:

```java
// ProtocolMessageEncoder.java:29-46
Buffer buffer = Buffer.buffer();
buffer.appendByte(header.getMagic());
buffer.appendByte(header.getVersion());
buffer.appendByte(header.getSerializer());
buffer.appendByte(header.getType());
buffer.appendByte(header.getStatus());
buffer.appendLong(header.getRequestId());
// 序列化消息体
Serializer serializer = SerializerFactory.getInstance(serializerEnum.getValue());
byte[] bodyBytes = serializer.serialize(protocolMessage.getBody());
// 写入长度和数据
buffer.appendInt(bodyBytes.length);
buffer.appendBytes(bodyBytes);
```

## 3. 解码流程详解

解码器 (`ProtocolMessageDecoder.java:25-64`) 的工作流程:

```
    接收到的 Buffer
        ↓
┌──────────────────────────┐
│  1. 读取并校验 Header    │
│  - getByte(0) → magic    │ ✓ 校验魔数
│  - getByte(1) → version  │
│  - getByte(2) → serializer│
│  - getByte(3) → type     │
│  - getByte(4) → status   │
│  - getLong(5) → requestId│
│  - getInt(13) → bodyLength│
└──────────────────────────┘
        ↓
┌──────────────────────────┐
│  2. 读取 Body (关键!)    │
│  - getBytes(17,          │
│      17 + bodyLength)    │ ← 解决粘包问题
│  - 只读取指定长度的数据  │
└──────────────────────────┘
        ↓
┌──────────────────────────┐
│  3. 反序列化 Body        │
│  - 根据 type 判断类型    │
│  - REQUEST → RpcRequest  │
│  - RESPONSE → RpcResponse│
└──────────────────────────┘
        ↓
  ProtocolMessage<T>
```

关键代码:

```java
// ProtocolMessageDecoder.java:28-41
byte magic = buffer.getByte(0);
if (magic != ProtocolConstant.PROTOCOL_MAGIC) {
    throw new RuntimeException("消息 magic 非法");
}
header.setRequestId(buffer.getLong(5));
header.setBodyLength(buffer.getInt(13));
// 解决粘包问题，只读指定长度的数据
byte[] bodyBytes = buffer.getBytes(17, 17 + header.getBodyLength());
```

## 4. 粘包/半包处理机制 (核心设计)

这是协议设计中最精妙的部分,使用装饰者模式 + RecordParser:

```
TCP 数据流 (可能粘包/半包)
        ↓
┌─────────────────────────────────────────────────┐
│  TcpBufferHandlerWrapper (装饰者模式)           │
│  ┌───────────────────────────────────────────┐  │
│  │  RecordParser (Vert.x 提供)              │  │
│  │  ┌─────────────────────────────────────┐ │  │
│  │  │  状态机模式:                        │ │  │
│  │  │                                     │ │  │
│  │  │  State 1: 读取 Header (17 bytes)   │ │  │
│  │  │  ┌──────────────────────────────┐  │ │  │
│  │  │  │ fixedSizeMode(17)            │  │ │  │
│  │  │  │ 等待 17 字节                 │  │ │  │
│  │  │  │ ↓                            │  │ │  │
│  │  │  │ 读取 bodyLength (位置13)     │  │ │  │
│  │  │  │ resultBuffer.append(header)  │  │ │  │
│  │  │  └──────────────────────────────┘  │ │  │
│  │  │           ↓                         │ │  │
│  │  │  State 2: 读取 Body (bodyLength)   │ │  │
│  │  │  ┌──────────────────────────────┐  │ │  │
│  │  │  │ fixedSizeMode(bodyLength)    │  │ │  │
│  │  │  │ 等待 bodyLength 字节         │  │ │  │
│  │  │  │ ↓                            │  │ │  │
│  │  │  │ resultBuffer.append(body)    │  │ │  │
│  │  │  │ 完整消息 → 交给业务处理      │  │ │  │
│  │  │  │ 重置状态 → State 1           │  │ │  │
│  │  │  └──────────────────────────────┘  │ │  │
│  │  └─────────────────────────────────────┘ │  │
│  └───────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘
        ↓
   完整的消息 Buffer
```

核心代码 (`TcpBufferHandlerWrapper.java:36-70`):

```java
private RecordParser initRecordParser(Handler<Buffer> bufferHandler) {
    // 初始状态: 读取固定 17 字节的 Header
    RecordParser parser = RecordParser.newFixed(ProtocolConstant.MESSAGE_HEADER_LENGTH);

    parser.setOutput(new Handler<Buffer>() {
        int size = -1;  // -1 表示正在读 Header, >=0 表示正在读 Body
        Buffer resultBuffer = Buffer.buffer();

        @Override
        public void handle(Buffer buffer) {
            if (-1 == size) {
                // 状态1: 读取消息头
                size = buffer.getInt(13);  // 获取 Body 长度
                parser.fixedSizeMode(size);  // 切换到读取 Body 模式
                resultBuffer.appendBuffer(buffer);  // 保存 Header
            } else {
                // 状态2: 读取消息体
                resultBuffer.appendBuffer(buffer);  // 保存 Body
                bufferHandler.handle(resultBuffer);  // 处理完整消息
                // 重置状态,准备读取下一个消息
                parser.fixedSizeMode(ProtocolConstant.MESSAGE_HEADER_LENGTH);
                size = -1;
                resultBuffer = Buffer.buffer();
            }
        }
    });
    return parser;
}
```

## 5. 完整的请求-响应流程

```
Consumer 端                                    Provider 端
    │                                              │
    │ 1. 调用代理方法                              │
    ↓                                              │
ServiceProxy.invoke()                             │
    │                                              │
    │ 2. 构建 RpcRequest                           │
    ↓                                              │
VertxTcpClient.doRequest()                        │
    │                                              │
    │ 3. 构建 ProtocolMessage                      │
    │    - Header: magic, version, serializer...   │
    │    - Body: RpcRequest                        │
    ↓                                              │
ProtocolMessageEncoder.encode()                   │
    │                                              │
    │ 4. 编码成 Buffer                             │
    │    [Header 17 bytes][Body N bytes]           │
    ↓                                              │
socket.write(buffer) ─────TCP 连接────────────→  VertxTcpServer
                                                   │
                                                   ↓
                                            TcpServerHandler
                                                   │
                                                   ↓
                                        TcpBufferHandlerWrapper
                                                   │
                                                   │ 5. 解决粘包/半包
                                                   ↓
                                        ProtocolMessageDecoder.decode()
                                                   │
                                                   │ 6. 解码得到 RpcRequest
                                                   ↓
                                            LocalRegistry.get()
                                                   │
                                                   │ 7. 反射调用本地服务
                                                   ↓
                                            Method.invoke()
                                                   │
                                                   │ 8. 构建 RpcResponse
                                                   ↓
                                        ProtocolMessageEncoder.encode()
                                                   │
                                                   │ 9. 编码响应
                                                   ↓
socket.write(responseBuffer) ←────TCP 连接────── socket.write()
    │
    │ 10. 接收响应
    ↓
TcpBufferHandlerWrapper
    │
    │ 11. 解码响应
    ↓
ProtocolMessageDecoder.decode()
    │
    │ 12. 返回结果
    ↓
CompletableFuture.complete()
```

## 6. 协议设计的关键特性

### 6.1 魔数校验 (安全性)

```java
// ProtocolMessageDecoder.java:28-32
byte magic = buffer.getByte(0);
if (magic != ProtocolConstant.PROTOCOL_MAGIC) {
    throw new RuntimeException("消息 magic 非法");
}
```

作用: 防止非法消息进入系统

### 6.2 固定长度头部 (解决粘包)

```
为什么是 17 字节?
┌────────────────────────────────────┐
│ magic      1 byte                  │
│ version    1 byte                  │
│ serializer 1 byte                  │
│ type       1 byte                  │
│ status     1 byte                  │
│ requestId  8 bytes (long)          │
│ bodyLength 4 bytes (int)           │
├────────────────────────────────────┤
│ Total:     17 bytes                │
└────────────────────────────────────┘
```

固定长度头部 + bodyLength 字段 = 完美解决粘包问题

### 6.3 请求ID (异步支持)

```java
// VertxTcpClient.java:55
header.setRequestId(IdUtil.getSnowflakeNextId());
```

使用雪花算法生成全局唯一ID,支持异步请求匹配

### 6.4 可扩展的序列化器

```java
// ProtocolMessageSerializerEnum.java:18-21
JDK(0, "jdk"),
JSON(1, "json"),
KRYO(2, "kryo"),
HESSIAN(3, "hessian");
```

通过 Header 中的 serializer 字段指定序列化方式

## 7. 协议字节布局详解

```
Byte Offset │ Field        │ Size  │ Value Example      │ Description
────────────┼──────────────┼───────┼────────────────────┼─────────────────────
     0      │ magic        │ 1     │ 0x01               │ 魔数
     1      │ version      │ 1     │ 0x01               │ 协议版本
     2      │ serializer   │ 1     │ 0x00 (JDK)         │ 序列化器类型
     3      │ type         │ 1     │ 0x00 (REQUEST)     │ 消息类型
     4      │ status       │ 1     │ 0x14 (20=OK)       │ 状态码
   5-12     │ requestId    │ 8     │ 1234567890123456   │ 请求ID (long)
  13-16     │ bodyLength   │ 4     │ 256                │ Body长度 (int)
  17+      │ body         │ N     │ [serialized data]  │ 序列化的消息体
```

## 总结

Yu-RPC 的协议设计亮点:

1. **固定长度头部**: 17字节固定头部,简化解析逻辑
2. **装饰者模式**: TcpBufferHandlerWrapper 优雅地解决粘包/半包
3. **状态机解析**: RecordParser 实现两阶段读取 (Header → Body)
4. **可扩展性**: 支持多种序列化器、消息类型
5. **安全性**: 魔数校验、版本控制
6. **异步支持**: 全局唯一的 requestId

这个协议设计在简洁性和功能性之间取得了很好的平衡,非常适合学习 RPC 框架的网络协议设计。
