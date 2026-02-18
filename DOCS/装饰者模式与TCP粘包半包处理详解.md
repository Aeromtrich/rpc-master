# 装饰者模式与 TCP 粘包半包处理详解

## 一、什么是粘包和半包问题？

### 1.1 TCP 传输的本质

TCP 是**流式协议**，数据像水流一样连续传输，没有明确的消息边界。

**类比**：
- UDP = 寄快递，每个包裹独立，有明确边界
- TCP = 水管传水，水流连续，没有边界

### 1.2 粘包问题

**定义**：多个消息粘在一起，接收方无法区分消息边界。

**示例**：

```
发送方：
消息1: [Hello]  (5 字节)
消息2: [World]  (5 字节)

理想接收：
接收1: [Hello]
接收2: [World]

实际接收（粘包）：
接收1: [HelloWorld]  ← 两个消息粘在一起了！
```

**原因**：
- TCP 有发送缓冲区，会把多个小消息合并发送（Nagle 算法）
- 接收方处理速度慢，多个消息堆积在接收缓冲区

### 1.3 半包问题

**定义**：一个消息被拆分成多个包，接收方收到不完整的消息。

**示例**：

```
发送方：
消息1: [HelloWorld]  (10 字节)

理想接收：
接收1: [HelloWorld]

实际接收（半包）：
接收1: [Hello]      ← 只收到前半部分
接收2: [World]      ← 后半部分在下一次
```

**原因**：
- TCP 有 MSS（最大报文段大小）限制，大消息会被拆分
- 网络拥塞，数据包分批到达

### 1.4 混合问题

**最复杂的情况**：粘包 + 半包同时出现

```
发送方：
消息1: [Hello]
消息2: [World]
消息3: [RPC]

实际接收：
接收1: [HelloWor]   ← 消息1完整 + 消息2的一半
接收2: [ldRPC]      ← 消息2的另一半 + 消息3完整
```

## 二、解决方案：固定长度协议头

### 2.1 协议设计

Yu-RPC 使用**固定长度消息头 + 变长消息体**的协议格式：

```
完整消息 = 消息头(17字节) + 消息体(变长)

消息头结构（17 字节）：
┌─────────┬─────────┬────────────┬──────┬────────┬─────────────┬──────────────┐
│ magic   │ version │ serializer │ type │ status │ requestId   │ bodyLength   │
│ (1字节) │ (1字节) │ (1字节)    │(1字节)│(1字节) │ (8字节)     │ (4字节)      │
└─────────┴─────────┴────────────┴──────┴────────┴─────────────┴──────────────┘
```

**关键字段**：
- `bodyLength`（第 13-16 字节）：消息体的长度，用于确定消息边界

### 2.2 解决思路

**两阶段读取**：

```
阶段1：读取固定 17 字节的消息头
       ↓
       解析出 bodyLength
       ↓
阶段2：根据 bodyLength 读取完整的消息体
       ↓
       拼接成完整消息
```

**示例**：

```
接收到的数据流：
[17字节头][100字节体][17字节头][50字节体]...

处理过程：
1. 读取 17 字节 → 解析出 bodyLength = 100
2. 读取 100 字节 → 得到完整消息1
3. 读取 17 字节 → 解析出 bodyLength = 50
4. 读取 50 字节 → 得到完整消息2
```

## 三、装饰者模式概述

### 3.1 什么是装饰者模式？

**定义**：在不修改原有对象的基础上，动态地给对象添加新功能。

**类比**：
- 原始对象 = 一杯咖啡
- 装饰者 = 加糖、加奶、加冰
- 装饰后 = 加糖加奶的冰咖啡

**核心思想**：
- 不改变咖啡本身
- 通过包装的方式增强功能
- 可以多层包装（加糖 → 加奶 → 加冰）

### 3.2 装饰者模式的结构

```
┌─────────────────┐
│  Component      │  ← 抽象组件（接口）
│  + operation()  │
└─────────────────┘
        ▲
        │
   ┌────┴────┐
   │         │
┌──┴──────┐  ┌──────────────┐
│Concrete │  │  Decorator   │  ← 装饰者（也实现接口）
│Component│  │  + operation()│
└─────────┘  └──────────────┘
                     ▲
                     │
              ┌──────┴──────┐
              │ Concrete    │
              │ Decorator   │
              └─────────────┘
```

**关键点**：
1. 装饰者和被装饰者实现同一个接口
2. 装饰者持有被装饰者的引用
3. 装饰者在调用被装饰者前后添加额外逻辑

