# 开发环境鉴权流程

## 1. 为什么需要 `/dev/login`

在本地开发阶段，前端可以调用 `POST /dev/login`，无需短信验证码即可获取 JWT token。
这样可以模拟真实的登录后流程，便于联调以下功能：

- 浏览店铺列表
- 查看店铺详情
- 创建订单
- 查看订单记录

## 2. 前端 Token 存储策略

主存储键为 `localStorage["token"]`。

运行时行为：

- 应用启动时，先读取 `localStorage["token"]`。
- 如果 token 存在，直接复用，不再调用 `/dev/login`。
- 如果 token 不存在，调用 `/dev/login` 获取并落盘保存。
- 兼容历史键 `jwt_token`，并迁移到 `token`。

## 3. Axios 拦截器行为

`src/api/http.js` 中定义了一个共享 Axios 实例，并配置了请求/响应拦截器。

请求拦截器：

- 从 localStorage 读取 token
- 自动附加请求头：`Authorization: Bearer <token>`

响应拦截器：

- 保持后端统一返回结构解包（`{ success, data, errorMsg }`）
- 当响应为 `401` 时：
  - 自动调用 `/dev/login` 刷新 token
  - 将新 token 写回 localStorage
  - 对原始失败请求重试一次
- 若刷新失败，则清理本地 token 并拒绝请求

## 4. 启动阶段鉴权引导

`src/main.js` 会在 Vue 应用挂载前完成鉴权初始化：

1. `ensureDevAuthToken()`
2. 复用或保存 token
3. `initUserState()`
4. 挂载应用

这样可以保证应用启动后的 API 请求具备可用 token。

## 5. 已落地文件

- `frontend/hmdp/src/api/auth.js`
  - `devLogin()`
  - token 工具方法（读/写/清理/标准化）
  - `ensureDevAuthToken()`
- `frontend/hmdp/src/api/http.js`
  - 共享 Axios 客户端
  - 全局鉴权请求拦截器
  - `401` 自动刷新与单次重试逻辑
- `frontend/hmdp/src/main.js`
  - 挂载前鉴权引导
- `frontend/hmdp/src/stores/user.js`
  - token 读写逻辑复用 `auth.js` 中的方法

## 6. 请求是如何带鉴权的

所有通过 `http` 发出的 API 请求都会自动包含：

`Authorization: Bearer <token>`

开发环境下的 token 生命周期：

- 初始 token 来源于 `/dev/login`
- token 持久化在 localStorage
- 过期或缺失时由拦截器自动刷新恢复
