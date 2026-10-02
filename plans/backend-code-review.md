# 后端代码审查报告（2026-10-01）

> 审查范围：`lajixia-server` 全部主代码（67 个 HTTP 端点、13 个迁移、64 个测试）。
> 结论按严重程度分级：**P0 必须修 / P1 上线前修 / P2 建议修 / P3 可选**。

---

## 一、P0（严重，会导致数据错乱）

### P0-1 存在**两套并行的等级系统**，互相覆盖 `account.level` / `account.score`

| | 旧系统 | 新系统（本次刚加，按需求做的） |
|---|---|---|
| 入口 | `ScoreService.award()` | `LevelService.claimDailyLogin()` |
| 阈值来源 | **`LevelTable.THRESHOLDS` 硬编码** `{0,50,200,500,1200,3000}` | **`level_config` 表，后台可配** `{100,200,300,500,800}` |
| `score` 语义 | **累计总分**（只增不减） | **当前等级内的进度**（升级时扣掉阈值） |
| 写库方式 | `account.applyScore(newScore, newLevel)` —— 直接覆盖 | `account.addScore(delta)` + `account.setLevel(level)` |
| 加分来源 | 建房 +10、被加入 +1、被收藏 +3、**每日活跃 +2** | **每日登录 +1（可配）** |
| 触发点 | 建房 / 被加入 / 被收藏 / 每日活跃调度 | 登录 |

**为什么是 P0**：两个系统写的是**同一份数据**，而且**语义还相反**。

具体事故推演：

```
玩家今天登录         → LevelService：score = 1（级内进度），level = 0
玩家随后建了个房     → ScoreService.award(ROOM_CREATED)：
                        newScore = account.score(1) + 10 = 11
                        newLevel = LevelTable.levelFor(11) = 0
                        account.applyScore(11, 0)      ← 把 score 改成 11（累计语义）、level 打回 0
```

→ 等级与经验在两种语义之间反复横跳，**后台把阈值配成什么都不作数**（`LevelTable` 永远赢一半）。
另外「每日登录 +1」与「每日活跃 +2」**可能同一天各发一次**，属于重复发放。

**建议修法**（保留需求要求的那一套）：

1. **以 `LevelService` + `level_config` 为唯一真源**：删掉 `LevelTable`，`ScoreService.award()` 改为调用 `LevelService` 的"加分并结算"逻辑（同样的扣经验制、同样的配置表）。
2. **`ScoreEventType.delta()` 保留**（建房/被收藏/被加入各加多少经验），但**这些数值也应该进配置**（可以放到 `app_config`，键如 `exp_room_created`），否则又变成"一半可配一半硬编码"。
3. **`score_event` 流水表继续保留**（它是加分的历史凭据），但**不再由它推算等级**。
4. **统一"每日"语义**：`DailyActiveScheduler`（每日活跃）与 `LevelService`（每日登录）二者**只留一个**，或明确区分（例如"登录 +1、当日有互动再 +2"）并写进文档。
5. **`account.applyScore` 收敛掉**，只留 `addScore` / `setLevel`，避免出现第三种写入口。

**回归风险**：`ScoreService` 有 4 个调用点（建房、被加入、被收藏、每日活跃调度），改动要一起测。

**实测证据**（真实接口跑出来的，不是推演）：

```
A) 刚注册     : level=0 score=0  距下一级=100
B) 每日登录后 : level=0 score=5  距下一级=100   ← LevelService 按 level_config 加经验（"级内进度"语义）
C) 建房       : code=0
D) 建房之后   : level=0 score=15 距下一级=100   ← ScoreService 按 LevelTable 又 +10（"累计总分"语义）
```

同一个 `score` 字段在两天之内被两种语义各写一次，已经实锤。

---

## 二、P1（上线前必须修）

### P1-1 JWT 密钥有**可用的默认值**，且没有启动校验

```yaml
ljx:
  jwt:
    secret: ${LJX_JWT_SECRET:bGp4LWRldi1qd3Qtc2VjcmV0LWtleS1kby1ub3QtdXNlLWluLXByb2R1Y3Rpb24tMjAyNg==}
```

配置注释已写明「生产必须覆盖」，但**代码里没有任何校验**：忘记配 `LJX_JWT_SECRET` 时后端照常启动，
而这把密钥已经写在仓库里 → **任何人都能用它伪造任意玩家的 access token**（管理员令牌若没单独配 `LJX_ADMIN_JWT_SECRET`，会回退到同一把）。

**建议**：加一个启动检查 —— 当 `ljx.jwt.secret` 仍是默认值、且生效 profile 不含 `local`/`dev` 时**直接启动失败**并给出明确提示。
（管理员 JWT 同理。）

### P1-2 限流只覆盖了登录与邮箱验证码

```java
registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/api/auth/**", "/api/me/email/code");
```

**建房、购买、CDK 兑换、客户端包上传、社交（收藏/点赞）、心跳**等写接口**没有任何限流**。
被刷的后果：批量建房占库、CDK 兑换被暴力枚举（码长 16 位、字符集 31 种，枚举难度大，但**接口本身零成本**）、上传接口反复占用磁盘 IO。

