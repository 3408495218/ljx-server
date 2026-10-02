# 垃圾侠重建 · 后端技术方案

版本 v1.0（2026-09-30） · 服务对象：桌面端 P1 及之后的全部平台功能
配套文档：《桌面端技术方案》（`lajixia-desktop` 仓库的 `plans/desktop-plan.md`），里程碑编号互相引用

## 1 目标与范围

支撑桌面端的平台侧能力：账号、房间登记与发现、配额、房间内容镜像、客户端压缩包、快照元数据、收藏足迹、消息推送、商业化。不包含游戏服务端托管——服务端进程在房主本机运行，后端只做登记、发现与元数据。

范围裁剪两项：

- 快照只存元数据（名称、大小、时间），文件保留在房主本地，云备份留待后续
- 插件与 Mod **不做分发**：真实文件在房主本机 `plugins/`、`mods/` 目录，平台只存一份供展示的清单镜像；客户端整合包不由平台打包，只做「房主上传 zip → 玩家下载」
- 商业化不含支付，VIP / 金币 / 商城只做数据模型与查询接口

## 2 技术栈

| 项 | 选型 | 说明 |
|---|---|---|
| 语言与框架 | Java 21 + Spring Boot 3.3 | 主力栈，Spigot 开发经验直接迁移 |
| 构建 | Maven，单应用 | package-per-feature；多模块在单人开发下只增加仪式感，出现独立部署诉求再拆 |
| 数据库 | MySQL 8 + Flyway | 迁移脚本入库；开发 profile 用 H2 |
| 实时推送 | Spring WebSocket（STOMP） | 大厅房间状态广播 |
| 认证 | jjwt，access 2h + refresh 30d 轮换 | refresh 一次性使用 |
| 密码 | BCrypt（强度 10） | |
| 接口文档 | springdoc-openapi | 联调契约以导出的 openapi.json 为准 |
| Redis | 暂不引入 | 心跳与在线态先用数据库 + 定时任务，房间量过千或压测异常再评估 |

## 3 模块划分

```
lajixia-server/src/main/java/com/ljx/server/
  auth/       注册登录、JWT、邮箱绑定
  lobby/      大厅查询（三视图 / 筛选 / 搜索 / 分页）
  room/       房间 CRUD、加入、心跳、在线判定
  quota/      配额模型与服务端校验
  content/    房间内容镜像（本地插件 / Mod 清单上报与展示）、客户端压缩包
  snapshot/   快照元数据
  social/     收藏、足迹（加入记录）、QQ 群配置
  commerce/   互动分数与等级、VIP、金币、商城（B4）
  ws/         WebSocket 推送
  common/     统一响应包装、异常、审计日志
```

## 4 领域模型

核心表（Flyway `V1__init.sql`；后续迁移 V3 内容镜像、V4 商业化、V5 容量解耦、V6 QQ 群 idkey、V7 清单可见性、V8 在线名单，见 §9「B2 修订」「B4」「B4 修订」「B4 补充」）：

| 表 | 关键字段 | 说明 |
|---|---|---|
| account | username（唯一）、password_hash、level、score、vip_level、coins | 等级与分数由 score_event 流水计算 |
| email_binding | account_id（PK）、email、code_hash、verified | 验证码 BCrypt 存储，5 分钟有效；邮箱替代短信控制成本 |
| room | owner_id、name、intro、core、mc_version、capacity、locked、no_guest、need_email、qq_group_code、qq_group_id_key、cover_url、plugin_list_visible、mod_list_visible、player_names、status、last_heartbeat_at | status: ONLINE / OFFLINE；软删除；`*_list_visible` 控制插件 / Mod 清单是否对进房玩家列出（V7，默认 1）；`player_names` 为心跳上报的在线名单（V8，逗号分隔，空名单存 NULL） |
| room_favorite | room_id + account_id 联合主键 | |
| join_record | room_id、account_id、joined_at | 足迹来源，也产出互动分数事件 |
| quota | account_id、type、base、add、limit | type: PLAYER_CAP / CLIENT_PKG_MB |
| room_content | room_id + name 联合主键、type、size_bytes、enabled、reported_at | 房主本机 `plugins/`、`mods/` 清单的**展示镜像**，房主整表替换上报 |
| client_package | room_id（PK）、file_name、size_bytes、uploaded_at | 客户端压缩包元数据，一房间一条；玩家下载后自行交给 PCL 导入 |
| snapshot | room_id、name、size_bytes、created_at | 仅元数据 |
| score_event | account_id、type、delta、created_at | 分数流水，等级可重算；`DAILY_ACTIVE` 按日去重也读这张表 |
| vip_plan（V4，V5 删 capacity_cap） | level（PK）、name、price_coins、description | VIP 档位名与价格；**容量由服主自设，与档位无关** |
| shop_item（V4） | name（唯一）、category、price_coins、description、sort_order | 商城目录，仅展示与查询 |
| audit_log（V4） | account_id、ip、action、detail、success、created_at | 关键操作留痕，按 `(account_id, created_at)` / `(action, created_at)` 建索引 |

配额生效值统一为 `min(base(等级) + add(购买), limit(上限))`，与原版 `*_capacity_add` / `*_capacity_limit` 字段族对应。**当前只有客户端压缩包大小受配额限制**（`CLIENT_PKG_MB`）：容量由房主自设、插件与 Mod 不再有数量配额（内容库表已随 V3 删除）。

## 5 API 契约

```
# 账号
POST /api/auth/register        {username, password}
POST /api/auth/login           {username, password} → {accessToken, refreshToken, account}
POST /api/auth/refresh         {refreshToken}
POST /api/auth/logout
GET  /api/me                   → 账号信息 + 各类配额生效值
PUT  /api/me/email             {email, code}
POST /api/me/email/code        {email}

# 大厅
GET /api/rooms?view=all|favorite|history&core=&mcVersion=&q=&page=1&size=18
   → {items[{id, name, cover, players, capacity, vip, online, core, mcVersion}],
      page, total}

# 房间（房主）
POST   /api/rooms               创建，需绑定邮箱；容量由房主自设，服务端不设上限（见 §6.2）
PUT    /api/rooms/{id}          局部更新（null 保持不变）；host / port 即房主公网地址；
                                pluginListVisible / modListVisible 控制清单对玩家的可见性（V7）
DELETE /api/rooms/{id}
POST   /api/rooms/{id}/heartbeat   {players, uptimeSec, playerNames}   客户端每 30s 上报；
                                   playerNames 为在线名单，随心跳整体覆盖（V8）
POST   /api/rooms/{id}/qq-group    {code, idKey}  整体覆盖；两者都为空即取消点亮
                                  code 为群号（仅展示），idKey 为腾讯 WPA 组件凭据（真正生成加群入口）

# 加入（玩家）
POST /api/rooms/{id}/join       校验 locked / no_guest / need_email → {host, port}
GET  /api/rooms/{id}            房间简介页：介绍、核心、版本、收藏数、favorited、qqGroupCode / qqGroupIdKey、
                                pluginListVisible / modListVisible（插件 / Mod 计数与明细改由 `/contents` 清单给出）、
                                playerNames（在线名单，房主心跳驱动的快照）

# 收藏 / 足迹
POST   /api/rooms/{id}/favorite 收藏（幂等）→ {favorited, favoriteCount}
DELETE /api/rooms/{id}/favorite 取消收藏（幂等）→ {favorited, favoriteCount}
DELETE /api/me/footprint/{roomId}  移除单条足迹（幂等）
DELETE /api/me/footprint           清空足迹（幂等）
  足迹列表读取复用大厅三视图 GET /api/rooms?view=history

# 房间内容镜像（插件 / Mod 的真实来源是房主本机目录，平台只存展示清单）
GET    /api/rooms/{roomId}/contents               登录即可读；房主关闭可见性的那一类对非房主不返回
   → [{name, type: plugin|mod, sizeBytes, enabled, reportedAt}]
PUT    /api/rooms/{roomId}/contents               仅房主；{items:[{name, type, sizeBytes, enabled}]} 整表替换
   → 替换后的完整清单

# 客户端压缩包（房主上传，玩家下载后交给 PCL 导入）
GET    /api/rooms/{roomId}/client-package         元数据；未上传时 data 为 null
POST   /api/rooms/{roomId}/client-package         multipart(file) 仅房主；仅 .zip，按 CLIENT_PKG_MB 校验体积
DELETE /api/rooms/{roomId}/client-package         仅房主
GET    /api/rooms/{roomId}/client-package/file    登录即可下载（Content-Disposition: attachment）

# 快照（元数据）
POST   /api/rooms/{roomId}/snapshots                  {name, sizeBytes}  同名 upsert：只留一条并刷新体积
DELETE /api/rooms/{roomId}/snapshots/{name}                              按名称幂等；返回余下列表
GET    /api/rooms/{roomId}/snapshots                                     新建优先

# 商业化（B4，无支付闭环，只读查询）
GET /api/commerce/vip    → {currentLevel, currentName, coins, plans:[{level, name, priceCoins, description}]}  容量由服主自设，与 VIP 无关
GET /api/commerce/shop   → {coins, items:[{id, name, category, priceCoins, description}]}

# 推送
WS /ws/lobby                    房间上/下线、人数变化广播（STOMP，订阅 /topic/lobby）
```

## 6 关键设计

### 6.1 心跳与在线判定

客户端每 30 秒 POST 一次 heartbeat；`@Scheduled` 任务每 15 秒扫描 `last_heartbeat_at` 超过 90 秒的房间置 offline，并向 `/ws/lobby` 广播状态变化。大厅查询直接读 status 字段，靠 `(status, updated_at)` 索引支撑；MVP 不加缓存，房间量过千再考虑 5 秒短缓存。

