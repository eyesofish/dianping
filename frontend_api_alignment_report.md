# 前后端 API 对齐报告（重写版）

生成时间：2026-03-05  
项目根目录：`D:\Github\dianping`  
前端目录：`D:\Github\dianping\frontend\hmdp`  
后端基础地址：`http://localhost:8080`

## 1. 对齐结论（摘要）

本轮联调后，前端最小闭环已可跑通：

- 浏览店铺列表 / 详情
- 秒杀下单（返回 `orderId`）
- 查询订单状态（按 `orderId`）
- 开发态自动登录（`POST /dev/login`）

并且针对浏览器报错已完成后端修正：

- CORS 允许 `http://localhost:5173`
- `OPTIONS` 预检请求不再被拦截
- 支持 `Authorization: Bearer <token>`

---

## 2. API 映射（本次重点）

### 2.1 下订单与秒杀相关接口

| 场景 | 接口 | Method | 请求参数 | 响应 `data` |
|---|---|---|---|---|
| 创建秒杀券 | `/voucher/seckill` | `POST` | `Voucher` JSON（`shopId/title/stock/beginTime/endTime...`） | `voucherId` |
| 秒杀下单 | `/voucher-order/seckill/{id}` | `POST` | 路径参数 `id=voucherId`，需登录 | `orderId` |
| 查订单状态 | `/voucher-order/{id}` | `GET` | 路径参数 `id=orderId`，需登录 | `VoucherOrderStatusDTO` |
| 我的订单分页 | `/voucher-order/of/me` | `GET` | 查询参数 `current`、`pageSize`，需登录 | `{ records,total,current,pageSize,pages }` |
| 开发登录 | `/dev/login` | `POST` | 无 | `{ token: "..." }` |

统一响应结构：

```json
{
  "success": true,
  "errorMsg": null,
  "data": {},
  "total": null
}
```

---

## 3. 重点一：下订单链路（前后端）

### 3.1 前端实现

- `CreateOrder.vue` 调用：`POST /voucher-order/seckill/{voucherId}`
- 成功后立刻展示 `orderId`
- 同步写入本地历史（仅用于前端临时回显/兼容）
- 页面明确提示：订单持久化为异步（MQ 消费）

对应文件：

- `frontend/hmdp/src/pages/CreateOrder.vue`
- `frontend/hmdp/src/api/index.js`（`createOrder`）

### 3.2 后端实现

- `VoucherOrderController.seckillVoucher()` 入口
- `VoucherOrderServiceImpl.seckillVoucher()` 完成资格校验 + 生成 `orderId` + 异步投递

对应文件：

- `src/main/java/com/hmdp/controller/VoucherOrderController.java`
- `src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java`

### 3.3 一眼看懂完整链路（前端入口 -> MQ -> 锁）

1. 前端店铺详情页点击下单按钮  
   文件：`frontend/hmdp/src/pages/ShopDetail.vue`  
   函数：`goCreateOrder(voucher)`  
   作用：跳转到下单页路由并携带 `voucherId`

2. 前端路由进入下单页  
   文件：`frontend/hmdp/src/router/index.js`  
   配置：`/orders/create/:voucherId`（`name: create-order`）  
   作用：渲染 `CreateOrder.vue`

3. 下单页发起请求  
   文件：`frontend/hmdp/src/pages/CreateOrder.vue`  
   函数：`submit()`  
   作用：调用 `createOrder(voucherId)`，请求后端秒杀下单接口

4. 前端 API 封装  
   文件：`frontend/hmdp/src/api/index.js`  
   函数：`createOrder(voucherId)`  
   作用：发送 `POST /voucher-order/seckill/{voucherId}`

5. 后端 Controller 入口  
   文件：`src/main/java/com/hmdp/controller/VoucherOrderController.java`  
   函数：`seckillVoucher(Long voucherId)`  
   作用：接收请求并转发给 Service

6. 后端快路径处理  
   文件：`src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java`  
   函数：`seckillVoucher(Long voucherId)`  
   作用：执行 Lua 资格校验、生成 `orderId`、发送 MQ 消息到交换机 `X`（routing key=`XA`）

7. RabbitMQ 路由  
   文件：`src/main/java/com/hmdp/config/QueueConfig.java`  
   函数：`queueABindingX()` / `queueDBindingY()`  
   作用：`X + XA -> QA`，死信交换后 `Y + YD -> QD`

8. MQ 消费者接单  
   文件：`src/main/java/com/hmdp/listener/SeckillVoucherListener.java`  
   函数：`receivedA()` / `receivedD()`  
   作用：消费 `QA/QD` 消息并统一调用 `handleVoucherOrder(voucherOrder)`

9. 分布式锁生效点  
   文件：`src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java`  
   函数：`handleVoucherOrder(VoucherOrder voucherOrder)`  
   作用：按 `userId` 获取 Redisson 锁 `lock:order:{userId}`，拿锁后通过 AOP 代理调用事务下单

10. 事务落库  
    文件：`src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java`  
    函数：`createVoucherOrder(VoucherOrder voucherOrder)`（`@Transactional`）  
    作用：DB 一人一单校验、DB 扣库存、保存订单

---

## 4. 重点二：秒杀前置校验与库存初始化

### 4.1 秒杀券创建改动

`VoucherServiceImpl.addSeckillVoucher()` 已补强：

- 入参校验：`shopId/title/payValue/actualValue/stock/beginTime/endTime`
- 保存主券 + 秒杀扩展信息
- 初始化 Redis 库存：`seckill:stock:{voucherId}`
- Redis 异常时抛出明确错误（便于联调定位）