### 3.3 装饰者模式 vs 继承

| 特性 | 继承 | 装饰者模式 |
|------|------|-----------|
| 扩展方式 | 编译时静态扩展 | 运行时动态扩展 |
| 灵活性 | 低（需要修改代码） | 高（无需修改原类） |
| 组合性 | 差（类爆炸） | 好（可以任意组合） |
| 耦合度 | 高 | 低 |

**示例**：

```java
// ❌ 继承方式：类爆炸
class Coffee {}
class CoffeeWithSugar extends Coffee {}
class CoffeeWithMilk extends Coffee {}
class CoffeeWithSugarAndMilk extends Coffee {}  // 组合爆炸

// ✅ 装饰者方式：灵活组合
Coffee coffee = new Coffee();
coffee = new SugarDecorator(coffee);
coffee = new MilkDecorator(coffee);
```

## 四、TcpBufferHandlerWrapper 详解

### 4.1 类的角色定位

```
装饰者模式在 Yu-RPC 中的应用：

Component（抽象组件）：
  Handler<Buffer>  ← Vert.x 的接口

ConcreteComponent（具体组件）：
  TcpServerHandler 中的 Lambda 表达式
  buffer -> { 解码、处理、编码 }

Decorator（装饰者）：
  TcpBufferHandlerWrapper  ← 增强粘包半包处理能力
```

### 4.2 类结构分析

**源码**（TcpBufferHandlerWrapper.java）：

```java
public class TcpBufferHandlerWrapper implements Handler<Buffer> {
    
    // 核心：RecordParser 用于解决粘包半包
    private final RecordParser recordParser;

    // 构造函数：接收原始处理器
    public TcpBufferHandlerWrapper(Handler<Buffer> bufferHandler) {
        recordParser = initRecordParser(bufferHandler);
    }

    // 实现 Handler<Buffer> 接口
    @Override
    public void handle(Buffer buffer) {
        recordParser.handle(buffer);  // 委托给 RecordParser
    }

    // 初始化 RecordParser
    private RecordParser initRecordParser(Handler<Buffer> bufferHandler) {
        // 详细逻辑见下文
    }
}
```

**设计要点**：

1. **实现同一接口**：`implements Handler<Buffer>`
   - 与原始处理器实现相同接口
   - 可以无缝替换原始处理器

2. **持有原始处理器**：`Handler<Buffer> bufferHandler`
   - 通过构造函数传入
   - 在处理完粘包半包后，委托给原始处理器

3. **增强功能**：使用 `RecordParser`
   - 在调用原始处理器之前，先解决粘包半包
   - 保证原始处理器收到的是完整消息

### 4.3 核心逻辑：initRecordParser()

**完整代码**（TcpBufferHandlerWrapper.java:36-70）：

```java
private RecordParser initRecordParser(Handler<Buffer> bufferHandler) {
    // 1. 创建 RecordParser，初始模式：读取固定 17 字节（消息头）
    RecordParser parser = RecordParser.newFixed(ProtocolConstant.MESSAGE_HEADER_LENGTH);

    // 2. 设置输出处理器
    parser.setOutput(new Handler<Buffer>() {
        // 状态变量
        int size = -1;  // 消息体长度，-1 表示还未读取
        Buffer resultBuffer = Buffer.buffer();  // 累积完整消息

        @Override
        public void handle(Buffer buffer) {
            // 阶段1：读取消息头
            if (-1 == size) {
                // 从消息头的第 13 字节读取消息体长度（4字节 int）
                size = buffer.getInt(13);
                
                // 切换模式：读取 size 字节的消息体
                parser.fixedSizeMode(size);
                
                // 保存消息头
                resultBuffer.appendBuffer(buffer);
            } 
            // 阶段2：读取消息体
            else {
                // 保存消息体
                resultBuffer.appendBuffer(buffer);
                
                // 完整消息已拼接完成，交给原始处理器
                bufferHandler.handle(resultBuffer);
                
                // 重置状态，准备读取下一个消息
                parser.fixedSizeMode(ProtocolConstant.MESSAGE_HEADER_LENGTH);
                size = -1;
                resultBuffer = Buffer.buffer();
            }
        }
    });

    return parser;
}
```

### 4.4 执行流程图