心跳体（`HeartbeatRequest`）为 `players`（人数，钳制在 0..capacity）、`uptimeSec`（当前仅透传）、`playerNames`（在线名单，≤64 个、每个 ≤16 字符）。**服务端不产生这两个值，只做转发与落库**——服务端进程跑在房主本机，平台没有任何途径感知其连接数（B4 补充三）。名单随心跳**整体覆盖**：空名单落成 NULL，不留空串占位。广播上，由离线转在线播 `ONLINE`；在线期间**人数或名单任一变化**才播 `PLAYERS`，避免每 30 秒的无意义广播。

### 6.2 配额校验

**配额体系里受限的只有 `CLIENT_PKG_MB`（客户端压缩包上传上限，由等级决定）**；`PLAYER_CAP` 标记为 `unlimited()`，生效值为 `Integer.MAX_VALUE`，`QuotaService` 层面校验恒通过。插件 / Mod 的数量配额类型（`PLUGIN` / `CLIENT_MOD` / `SERVER_MOD`）已随内容库一并从 `QuotaType` 移除。

**房间容量由服主自行设置，服务端不设上限**（B4 修订）：容量与 VIP 等级**不挂钩**，`RoomService.checkCapacity` 只保留 `quotaService.check(PLAYER_CAP, capacity)` 这一个入口（`PLAYER_CAP` 为「不限」，当前恒通过）。若产品后续要定上限，只改 `QuotaType.PLAYER_CAP` 的 base 表即可，调用点零改动。B4 初版曾按 VIP 档位收紧容量（`VipService` + `vip_plan.capacity_cap`），该设计已废弃并删除（见 §9「B4 修订」）。

配额类型自身表达「是否受限」：`QuotaType.unlimited()` 由 base 表是否为空决定。调用点（客户端压缩包上传的体积校验）**保持不变**，由 `QuotaService` 内部分流：

- `check(accountId, type, value)`：值类校验，不受限类型不触库直接放行
- `checkCount(accountId, type, usageSupplier)`：计数类校验，先 `lockEffective()` 加行锁，不受限类型跳过用量统计（supplier 不会被求值）与校验

计数类仍**始终加锁**（`QuotaRepository.findForUpdate` 的 `SELECT ... FOR UPDATE` / `@Lock(PESSIMISTIC_WRITE)`），一是保留「先锁行 → 再算用量 → 后校验」的并发正确性，二是顺带把同类并发请求串行化。客户端展示的上限只作 UI 提示，以服务端校验为准。

### 6.3 互动分数与等级

score_event 事件枚举与初始分值（数值为初始假设，上线前需确认）：

| 事件 | 分值 |
|---|---|
| ROOM_CREATED | +10 |
| JOINED_BY_OTHER | +1 |
| FAVORITE_RECEIVED | +3 |
| DAILY_ACTIVE | +2 |

等级阈值：LV0 = 0，LV1 = 50，LV2 = 200，LV3 = 500，LV4 = 1200，LV5 = 3000。**等级只决定客户端压缩包上传上限**（`CLIENT_PKG_MB`），不参与容量。`QuotaType` 内按等级存一行 base 表（下标即等级，LV0 行即注册时的初始值），升级时 `QuotaService.applyLevelBase` 重写 base，`add` / `limit` 保留。

客户端包分档（封顶 30 MB）：LV0 = 5、LV1 = 10、LV2 = 15、LV3 = 20、LV4 = 25、LV5 = 30 MB。

### 6.4 邮箱绑定

`MailSender` 接口 + 两个实现，按 `ljx.mail.enabled` 二选一装配：`SmtpMailSender`（`true`，真实 SMTP）与 `LogMailSender`（`false`，验证码打日志，`matchIfMissing=true` 作开发期兜底）。**邮箱替代手机短信以控制成本**。

真实通道用 QQ 邮箱（`smtp.qq.com:465` 隐式 SSL，`MailConfig` 手工装配 `JavaMailSenderImpl`），**密码填「授权码」而非登录密码**。配置项 `ljx.mail.{enabled,host,port,username,password,from,ssl-enabled,connect-timeout,read-timeout}`；`from` 留空回落 `username`。

**凭据只从环境变量读取，配置文件里不留默认值**：

| 环境变量 | 说明 |
|---|---|
| `LJX_MAIL_ENABLED` | `true` 走真实 SMTP；缺省 `false`（验证码打日志），未注入凭据也能正常启动 |
| `LJX_MAIL_USERNAME` | 发件邮箱，如 `xxx@qq.com` |
| `LJX_MAIL_PASSWORD` | QQ 邮箱**授权码**（设置 → 账户 → IMAP/SMTP 服务），不是登录密码 |
| `LJX_MAIL_HOST` / `LJX_MAIL_PORT` / `LJX_MAIL_FROM` / `LJX_MAIL_SSL` | 可选覆盖，默认 `smtp.qq.com` / `465` / 空（回落 username）/ `true` |

`enabled=true` 而 `username` 或 `password` 为空时，`MailConfig` 在**装配 Bean 阶段直接抛 `IllegalStateException`**，启动即失败并点名缺哪个变量——避免上线后才发现验证码发不出去。`enabled=false` 时 `LogMailSender` 兜底，验证码打到服务端日志。

验证码 6 位、5 分钟有效、同一账号 60 秒冷却；发送失败由 `SmtpMailSender` 上抛，`EmailService` 转成 1204 错误码，**不会**因为发信失败而写入绑定行。绑定成功的邮箱参与 need_email 进房校验。滥用防护由接口层 IP 限流承担（见 §6.5），未做「每邮箱每日条数」的独立计数。

### 6.5 安全

- BCrypt 存密码，注册校验密码 ≥ 6 位（对齐原版）
- JWT access 2 小时；refresh 30 天且一次性轮换，重放即失效全部令牌
- 登录、注册、验证码接口按 IP 限流（固定窗口计数，进程内 `ConcurrentHashMap`；`RateLimitInterceptor` 挂在**认证拦截器之前**，未认证请求先被限流挡掉，不触发 JWT 解析。压测不达标再换 Bucket4j / Redis）
  - 默认阈值：login 10 次/5 分钟、register 5 次/10 分钟、refresh 30 次/5 分钟、email-code 5 次/10 分钟；`ljx.rate-limit.*` 可调
  - 限流**仅防接口滥用**，**不做审计记录**（区分「运维审计」与「防御日志」，限流拒绝本身不入 audit_log）
- 关键操作写审计日志（`audit_log` 表）：注册、登录成功 / 失败、邮箱绑定、房间创建 / 删除、客户端包上传 / 删除（共 8 个 `AuditAction`，只覆盖身份与数据变更，不记录读操作）；`AuditService.record` 与业务同事务，操作成功则日志必存在。IP 取 `ClientIp.resolve(request, trustForwardedHeader)`，无请求上下文（定时任务）时为 null
- CORS 只放行 `tauri://localhost` 与自定义 scheme
- 房间所有权校验：所有 `/api/rooms/{id}` 写操作先比对 owner

## 7 里程碑

| 阶段 | 范围 | 预估 | 状态 |
|---|---|---|---|
| B0 骨架与账号 | Spring Boot 骨架、Flyway V1、注册登录 JWT、邮箱绑定桩、openapi 导出 | 1 周 | **已完成（2026-09-30）** |
| B1 房间与大厅 | 房间 CRUD、三视图 / 筛选 / 搜索 / 分页、心跳与在线判定、加入记录 | 1.5 周 | **已完成（2026-09-30）** |
| B2 配额与内容 | 配额模型与校验、房间内容镜像（本地清单上报）、客户端压缩包、快照元数据 | 1.5 周 | **已完成（2026-09-30）** |
| B3 社交与推送 | 收藏、足迹、WebSocket 推送、互动分数与等级计算 | 1 周 | **已完成（2026-09-30）** |
| B4 商业化与加固 | VIP / 金币 / 商城（无支付）、SMTP 邮件服务、限流与审计 | 1.5 周 | **已完成（2026-09-30）** |

**后端 B0–B4 全部完成**，契约共 23 条路径 / 31 个操作（`lajixia-server/openapi.json`），`mvn verify` 18 条测试全绿。

每个阶段结束导出一次 openapi.json，桌面端按契约先 mock 后联调。进度明细见 §9。

## 8 与桌面端联调节奏

| 周 | 桌面端 | 后端 |
|---|---|---|
| W1–2 | P0 本地闭环（mock 数据） | B0 |
| W3–4 | P1 平台接入 | B1 |
| W5 | P2 玩家闭环 | B1 收尾 |
| W6–7 | P2 收尾 + P3 内容与快照 | B2 |
| W8 | P3 | B3 |
| W9–10 | P4 完整体验 | B4 |

联调顺序固定为「后端导出契约 → 桌面端 mock 跑通 → 双端对时联调」，避免接口未定时双端同时开工造成返工。

## 9 进度记录

### B0 骨架与账号（已完成，2026-09-30）

代码位置：`lajixia-server/`。运行方式：`mvn spring-boot:run`（H2 内存库，http://localhost:8080，Swagger 在 /swagger-ui.html）；MySQL 用 `--spring.profiles.active=mysql`。

已交付：