对应文件：

- `src/main/java/com/hmdp/service/impl/VoucherServiceImpl.java`
- `src/main/java/com/hmdp/controller/VoucherController.java`

### 4.2 秒杀资格校验（Lua）

`seckill.lua` 负责原子校验：

- 库存不足 -> 返回 `1`
- 重复下单 -> 返回 `2`
- 校验通过 -> Redis 扣减库存 + 标记用户下单 + 写入 `stream.orders`

对应文件：

- `src/main/resources/seckill.lua`

---

## 5. 重点三：异步处理库存与订单持久化

### 5.1 当前异步架构

下单请求线程只做“快路径”并快速返回：

1. 执行 Lua（Redis 原子校验/扣减）
2. 发送 RabbitMQ 消息（交换机 `X`，routing key `XA`）
3. 立即返回 `orderId`

消费者异步落库（已接入统一订单处理）：

- `QA` 队列消费者：`SeckillVoucherListener.receivedA()` -> `handleVoucherOrder()`
- `QD` 死信队列消费者：`SeckillVoucherListener.receivedD()` -> `handleVoucherOrder()`
- `handleVoucherOrder()` 内部先获取 Redisson 锁 `lock:order:{userId}`
- 拿锁后通过 AOP 代理调用事务方法 `createVoucherOrder()`，执行“DB 一人一单校验 + DB 扣库存 + 保存订单”

对应文件：

- `src/main/java/com/hmdp/config/QueueConfig.java`
- `src/main/java/com/hmdp/listener/SeckillVoucherListener.java`
- `src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java`

### 5.2 前端如何感知异步状态

- 下单成功仅表示“请求已受理”
- `OrderList.vue` 直接调用 `GET /voucher-order/of/me` 拉取后端分页订单
- 对单个 `orderId` 的状态查询接口 `GET /voucher-order/{orderId}` 仍保留
- 若订单未落库，后端返回 `PENDING`（`processing=true`）

对应文件：

- `frontend/hmdp/src/pages/OrderList.vue`
- `src/main/java/com/hmdp/dto/VoucherOrderStatusDTO.java`

---

## 6. 重点四：分布式锁（已接入主消费链路）

### 6.1 已有锁实现

后端已实现 Redisson 锁代码：

- 锁键：`lock:order:{userId}`
- 位置：`VoucherOrderServiceImpl.handleVoucherOrder()`
- 用途：限制同用户并发创建订单

对应文件：

- `src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java`
- `src/main/java/com/hmdp/config/RedissonConfig.java`

### 6.2 当前状态（重要）

RabbitMQ 消费者（`QA/QD`）已统一调用 `handleVoucherOrder()`，主消费链路已进入锁逻辑。  
锁键仍为 `lock:order:{userId}`，并通过 `AopContext.currentProxy()` 调用 `@Transactional` 的 `createVoucherOrder()`，避免事务失效。

---

## 7. 前端鉴权与联调可用性

### 7.1 开发态登录

- 新增 `POST /dev/login`
- 前端启动自动拉 token，存 `localStorage["token"]`
- 请求统一加 `Authorization: Bearer <token>`
- 401 自动触发 `/dev/login` 刷新并重试原请求

对应文件：

- `frontend/hmdp/src/api/auth.js`
- `frontend/hmdp/src/api/http.js`
- `frontend/hmdp/src/main.js`
- `src/main/java/com/hmdp/controller/DevAuthController.java`

### 7.2 CORS 与预检

后端已放开开发域名并放行 `OPTIONS`，浏览器跨域请求可正常预检通过。

对应文件：

- `src/main/java/com/hmdp/config/MvcConfig.java`
- `src/main/java/com/hmdp/interceptor/LoginInterceptor.java`
- `src/main/java/com/hmdp/interceptor/RefreshTokenInterceptor.java`

---

## 8. 剩余问题（按优先级）

1. **消息消费幂等性仍需增强**
- 当前幂等主要依赖“锁 + 业务查询”，建议在 DB 增加唯一约束（如 `user_id + voucher_id`）并保留幂等校验，降低重复消费导致脏数据的风险。

2. **Lua 返回码语义可再细化（傻瓜教程）**

你只要做 3 步：

1. 先约定返回码含义（写死规则，别临时猜）
- `0`：成功
- `1`：库存不足
- `2`：重复下单
- `-1`：库存 key 不存在（通常是秒杀券没初始化库存）

2. Lua 保持/确认返回 `-1`
- 文件：`src/main/resources/seckill.lua`
- 关键逻辑：`stock == nil` 时返回 `-1`

3. Java 单独处理 `-1`，不要走通用文案
- 文件：`src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java`
- 在 `seckillVoucher()` 里判断返回码时，加一个分支：
  - `r == -1` -> 返回明确错误，例如：`秒杀库存未初始化，请联系管理员`
  - `r == 1` -> `库存不足`
  - `r == 2` -> `不能重复下单`

为什么这样改：
- 你一眼就能知道是“库存没初始化”，不是“库存不足”或“系统异常”
- 联调时定位会快很多，不会误判业务问题

---

## 9. 本次报告对应改动清单

- 前端：开发态自动登录、Bearer 注入、401 自动刷新、下单/查单页面联动、订单页后端分页查询
- 后端：`/dev/login`、CORS+预检修复、秒杀券创建校验增强、订单状态查询接口、`/voucher-order/of/me` 分页接口
- 联调重点：下订单、秒杀、异步库存处理、分布式锁主链路接入