**建议**：把限流按"每账号 + 每 IP"扩展到所有写接口（至少：建房、CDK 兑换、购买、上传）。

---

## 三、P2（建议修）

| # | 问题 | 影响 | 建议 |
|---|---|---|---|
| P2-1 | **`LogMailSender` 默认启用**（`ljx.mail.enabled` 缺省即 true） | 生产若忘记配真实邮件，**验证码会打进应用日志** | 生产 profile 里显式 `ljx.mail.enabled: true`；或把缺省改成"生产不允许" |
| P2-2 | **`storage.dir` 默认落到系统临时目录** | Linux 上重启**丢光所有客户端包** | 已在本地脚本里修（指向 `data/storage`）；生产务必设 `LJX_STORAGE_DIR`（后台「存储」面板现在会红字告警） |
| P2-3 | **默认 datasource 是 H2 内存库** | 忘了选 profile 就用内存库，重启丢全部业务数据 | 有日志提示；建议生产 profile 显式指向 MySQL，并把"内存库 + 非 local profile"也纳入启动告警 |
| P2-4 | **配额上限有两个来源**：`QuotaType.BASE_BY_LEVEL`（硬编码 5/10/15/20/25/30）与 `level_config.upload_mb` | 实际以配置为准，但 `quota` 表里仍会写入硬编码的 base，排查时容易误判 | 让 `QuotaType` 只保留"不限"与"类型"语义，删掉按等级的表；或在注释里明确"仅作为兜底" |
| P2-5 | **`AdminStorageService` 用 `roomRepository.findAll()`** 统计孤儿包 | 房间数很大时一次性全表加载 | 改成 `roomRepository.count()` + 只查目录里的 `{roomId}.zip` 做存在性判断（`existsById`），或分页 |
| P2-6 | **`shop_order` 的 `item_kind` 与 `effect_kind` 两个字段语义重叠** | 订单表里同时存了"订单类型"与"效果类型"，VIP 订单两者都是 `VIP`，道具订单是 `SHOP_ITEM` + `TOP_CARD` | 明确保留一个（建议只留 `effect_kind`），避免以后新人误用 |

---

## 四、P3（可选）

| # | 问题 | 说明 |
|---|---|---|
| P3-1 | 4 个 `@RequestBody` 没加 `@Valid` | `CdkToggleRequest`、`EffectExpiryRequest`×2、`LogoutRequest`。目前不影响（字段本身无约束），但 `EffectExpiryRequest.expiresAt` 是**用户可传的任意时间**，建议加范围校验（如不能超过 10 年后），防止误传一个离谱的时间把效果"永久化" |
| P3-2 | `cb.like(name, "%" + q + "%")` 未转义 `%` / `_` | **无 SQL 注入风险**（Criteria 参数化），只是搜索 `%` 会匹配全部；如需精确可 `escape` |
| P3-3 | 用户搜索无长度上限 | `keyword` 未限制长度，超长关键字会让 `like` 变慢 |
| P3-4 | `LevelTable` 若在 P0 修复后保留，会成为死代码 | 建议直接删除 |
| P3-5 | **集成测试共享同一个 H2 库，会互相污染全局配置** | 所有 `@SpringBootTest` 用同一个内存库，而「等级配置」「每日经验」是**全局单行数据**：`LevelFlowTest` 会临时改 `daily_login_exp` 再复原，但实测发现跑完一轮后该值停在了 **5**（迁移初值是 1）。目前没让用例失败（断言只校验"为正"），但**结论已经不可复现**。建议：① 测试用独立的库/`@DirtiesContext`；② 或把全局配置的断言改成"相对变化"而不是绝对值；③ 或在 `@AfterAll` 里强制重置 |

---

## 五、做得好的地方（无需改动）

1. **授权模型统一且正确**：`RoomService.requireOwned()` 是房主类操作的**单一校验点**，
   `update / delete / heartbeat / setQqGroup / 客户端包 / 房间内容 / 快照` **全部复用它**，没有发现"忘了校验归属"的端点。
2. **两套认证彻底隔离**：玩家令牌 `typ=access`、管理员 `typ=admin`；`/api/admin/**` 同时从玩家认证里排除，
   有测试同时覆盖正反两个方向（玩家令牌打管理接口被拒、管理令牌打玩家接口被拒）。
3. **幂等与并发处理到位**：`room_member` 的独立事务注册、CDK 兑换的 `FOR UPDATE` 行锁、
   配额校验的行锁，都有针对性测试（含"10 个账号并发兑同一个单次码只成功一次"）。
4. **异常兜底不泄露内部信息**：`GlobalExceptionHandler` 对未知异常只回 `5000 服务器内部错误`，
   堆栈只进日志。
5. **敏感信息保护**：数据库面板的响应 DTO **不含密码字段**（用反射断言钉住）、
   生成配置片段用环境变量占位、CDK 在审计里只记首尾（`ABC****XYZ`）、订单冗余存成交时的名称与价格。