- 骨架：Java 21 + Spring Boot 3.3.13 + JPA + Flyway + jjwt + springdoc-openapi；不引入 Spring Security 过滤链，仅用 `spring-security-crypto`（BCrypt）
- `V1__init.sql`：§4 全量 12 张核心表（account / auth_refresh_token / email_binding / room / room_favorite / join_record / quota / plugin / plugin_version / room_plugin / snapshot / score_event），B1–B4 不再动表结构
- auth：注册即登录（注册直接发令牌 + 初始化 5 类配额）、登录、refresh 30 天一次性轮换（**重放已撤销 token 时撤销该账号全部令牌**）、登出、`GET /api/me`（账号信息 + 5 类配额生效值）。B0 当时写的是「LV0：容量 10 / 插件 5 / 客户端 Mod 5 / 服务端 Mod 5 / 客户端包 10 MiB」，**该组数值已在 B3 调整**：容量与插件 / Mod 不再受限，客户端包 LV0 = 5 MiB（见 §6.2 / §6.3）
- 邮箱绑定桩：`POST /api/me/email/code` + `PUT /api/me/email`；验证码 BCrypt 存储、5 分钟有效、60 秒冷却；`MailSender` 接口 + `LogMailSender`（验证码打服务端日志），B4 换 SMTP 实现。（2026-09-30 修订：验证通道由手机短信改为邮箱，控制成本）
- common：`{code, message, data}` 统一响应、错误码枚举（1101 用户名占用 / 1102 凭证错误 / 1103 令牌失效 / 1201–1203 邮箱与验证码 / 5000 内部错误）、Bearer 认证拦截器（`/api/**` 放行 `/api/auth/**`，通过后 `@RequestAttribute("accountId")` 取账号）、CORS（tauri://localhost、http://tauri.localhost、http://localhost:5173）、OpenAPI bearer scheme
- 测试：`AuthFlowTest` 2 条端到端用例全绿（注册→登录→me→轮换→重放撤销全链路；邮箱绑定含错误码/冷却/格式校验）
- 契约：`mvn verify` 自动导出 `lajixia-server/openapi.json`（7 端点），桌面端 P1 按此 mock

实现中修掉的两个缺陷（后续 agent 注意不要退化）：

1. JWT 加随机 `jti`——否则同毫秒连续签发的 token 完全相同，撞 `token_hash` 唯一约束
2. 重放撤销走 `REQUIRES_NEW` 独立事务——重放分支外层事务必然随异常回滚，同事务撤销会被一并回滚

环境备忘：本机 `JAVA_HOME` 未设置时 Maven 会拿到 TRAE 自带 JRE（`...\TRAE SOLO CN\...\vm\tools\app\jre`，无编译器），**症状是 18 条测试全报 `Could not initialize inline Byte Buddy mock maker` / `No compatible attachment provider is available`**（JRE 缺 `jdk.attach`，Mockito 无法自附加 agent），并非代码问题。修复：跑 Maven 前 `$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.10"` 并把 `$env:JAVA_HOME\bin` 前置到 `PATH`。生产必须设置 `LJX_JWT_SECRET` 与 `LJX_MYSQL_PASSWORD` 环境变量。

### B1 房间与大厅（已完成，2026-09-30）

新增 9 个端点，`mvn verify` 后契约共 16 个端点（`lajixia-server/openapi.json`）。测试 `RoomFlowTest` 6 条用例全绿（创建/心跳/加入/三视图与筛选、门槛与房主权限、超时下线与软删除、畸形 JSON 参数错误、创建需绑邮箱、我的游戏列表），与 B0 合计 8 条。

已交付：

- `room` 包：`Room` 实体 + `RoomStatus`(ONLINE/OFFLINE) + `RoomRepository`（含 `markStaleOffline` 批量更新）、`RoomService`、`RoomController`、`RoomHeartbeatScheduler`
- `lobby` 包：`LobbyService`（Specification 动态筛选 + 分页）、`LobbyController`
- `quota` 包：新增 `QuotaService`——**配额校验单点**，`effective()` 与 `check()`；创建与修改房间的容量校验都走这里（B2 在此加 `SELECT ... FOR UPDATE`）
- `social` 包：`RoomFavorite` + `JoinRecord` 实体与仓库（B1 只用读取与写入足迹，收藏/取消接口留 B3）
- `library` 包：`RoomPlugin` 实体与仓库，仅用于详情页插件数（B2 扩展为完整内容库）
- `common`：新增 `PageResult<T>`；`BizException` 增加带自定义消息的构造器；错误码新增 1301 房间不存在 / 1302 无权操作 / 1303 已上锁 / 1304 未在线 / 1305 需绑定邮箱 / **1306 创建房间需先绑定邮箱** / 1401 超出配额；`GlobalExceptionHandler` 补 `HttpMessageNotReadableException` 与 `MethodArgumentTypeMismatchException` → 1001（此前畸形 JSON 会落到 5000）
- `GET /api/rooms/mine`：「我的游戏」列表（当前账号名下未删除房间，新建优先，VIP 取账号等级）。注意该字面量路由必须优先于 `/api/rooms/{id}` 匹配——Spring 按字面量优先解析，已由联调验证

关键实现决定（后续 agent 注意）：

1. `room.status` 存大写 `ONLINE`/`OFFLINE`（`@Enumerated(STRING)`），V1 的 DDL 默认值已同步改为 `'OFFLINE'`
2. 心跳超时用 `markStaleOffline` 批量 UPDATE（绕过 `@PreUpdate`，故显式写 `updatedAt`）；扫描方法 `RoomService.expireStale(Duration)` 是 public 的，测试用负超时把阈值推到未来来验证，不需要 sleep
3. 大厅排序为「人数降序、其次新建优先」，**未做在线优先排序**（原版截图里离线房间与在线混排），如需在线优先再调
4. `view=favorite` / `view=history` 先取该账号的房间 id 列表再 `id IN (...)` 过滤，避免 Criteria 子查询；空列表直接返回空页
5. 房主公网地址由 `PUT /api/rooms/{id}` 的 `host`/`port` 设置（heartbeat 契约保持 `{players, uptimeSec}` 不变），join 时下发

与计划的偏差：

- `no_guest` 与 `need_email` 当前同义：原版「禁止游客」针对未注册游客，重建版全量要求登录注册，故两者都收敛为「加入者必须已绑定邮箱」。若后续引入游客模式需拆分
- 创建房间已在服务端强制校验邮箱绑定（1306），前端只做引导提示，**不能**依赖前端拦截
- 一级页签为「单房间」模型（对齐原版）：`/api/rooms/mine` 允许返回多间，但桌面端只取最新一间进入管理页；若开放多房间需前端补房间切换
- 心跳未向 `/ws/lobby` 广播（B3 交付 WS）
- 未做 IP 限流（B4）

下一步（B2）：插件 / Mod 目录与版本、`POST /api/rooms/{id}/plugins` 安装校验配额、快照元数据接口；同时给 `QuotaService` 加行锁防并发绕过。

### B2 配额与内容库（已完成，2026-09-30；内容库部分已被「B2 修订」废弃）

新增 6 个端点，`mvn verify` 后契约共 22 个端点（`lajixia-server/openapi.json`）。测试 `LibraryFlowTest` 4 条用例全绿（目录筛选与非法类型、安装配额与幂等、**并发安装不超装**、快照元数据 CRUD），与 B0/B1 合计 12 条。

已交付：

- `quota` 包：`QuotaService` 重构为 `lockLimit()` + `ensureWithin()`；`QuotaRepository.findForUpdate` 加 `@Lock(PESSIMISTIC_WRITE)` 行锁。安装内容按「先锁行 → 算用量 → 后校验」执行，同账号同类型并发请求串行化
- `library` 包：`ContentType` 枚举（PLUGIN / CLIENT_MOD / SERVER_MOD，`@JsonCreator` 支持 slug 解析、`parseFilter` 支持 `mod` 合集别名）、`Plugin` / `PluginVersion` / `RoomPlugin`（复合主键 `RoomPluginId`）实体与仓库、`LibraryService`（Specification 动态筛选 + 分页）、`RoomContentService`（安装 / 卸载 / 已装列表）
- 目录接口 `GET /api/library/plugins`：按 `type`（含 `mod` 别名）、`core`、`mcVersion`、`q` 筛选 + 分页；`mcVersion` 用 exists 子查询参与分页，避免「先分页再按版本过滤」导致 `total` 与 `items` 不一致；条目内嵌版本列表
- 房间内容接口：`GET/POST /api/rooms/{roomId}/plugins`、`DELETE /api/rooms/{roomId}/plugins/{pluginVersionId}`；重复安装与卸载未安装版本均幂等。B2 当时按 PLUGIN / CLIENT_MOD / SERVER_MOD 三条独立配额线校验（上限 5），**B3 起数量上限暂不启用**（校验入口保留）
- `snapshot` 包：`Snapshot` 实体 + `SnapshotService` + `SnapshotController`，`POST/GET /api/rooms/{roomId}/snapshots`；仅存元数据（名称、大小、时间），文件保留在房主本机，列表新建优先
  - **P3 收口（2026-09-30）**：桌面端 P3 打通平台元数据时发现两个缺口，已补齐——① 缺删除接口，本地删掉快照后平台侧会留下清不掉的孤儿记录 → 新增 `DELETE /api/rooms/{roomId}/snapshots/{name}`（按名称幂等，返回余下列表）；② `POST` 每次调用都插新行，而本地快照是「同名即同一文件」（`ljx-snapshots/<名称>.zip`，重名创建直接报错），重建快照后重连会堆出重复行 → `POST` 改为**按名称 upsert**（同名只留一条并刷新体积）。两端对齐的键就是**名称**，改命名规则必须同步考虑。`LibraryFlowTest` 补 `snapshotUpsertByNameAndIdempotentDelete`，契约操作数 26 → **27**、测试 16 → **17**
- `V2__seed_library.sql`：起步目录种子（6 插件 + 4 客户端 Mod + 3 服务端 Mod），`download_url` 统一指向 `example.com` 占位，待接入真实内容源后替换；**仅数据、不动 V1 表结构**
- 错误码：新增 1501 内容不存在
- `RoomService.requireOwned` 提为 public，供内容 / 快照模块复用

