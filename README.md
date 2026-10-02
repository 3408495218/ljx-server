# lajixia-server

垃圾侠平台后端（**B0–B4 全部完成**，契约 24 条路径 / 33 个操作）。配套桌面客户端为 `lajixia-desktop`（同系列项目，单独开源）。

- 许可：[MIT](LICENSE)

## 技术栈

Java 21 · Spring Boot 3.3 · Spring Data JPA · Flyway · jjwt · springdoc-openapi。开发 profile 用 H2（MySQL 模式，**内存库**），生产用 MySQL 8（`--spring.profiles.active=mysql`）。

## 运行

```powershell
mvn spring-boot:run                                    # 默认：H2 内存库（重启即清空）
mvn spring-boot:run -Dspring-boot.run.profiles=local   # 落盘：H2 文件库 data/ljx.mv.db
mvn verify                                             # 跑测试并在项目根导出 openapi.json 契约
```

- 需要 **JDK 21**：本机默认 java 可能是更高版本，直接跑会编译 / 启动失败，先确认 `java -version`
- 接口文档：http://localhost:8080/swagger-ui.html
- 统一响应包装：`{code, message, data}`，业务错误码见 `common/api/ErrorCode.java`
- **数据落盘（`local` profile）**：H2 文件库 `data/ljx.mv.db`，**重启后端不丢账号与房间**；`data/` 已进 `.gitignore`，删掉该目录即重置
  - H2 2.x 的 file 模式**不会自动建目录**，手工启动前请先 `mkdir data`，否则现象只是"后端未就绪"
- **默认仍是内存库**：`mvn test` 的用例共享一个内存库做隔离，默认数据源必须保持 `jdbc:h2:mem:ljx`，落盘只能走 profile
- **开发期邮箱验证码**：不发真实邮件，直接打在后端日志里（搜 `[MAIL][dev]`）
- **连接桌面客户端**：客户端「设置 → 服务器地址」填默认值 `127.0.0.1:8080` 即可

## 模块

```
src/main/java/com/ljx/server/
  auth/       注册登录、JWT 轮换、邮箱绑定（LogMailSender 桩 / SmtpMailSender）
  lobby/      大厅查询（三视图 / 筛选 / 搜索 / 分页）
  room/       房间 CRUD、心跳、加入、成员（MemberService）、在线判定（RoomHeartbeatScheduler）
  quota/      配额类型与校验单点（QuotaService）
  content/    房间内容镜像（本地清单上报）+ 客户端压缩包上传下载
  snapshot/   快照元数据（文件留在房主本机）
  social/     收藏、足迹（加入记录）
  commerce/   VIP / 金币 / 商城（只读）、互动分数与等级、每日活跃
  ws/         STOMP 推送（/ws/lobby：上线 / 人数 / 下线）
  common/     统一响应、错误码、全局异常、认证拦截器、限流、审计、CORS、OpenAPI
```

`/api/**` 除 `/api/auth/**` 外均要求 `Authorization: Bearer <accessToken>`；通过后 handler 可用 `@RequestAttribute("accountId")` 取当前账号。`/ws/lobby` 握手不鉴权（大厅是公开只读数据），写操作仍全部走 `/api/**`。

## 数据库

Flyway 迁移 8 个（`V1`、`V3`–`V9`；**`V2__seed_library.sql` 已随内容库废弃删除**），最终 15 张表：

| 分组 | 表 |
|---|---|
| 账号 | `account` `auth_refresh_token` `email_binding` |
| 房间与内容 | `room` `room_member` `room_content` `client_package` `snapshot` |
| 社交与分数 | `room_favorite` `join_record` `score_event` |
| 商业化与运维 | `quota` `vip_plan` `shop_item` `audit_log` |

> 曾应用过 V2 的持久库升级时会报 missing migration，全新库不受影响。
> `room` 表含公网地址（`host` / `port`）、QQ 群凭据（`qq_group_id_key`）、清单可见性（`plugin_list_visible` / `mod_list_visible`）、在线名单（`player_names`）等后续增列；
> `room.players` 是**成员数**（物化自 `room_member`），服务端真实在线玩家由 `player_names` 承载。

## 已实现

### B0 骨架与账号