6. **交易类逻辑有不变量保护**：`Account.deductCoins()` 余额不足直接抛异常（没有沿用 `addCoins` 的 `Math.max`，
   避免"超扣静默归零等于白送商品"）。
7. **迁移与实体一致**：13 个迁移覆盖 20 张表，`ddl-auto: none` 下没有出现字段错配
   （此前 `qq_group_id_key`、`player_names` 那类蛇形映射坑已被测试覆盖）。

---

## 六、修复优先级建议

```
第一步（本次审查的 P0）     统一等级系统到 level_config，删掉 LevelTable，收敛 score 写入口
第二步（上线前，P1）        JWT 默认密钥启动校验 + 写接口限流
第三步（部署前，P2-1/2/3）  mail / storage / datasource 三个默认值的生产检查
第四步（有余力，P2-4/5/6、P3）配额来源收敛、存储统计优化、订单字段收敛
```

**P0 的具体改法**已经在第一节给出；它会影响 `ScoreService` 的 4 个调用点，属于需要跑通回归的改动，
建议单独一轮来做，不要和其他改动混在一起。


---

## 七、P0 修复记录（2026-10-01 已完成）

**改动清单**：

| 动作 | 对象 | 说明 |
|---|---|---|
| **删除** | `LevelTable` | 硬编码阈值 `{0,50,200,500,1200,3000}`，与后台可配的 `level_config` 冲突 |
| **删除** | `DailyActiveScheduler` | 「每日活跃 +2」与「每日登录 +1」会在同一天重复发经验；需求只要"每天登录一次获得一次经验" |
| **删除** | `Account.applyScore(score, level)` | 第三种写入口（整体覆盖），只保留 `addScore` / `setLevel` |
| **新增** | `LevelService.grantExp(accountId, exp)` | **唯一加分入口**：加经验 → 按 `level_config` 结算等级 → 刷新按等级分档的配额，三步同事务 |
| **改造** | `ScoreService.award` | 不再自己算等级、不再自己改 score；改为**读 `app_config` 取分值** + 委托 `grantExp` |
| **改造** | `ScoreEventType` | 去掉 `DAILY_ACTIVE`；每一项带 `configKey()`（`app_config` 键）与 `defaultDelta()` 兜底 |
| **改造** | `ScoreEvent` | 构造函数带 `delta`，流水留档"**当时实际加了多少**"，不因后续改配置而变 |
| **迁移** | `V19__unify_level_system.sql` | `app_config` 新增 `exp_room_created=10` / `exp_joined_by_other=1` / `exp_favorite_received=3` |

**修复后的唯一真源**：

```
升级阈值 + 各等级上传上限 : level_config      （后台「等级」面板可改）
各类加分值                : app_config        （后台可改；键名见 ScoreEventType.configKey）
等级结算                  : 只有 LevelService 一处
score 语义                : 当前等级内的进度（升级时扣掉阈值）—— 全项目一致
配额生效值                : 读时取 VIP 档位 > 等级配置 > 兜底
```

**实测对比**（同一流程，修复前后）：

```
修复前：每日登录 +5 → score=5 ；建房 +10 → score=15（但由 LevelTable 语义写入，距下一级仍是 50 那一套）
修复后：每日登录 +5 → score=5 ；建房 +10 → score=15（连续累加，距下一级始终是配置的 100）
```

**测试**：`mvn verify` **63/63 全绿**。

- `SocialFlowTest` 的升级用例改为「**循环加分直到升级**」并断言 `score < 该级阈值` —— **不写死具体配置值**，以后后台改阈值用例依然成立；
- 删除了原「每日活跃只发一次」用例（机制本身已废弃），该行为由 `LevelFlowTest` 的登录场景覆盖；
- 顺手恢复了被测试改脏的全局配置 `daily_login_exp`（跑完一轮后停在 5，初值是 1）—— 与之相关的"集成测试共享 H2 库会污染全局配置"问题见 **P3-5**，尚未修复。


---

## 八、P1 修复 + SMTP 后台配置（2026-10-01 已完成）

### P1-1 JWT 默认密钥 → 新增启动期安全自检

新增 `SecurityStartupCheck`（`ApplicationRunner`），**只在 `prod` / `production` profile 下直接拒绝启动**，
其余环境打醒目警告（这样不会误伤本地开发与集成测试——它们本来就用默认值）：

| 检查项 | 为什么危险 | prod 行为 | 其他环境 |
|---|---|---|---|
| **JWT 仍用内置默认密钥** | 密钥写在仓库里，任何人都能伪造任意玩家令牌 | **拒绝启动** | ⚠️ 警告 |
| **邮件通道未开启** | 验证码会被打进应用日志 | **拒绝启动** | ⚠️ 警告 |
| **存储目录在系统临时目录** | Linux 重启清空，客户端包全丢 | **拒绝启动** | ⚠️ 警告 |
| **数据库是 H2 内存库** | 重启后账号/房间/订单全丢 | **拒绝启动** | ⚠️ 警告 |

后三项正是审查报告里的 **P2-1 / P2-2 / P2-3**，与 P1-1 同属"默认值上线风险"，故一并做掉。