关键实现决定（后续 agent 注意）：

1. 配额锁必须在**同一事务内**先加锁再算用量——`checkCount()` 从 `install()` 内部调用，走外层事务，锁在事务提交时释放（B3 起该方法内部为私有 `lockEffective()`）
2. `V2` 只插数据、不改表结构：§4 的 12 张表在 V1 已一次建全，Flyway 校验和不受影响
3. 快照只登记元数据，服务端不落文件；`size_bytes` 由客户端上报，服务端不校验真实性（B4 若做容量统计再收紧）

与计划的偏差：

- 目录 `q` 关键词为大小写敏感的 `LIKE`（与大厅房间搜索 `LobbyService` 一致）。Mod 名多为英文，`q=sodium` 匹配不到 `Sodium`，如需宽松匹配后续统一改为 `lower()` 比较
- 安装接口返回「安装后的完整已装列表」而非单条记录，便于桌面端一次刷新；契约与 `GET` 同形
- 目录未做「按 MC 版本过滤版本列表」以外的排序策略（当前按 id 倒序，新版本优先）

下一步（B3）：收藏 / 足迹接口、`/ws/lobby` WebSocket 推送（STOMP）、score_event 互动分数与等级计算。

### B3 社交与推送（已完成，2026-09-30）

新增 4 个端点 + 1 个 WebSocket 端点，`mvn verify` 后契约共 26 个操作（`lajixia-server/openapi.json`）。测试 `SocialFlowTest` 4 条用例全绿（收藏幂等与计数、足迹移除与清空、分数累计与升级同步包配额、**STOMP 订阅收到房间上线广播**），与 B0/B1/B2 合计 16 条。

已交付：

- `social` 包：`FavoriteService` + `FavoriteController`（`POST/DELETE /api/rooms/{id}/favorite`，均幂等，返回 `{favorited, favoriteCount}` 便于前端一次刷新按钮态）、`FootprintService` + `FootprintController`（`DELETE /api/me/footprint/{roomId}`、`DELETE /api/me/footprint`）
- `RoomDetailDto` 新增 `favorited` 字段（当前账号是否已收藏），房间简介页可直接渲染收藏按钮状态
- `commerce` 包：`ScoreEventType`（分值表）、`LevelTable`（等级阈值）、`ScoreEvent` 实体 + 仓库（含流水求和 `sumDeltaByAccountId` 作重算入口）、`ScoreService`
- 计分挂钩：创建房间 +10、他人加入 +1、他人收藏 +3；自己收藏自己的房间不计分（防刷分）
- `quota`：`QuotaType` 改为「base 表为空即不受限」（`unlimited()` / `baseFor(level)`，`base()` 即 LV0 行）、`Quota` 增 `applyBase` 与 `effective()` 防溢出、`QuotaService` 收敛为 `check` / `checkCount` / `applyLevelBase`；升级时同步重写 base，`add` / `limit` 保留
- `ws` 包：`WebSocketConfig`（`@EnableWebSocketMessageBroker`，端点 `/ws/lobby`，simple broker `/topic`）、`LobbyEvent`（type + 房间卡片字段）、`LobbyBroadcaster`
- 广播触发点：心跳由离线转在线播 `ONLINE`，在线期间人数变化播 `PLAYERS`（人数不变不播，避免每 30 秒空广播）；心跳超时下线逐个播 `OFFLINE`

关键实现决定（后续 agent 注意）：

1. 广播用 `@TransactionalEventListener(AFTER_COMMIT)`（`LobbyBroadcaster` 监听 `LobbyEvent`），只在事务提交后推送，回滚掉的状态变化不会播出去；推送失败只告警不影响业务事务
2. `expireStale` 改为「先 `findStaleOnline` 取出待下线房间 → 再批量 `markStaleOffline` → 最后逐个发事件」，批量 UPDATE 绕过持久化上下文，事件字段在更新前就已取好
3. 分数、等级、配额 base 的更新必须同事务：`ScoreService.award` 是唯一加分入口，四步（落流水 → 更新物化分数 → 重算等级 → 升级时同步配额 base）收敛在这里
4. `/ws/lobby` 握手不做 JWT 校验（大厅是公开只读数据），写操作仍全部走 `/api/**` 的 Bearer 认证；`AuthInterceptor` 只拦 `/api/**`，不影响 WS 端点
5. 足迹列表**不另开端点**，复用大厅三视图 `GET /api/rooms?view=history`；`/api/me/footprint` 只做清理，避免同一数据两套接口
6. **配额「是否受限」由 `QuotaType` 表达，调用点不感知**：`checkCount` 在不受限类型上仍取行锁（保住并发串行化），但跳过用量统计与校验。VIP 或产品定下上限时只改 `QuotaType`，`RoomService` / `RoomContentService` 零改动

与计划的偏差：

- `DAILY_ACTIVE` 已在枚举中定义但**未接线**：需要按日去重的定时任务（同一账号每天只发一次），留待 B4 与限流 / 审计一起做
- 客户端包分档（5/10/15/20/25/30 MB）为初始假设（见 §6.3），机制已就绪，改数值只需改 `QuotaType` 一行
- 收藏 / 取消收藏只返回按钮态与计数，未返回房间摘要；前端大厅刷新仍走 `GET /api/rooms?view=favorite`
- 广播只覆盖上/下线与人数变化，未覆盖建房、改名、改容量（大厅卡片刷新仍靠轮询或重新拉列表）
- **`CLIENT_PKG_MB` 当时没有服务端校验点**：客户端压缩包上传接口尚未排期，该上限只在 `/api/me` 暴露给前端作 UI 提示。**已于「B2 修订」补齐**：`POST /api/rooms/{roomId}/client-package` 上传前按该上限校验体积

### B3 修订（2026-09-30，配额语义调整）

产品口径确认后调整配额模型，与 B3 同日完成：

- **容量由房主自行设置**，不再受等级配额限制。`PLAYER_CAP` 标记 `unlimited()`（当时设想「提升容量需开通 VIP」，该设想已于「B4 修订」作废——容量与 VIP 彻底解耦）
- **等级只负责限制客户端压缩包上传大小**，封顶 30 MB，分档 LV0 = 5 → LV5 = 30（每级 +5 MB）
- **插件 / Mod 数量暂不限制**，`PLUGIN` / `CLIENT_MOD` / `SERVER_MOD` 标记 `unlimited()`；配额行与校验入口保留（**后于「B2 修订」随内容库一并删除这三个类型**）
- `QuotaService` 的 `lockLimit` 收敛为私有 `lockEffective`，公开 API 改为 `check`（值类）与 `checkCount`（计数类，用量用 `Supplier` 惰性求值，不受限时不算用量）
- 受影响测试同步调整：`AuthFlowTest` 断言 LV0 配额组、`RoomFlowTest` 容量 11 可建（原断言 1401）、`LibraryFlowTest` 改为「7 个插件版本全部可装 + 并发全部成功」、`SocialFlowTest` 断言 `CLIENT_PKG_MB` 5 → 10

下一步（B4）：VIP / 金币 / 商城数据模型与查询接口（无支付）、~~VIP 容量上限收紧~~（⚠️ 已于「B4 修订」废弃：容量与 VIP 解耦）、SMTP 邮件服务（替换 `LogMailSender`）、IP 限流与审计日志、`DAILY_ACTIVE` 每日任务。

### B2 修订：内容库废弃 + 房间内容镜像与客户端压缩包（2026-09-30）

**需求变更**：插件与 Mod 以**房主本机游戏服务端的 `plugins/`、`mods/` 目录为唯一事实来源**，平台不参与分发，只保存一份供进房玩家查看的清单镜像；客户端压缩包由房主自行整理后上传，玩家下载后交给 **PCL** 导入，**平台不参与客户端打包**。

验证（2026-09-30）：

- `mvn verify`：**13 个测试全绿**（AuthFlowTest 2 + RoomFlowTest 7 + SocialFlowTest 4）
- `openapi.json` 重导出：新增 `/api/rooms/{roomId}/contents`（GET/PUT）、`/api/rooms/{roomId}/client-package`（GET/POST/DELETE）与 `/api/rooms/{roomId}/client-package/file`；`RoomDetailDto` 去掉 `pluginCount`

已交付：

- `V3__room_content_and_client_package.sql`：`DROP TABLE room_plugin / plugin_version / plugin`，新建 `room_content`（主键 `(room_id, name)`，字段 `type` / `size_bytes` / `enabled` / `reported_at`）与 `client_package`（主键 `room_id`，字段 `file_name` / `size_bytes` / `uploaded_at`）
- 删除 `library` 包全部类（`Plugin` / `PluginVersion` / `RoomPlugin` / `LibraryService` / `LibraryController` / 旧 `RoomContentService`）与 `LibraryFlowTest`；`RoomService` 去掉 `RoomPluginRepository` 依赖
- 新增 `content` 包：
  - `ContentType`：只剩 `PLUGIN` / `MOD`，存库用大写名、对外 JSON 用小写 slug（`@JsonValue` / `@JsonCreator`）
  - `RoomContentService`：`list`（登录可读，不校验归属）+ `report`（`requireOwned` 后**先删后写**整表替换）
  - `ClientPackageService`：`meta` / `upload` / `delete` / `load`；文件落 `<roomId>.zip` 形态的固定路径，`sanitizeFileName` 剥掉路径分隔符与 `..` 防路径穿越，仅接受 `.zip`，上传前 `quotaService.check(CLIENT_PKG_MB, ceilMb(size))`
  - `RoomContentController` / `ClientPackageController`：下载端点返回 `FileSystemResource` + `Content-Disposition: attachment`（文件名按 UTF-8 编码，中文名不乱码）