- 骨架：Java 21 + Spring Boot 3.3 + JPA + Flyway + jjwt + springdoc-openapi；不引入 Spring Security 过滤链，仅用 `spring-security-crypto`（BCrypt）
- `V1__init.sql` 一次建全 12 张核心表（其中内容库 3 张已由 V3 删除，见 B2）
- 注册即登录、登录、refresh 30 天一次性轮换（**重放已撤销令牌即撤销该账号全部令牌**）、登出
- `GET /api/me`：账号信息 + 配额生效值。**当前只有 2 类配额**——容量「不限」，客户端压缩包按等级分档（5 → 30 MB）；插件 / Mod 数量已随内容库废弃
- 邮箱绑定：验证码 BCrypt 存储、5 分钟有效、60 秒冷却；开发期验证码打到服务端日志（`[MAIL][dev]`）
- CORS 放行 `tauri://localhost`、`http://tauri.localhost`、`http://localhost:5173`

### B1 房间与大厅

- `POST/PUT/DELETE /api/rooms`：创建先校验房主邮箱绑定（未绑定返回 **1306**），再校验配额；改删均校验房主；删除为软删除
- `GET /api/rooms`：`view=all|favorite|history` 三视图 + `core` / `mcVersion` / `q` 筛选 + 分页（默认 18/页，上限 100）；排序人数降序、其次新建优先
- `GET /api/rooms/{id}`：介绍、核心、版本、收藏数、房主名与 VIP、`mine` 标识、清单可见性、在线名单、成员名单（`members`）
- `POST /api/rooms/{id}/heartbeat`：仅房主；刷新在线态、服务端在线名单与心跳时间。**不再写房间人数**——人数由成员决定，在线名单用于判定成员是否「游戏中」
- `PUT/DELETE /api/rooms/{id}/presence`：进入房间 / 续期（幂等）与离开（幂等）。**房间人数 = 成员数**，进入即计数、房主与玩家一致、房主不占容量（超员 1401）；成员 90 秒未续期由 `MemberScheduler` 清理
- `POST /api/rooms/{id}/join`：校验上锁（1303）/ 在线（1304）/ 邮箱门槛（1305），成功后写足迹并下发房主公网地址
- 心跳超时下线：`RoomHeartbeatScheduler` 每 15 秒扫描，超 90 秒未心跳置 `OFFLINE`

### B2 配额 · 内容镜像 · 客户端包 · 快照

- 配额与并发：`QuotaService` 是唯一校验入口，计数类走 **行锁**（`@Lock(PESSIMISTIC_WRITE)`）后算用量，同账号同类型并发请求串行化，杜绝并发超装
- 等级只限制**客户端压缩包大小**（LV0 5 MB → LV5 30 MB，每级 +5 MB）；容量与插件 / Mod 数量均为「不限」（`QuotaType` 表达是否受限，调用点不感知）
- 房间内容镜像：`GET/PUT /api/rooms/{roomId}/contents`。**上报是整表替换**（本地 `plugins/`、`mods/` 目录是唯一事实来源，平台只存展示清单）；非房主读取按房间可见性开关**在接口层裁剪**
- 客户端压缩包：`GET/POST/DELETE /api/rooms/{roomId}/client-package` + `/file` 下载。平台只存一份、重传即替换，上传前按配额校验体积，文件名过 `sanitizeFileName` 防路径穿越；玩家下载后交给 PCL 导入
- 快照：`GET/POST /api/rooms/{roomId}/snapshots` + `DELETE .../{name}`。只登记元数据（名称 / 体积 / 时间），文件留在房主本机；`POST` 按**名称 upsert**（同名只留一条）
- 房间清单可见性：`pluginListVisible` / `modListVisible` 两个**房间级**开关（走 `PUT /api/rooms/{id}`），关闭后玩家只看到总数
- `V3__room_content_and_client_package.sql`：废弃平台内容库（`DROP` `plugin` / `plugin_version` / `room_plugin`），新建 `room_content`、`client_package`

### B3 社交 · 推送 · 分数与等级