```
数据流到达
    ↓
TcpBufferHandlerWrapper.handle(buffer)
    ↓
RecordParser.handle(buffer)
    ↓
┌─────────────────────────────────────┐
│  状态机循环                          │
│                                     │
│  [状态1: size = -1]                 │
│    ↓                                │
│  读取 17 字节（消息头）              │
│    ↓                                │
│  解析 bodyLength → size = 100       │
│    ↓                                │
│  切换模式：fixedSizeMode(100)       │
│    ↓                                │
│  保存消息头到 resultBuffer          │
│    ↓                                │
│  [状态2: size = 100]                │
│    ↓                                │
│  读取 100 字节（消息体）             │
│    ↓                                │
│  保存消息体到 resultBuffer          │
│    ↓                                │
│  完整消息 = 消息头 + 消息体          │
│    ↓                                │
│  bufferHandler.handle(resultBuffer) │ ← 调用原始处理器
│    ↓                                │
│  重置状态：size = -1                │
│    ↓                                │
│  回到状态1，处理下一个消息           │
└─────────────────────────────────────┘
```

### 4.5 状态机详解

**状态转换**：

```
初始状态：size = -1，模式 = 读取 17 字节
    ↓
收到 17 字节数据
    ↓
状态1：读取消息头
  - 解析 bodyLength
  - size = bodyLength
  - 切换模式 = 读取 size 字节
    ↓
收到 size 字节数据
    ↓
状态2：读取消息体
  - 拼接完整消息
  - 调用原始处理器
  - size = -1
  - 切换模式 = 读取 17 字节
    ↓
回到初始状态
```

**关键变量**：

| 变量 | 作用 | 初始值 | 状态1 | 状态2 |
|------|------|--------|-------|-------|
| size | 消息体长度 | -1 | bodyLength | -1 |
| resultBuffer | 累积缓冲区 | 空 | 消息头 | 消息头+消息体 |
| parser 模式 | 读取字节数 | 17 | size | 17 |

## 五、实际运行示例

### 5.1 场景：粘包问题

**发送方**：

```java
// 发送两个消息
消息1: [17字节头][50字节体]  = 67 字节
消息2: [17字节头][30字节体]  = 47 字节
```

**网络传输**：

```
TCP 缓冲区合并，一次性发送：
[17字节头1][50字节体1][17字节头2][30字节体2]  = 114 字节
```

**接收方处理**：

```
第1次 handle() 调用：
  输入: [17字节头1][50字节体1][17字节头2][30字节体2]
  
  RecordParser 处理：
    1. 读取 17 字节 → 消息头1
       size = 50
       切换模式：读取 50 字节
    
    2. 读取 50 字节 → 消息体1
       resultBuffer = 消息头1 + 消息体1
       调用 bufferHandler.handle(resultBuffer)  ← 完整消息1
       重置状态
    
    3. 读取 17 字节 → 消息头2
       size = 30
       切换模式：读取 30 字节
    
    4. 读取 30 字节 → 消息体2
       resultBuffer = 消息头2 + 消息体2
       调用 bufferHandler.handle(resultBuffer)  ← 完整消息2
       重置状态

结果：正确分离出两个完整消息！
```

### 5.2 场景：半包问题

**发送方**：

```java
消息1: [17字节头][100字节体]  = 117 字节
```

**网络传输**：

```
由于 MTU 限制，分两次到达：
第1次: [17字节头][60字节体]  = 77 字节
第2次: [40字节体]             = 40 字节
```

**接收方处理**：

```
第1次 handle() 调用：
  输入: [17字节头][60字节体]
  
  RecordParser 处理：
    1. 读取 17 字节 → 消息头
       size = 100
       切换模式：读取 100 字节
    
    2. 读取 60 字节 → 消息体的前半部分
       resultBuffer = 消息头 + 60字节
       等待更多数据...（不调用 bufferHandler）

第2次 handle() 调用：
  输入: [40字节体]
  
  RecordParser 处理：
    1. 继续读取 40 字节 → 消息体的后半部分
       resultBuffer = 消息头 + 60字节 + 40字节
       调用 bufferHandler.handle(resultBuffer)  ← 完整消息
       重置状态

结果：正确拼接出完整消息！
```

### 5.3 场景：混合问题

**网络传输**：

```
第1次到达: [17字节头1][50字节体1][17字节头2][20字节]
第2次到达: [10字节][17字节头3][30字节体3]
```

**RecordParser 处理**：