- `QuotaType` 移除 `PLUGIN` / `CLIENT_MOD` / `SERVER_MOD`；`AuthFlowTest` 配额断言由 5 类改为 2 类
- 错误码复用 1401（超出配额）与 1302（无权操作），未新增码

关键实现决定（后续 agent 注意）：

1. **上报是整表替换，不是增量合并**：本地目录是唯一事实来源，`report` 必须 `deleteByRoomId` 后再 `saveAll`，否则本地删掉的文件会在平台侧留下幽灵记录
2. `room_content` 的键是**文件名**（`name`），与本地展示名一致；`size_bytes` 由客户端上报，服务端不校验真实性（与快照同一口径）
3. 客户端压缩包**只存一份**（一房间一条，重传即替换），不保留历史版本；文件名必须过 `sanitizeFileName`，**不要**直接把 `MultipartFile.getOriginalFilename()` 拼进路径
4. 下载端点只要求登录，不要求房主——这是玩家侧的正常用法

与计划的偏差：

- 原 B2 的「插件 / Mod 目录与版本 + 安装关联 + 三条数量配额线」整套设计作废，改为本地文件清单镜像；`V2__seed_library.sql` 的种子数据随之失去消费方（表已删，脚本保留但不再生效）
- `CLIENT_PKG_MB` 从「只在 `/api/me` 做 UI 提示」升级为**真实服务端校验点**（上传接口），前端保留同口径的本地预校验以给出更快的提示

### B4 商业化与加固（已完成，2026-09-30）

新增 2 个端点（`/api/commerce/vip`、`/api/commerce/shop`），`mvn verify` 后契约共 23 条路径 / 31 个操作（`lajixia-server/openapi.json`）。测试新增 `CommerceFlowTest` 4 条 + `RateLimitFlowTest` 1 条，与 B0/B1/B2/B3 合计 **18 条全绿**。

已交付：

- **SMTP 邮件服务**：`MailProperties`（`ljx.mail.*`）+ `MailConfig`（手工装配 `JavaMailSenderImpl`，465 隐式 SSL / 587 STARTTLS 可切）+ `SmtpMailSender`（`enabled=true` 生效）；`LogMailSender` 改为 `havingValue="false", matchIfMissing=true`，作开发期兜底。**QQ 邮箱用授权码当密码**（凭据走环境变量，见 §6.4 与「B4 修订」）
- **V4 迁移** `V4__commerce_and_audit.sql`：新建 `vip_plan`（6 档）、`shop_item`（4 条种子）、`audit_log`（两个复合索引）
- **VIP 容量收紧**（⚠️ 已于「B4 修订」废弃）：`VipService` + `vip_plan.capacity_cap`，`RoomService` 创建与修改房间时按 VIP 档位校验容量。**该设计已整体删除**——容量改由服主自设，与 VIP 解耦
- **商业化查询**：`CommerceService` + `CommerceController`，只读展示；`coins` 取 `account.coins`，本期无购买通道
- **IP 限流**：`RateLimiter`（固定窗口，`ConcurrentHashMap` + 每 10 分钟清扫过期窗口）、`RateLimitProperties`（`ljx.rate-limit.*`，含 enabled 开关）、`RateLimitInterceptor`（命中返回 429 + 1601），`WebConfig` 中注册在 `AuthInterceptor` **之前**，覆盖 login / register / refresh / email-code 四个 URI（URI 精确匹配，不做通配）
- **审计日志**：`AuditLog` 实体 + `AuditLogRepository` + `AuditAction`（8 个）+ `AuditService.record`；接入点：注册、登录成功 / 失败、邮箱绑定、房间创建 / 删除、客户端包上传 / 删除。IP 走 `ClientIp.resolve`，无请求上下文时为 null
- **`DAILY_ACTIVE` 接线**：`DailyActiveScheduler`（cron `0 5 0 * * *`）取当日有互动流水的账号，逐账号查「今天是否已领」后经 `ScoreService.award` 补发 +2

关键实现决定（后续 agent 注意）：

1. **每日活跃去重不建表**：直接读 `score_event`（`findActiveAccountIdsSince` 排除 `DAILY_ACTIVE` 自身 + `existsByAccountIdAndTypeAndCreatedAtGreaterThanEqual`），流水本身就是分数与等级的真相来源，另建一张去重表只会带来一致性负担
2. **限流先于认证**：拦截器顺序为「限流 → 认证」，未认证的洪水请求在限流层就被挡下，不付 JWT 解析成本；`OPTIONS` 预检直接放行
3. **限流键是 `URI + "|" + IP`**，按接口独立计数；`trust-forwarded-header` 默认 false，只有部署在可信反代之后才能开，否则客户端可伪造 `X-Forwarded-For` 绕过
4. **审计与业务同事务**：`AuditService.record` 不开 `REQUIRES_NEW`，业务回滚则日志一并回滚（「成功才留痕」）；定时任务无请求上下文时 IP 记 null，不能抛异常
5. **发信失败不落绑定行**：`EmailService.sendCode` 先 `save` 再 `send`，`send` 抛异常时整个事务回滚，避免「验证码没发出去却已写入冷却时间」导致 60 秒内无法重试
6. **容量不走 `QuotaType` 的 base 表也不走 VIP**（B4 修订后）：容量由服主自设，`RoomService.checkCapacity` 只保留 `quotaService.check(PLAYER_CAP, …)` 这一入口，`PLAYER_CAP` 为「不限」故恒通过；入口保留是为了将来定上限时只改 `QuotaType`（见 §6.2）

验证（2026-09-30）：

- `mvn verify`：**18 条测试全绿**（AuthFlowTest 2 + RoomFlowTest 7 + SocialFlowTest 4 + CommerceFlowTest 4 + RateLimitFlowTest 1）
- 真实 SMTP 投递：以 `ljx.mail.enabled=true` + 环境变量注入启动，注册账号后 `POST /api/me/email/code {email: 3408495218@qq.com}` 返回 `code=0`，服务端日志**无 `[MAIL][dev]` 兜底输出**（证明走的是 `SmtpMailSender` 而非 `LogMailSender`）
- 商业化接口抽查：`/api/commerce/vip` → level 0 / 普通用户 / 6 档（`currentLevel, currentName, coins, plans`，无 capacityCap）；`/api/commerce/shop` → 4 件商品
- `openapi.json` 重导出：新增 `/api/commerce/vip`、`/api/commerce/shop`

与计划的偏差：

- 邮箱验证码的**「每日每邮箱 10 条」上限未实现**，滥用防护改由接口层 IP 限流承担（email-code 5 次 / 10 分钟）；如需按邮箱计数再补
- 限流是**进程内单机**实现，多实例部署时各算各的；上量后需换 Redis / Bucket4j
- 审计日志只写库，**未做查询接口**（运营侧暂用 SQL 直查）；`audit_log` 也无清理策略，长期需归档
- 金币（`coins`）只读展示，**无任何产出 / 消耗路径**，数值恒为 0；VIP 也无购买通道，`vip_level` 只能由后台改库
- **VIP 现在不附带任何功能权益**（容量已解耦，见「B4 修订」），`vip_plan` 退化为「档位名 + 价格」的展示目录；若产品要给 VIP 定义权益（如更高客户端包上限），需要新增设计

下一步：后端 B0–B4 全部完成，转前端 P4（收藏 / 足迹页签接入、商城 UI 置灰）与桌面端联调。

### B4 修订（2026-09-30，容量解耦 + 凭据外置）

产品口径确认后同日调整两处：

**1. 房间容量与 VIP 等级解耦，由服主自行设置**

- `RoomService` 去掉 `VipService` 依赖与 `ensureWithinCapacity` 调用，`checkCapacity` 只剩 `quotaService.check(PLAYER_CAP, capacity)`（恒通过）
- 删除 `commerce/VipService.java`（`vipLevelOf` 内联进 `CommerceService`）
- `V5__vip_plan_drop_capacity_cap.sql`：`ALTER TABLE vip_plan DROP COLUMN capacity_cap`，并把描述里已不成立的「房间容量上限 N 人」改为占位（0 档 NULL，1–5 档「VIP 专属权益敬请期待」）
- `VipPlan` 实体去掉 `capacityCap`；`VipPlanRepository` 去掉 `findFirstByLevelLessThanEqualOrderByLevelDesc`；`VipPlanDto` / `VipDto` 去掉 `capacityCap` 字段
- 测试：`CommerceFlowTest.vipTierGatesRoomCapacity` → `roomCapacityIsSetByOwnerAndNotGatedByVip`（容量 999 / 500 均通过，提升 VIP 不改变结果）；`RoomFlowTest` 容量 11 断言 1401 → 容量 500 断言成功

**2. SMTP 凭据外置为环境变量**

- `application.yml` 的 `ljx.mail.username/password` 默认值清空，`enabled` 默认值由 `true` 改为 `false`；凭据只经 `LJX_MAIL_USERNAME` / `LJX_MAIL_PASSWORD` 注入
- `MailConfig.javaMailSender` 增加前置校验：`enabled=true` 而凭据为空时抛 `IllegalStateException`，**启动即失败**而不是等发信时才报错

验证（2026-09-30）：

- `mvn verify`：**18 条测试全绿**（V5 迁移在 H2 上正常执行）
- 三种启动形态实测：① 默认（无环境变量）→ 启动成功、请求验证码时日志出现 `[MAIL][dev] to=… code=…`，**未发真实邮件**；② `LJX_MAIL_ENABLED=true` 但凭据为空 → 启动失败，异常信息点名缺 `LJX_MAIL_USERNAME` / `LJX_MAIL_PASSWORD`；③ `LJX_MAIL_ENABLED=true` + 凭据注入 → 请求验证码返回 `code=0`，日志无 `[MAIL][dev]`、无邮件异常（真实投递成功）
- 容量抽查：未绑邮箱的账号以 `capacity=999` 建房返回 **1306**（需绑邮箱）而非 1401（配额），确认容量已不是拦截项

