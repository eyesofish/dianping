# 前后端 API 对齐报告（重写版）

生成时间：2026-03-04  
项目根目录：`D:\Github\dianping`  
前端目录：`D:\Github\dianping\frontend\hmdp`  
后端基础地址：`http://localhost:8081`

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

消费者异步落库：

- `QA` 队列消费者：保存订单 + DB 扣减库存
- `QD` 死信队列消费者：同样执行保存订单 + DB 扣减库存

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

## 6. 重点四：分布式锁（当前状态）

### 6.1 已有锁实现

后端已实现 Redisson 锁代码：

- 锁键：`lock:order:{userId}`
- 位置：`VoucherOrderServiceImpl.handleVoucherOrder()`
- 用途：限制同用户并发创建订单

对应文件：

- `src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java`
- `src/main/java/com/hmdp/config/RedissonConfig.java`

### 6.2 现状说明（重要）

当前 RabbitMQ 消费者路径是“直接 `save + 扣库存`”，**没有复用** `handleVoucherOrder()` 的 Redisson 锁逻辑。  
也就是说：锁代码存在，但不在当前主要消费链路中生效。

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

1. **分布式锁未接入 MQ 主消费链路**
- 现有 `QA/QD` 消费者未调用 `handleVoucherOrder()`，锁逻辑未实际生效。

2. **消息消费幂等性仍需增强**
- 消费端直接 `save`，建议增加唯一约束/幂等校验，避免重复消费造成脏数据。

3. **Lua 返回码语义可再细化**
- `seckill.lua` 中 `-1`（库存 key 缺失）在当前 Java 分支会落到通用失败文案，建议单独映射可读错误。

---

## 9. 本次报告对应改动清单

- 前端：开发态自动登录、Bearer 注入、401 自动刷新、下单/查单页面联动、订单页后端分页查询
- 后端：`/dev/login`、CORS+预检修复、秒杀券创建校验增强、订单状态查询接口、`/voucher-order/of/me` 分页接口
- 联调重点：下订单、秒杀、异步库存处理、分布式锁现状说明