```
第1次 handle():
  1. 读取消息1 → 完整 ✓
  2. 读取消息头2 → size = 30
  3. 读取 20 字节 → 等待剩余 10 字节...

第2次 handle():
  1. 读取剩余 10 字节 → 消息2完整 ✓
  2. 读取消息头3 → size = 30
  3. 读取 30 字节 → 消息3完整 ✓

结果：正确处理所有消息！
```

## 六、装饰者模式的优势

### 6.1 不修改原有代码

**原始处理器**（TcpServerHandler.java:28-67）：

```java
// Lambda 表达式，专注于业务逻辑
buffer -> {
    // 1. 解码
    ProtocolMessage<RpcRequest> protocolMessage = ProtocolMessageDecoder.decode(buffer);
    
    // 2. 处理请求（反射调用）
    RpcResponse rpcResponse = handleRequest(protocolMessage);
    
    // 3. 编码响应
    Buffer encode = ProtocolMessageEncoder.encode(responseProtocolMessage);
    socket.write(encode);
}
```

**关键点**：
- 原始处理器只关心**完整消息**的处理
- 不需要关心粘包半包问题
- 代码简洁，职责单一

### 6.2 灵活扩展

**使用装饰者**：

```java
// 创建原始处理器
Handler<Buffer> originalHandler = buffer -> {
    // 业务逻辑
};

// 用装饰者包装
Handler<Buffer> enhancedHandler = new TcpBufferHandlerWrapper(originalHandler);

// 设置到 socket
socket.handler(enhancedHandler);
```

**如果不需要粘包半包处理**：

```java
// 直接使用原始处理器
socket.handler(originalHandler);
```

**如果需要其他增强**：

```java
// 可以多层装饰
Handler<Buffer> handler = originalHandler;
handler = new TcpBufferHandlerWrapper(handler);      // 粘包半包处理
handler = new LoggingHandlerWrapper(handler);        // 日志记录
handler = new CompressionHandlerWrapper(handler);    // 压缩处理
socket.handler(handler);
```

### 6.3 职责分离

```
TcpBufferHandlerWrapper：
  职责：解决粘包半包，保证消息完整性
  
TcpServerHandler 中的 Lambda：
  职责：处理完整消息（解码、业务逻辑、编码）
  
ProtocolMessageDecoder：
  职责：协议解码
  
LocalRegistry + 反射：
  职责：服务调用
  
ProtocolMessageEncoder：
  职责：协议编码
```

**每个类职责单一，易于维护和测试。**

### 6.4 可测试性

**测试粘包半包处理**：

```java
@Test
public void testStickyPacket() {
    // 模拟原始处理器
    List<Buffer> receivedMessages = new ArrayList<>();
    Handler<Buffer> mockHandler = buffer -> {
        receivedMessages.add(buffer);
    };

    // 创建装饰者
    TcpBufferHandlerWrapper wrapper = new TcpBufferHandlerWrapper(mockHandler);

    // 模拟粘包：两个消息粘在一起
    Buffer stickyBuffer = createMessage1().appendBuffer(createMessage2());
    wrapper.handle(stickyBuffer);

    // 验证：应该收到两个完整消息
    assertEquals(2, receivedMessages.size());
    assertMessageComplete(receivedMessages.get(0));
    assertMessageComplete(receivedMessages.get(1));
}
```

## 七、RecordParser 工作原理

### 7.1 RecordParser 简介

**Vert.x 提供的工具类**，用于从流式数据中解析记录。

**两种模式**：

1. **固定长度模式**：`fixedSizeMode(n)`
   - 每次读取固定 n 字节
   - 适合读取消息头

2. **分隔符模式**：`delimitedMode("\n")`
   - 读取到分隔符为止
   - 适合文本协议（HTTP、Redis）

### 7.2 模式切换机制

```java
RecordParser parser = RecordParser.newFixed(17);  // 初始：读取 17 字节

parser.setOutput(buffer -> {
    if (isHeader) {
        int bodyLength = buffer.getInt(13);
        parser.fixedSizeMode(bodyLength);  // 切换：读取 bodyLength 字节
    } else {
        parser.fixedSizeMode(17);  // 切换回：读取 17 字节
    }
});
```

**内部实现**（简化版）：

```java
class RecordParser {
    private int fixedSize;
    private Buffer accumulator = Buffer.buffer();

    public void handle(Buffer buffer) {
        accumulator.appendBuffer(buffer);

        while (accumulator.length() >= fixedSize) {
            // 提取固定长度的数据
            Buffer record = accumulator.getBuffer(0, fixedSize);
            accumulator = accumulator.getBuffer(fixedSize, accumulator.length());

            // 调用输出处理器
            output.handle(record);
        }
    }
}
```

