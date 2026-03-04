# 前端 API 对齐报告

生成时间：2026-03-04  
项目：`D:\Github\dianping\frontend\hmdp`  
后端基础地址：`http://localhost:8081`

## A. API 映射摘要（最新）

后端统一返回 `Result`：

```json
{
  "success": true,
  "errorMsg": null,
  "data": {},
  "total": null
}
```

鉴权规则（已落地）：
- 前端发送：`Authorization: Bearer <token>`。
- 后端兼容：
  - `Authorization: Bearer <token>`
  - `authorization: <token>`（裸 token）
- token 来源：`POST /user/login` 的 `data` 字段。

### 用户接口
- `POST /user/code?phone=...`
- `POST /user/login`
- `POST /user/logout`
- `GET /user/me`
- `GET /user/info/{id}`
- `GET /user/{id}`
- `POST /user/sign`
- `GET /user/sign/count`

### 商铺接口
- `GET /shop/{id}`
- `POST /shop`
- `PUT /shop`
- `GET /shop/of/type`
- `GET /shop/of/name`
- `GET /shop-type/list`

### 博客接口
- `POST /blog`
- `PUT /blog/like/{id}`
- `GET /blog/of/me`
- `GET /blog/hot`
- `GET /blog/{id}`
- `GET /blog/likes/{id}`
- `GET /blog/of/user`
- `GET /blog/of/follow`

### 关注接口
- `PUT /follow/{id}/{isFollow}`
- `GET /follow/or/not/{id}`
- `GET /follow/common/{id}`

### 优惠券接口
- `POST /voucher`
- `POST /voucher/seckill`
- `GET /voucher/list/{shopId}`

### 订单接口
- `POST /voucher-order/seckill/{id}`（下单，返回 `orderId`）
- `GET /voucher-order/{id}`（新增，查询订单状态）

### 上传接口
- `POST /upload/blog`
- `GET /upload/blog/delete?name=...`

---

## B. 旧前端主要不一致项（已识别）

- 调用了后端不存在的接口：`POST /blog/queryBlogById`。
- 未实现秒杀下单链路：`POST /voucher-order/seckill/{id}`。
- Axios 拦截器中误用 `useRouter()`，并存在 setup 中 `this` 用法错误。
- token 使用 `sessionStorage`，与要求不符（应为 `localStorage`）。
- 旧模板存在未定义方法与引用（`onScroll`、`handleCommand`、`deletePic` 等）。

---

## C. 已完成修复

### 前端修复
- 重建最小可用 Vue3 前端：
  - `ShopList`、`ShopDetail`、`CreateOrder`、`OrderList`、`Login`
- 新增 API 层：
  - `src/api/http.js`（统一拦截器、统一错误处理）
  - `src/api/index.js`（接口函数封装）
- token 改为 `localStorage`。
- 自动附加 `Authorization: Bearer <token>`。
- `OrderList` 支持按 `orderId` 调后端查询最新状态。
- 已清理旧 `src/components` / `src/utils` / `src/css` 等历史代码，降低维护成本。

### 后端修复
- 新增订单查询接口：
  - `GET /voucher-order/{id}`
  - 返回 `VoucherOrderStatusDTO`（包含 `status`、`statusDesc`、`dbExists`、`processing` 等）
- 鉴权头兼容增强：
  - 支持 `Bearer <token>` 与裸 token 两种格式
  - 支持 `Authorization` 与 `authorization` 两种 header key

### 联调结果
- `GET /user/me`：
  - Bearer token 成功
  - 裸 token 成功
- `GET /voucher-order/{不存在id}`：成功返回 `PENDING`（处理中/未落库）
- 构建验证：
  - 后端：`mvn -q -DskipTests package` 成功
  - 前端：`npm run build` 成功

---

## D. 剩余问题 / 后续建议

1. 当前订单列表仍依赖前端本地历史
- 现在能查单条 `orderId`，但后端还没有“按当前用户分页查询订单列表”接口。
- 建议新增：`GET /voucher-order/of/me?current=1`。

2. 秒杀下单链路依赖中间件状态
- 若 RabbitMQ/消费者异常，`POST /voucher-order/seckill/{id}` 可能返回服务器异常或长时间停留在 `PENDING`。
- 建议补充：下单失败可观测日志 + 重试/死信处理可视化。

3. 登出接口仍是占位实现
- `POST /user/logout` 当前返回“功能未完成”。
- 建议后续补 token 失效策略（黑名单或短时 token + 刷新机制）。