**实测启动日志**：

```
WARN  SecurityStartupCheck : ⚠️ 安全提示：仍在使用**内置的 JWT 默认密钥** …
WARN  SecurityStartupCheck : ⚠️ 安全提示：邮件通道未开启（ljx.mail.enabled=false），邮箱验证码会被打印到应用日志 …
INFO  SecurityStartupCheck : 启动自检完成（当前不是 prod profile，以下问题仅提示不拦截）
```

（本地已把 `LJX_STORAGE_DIR` 指到 `data/storage`、profile=local 用 H2 文件库，所以这两项不再告警。）

### P1-2 限流扩展到业务写接口

原先只挂了 `/api/auth/**` 与 `/api/me/email/code`。现在：

- `RateLimitProperties` 新增三条规则：**`roomWrite`（建房/进房/心跳/上传，60 次/10 分钟）**、
  **`purchase`（30 次/10 分钟）**、**`redeem`（CDK 兑换，20 次/10 分钟，防脚本枚举）**；
- `RateLimitInterceptor.ruleFor(uri, method)` 改为「**精确 + 前缀正则 + 方法**」匹配 ——
  业务接口 URI 带房间号/商品号，只能前缀匹配；**必须带方法**，因为 `POST /api/rooms`（建房）要限流而 `GET /api/rooms`（看列表）不能；
- 拦截器挂到 `/api/**`，**未登记的接口（全部读接口）直接放行**。

**排查记录（一个"假失败"）**：限流测试一开始拿不到 429，加日志发现 `preHandle` 第一行就没执行 ——
根因是 **`src/test/resources/application.properties` 里全局 `ljx.rate-limit.enabled=false`**（测试环境的既有约定），
`RateLimitFlowTest` 是用 `@TestPropertySource(enabled=true)` 单独开启的。按同样方式修正后通过。

### SMTP 后台配置

| 项 | 设计 |
|---|---|
| **配置来源** | **环境变量 > 后台配置** —— 两种部署方式都支持；面板显示"当前生效来源" |
| **立刻生效** | 删掉启动期固化的 `MailConfig`（`JavaMailSender` bean），改为 **`SmtpMailSender` 每次发送时按当前配置构建 `JavaMailSenderImpl`**。原来的写法改了配置必须重启 |
| **通道选择** | 新增 `MailSenderRouter`（`@Primary`）：有可用配置走真实 SMTP，否则回落日志通道。`EmailService` 零改动 |
| **密码保护** | 面板**永不回显密码**，只回 `passwordSet`；保存时**留空表示保持原值**；审计只记"密码已更新/保持不变" |
| **验证手段** | 后台「邮件」面板可**发送测试邮件**（用当前生效配置真发一封），失败原因原样返回 |
| **存储位置** | `app_config`（键 `mail_enabled/mail_host/mail_port/mail_username/mail_password/mail_from/mail_ssl`） |

接口：`GET /api/admin/mail`、`PUT /api/admin/mail`、`POST /api/admin/mail/test`；后台新增「邮件」面板。

### 验证

```
mvn verify → Tests run: 67, Failures: 0, Errors: 0, BUILD SUCCESS
契约重导出 → 54 条路径（新增 /api/admin/mail、/api/admin/mail/test）
```

`RateLimitAndMailFlowTest` 4 条：**建房超过阈值被限流**（阈值用 `@TestPropertySource` 调成 3）、
**读接口不受影响**、**SMTP 配置可读写且密码永不回显**（断言响应里不含密码字段与密码明文）、邮件接口需管理员令牌。

**附带核对**：校验失败时后端返回 `HTTP 400 + {"code":1001,"message":"密码不能为空"}`，
而前端 `rawRequest` **无条件解析响应体**、只认业务码，所以能正确显示这句中文提示 ——
也就是说项目前后端都**不依赖 HTTP 状态码判断成败**（这是一致的好设计）。

### 仍未修复（P2-4/5/6、P3-1/2/3/5）

- `QuotaType` 与 `level_config` 双份上传上限来源；
- `AdminStorageService` 统计孤儿包时 `roomRepository.findAll()` 全表加载；
- 订单表 `item_kind` 与 `effect_kind` 语义重叠；
- 4 个 `@RequestBody` 缺 `@Valid`（其中 `EffectExpiryRequest.expiresAt` 建议加范围校验）；
- `like` 未转义 `%`/`_`；搜索关键字无长度上限；
- **集成测试共享 H2 库会污染全局配置**（本轮又踩了一次：`daily_login_exp` 被改成 5）。


---

## 九、P3-5 修复：集成测试污染全局配置（2026-10-01 已完成）

### 问题

所有 `@SpringBootTest` **共用一个 H2 内存库**（`jdbc:h2:mem:ljx`，上下文还会被缓存），
而 `app_config` / `level_config` 是**全局单行数据**。任何用例改了它们没复原，
**后面所有用例看到的初值就变了，结论不可复现** —— 实际踩到过 `daily_login_exp` 跑完一轮停在 **5**（迁移初值是 1）。