### 7.3 为什么需要装饰者？

**直接使用 RecordParser 的问题**：

```java
// ❌ 不使用装饰者：代码混乱
socket.handler(buffer -> {
    RecordParser parser = RecordParser.newFixed(17);
    parser.setOutput(new Handler<Buffer>() {
        int size = -1;
        Buffer resultBuffer = Buffer.buffer();

        @Override
        public void handle(Buffer buf) {
            if (size == -1) {
                size = buf.getInt(13);
                parser.fixedSizeMode(size);
                resultBuffer.appendBuffer(buf);
            } else {
                resultBuffer.appendBuffer(buf);
                
                // 业务逻辑混在这里
                ProtocolMessage msg = decode(resultBuffer);
                RpcResponse response = handleRequest(msg);
                socket.write(encode(response));
                
                parser.fixedSizeMode(17);
                size = -1;
                resultBuffer = Buffer.buffer();
            }
        }
    });
    parser.handle(buffer);
});
```

**使用装饰者：职责分离**：

```java
// ✅ 使用装饰者：清晰简洁
Handler<Buffer> businessHandler = buffer -> {
    // 只关心业务逻辑
    ProtocolMessage msg = decode(buffer);
    RpcResponse response = handleRequest(msg);
    socket.write(encode(response));
};

// 粘包半包处理封装在装饰者中
socket.handler(new TcpBufferHandlerWrapper(businessHandler));
```

## 八、与其他设计模式的对比

### 8.1 装饰者 vs 代理模式

| 特性 | 装饰者模式 | 代理模式 |
|------|-----------|---------|
| 目的 | 增强功能 | 控制访问 |
| 关注点 | 添加新行为 | 访问控制、延迟加载 |
| 透明性 | 对客户端透明 | 可能不透明 |
| 示例 | TcpBufferHandlerWrapper | ServiceProxy（RPC代理） |

### 8.2 装饰者 vs 适配器模式

| 特性 | 装饰者模式 | 适配器模式 |
|------|-----------|-----------|
| 目的 | 增强功能 | 接口转换 |
| 接口 | 保持不变 | 改变接口 |
| 对象 | 包装同类型对象 | 包装不同类型对象 |

### 8.3 装饰者 vs 策略模式

| 特性 | 装饰者模式 | 策略模式 |
|------|-----------|---------|
| 目的 | 动态添加功能 | 动态选择算法 |
| 结构 | 包装对象 | 替换算法 |
| 组合 | 可以多层包装 | 只能选择一个策略 |

## 九、总结

### 9.1 核心要点

1. **粘包半包问题**：
   - TCP 流式协议的固有问题
   - 需要应用层协议来解决

2. **解决方案**：
   - 固定长度消息头 + 变长消息体
   - 两阶段读取：先读头，再读体

3. **装饰者模式**：
   - 不修改原有代码
   - 动态添加新功能
   - 职责分离，易于维护

4. **TcpBufferHandlerWrapper**：
   - 封装粘包半包处理逻辑
   - 使用 RecordParser 实现状态机
   - 保证原始处理器收到完整消息

### 9.2 设计优势

```
传统方式：
  业务逻辑 + 粘包半包处理 混在一起
  ↓
  代码复杂、难以维护、难以测试

装饰者方式：
  TcpBufferHandlerWrapper（粘包半包）
    ↓ 委托
  原始处理器（业务逻辑）
  ↓
  职责分离、代码清晰、易于扩展
```

### 9.3 适用场景

**适合使用装饰者模式**：
- 需要动态添加功能
- 不想修改原有代码
- 需要灵活组合多个功能
- 职责需要分离

**不适合使用装饰者模式**：
- 功能简单，不需要扩展
- 性能要求极高（装饰者有轻微开销）
- 装饰层次过多会导致复杂度增加

### 9.4 在 Yu-RPC 中的价值

1. **解决了 TCP 粘包半包问题**
2. **保持了代码的简洁性**
3. **提高了可维护性和可测试性**
4. **为未来扩展留下了空间**（可以添加更多装饰者）

---

**装饰者模式的精髓**：在不改变原有对象的前提下，通过包装的方式动态地扩展功能，实现了"开闭原则"（对扩展开放，对修改关闭）。