### B4 补充：QQ 群加群凭据（2026-09-30，配合桌面端 P4）

**背景**：原 `POST /api/rooms/{id}/qq-group` 只收 `{code}`（群号）。但腾讯「一键加群」链接需要 WPA 组件的 **idkey**，纯群号拼不出可用的加群入口，桌面端无法据此生成按钮。

已交付：

- `V6__room_qq_group_idkey.sql`：`ALTER TABLE room ADD COLUMN qq_group_id_key VARCHAR(128) NULL`
  - 实测 idkey 为 63 位，`VARCHAR(128)` 留一倍余量
  - **列名必须是 `qq_group_id_key`**：Spring 的 `CamelCaseToUnderscoresNamingStrategy` 把 `qqGroupIdKey` 映射为 `qq_group_id_key`，写成 `qq_group_idkey` 会映射失败，所有房间接口一起 500（本轮踩过）
- `Room` 实体：新增 `qqGroupIdKey` 字段；`setQqGroupCode(String)` 改为 `setQqGroup(String code, String idKey)` 整体覆盖（传 null 即取消点亮）
- `QqGroupRequest`：`{code, idKey}`，`idKey` 加 `@Size(max=128) @Pattern("[A-Za-z0-9_-]{1,128}")`——该值会被拼进加群链接，必须限定字符集
- `RoomDetailDto` 增加 `qqGroupIdKey`；`qqGroupCode` 语义收窄为「群号（展示用）」
- `CommerceController#vip` 的 `@Operation` 描述去掉已不成立的「当前档位容量上限」

验证（2026-09-30）：`mvn verify` **18 条测试全绿**（V6 迁移在 H2 上正常执行）；`openapi.json` 已重导出

关键实现决定（后续 agent 注意）：

1. 判断房间是否展示加群入口，一律看 `qqGroupIdKey` 是否非空，**不要用 `qqGroupCode`**
2. 新增列时列名必须与实体字段的 CamelCaseToUnderscores 结果一致，写完先跑 `mvn verify` 而不是等联调时才发现
3. `qqGroupIdKey` 的 `@Pattern` 是安全边界（值会进 URL），放宽前先确认不会把任意文本拼进链接

### B4 补充（二）：房间清单可见性字段（2026-09-30，配合桌面端 P5 修订二）

**背景**：房主需要一个开关，决定**进房玩家**能否看到插件 / Mod 的**文件明细**（只给玩家看，房主侧始终完整）。此前桌面端把开关做成了房主本地隐藏，方向错误，本轮回滚重做为房间级字段。

已交付：

- `V7__room_content_list_visibility.sql`：`room` 表新增两个字段
  - `plugin_list_visible TINYINT NOT NULL DEFAULT 1`、`mod_list_visible TINYINT NOT NULL DEFAULT 1`
  - 默认 1（可见），历史房间升级后自动落为可见，无需回填
  - 列名同样必须与 CamelCaseToUnderscores 结果一致（`pluginListVisible` → `plugin_list_visible`），踩坑记录见「B4 补充」第 2 条
- `Room` 实体：新增 `pluginListVisible` / `modListVisible` 布尔字段（初值 `true`）与 `setListVisibility(Boolean pluginVisible, Boolean modVisible)`——**null 表示该字段不变**，与 `UpdateRoomRequest` 的局部更新语义一致
- `RoomDtos`：`UpdateRoomRequest` 增加两个可选 `Boolean`；`RoomDetailDto` 增加两个 `boolean`（详情页与玩家侧共用一个 DTO）
- `RoomService.update`：在既有字段赋值后调用 `room.setListVisibility(request.pluginListVisible(), request.modListVisible())`，其余逻辑不动
- **接口层裁剪**：`GET /api/rooms/{roomId}/contents` 由 `RoomContentService.list(accountId, roomId)` 承担——先 `roomService.findRoom` 取房间，`owner` 为真直接返回全量；非房主则按 `pluginListVisible` / `modListVisible` 过滤掉被隐藏的那一类。`report` 回读改调同一方法（房主，天然全量）。`RoomService.findRoom` 由 private 提为 public 供内容模块复用
- 无新增端点、无新增错误码：可见性走既有 `PUT /api/rooms/{id}`，归属校验复用 `requireOwned`

验证（2026-09-30）：

- `mvn verify`：**18 条测试全绿**；`RoomFlowTest.roomContentMirrorAndClientPackageFlow` 补断言——① 默认对玩家可见；② 房主关闭插件清单后**玩家侧**详情读到的 `pluginListVisible=false`，未被触碰的 `modListVisible` 仍为 `true`；③ **接口层裁剪生效**：非房主读 `/contents` 只剩 Mod 一条，房主读仍是两条；④ 重新打开后非房主恢复两条
- `openapi.json` 已重导出，`RoomDetailDto` / `UpdateRoomRequest` 均含两个新字段，`GET /contents` 的 `@Operation` 描述同步更新

关键实现决定（后续 agent 注意）：

1. 可见性是**房间级**字段，不是账号级也不是客户端本地偏好：同一房间换设备登录读到一致状态，因此必须存库而非走 `tauri-plugin-store`
2. 局部更新语义：`setListVisibility` 只在入参非 null 时写入，**不要**用基本类型 `boolean` 接收——否则前端只想改 Mod 可见性时会把插件可见性一并重置为 false
3. **裁剪在接口层做，不在前端做**：`GET /contents` 对非房主直接不返回被隐藏那一类，否则绕过前端直接调接口就能拿到完整文件名。房主侧必须保持全量——`roomContentsSync.syncRoomContents` 上报前会先读镜像合并另一类，读到的若是裁剪后的结果就会把清单写坏
4. 与 `qq_group_id_key` 同一类教训：新增列先跑 `mvn verify`（H2 上执行迁移）确认 ORM 映射通过，不要等到联调才发现列名不匹配导致房间接口集体 500

### B4 补充（三）：房间在线名单（2026-09-30，配合桌面端 P5 修订五）

**背景**：桌面端已能数出本机在线人数（P5 修订四），但「当前加入」页右侧的**玩家名单**仍是占位。平台看不到房主本机的服务端，名单只能由房主端随心跳一并上报。

已交付：

- `V8__room_player_names.sql`：`room` 表新增 `player_names VARCHAR(1024)`，可空
  - 逗号分隔存储：Minecraft 玩家名只含 `[A-Za-z0-9_]`，与分隔符不冲突，无需单独建表
  - 名单是易失数据（心跳覆盖），空名单落 **NULL** 而非空串，避免 `""` 被 `split` 解析出一个空名
  - 列名仍须与 CamelCaseToUnderscores 结果一致（`playerNames` → `player_names`）
- `Room` 实体：新增 `playerNames` 字段与 `listPlayerNames()`（NULL / 空串 → `List.of()`）；`heartbeat(int players, List<String> names, LocalDateTime at)` 改为接收名单，内部 `applyPlayerNames` 做整体覆盖
- `RoomDtos`：`HeartbeatRequest` 增加 `playerNames`（`@Size(max=64)`，元素 `@Size(max=16)`）；`RoomDetailDto` 增加 `playerNames`
- `RoomService.heartbeat`：广播条件从「人数变化」放宽为「**人数或名单任一变化**」；`detail` 一并返回名单
- 无新增端点、无新增错误码：复用既有 `POST /api/rooms/{id}/heartbeat`

验证（2026-09-30）：

- `mvn verify`：**18 条测试全绿**（AuthFlow 2 / CommerceFlow 4 / RateLimitFlow 1 / RoomFlow 7 / SocialFlow 4）；`RoomFlowTest` 补断言——心跳带 `["Drbiaodi","Steve"]` 后玩家侧详情读到同一名单，第二次心跳覆盖为单名，空名单心跳后名单清空
- `openapi.json` 已重导出，`HeartbeatRequest` / `RoomDetailDto` 均含 `playerNames`

关键实现决定（后续 agent 注意）：

1. **人数与名单都由房主端上报，后端只转发**：任何「后端去数连接数」的方案都不成立
2. 名单**整体覆盖**而不是增量合并：房主端持有权威集合（`BTreeSet`），合并会在玩家掉线漏事件时留下幽灵条目
3. 广播条件必须带上名单比较：人数不变但成员换了（一人走一人进）同样要播 `PLAYERS`，否则前端名单会停在旧快照
4. `HeartbeatRequest` 加字段后，**所有构造点都要同步**（`RoomFlowTest` / `SocialFlowTest` 的心跳辅助方法），漏改会直接编译失败——这是 record 的好处
5. 名单未做去重与排序校验，直接信任房主端；平台侧只做长度上限保护（防超大请求体）

### B4 补充（四）：房间成员与在线状态（2026-09-30，配合桌面端 P5 修订八）

**背景**：房间卡片的「在线 X/Y」此前表示「服务端里真实在线的玩家数」（房主心跳上报），房主开房、本人还没进游戏时显示 0，与「房间人数」的产品语义不符。口径（用户确认）：X = **房间成员数**（进入房间即计数，房主与玩家一致），玩家列表状态列 = 该成员**是否已进入游戏**。设计见 `room-member-design.md`（已实施）。

已交付：

- `V9__room_member.sql`：新表 `room_member`，主键 `(room_id, account_id)` + `joined_at` / `last_seen` + `last_seen` 索引
  - 与 `join_record`（足迹，永久保留）**职责分离**：成员是易失数据，90 秒未续期即清理