### 修法：`GlobalConfigGuardTest` 守卫基类

| 设计点 | 说明 |
|---|---|
| **时机** | `@BeforeEach` 拍快照、`@AfterEach` **整表还原**；快照还原后清空，所以**每个用例都从干净起点开始**，用例内部的修改不会外溢 |
| **保护范围** | `level_config` / `vip_plan` / `shop_item` / `app_config` —— 共同点是**后台可改的全局配置/目录数据** |
| **还原方式** | 「清空 + 按快照插回」而不是「逐行比对」：用例可能**新增或删除**配置行（例如后台新增等级档） |
| **失败可见** | 还原抛异常时打 `System.err` 并继续，**不静默污染后续用例** |
| **已接入** | `LevelFlowTest`、`AdminShopFlowTest`（这两个类会改等级阈值与 VIP 权益） |

### 反证实验（证明守卫真的在起作用，而不是"恰好没触发"）

```
① 临时注释掉 @AfterEach（停用守卫）
   → GlobalConfigGuardSelfCheckTest.secondCaseSeesCleanValue **失败**：读到的是上一个用例写下的 999
② 恢复 @AfterEach
   → 3/3 全绿：第二个用例读到的是迁移初值 1
```

`GlobalConfigGuardSelfCheckTest`（3 条，`@Order` 固定顺序）就是为此保留的：
**用例 1 故意把配置改成 999，用例 2 断言看到的是 1** —— 守卫哪天被误删，这个用例会立刻失败。

### 一个顺便澄清的误判

之前我用「跑测试前后读 8080 的配置」来判断是否被污染 —— **那个观察是错的**：
测试跑在**独立 JVM 的独立 H2 内存库**里，与本地 8080 的后端库无关。
真正会被污染的是「**一次 `mvn test` 内部、测试类之间**」共享的那个库。
（这也说明：涉及共享状态的判断，要先确认"自己在看哪个库"。）

### 验证

```
mvn verify → Tests run: 70, Failures: 0, Errors: 0, BUILD SUCCESS   （新增 3 条守卫自证）
```

### 审查报告剩余项

| 级别 | 项 |
|---|---|
| P2-4 | `QuotaType` 与 `level_config` 双份上传上限来源 |
| P2-5 | `AdminStorageService` 统计孤儿包时全表加载房间 |
| P2-6 | 订单表 `item_kind` 与 `effect_kind` 语义重叠 |
| P3-1 | 4 个 `@RequestBody` 缺 `@Valid`（`EffectExpiryRequest.expiresAt` 建议加范围校验） |
| P3-2 | `like` 未转义 `%` / `_` |
| P3-3 | 搜索关键字无长度上限 |
| P3-4 | `LevelTable` 已随 P0 修复删除（此项已完成） |


---

## 十、剩余项一次清完（P2-4/5/6、P3-1/2/3）（2026-10-01 已完成）

### P2-4 配额上限只保留一种来源

**问题**：`QuotaType.CLIENT_PKG_MB` 内置了按等级的上限表 `{5,10,15,20,25,30}`，与后台可配的
`level_config.upload_mb` 形成**两个来源** —— 实际以配置为准，但 `quota` 表里仍会被写入那张表的数值。

**改动**：

- `QuotaType`：**删掉按等级的表**，只留"两条来源都取不到时的兜底常量"（并注释清楚真实来源）；
- `QuotaService.applyLevelBase()` **删除**：等级上限已由 `levelLimit()` 在读时决定，
  再往 `quota` 表写一份只会让"到底哪个生效"说不清；`LevelService` 里对它的调用一并移除。

**顺带挖出一个真 bug（已验证并修复）**：`AuthService.toAccountDto()` 给 `/api/me` 拼配额时
**直接读 `quota` 表**，绕过了 `QuotaService.effective()` —— 会出现「**界面显示 30MB、实际只让传 5MB**」
这类不一致。已改为统一走 `QuotaService.effective()`，并加了"改了后台配置后展示值立刻跟随"的断言。

### P2-5 存储统计不再全表加载房间

`AdminStorageService` 原先用 `roomRepository.findAll()` 把所有房间捞出来判断孤儿包。
改为**先扫目录收集文件名里的房间号，再按需 `findAllById` 批量查存在性** —— 目录里的文件数通常远小于房间数。

### P2-6 订单字段收敛

`shop_order` 同时有 `item_kind`（SHOP_ITEM/VIP）与 `effect_kind`（TOP_CARD/VIP），表达的是同一件事的两半。
**V20 迁移删除 `item_kind`**，只留 `effect_kind`：

- `effect_kind = 'TOP_CARD'` → 道具订单，`item_id` 指向 `shop_item`；
- `effect_kind = 'VIP'` → VIP 订单，`item_id` 为 `NULL`（VIP 来自 `vip_plan`）。

也就是"订单来源"可由 `item_id` 是否为空推断，不必再存一列。

### P3-1 补齐 `@Valid` + 到期时间范围校验