- 收藏：`POST/DELETE /api/rooms/{id}/favorite` 均幂等，返回 `{favorited, favoriteCount}`；`RoomDetailDto` 带 `favorited` 供简介页直接渲染
- 足迹：`DELETE /api/me/footprint/{roomId}`、`DELETE /api/me/footprint`；**列表不另开端点**，复用大厅 `GET /api/rooms?view=history`
- 分数与等级：`score_event` 流水 + 物化分数，创建房间 +10 / 他人加入 +1 / 他人收藏 +3（自己收藏自己的房间不计分，防刷）；`ScoreService.award` 是唯一加分入口，落流水 → 更新分数 → 重算等级 → 同步配额 base **同事务**
- WebSocket：`/ws/lobby`（STOMP，simple broker `/topic`），广播 `ONLINE` / `PLAYERS` / `OFFLINE`；用 `@TransactionalEventListener(AFTER_COMMIT)` 在事务提交后推送
- 房间在线人数与名单都由房主端上报，平台只转发与广播（服务端进程跑在房主本机，平台看不到它）

### B4 商业化 · 邮件 · 限流 · 审计

- 商业化（`V4`）：`GET /api/commerce/vip`（6 档）、`GET /api/commerce/shop`（4 件商品）。**只读展示，无支付端点、无购买通道**；金币与 VIP 目前无产出 / 消耗路径
- VIP 与容量解耦（`V5`）：容量由服主自设，`vip_plan.capacity_cap` 已删除，VIP 暂不附带功能权益
- SMTP 邮件：`ljx.mail.*` + `MailConfig` 手工装配（465 隐式 SSL / 587 STARTTLS 可切），QQ 邮箱用**授权码**当密码；`enabled=true` 而凭据为空时**启动即失败**，未启用时由 `LogMailSender` 把验证码打日志
- IP 限流：`login` / `register` / `refresh` / `email-code` 四个接口按 `URI + IP` 固定窗口计数，命中返回 429 + 1601；拦截器注册在**认证之前**，`OPTIONS` 直接放行
- 审计：`audit_log` 表 + 8 类 `AuditAction`（注册、登录成功 / 失败、邮箱绑定、房间创建 / 删除、客户端包上传 / 删除），`AuditService.record` 与业务**同事务**（业务回滚则日志一并回滚）
- 每日活跃：`DailyActiveScheduler` 每日 0:05 补发 +2，按 `score_event` 去重（不另建表）
- QQ 群加群凭据（`V6`）：`POST /api/rooms/{id}/qq-group` 收 `{code, idKey}` 整体覆盖，`idKey` 限 `[A-Za-z0-9_-]{1,128}`。**判断是否展示加群入口一律看 `qqGroupIdKey` 非空**，群号只作展示

## 房间的两个准入开关

「禁止游客」(`no_guest`) 与「需要绑定邮箱」(`need_email`) 是**互相独立**的两个房间级开关，判定集中在 `RoomAccessPolicy.checkRoomGates`：

| 开关 | 拦截对象 | 错误码 |
|---|---|---|
| `no_guest` | **匿名游客**（`POST /api/auth/guest` 自动建的 `account.anonymous` 账号） | `1307` |
| `need_email` | **任何未绑定邮箱的账号**，含正式注册用户（防小号 / 骚扰） | `1305` |

两者可单独开、也可同时开：同时开时游客被 `1307` 拦在最前，正式用户没绑邮箱则被 `1305` 拦。

## 安全约定

- **凭据只走环境变量**，配置文件里只有占位符：`LJX_JWT_SECRET`、`LJX_MYSQL_PASSWORD`、`LJX_MAIL_USERNAME` / `LJX_MAIL_PASSWORD`（QQ 邮箱授权码）、`LJX_STORAGE_DIR`
- BCrypt 存密码与验证码；密码最短 6 位（对齐原版）
- JWT access 2 小时、refresh 30 天一次性轮换，重放即撤销该账号全部令牌
- 限流先于认证；`ljx.security.trust-forwarded-header` 默认 `false`，只有部署在可信反向代理之后才能开，否则客户端可伪造 `X-Forwarded-For` 绕过限流
- 房间写操作一律比对 owner；客户端包文件名必须过 `sanitizeFileName`；`qqGroupIdKey` 的字符集限制是安全边界（值会拼进 URL）
- 限流为进程内单机实现，多实例部署需换 Redis / Bucket4j

## 排期

后端 **B0–B4 已全部完成**，`mvn verify` 85 条测试（19 个测试类）。进度明细见 `plans/backend-plan.md` §9，代码审查与修复记录见 `plans/backend-code-review.md`。

后续可选方向（均无排期）：限流换 Redis、审计查询接口与归档、金币 / VIP 权益与支付闭环、真实内容源接入、`DAILY_ACTIVE` 之外的运营任务。