- 成员行数**物化写入 `room.players`**：大厅卡片、列表排序（按 players 降序）、广播、前端**全部零改动**
- `Room.heartbeat(...)` 去掉 `players` 参数：心跳只维护在线名单与在线态，**不再影响房间人数**
- `PUT /api/rooms/{id}/presence`（进入 / 续期，幂等）、`DELETE /api/rooms/{id}/presence`（离开，幂等）
  - 房主与玩家**走同一套逻辑**（房主也必须"进入房间"才算成员）
  - 容量按**非房主成员数**校验（房主不占容量），超员返回 1401
- `MemberService`（enter / leave / expireStale / purgeRoom / view）+ `MemberScheduler`（15 秒扫描、90 秒超时）
- `RoomAccessPolicy`：把「上锁 1303 / 在线 1304 / 邮箱门槛 1305」抽成独立组件，**避免 `RoomService ↔ MemberService` 循环依赖**；`RoomService.create` 的 1306 也改走它
- `RoomDetailDto` 增加 `members`（`MemberDto`：accountId / username / vip / level / inGame / owner / joinedAt）；`players` 语义改为成员数；删除房间时一并清理成员行
- `inGame` 判定：成员名 ∈ 房主上报的 `playerNames`（服务端在线名单）

验证（2026-09-30）：

- `mvn verify`：**24 条测试全绿**（新增 `RoomMemberFlowTest` 6 条：心跳不影响人数、房主不占容量、重复进入不重复计数且 `joined_at` 不变、`inGame` 随在线名单翻转、主动离开与超时清理、软删房间返回 1301）
- `RoomFlowTest` 原断言「`players` == 心跳上报人数」按新语义改为 0
- 端到端（18099 端口 + 真实接口）：房主开服 0 人 → 房主进入 1 人 → 玩家进入 2 人 → 玩家进服后其状态翻为「游戏中」→ 大厅卡片 `2/2` → 玩家离开回 1 人
- `openapi.json` 重导出：**24 条路径 / 33 个操作**

关键实现决定（后续 agent 注意）：

1. **`room.players` 现在是「成员数」，不再是「服务端在线人数」**——服务端在线玩家由 `player_names` 承载；任何"players = 服务端在线数"的旧假设都要改（排序、广播、卡片展示都依赖它）
2. 成员名单**只能由客户端上报**：平台既看不到房主的服务端，也看不到玩家的客户端（与房间心跳同源的架构约束）
3. 与足迹 `join_record` 严格分离：足迹永久、成员 90 秒超时；混用会导致幽灵成员占位或足迹被清理
4. 测试类**共享同一个 H2 库**：新增测试的用户名不能与既有测试重名，否则注册直接返回 1101（本轮踩过：`cap_owner` 与 `CommerceFlowTest` 撞名）
5. `joined_at` 的比较要用 `isEqualToIgnoringNanos`：首次返回的是持久化上下文里的纳秒值，再次查询是 H2 的微秒值

### 开发体验：本地持久化数据 + 验证码可见（2026-09-30）

**问题**：默认数据源是 H2 内存库，每次重启后端都要重新注册账号；一键脚本把后端输出重定向到文件后，开发期邮箱验证码（`[MAIL][dev]`）在主控制台看不到。

已交付：

- `application-local.yml`（新增 profile）：H2 **文件库** `jdbc:h2:file:./data/ljx;MODE=MySQL;DATABASE_TO_LOWER=TRUE`，数据落 `lajixia-server/data/ljx.mv.db`
  - **不动默认数据源**：`mvn test` 的用例共享同一内存库做隔离，默认必须保持 `jdbc:h2:mem:ljx`
- `lajixia-server/.gitignore`（新增）：`target/` 与 `data/`——文件库里含账号、密码哈希与房间数据，不得提交
- `dev-start.ps1`：默认 `-Profile local`（经 `SPRING_PROFILES_ACTIVE` 环境变量激活，避免往 `-Dspring-boot.run.arguments` 里再嵌一个含空格的参数）；`-Profile ""` 回内存库；新增 `-NoLogWindow`
- `dev-start.ps1` **自动创建 `data/` 目录**：H2 2.x 的 file 模式不会自动建目录，缺目录时后端启动失败，而现象只是"后端未就绪"（真正的报错只在后端日志里，本轮排查踩过）
- `dev-start.ps1` 默认新开一个实时日志窗口（`Get-Content -Wait` 跟随 `_tmp/dev-backend.log`），专用于看 `[MAIL][dev] ... code=xxxxxx`；该窗口不加入 `$procs`，用户随手关掉不会结束启动脚本

验证（2026-09-30）：

- 起后端 → 注册 `persist_u` → 停止 → 再次启动（**1 秒就绪，复用已有 data**）→ 同一账号登录返回 `code=0`；`data/ljx.mv.db` 已落盘
- 请求邮箱验证码 → 后端日志出现 `[MAIL][dev] to=… code=868969` → 用该码绑定邮箱返回 `code=0`
- `mvn verify` 仍 **24 条全绿**（默认 profile 未受影响）

关键实现决定（后续 agent 注意）：

1. **落盘只能走 profile**：直接改 `application.yml` 的默认数据源会让测试数据落盘，重跑因用户名唯一约束失败
2. H2 file 模式的 URL **不要加 `DB_CLOSE_DELAY=-1`**（那是内存库专用的）
3. 启动脚本必须**先建好 data 目录**，否则只看到"后端未就绪"，排查成本高
4. `data/` 必须忽略：文件库里是真实的账号与密码哈希

### B4 补充（四）修订：并发重复进入导致的 5000 与人数不一致（2026-09-30）

**现象**：房主进入房间后，封面下的「在线 0/N」仍为 0（玩家列表里却已经有房主）；后端日志出现
`DataIntegrityViolationException: Unique index or primary key violation ... room_member(1, 2)`。

**根因（两层）**：

1. **并发重复进入**：客户端在开发模式（React StrictMode）会把挂载的 effect 跑两遍，同一账号的
   `PUT /presence` 会并发到达；`MemberService.enter` 是"先查后插"，两个请求双双查到"不存在"→
   双双插入 → 一个成功、一个**主键冲突**
2. **冲突的代价被放大**：冲突发生在外层事务里 → 整个事务被标记回滚 → 连 `room.players`（成员数物化）
   的更新一起丢掉，于是**成员行存在、人数却是 0**；`GlobalExceptionHandler` 把未处理的持久层异常返回 **5000**

**修复**：

- 新增 `RoomMemberRegistrar`：用**独立的编程式短事务**（`TransactionTemplate` + `REQUIRES_NEW`）插入，
  撞唯一键时返回 false 而不抛异常；**插入与 `room.players` 的校准都收在这个事务内**，调用方事务完全不碰成员行
  - 为什么不用 `@Transactional(REQUIRES_NEW)`：注解方式下即使方法内 catch 住异常，内层事务仍是
    rollback-only，提交时会抛 `UnexpectedRollbackException`（实测踩到）
- `RoomService` / `LobbyService` 的**读路径一律用 `MemberService.countOf` / `countByRoomIds` 实时统计**，
  `room.players` 物化值只服务大厅排序与广播 —— 从结构上消除"列表有人、人数为 0"
- `MemberService.enter` 的续期路径顺带校准一次物化值（历史脏数据自愈，无需手工修数据）
- 广播人数改用实时统计，不再用可能滞后的物化值

**验证（2026-09-30）**：

- 单测：新增 `concurrentEnterIsIdempotent`（8 线程**直接并发调用 service**，走 HTTP 会被客户端串行化而测不出竞争）
  与 `playerCountAlwaysMatchesMemberList`；`mvn test` **26 条全绿**
- **真实 HTTP 并发**（8 线程 + `Barrier` 同时发 `PUT /presence`）：修复前 `[0, 5000×7]` 且 7 条 unhandled exception；
  修复后 **`[0×8]`、unhandled exception 0**，详情 `players == members.size()`，大厅卡片 `1/5`

**排查教训（重要）**：

1. `mvn test` **只编译 classes、不会重打 jar**；用 `java -jar` 验证前必须先 `mvn package`，
   否则跑的是旧产物（本轮因此误判"修复成功"一次）
2. 启动验证进程前先用 `netstat` 确认端口空闲：端口被占时新进程**启动失败**，请求会静默打到残留的旧进程上，
   日志与代码版本对不上（本轮两次踩到）
3. 判断"异常来自哪个版本"最快的办法是看堆栈里的**行号**：行号与新代码不符即说明跑的不是新产物

关键实现决定（后续 agent 注意）：

1. **任何"先查后插"的幂等接口都要考虑并发**：客户端 StrictMode、用户连点、定时器撞车都会双发
2. **唯一键冲突不许污染外层事务**：必须用独立事务（或数据库层 upsert）；在同一事务里 catch 后继续用是无效的
3. **易失数据的物化计数不能作为展示的唯一来源**：读路径必须能独立算出来（`players` 正是这样自愈的）

### 可观测性：房间/成员关键链路日志（2026-10-01）

**背景**：房间人数相关的故障，表现永远是「人数 0 / 人数不变」，但成因可能在 6 环中的任一环。
后端原本对「成员进出」「在线名单变化」没有任何日志，排查只能靠前端复现。

**做法**（`MemberService` / `RoomService`）：**只在状态真变化时记一行**，常规心跳（30 秒一次）不产生日志，避免刷屏。

```
房间 1 成员进入：账号 2（测试服务器）
房间 1 人数变更：0 -> 1（测试服务器）
房间 1 服务端在线名单变更：1 人 [Drbiaodi]
房间 1 上线（测试服务器）
房间 1 成员离开：账号 2（测试服务器）
```

**配合前端**：设置弹窗的「诊断」区块展示各环节最后一次成功时间（实时通道 / 成员上报 / 房间心跳 / 大厅列表 / 本机服务端），
详见 `desktop-plan.md` P5 修订（十一）。