- `CdkToggleRequest`、`EffectExpiryRequest`（2 处）、`LogoutRequest` 补上 `@Valid`；
- **新增到期时间上限（最多 10 年）**：防止误传一个离谱的时间把效果"永久化"。
  放在 Service 而不是用 `@Future` 之类的注解 —— 后者会连"合法缩短有效期"一起拒掉，
  而且 `null` 在这套语义里表示"立即撤销"。

### P3-2 / P3-3 搜索加固

- **转义 like 通配符**：用户输入里的 `%` 与 `_` 是字面字符，不转义时搜一个 `%` 会命中全部房间
  （Criteria 本身参数化，**没有注入风险**，纯粹是语义问题）；
- **关键字长度上限 50**：超长关键字会让 `like` 扫全表，且没有实际价值。

### 验证

```
mvn verify → Tests run: 74, Failures: 0, Errors: 0, BUILD SUCCESS
```

新增 `ReviewFixesFlowTest` 4 条：
**搜 `%` / `_` 命中 0 条**、**500 字符关键字不报错（被截断）**、
**到期时间设到 20 年后被拒（1001）而 1 年后不因范围被拒**、
**`/api/me` 报的上传上限等于 `level_config` 配置值，且改配置后立刻跟随**。

真实接口实测：

```
全部房间=4 条 | 搜 % 命中=0 条 | 500 字符关键字 code=0
20 年后 → 1001（超范围被拒）| 1 年后 → 1301（房间不存在，说明未被范围拦）
/api/me 报的上传上限=5MB | level_config 配置值=5MB | 一致
```

**至此，代码审查报告里的 P0 / P1 / P2 / P3 全部处理完毕。**


---

## 十一、后台修改密码 + 时长可配置（2026-10-01 已完成）

### ① 管理员登录后修改密码

**接口**：`PUT /api/admin/password`，`{oldPassword, newPassword}`

| 校验 | 说明 |
|---|---|
| 旧密码必须正确 | 用 BCrypt `matches` 校验，失败返回 1102 并**记审计**（含失败原因） |
| 新密码长度 | **8–64 位**（DTO 注解，失败返回 1001）。后台是平台最高权限入口，弱口令风险远高于玩家账号 |
| 新旧不能相同 | 返回 1001 |

**后台界面**：侧边栏底部新增「**修改密码**」，弹窗含**两次确认**，并提示「已登录的其他令牌最长 2 小时后失效」。

> ⚠️ **已签发的 JWT 是无状态的，改密码不会让它立刻失效**（最长 2 小时后自然过期）。
> 单管理员的小平台可以接受；若要立即踢下线，需要引入令牌黑名单或版本号 —— 已在代码注释里写明这个权衡。

### ② 置顶卡与 VIP 的时长可配置

**先说结论**：**时长本来就能改**（实测：后台改成 3 天 → 买置顶卡 → 到期正好是今天+3 天 ✅）。
使用者反馈"不可修改"，是因为**三处"看不到/不好改"**：

| 问题 | 修复 |
|---|---|
| `VipPlanDto` **没有 `durationDays`** | 补上 → 商城能显示「有效期 N 天」 |
| `AccountDto` **没有 `vipExpiresAt`** | 补上 → 买完能看到什么时候到期 |
| 后台编辑是**连续弹 3 个 prompt** | 改成**表格内直接编辑**（价格/时长/上限都是输入框 + 保存按钮），并加了说明文字 |

**顺带修掉两个真 bug**（做"永久"时长时暴露的）：

1. **"永久"（留空）置顶卡根本存不进去**：`room_top_card.expires_at` 是 **NOT NULL**，而"永久"原先用 `null` 表示 → 插入直接报 5000；
2. **"永久" VIP 会被判成过期**：`account.vip_expires_at` 用 `null` 表示"**从未购买**"，
   永久也用 `null` 的话，`effectiveVipLevel()` 会把它算成 0 档。

**统一改法**：**"永久" 不再用 null，而是用远期时间 `9999-12-31T23:59:59`**。
这样三处逻辑自然自洽：

- `TopCardScheduler.findByExpiresAtBefore(now)` → 永久置顶**不会被清理**；
- 大厅排序 `top_expires_at DESC` → 永久置顶**天然排最前**（正是它该在的位置）；
- `vipLevels()` / `effectiveVipLevel()` 的 `isAfter(now)` → 永久 VIP **不会过期**。

**时长口径统一为**：`null` 或 **`<= 0`** 都表示永久（后台「留空」会存成 0）。

### 验证

```
mvn verify → Tests run: 79, Failures: 0, Errors: 0, BUILD SUCCESS   （新增 5 条）
契约重导出 → 55 条路径（新增 /api/admin/password）
npx tsc --noEmit / npm run build → 通过
```

新增 `AdminPasswordAndDurationFlowTest` 5 条：**改密码全流程**（旧密码错 1102 / 太短 1001 / 新旧相同 1001 / 成功后旧密码失效新密码可用）、
**改密码需令牌**、**置顶卡时长来自配置**（改成 3 天 → 到期正好 +3 天）、
**VIP 时长来自配置且可见**（改成 5 天 → `vipExpiresAt` 是 +5 天且商城读得到 `durationDays`）、
**0 表示永久**（到期是远期时间而非 null）。


---

## 十二、VIP 档位收敛 + 「金币」改称「钻石」（2026-10-01 已完成）

### ① 后台 VIP 档位移除 LV0

**问题**：后台「商品 → VIP 档位」里有一行「普通用户 LV0」，它的上传上限（30MB）与
「等级」面板里 `level_config` 的上传上限**表达的是同一件事**，两个地方都能改同一个值，容易混淆。

**改法**：

- 后台 `listPlans()` **过滤掉 LV0** → 列表只剩 LV1~LV3；
- **接口层也拒绝修改 LV0**（返回 1514，提示"LV0 是普通用户占位档位，其上传上限请在「等级」面板里配置"）
  —— UI 没有入口了，但接口不能靠 UI 兜底；
- **玩家端商城仍保留 LV0**：它在那里是「当前档位」的展示位，不能删。

**验证**（实测）：

```
后台档位数 = 3 → [(1, 铁块VIP), (2, 金块VIP), (3, 钻石VIP)]，不含 LV0
直接 PUT /api/admin/shop/vip-plans/0 → 1514 ✓
玩家端档位数 = 4（含 LV0，用于显示"当前"）
普通用户实际上传配额 = 5MB = level_config LV0 的 5MB ✓（确认上限确实由等级配置决定）
```

### ② 「金币」统一改称「钻石」

| 位置 | 改动 |
|---|---|
| 「我的账号」面板 | `label="金币"` → **`label="钻石"`**（值仍是同一个 `coins` 字段，只是不再叫金币） |
| 商城头部注释 | 「标题 + 金币 + 当前档位」→「标题 + **钻石** + 当前档位」 |
| 商城未登录提示 | 「登录后可查看**金币**余额与商城内容」→「**钻石**余额」 |
| 商城底部说明 | 「**金币**可通过 CDK 兑换获取…」→「**钻石**可通过 CDK 兑换获取…」 |
| 后台 CDK 面板说明 | 「兑换成钻石（**金币**）」→「兑换成**钻石**」 |
| `api.ts` 注释 | 三处同步为「钻石余额」 |

**字段名不动**：后端 DTO 里仍叫 `coins`（改字段名会牵动接口契约与数据库列，收益不大）；
本次只统一**面向使用者的称呼**。若要连内部命名一并改，建议单独一轮做。

### 验证

```
mvn verify → Tests run: 79, Failures: 0, Errors: 0, BUILD SUCCESS
npx tsc --noEmit / npm run build → 通过
grep 复查 → 前端与后台静态资源里已无「金币」字样
```


---

## 十三、公告轮播间隔改为后台可配（2026-10-01 已完成）

**问题**：8 秒写死在客户端（`App.tsx` 里 `8_000`），运营想放慢（让玩家读完）或加快都改不了。

**改法**（复用 `app_config` 这套既有的配置机制）：

| 层 | 改动 |
|---|---|
| **存储** | `V21` 迁移新增 `app_config.announcement_rotate_seconds`（默认 **8**） |
| **后端** | `AnnouncementService.rotateSeconds()` / `updateRotateSeconds(int)`；**范围 3–120 秒**（低于 3 秒读不完、高于 120 秒像卡住），越界返回 1001，非法值读取时回落到 8 |
| **玩家端接口** | `GET /api/announcements` 的响应由「数组」改为 **`{ items, rotateSeconds }`** —— 间隔随公告一起下发，客户端不必再写死 |
| **后台接口** | `GET/PUT /api/admin/announcements/rotate-seconds`（改动记审计） |
| **后台界面** | 「公告」面板新增「**轮播间隔（秒，3-120）**」输入框 + 保存，并显示当前值 |
| **客户端** | `App.tsx` 用配置值轮播（`Math.max(3, rotateSeconds) * 1000`），随公告每 5 分钟轮询 / WebSocket 推送一起更新 |

**验证**：

```
mvn verify → Tests run: 80, Failures: 0, Errors: 0, BUILD SUCCESS（新增 1 条）
契约重导出 → 56 条路径
npx tsc --noEmit / npm run build → 通过
实测：默认 8 秒 → 改 20 秒 → 玩家端公开接口立刻读到 rotateSeconds=20
      越界 2 / 200 → 1001 ✅   改回 8 秒 → 玩家端读到 8 ✅
```

**注意**：`/api/announcements` 的响应结构变了（数组 → 对象）。这是**本项目自己的前后端**，已同步修改并在测试中适配；
若将来有第三方调用该公开接口，需要一并调整。


---

## 十七、管理员强制修改密码（2026-10-02）

### 问题

管理员账号由 `LJX_ADMIN_USERNAME` / `LJX_ADMIN_PASSWORD` 在首次启动时创建，初始密码往往是文档里的默认值。
**只要有人拿默认密码登录成功，就能改商品、发 CDK、看数据库配置** —— 后台是平台最高权限入口，这个风险必须堵住。

### 四层实现