**约定**：后续凡新增「状态在客户端上报、平台只转发」的数据（与房间人数同类），
都应在**变化点**补一行中文日志——这是本类问题唯一低成本的定位手段。

### 管理后台 阶段 A/B 与排查记录（2026-10-01）

**已完成**：阶段 A（后台骨架：独立管理员认证 + 静态页框架）、阶段 B（公告：后台 CRUD + 玩家端底部状态栏轮播）。

- 管理端认证与玩家端**彻底隔离**：独立 `admin_user` 表 + `typ=admin` 令牌 + `AdminAuthInterceptor`，
  `AuthInterceptor` 必须把 `/api/admin/**` **排除**，否则玩家认证会先挡下管理请求（有测试覆盖双向隔离）
- 公告 `GET /api/announcements` **公开免登录**；后台 `/api/admin/announcements` 增删改，写审计
- 玩家端底部状态栏改为轮播（8 秒/条），**无启用公告时回退显示原 QQ 群文案**

**排查记录：后台"点登录没反应"**（值得复用的几条）

1. **不要只看 HTTP 200**：本项目用统一响应包装，业务失败也是 200。判断成败要看 **`body.code`**
2. **审计日志写库、不打日志**：我用 `grep ADMIN_LOGIN` 判断"请求是否到达"是**误判**，
   正确依据是**后端日志里有无对应访问痕迹 + 浏览器网络面板的请求列表**
3. **Spring Boot 的欢迎页机制只作用于根路径**：`/admin/` 不会自动映射到 `admin/index.html`，
   会抛 `NoResourceFoundException`（被全局异常处理器放大成 500），需显式 `forward`
4. **静态资源必须带版本号**：否则改完 JS 浏览器仍用旧缓存，表现为"改了没生效"；
   登录页现在直接显示**页面版本号**与**最近 8 步执行轨迹**，没有控制台也能自查
5. **`python - <<'EOF'` 里写 JS 字符串要避开反斜杠转义**：`"
"` 这类写法被吞成真实换行，
   导致 JS 字符串跨行语法错误——**同类问题犯了两次**，改用 `String.fromCharCode(10)` 这类零转义写法
6. **换控件/改片段时，替换片段的起点要包含开标签**，否则会出现 `<select list=…>` 这种非法组合或字段重复

### 管理后台 阶段 D：CDK 兑换（2026-10-01）

**接口**：后台 `/api/admin/cdk`（批量生成 / 列表 / 启停 / 删除）、玩家 `POST /api/commerce/redeem`。
**错误码**：1502 不存在 / 1503 已停用 / 1504 已过期 / 1505 已被领完 / 1506 本账号已领取过 / 1508 有领取记录不可删除。

**并发与防刷**（这是本阶段的核心）：

1. **行锁**：`CdkRepository.findByCodeForUpdate` 用 `@Lock(PESSIMISTIC_WRITE)`，
   兑换时先锁住该码再校验名额——否则并发请求会同时读到"还有名额"而超领。
   **有专门用例**：10 个账号并发兑同一个单次码，断言**只成功 1 次**。
2. **同账号同码唯一**：`uk_cdk_redeem(cdk_id, account_id)` 兜底，**不限次（totalUses=0）的码也不允许同一账号重复领**。
3. **兑换码字符集去掉了 0/O/1/I**，降低手工输入出错概率。
4. **审计与日志只留首尾**（`ABC****XYZ`），避免完整兑换码进日志。
5. **有领取记录的码不允许删除**（只能停用），保留追溯链。

**校验顺序（实现中修正过一次）**：

正确顺序是 **码可用性（启用/过期）→ 本账号是否已领过(1506) → 是否还有名额(1505)**。
最初写成"先判名额"，导致**同一个单次码被同一个人重复兑换时返回 1505（已被领完）**，
而使用者真正需要知道的是"你已经领过了"（1506）。已调整并补用例。

**其他**：`Account` 新增 `addCoins(int)` —— 这是金币的**第一个产出路径**（此前只有展示，没有产出/消耗）。

**验证**：`mvn verify` **42/42 全绿**（CDK 6 条：加币与同号重复、单次码同人重复、不限次多账号、
并发只成功一次、不存在/停用/领完、已领取不可删）；契约重导出 **34 条路径**；
并用真实接口跑通端到端：生成 2 码 → 玩家兑换 +100 钻石 → 同人再兑 1506 → 已领取不可删 1508。

### 管理后台 阶段 E：数据库面板（2026-10-01）

**接口**：`GET /api/admin/database`（现状）、`POST /api/admin/database/test`（试连）、
`POST /api/admin/database/snippet`（生成部署片段）。后台页「数据库」面板。

**核心决策：不做"保存并生效"**。三条理由写进了代码注释：

1. Spring 的 `DataSource` 启动时固化，热切换会让**连接池 / JPA 元模型 / Flyway** 全部失配；
2. 连接串写错的后果是**下次启动起不来 → 连后台都进不去**，只能手改文件（自锁）；
3. 连接串里的密码属于**部署凭据**，不该落进业务库或仓库。

因此面板只做「**看现状 → 当场试连 → 生成片段**」，把"改配置"留给人：先试通，再粘到部署环境，重启生效。

**两条安全约束用测试钉住**：

- `DatabaseStatusDto` **不得含 password 字段**（用反射检查 record 组件名）；
- 生成片段**不得回显**请求里带来的真实密码（密码一律用环境变量占位符）。

**信息呈现的改进（第二版）**：第一版只平铺原始参数，使用者看不出"现在是什么库、要不要换"。
第二版由**后端算结论**，DTO 增加 `kind`（H2 / MySQL / PostgreSQL）/ `kindHint`（结论与建议）/
`persistent`（是否落盘），前端只做展示：

- **H2 文件库** → "数据保存在 data/ 里，重启不丢，适合本地测试与单机使用；要正式对外开服建议切换到 MySQL"
- **H2 内存库(`:mem:`)** → "**数据只存在内存里，重启后全部丢失！**仅适合临时测试"（红色告警）
- **MySQL** → "正式使用推荐的方式"（绿色）

前端改为三步式：**① 当前使用什么数据库（结论卡片 + 两列明细）→ ② 要换成 MySQL？三步就好（建库 / 试连 / 生成片段并重启）→ ③ 表单（字段带标签，"目标：MySQL"明确标注）**。

**验证**：`mvn verify` **47/47 全绿**（本轮新增 `DatabasePanelFlowTest` 4 条）；契约重导出 **38 条路径**；
端到端实测：`kind=H2 / persistent=true / kindHint` 正确、试连失败 57ms 返回可读原因、片段含占位符且不含真实密码。

**注**：`persistent` 的断言写成"与 JDBC URL 一致性"而非固定值——测试环境用 `jdbc:h2:mem:ljx`
（内存库，persistent=false），本地运行用文件库（true），固定值会让用例在两种环境之一必失败。

### 商城完善 第一阶段：数据基础（2026-10-01）

**需求**：商城商品由后端发放；默认 4 件（置顶卡 / 铁块VIP / 金块VIP / 钻石VIP）；
VIP 改变房间边框，置顶卡显示左上角角标并让房间排到前列；效果可设过期时间；
VIP 档位附带客户端压缩包上传上限（默认 50 / 75 / 100 MB，后台可改）。

**已完成（后端数据与查询）**：

- **图片接入后端**：7 张原版图复制到 `src/main/resources/static/shop/` 并语义化命名
  （`top-card.png` / `vip-iron|gold|diamond.png` / `border-iron|gold|diamond.png`）。
  放在后端是为了落实"**商品由后端发放**"——前端只认接口给的 URL。
- **V11 迁移**：`shop_item` 加 `effect_kind / icon_url / duration_days / vip_level / enabled` 并写入 4 件真实商品
  （替换原"敬请期待"占位）；`vip_plan` 收敛为 0-3 档并加 `package_max_mb / border_url`；
  `account` 加 `vip_expires_at`；新建 `room_top_card`（置顶卡，房间维度）与 `shop_order`（订单）。
- **实体**：`ShopItem` / `VipPlan` 扩字段；`Account` 加 `vipExpiresAt`、`grantVip`（档位取高、到期顺延）、
  **`deductCoins`（余额不足抛异常）**——注意不能用既有 `addCoins` 的 `Math.max(0, …)`，
  那会把"超扣"静默变成归零，等于白送商品。
- **接口**：`GET /api/commerce/shop` 现在返回 `iconUrl / effectKind / durationDays / vipLevel`（只发上架商品）
  与当前 VIP 状态；`GET /api/commerce/vip` 返回各档位的 `packageMaxMb / borderUrl`。
- **VIP 过期语义**：`effectiveVipLevel()` **读时判断**——档位 > 0 但到期时间为空或已过期即按普通用户，
  不依赖定时任务清零（避免任务间隔内"已过期却仍享权益"）。

**验证**：`mvn verify` 级别 47/47 全绿；V11 迁移在真实库（H2 文件库与内存库）均执行成功。
改造后的 `CommerceFlowTest` 顺带确认了新语义：测试辅助 `grantVip` 原先只设档位不设到期时间，
新逻辑把它正确判为 0 档，遂改为带 30 天到期时间。

**剩余（下一阶段）**：① `POST /api/commerce/purchase`（事务：扣钻石 → 记订单 → 发效果）；
② 上传限额改读 `vip_plan.package_max_mb`（现为 `QuotaType.CLIENT_PKG_MB` 按玩家等级写死 5-30MB）；
③ 大厅排序：置顶优先 → 再按人数；④ 前端：商品由后端渲染、VIP 房间边框、置顶角标；⑤ 后台商品管理（改价/上下架）。