| 层 | 改动 |
|---|---|
| **数据** | `V22` 迁移：`admin_user` 加 `must_change_password`（NOT NULL DEFAULT FALSE），**并把存量账号全部置 TRUE** |
| **领域** | `AdminUser`：由环境变量首次创建 → 标记为 true；**本人改密码** → 自动清除；另留 `resetPassword()` 供运维重置（会重新置 true） |
| **接口** | `AdminAuthInterceptor`：标记为 true 时，**除下面两个接口外一律返回 1604**；登录响应与 `/api/admin/me` 都带 `mustChangePassword` |
| **界面** | 登录后若 `mustChangePassword` → 弹**不可取消**的改密码窗口（隐藏"取消"、遮罩点击无效），改完自动重新加载后台 |

### 为什么存量账号也标记为 true

它们都是用同一套环境变量创建的，**无法区分谁已经改过密码**。
宁可让管理员多改一次，也不要留一个可能仍是默认密码的入口。

### 踩到并修正的一个设计漏洞（测试抓出来的）

最初我只放行了 `/api/admin/password`，结果 **`/api/admin/me` 也被拦掉** ——
而前端**正是靠 `/me` 读 `mustChangePassword` 来决定要不要弹强制窗口**。
那样会退化成「每个请求都 1604，但界面上没有任何提示」，管理员完全不知道要做什么。

**修正**：放行集合改为 `{PUT /api/admin/password, GET /api/admin/me}`。
`/me` 是只读接口，放行无安全影响。

### 验证

```
mvn verify → Tests run: 81, Failures: 0, Errors: 0, BUILD SUCCESS（新增 1 条完整流程用例）
契约重导出 → 56 条路径
```

**真实接口实测**（6 步全过）：

```
① 登录                     code=0，mustChangePassword=True
② 改密码前读邮件配置        1604（被强制拦截）
③ 读 /me                   code=0，仍能拿到标记（前端据此弹窗）
④ 改密码                   code=0（放行，否则管理员会被锁死）
⑤ 改完再读邮件配置          code=0（恢复正常）
⑥ /me 标记                 false
```

> 排查过程中的一个坑：用 H2 Shell 直接改库时漏了连接串里的 `MODE=MySQL;DATABASE_TO_LOWER=TRUE`，
> 一直连不上。后来按 `dev-start.ps1` 实际使用的连接串才成功。


---

## 十八、「禁止游客」与「需要邮箱」拆成两个独立开关（2026-10-02）

### 起因

使用者问「创建房间里的『禁止游客』是否实现了」。查证结果：

**已实现**（`RoomAccessPolicy.checkJoinable/checkEnterable`），**但实现方式是错的**：

```java
if (room.isNeedEmail() || room.isNoGuest()) {
    requireVerifiedEmail(accountId, ErrorCode.ROOM_EMAIL_REQUIRED);
}
```

**两个开关走同一个分支、行为完全相同** —— 房主勾哪个都一样，界面上一行注释也坦白了这点：
「禁止游客与需要邮箱当前同义（均要求已绑定邮箱）」。

**根源**：这个功能做在"还没有访客身份概念"的时候，那时"游客"只能近似成"没绑邮箱的人"。

### 修法：用匿名账号标记精确判定

上一轮引入「未登录自动创建访客身份」时，`account.anonymous` 已经能**精确**标识访客，于是两个开关可以彻底分开：

| 开关 | 新语义 | 判定方式 |
|---|---|---|
| **禁止游客** | 只有**注册账号**能进 | `account.anonymous == true`（或未登录）→ **1307** |
| **需要邮箱** | 进房的人**必须已绑邮箱** | 原有的邮箱校验 → **1305** |

**两处准入校验（`checkJoinable` 与 `checkEnterable`）统一走新的 `checkRoomGates()`**，避免"入房"和"进入房间"口径不一致。

### 实测（三个组合 × 两类身份）

| 房间设置 | 访客进入 | 正式账号（未绑邮箱）进入 |
|---|---|---|
| 只勾「禁止游客」 | **1307** | **✅ 放行** ← 新增的区分点 |
| 只勾「需要邮箱」 | 1305 | 1305 |
| 两个都勾 | 1307 | 1305 |

### 配套改动

- 新增错误码 **1307 `ROOM_NO_GUEST`**（"该房间禁止游客进入，请先登录账号"）；
- 建房页给两个开关加了**可见的中文说明**（不依赖鼠标悬停）：
  「禁止游客：只有注册账号能进（未登录的访客会被挡下）；需要邮箱：进房的人必须已绑定邮箱。两者可单独勾选，也可同时勾选。」

### 验证

```
mvn verify → 82/82 全绿（新增 1 条独立性用例，另更新 1 条旧断言）
tsc / npm run build → 通过
```

**测试过程中又踩了一次「共享测试库」的坑**：新用例用了 `gate_owner`，而 `RoomFlowTest` 已经占了同名账号
（所有 `@SpringBootTest` 共用一个 H2 库，用户名必须全局唯一）→ 报 `1101 用户名已存在`。
改名 `social_gate_*` 解决。**这个坑之前记录过，这次是复发提醒：新增测试时用户名务必带类名前缀。**
