# 垃圾侠平台 · 网页管理后台（Web Admin）设计文档

> 状态：**已确认，实施中**（2026-10-01）
> 确认结果：
> - **公告位置**：**底部状态栏右侧**（当前显示「问题反馈，请加官方QQ群：1211651055」的那一格）→ 该格改为**轮播公告**，
>   **无公告时回退显示原来的 QQ 群文案**（不丢信息）；多条按 `sort_order` 循环，**每 8 秒切换一条**
> - **MySQL 配置**：**只做「查看当前 + 测试连接 + 生成配置片段」**，不做运行时改数据源、不写配置文件
> - **商品价格**：**暂缓**（等商城购买通道做完再一起实现）→ **阶段 C 跳过**
> - 其余未答项**按本文建议执行**：公告不做实时推送（打开拉一次 + 5 分钟轻刷）；
>   管理员由 `LJX_ADMIN_*` 环境变量初始化；后台可选 IP 白名单（默认不限制，留空即不限）；
>   CDK 列表支持一键复制该批全部码
> 执行顺序：**A（后台骨架）→ B（公告）→ D（CDK）→ E（数据库配置面板）**
> 关联：`backend-plan.md`（B0–B4）、`desktop-plan.md`（P0–P5）、`room-member-design.md`
> 需求来源：使用者需要配置 **MySQL 连接 / 右下角轮播公告 / 商城 CDK 兑换钻石 / 商品价格**

---

## 1 目标与范围

### 1.1 要做的事

| # | 需求 | 本质 | 优先级 |
|---|---|---|---|
| ① | **MySQL 连接配置** | 让部署者不改代码就能把后端指向自己的 MySQL | 高（**是其他三项的前提**） |
| ② | **轮播公告** | 给玩家端下发可轮播的公告文案 | 中 |
| ③ | **CDK 兑换钻石** | 生成兑换码 → 玩家兑换 → 账号加钻石（**目前金币无任何产出路径**） | 中 |
| ④ | **商品价格** | 编辑 `shop_item` 的价格 | 低（**当前无购买通道，改了也买不了**） |

### 1.2 明确的非目标（本期不做）

- **不做支付闭环**：CDK 是"发码 + 兑换"，不接任何第三方支付
- **不做动态数据源热切换**：MySQL 连接改动**需要重启后端**（见 §4.5 的理由）
- **不做多管理员/权限分级**：只有一个 `ADMIN` 角色
- **不做后台 UI 框架化**：零构建的原生页面，先保证可用（见 §2.1）

### 1.3 ⚠️ 一条必须先讲清的依赖关系

**管理后台的价值依赖「数据持久化」**：

- 后端默认 profile 是 **H2 内存库**（重启即清空）
- 若在这个状态下做后台，**公告/CDK/价格一重启就没了**，后台形同虚设
- 因此推进顺序必须是：**先把数据库落到持久存储**（MySQL，或 `local` profile 的 H2 文件库）→ **再做后台**

> 已具备的基础：`application-mysql.yml` 早已写好（生产 profile），`local` profile 用 H2 文件库也已支持（见 `backend-plan.md`「开发体验」小节）。**所以本设计不引入新的持久化机制，只是让它在后台可配置。**

---

## 2 总体架构

### 2.1 形态：后端内置静态页（零构建）

```
浏览器  ──GET /admin ──────────────►  Spring Boot 内置静态资源
        ──POST /api/admin/auth/login ──►  管理接口（独立 JWT）
        ──GET  /api/admin/** ──────────►  管理接口
玩家端  ──GET  /api/announcements ───►  公开只读接口（无鉴权）
```

- 页面放 `src/main/resources/static/admin/`（`index.html` + `admin.js` + `admin.css`）
- **不引入前端构建链**（不新增 Vite/React 工程）：后台界面是表单 + 表格，原生 JS 足够
- 同源访问：**复用后端端口**，不额外开端口、不配 CORS

> 备选（若后续 UI 变复杂）：单独 Vite 工程构建到 `static/admin/`，本期不做。

### 2.2 认证：独立管理员账号（与玩家账号彻底隔离）

**为什么不用 `account.role` 字段**：玩家账号是公开注册的，一旦角色字段被写错或提权，后果不可控；管理员应该是**私有表 + 私有登录入口 + 私有令牌**。

| 项 | 设计 |
|---|---|
| 账号表 | `admin_user`（独立表，代码里**没有任何注册入口**） |
| 初始账号 | 由环境变量在**首次启动时**创建：`LJX_ADMIN_USERNAME` / `LJX_ADMIN_PASSWORD`；未设置则**不创建**并打一条提示日志（**不内置默认密码**） |
| 密码 | BCrypt（复用 `spring-security-crypto`） |
| 令牌 | **独立 JWT**，`typ=admin`，有效期 **2 小时**（比玩家端更短），**无 refresh**（过期重新登录） |
| 存储位置 | 前端只放**内存**（`sessionStorage`），关闭标签页即失效 |
| 鉴权 | `AdminAuthInterceptor` 拦截 `/api/admin/**`（登录接口除外） |

### 2.3 接口分区

- **管理端**：`/api/admin/**` → 走 `AdminAuthInterceptor`
- **玩家端公开只读**：`/api/announcements` → **不需要登录**（公告是给未登录用户也看的）
- **玩家端写操作**：`POST /api/commerce/redeem`（CDK 兑换）→ 走既有玩家 Bearer 认证

> 既有 `AuthInterceptor` 目前放行 `/api/auth/**`；需要**新增放行 `/api/announcements`**，并确保它**不拦 `/api/admin/**`**（两套认证互不干扰，见 §4 的拦截器顺序）。

### 2.4 审计

后台的**每个写操作**都要留痕：

- 复用既有 `audit_log` 表（`AuditAction` 枚举扩 6 个：`ADMIN_LOGIN` / `ANNOUNCEMENT_*` / `CDK_*` / `SHOP_PRICE_UPDATE` / `CUSTOMER_*`）
- 记录：操作者管理员、动作、目标、时间、IP（复用 `ClientIp.resolve`）
- **只读操作不记**（与既有口径一致）

---

## 3 数据模型

### 3.1 新增表（V10 迁移）

```sql
-- 管理员账号（与玩家 account 表完全隔离；无任何注册入口）
CREATE TABLE admin_user (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(32)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMP    NULL,
    CONSTRAINT uk_admin_user_username UNIQUE (username)
);

-- 公告（玩家端右下角轮播）
CREATE TABLE announcement (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    content    VARCHAR(500) NOT NULL,
    sort_order INT          NOT NULL DEFAULT 0,   -- 越小越靠前
    enabled    TINYINT      NOT NULL DEFAULT 1,   -- 开关，关闭后不下发
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_announcement_enabled_sort ON announcement (enabled, sort_order);

-- CDK 兑换码
CREATE TABLE cdk (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    code        VARCHAR(32)  NOT NULL,
    coins       INT          NOT NULL,            -- 兑换后发放的钻石数
    total_uses  INT          NOT NULL DEFAULT 1,  -- 可兑换次数（0 表示不限次）
    used_uses   INT          NOT NULL DEFAULT 0,
    batch_no    VARCHAR(32)  NULL,                -- 同批生成的标记，便于筛选/导出
    enabled     TINYINT      NOT NULL DEFAULT 1,
    expires_at  TIMESTAMP    NULL,                -- 空=永不过期
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_cdk_code UNIQUE (code)
);
CREATE INDEX idx_cdk_batch ON cdk (batch_no);

-- 兑换记录（防重复 + 可追溯）
CREATE TABLE cdk_redeem (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    cdk_id     BIGINT    NOT NULL,
    account_id BIGINT    NOT NULL,
    coins      INT       NOT NULL,
    redeemed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_cdk_redeem_cdk FOREIGN KEY (cdk_id) REFERENCES cdk (id),
    CONSTRAINT fk_cdk_redeem_account FOREIGN KEY (account_id) REFERENCES account (id),
    -- 同一账号同一码只能兑换一次（不限次的码也不允许同人反复领）
    CONSTRAINT uk_cdk_redeem UNIQUE (cdk_id, account_id)
);
```

**列名必须与 Spring 的 `CamelCaseToUnderscores` 策略一致** —— 这是本项目反复踩过的坑（`qq_group_id_key`、`player_names`），写完立刻跑 `mvn verify`。

### 3.2 既有表的改动

| 表 | 改动 | 说明 |
|---|---|---|
| `shop_item` | **不改结构** | `price_coins` 已存在，后台只是编辑它 |
| `audit_log` | **不改结构** | 新增枚举值即可 |
| `account` | **不改结构** | CDK 发钻石走既有 `coins` 字段 |

> **没有为"数据库配置"建表** —— 理由见 §4.5：那是**部署期配置**，不是业务数据，不该存进业务库（否则"连不上库就读不到改库配置"的死锁）。

---

## 4 协议（接口）

### 4.1 管理员认证

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/admin/auth/login` | `{username, password}` → `{token, username, expiresIn}`；**复用限流**（5 次/10 分钟） |
| `POST` | `/api/admin/auth/logout` | 无状态，前端清 token 即可（接口保留用于审计） |
| `GET` | `/api/admin/me` | 校验令牌 + 返回管理员名（前端启动时探活） |

### 4.2 公告（玩家端 + 后台）

| 方法 | 路径 | 端 | 说明 |
|---|---|---|---|
| `GET` | `/api/announcements` | 玩家 | **公开只读**，返回 `enabled=1` 的列表（按 `sort_order`），字段只有 `id` + `content` |
| `GET` | `/api/admin/announcements` | 后台 | 全量（含已关闭） |
| `POST` | `/api/admin/announcements` | 后台 | 新增 |
| `PUT` | `/api/admin/announcements/{id}` | 后台 | 修改（内容 / 排序 / 开关） |
| `DELETE` | `/api/admin/announcements/{id}` | 后台 | 删除 |

### 4.3 商品价格

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/admin/shop` | 商品列表（含 `id` / 名称 / 当前价 / 分类） |
| `PUT` | `/api/admin/shop/{id}` | 改价 `{priceCoins}`（校验 `>= 0`） |

> 玩家端 `GET /api/commerce/shop` 已是现成的，改价后**玩家端立即生效**（无需重启）。

### 4.4 CDK

| 方法 | 路径 | 端 | 说明 |
|---|---|---|---|
| `POST` | `/api/admin/cdk/batches` | 后台 | 批量生成 `{count, coins, totalUses, expiresAt?}` → 返回该批所有码（同一 `batch_no`） |
| `GET` | `/api/admin/cdk` | 后台 | 分页列表（可按 `batchNo` / `code` / 是否启用筛选），**不返回已兑换者名单**（另接口） |
| `PUT` | `/api/admin/cdk/{id}` | 后台 | 启用 / 停用 |
| `DELETE` | `/api/admin/cdk/{id}` | 后台 | 删除（**已有兑换记录的不允许删**，返回 1502） |
| `POST` | `/api/commerce/redeem` | 玩家 | `{code}` → 校验并发放钻石，返回最新金币数 |

**兑换校验顺序**（每一步都要有明确错误码）：

```
code 非空 → 查码不存在(1502) → 未启用(1503) → 已过期(1504)
        → 次数已用尽(1505) → 该账号已兑换过(1506)
        → 原子占用一次名额(used_uses+1) → 写 cdk_redeem → account.coins += coins
```

**并发安全**：用 `SELECT ... FOR UPDATE`（复用既有 `QuotaRepository` 的行锁写法）锁住 `cdk` 行，避免同一码被并发超领。

### 4.5 MySQL 连接配置 ⚠️ 本节是重点

**技术约束**：Spring Boot 的 `DataSource` 在**应用启动时**由 `spring.datasource.*` 决定，**运行时无法安全替换**（连接池、JPA 元模型、Flyway 全部已初始化）。因此：

**不采用"后台直接改数据源"**，而是让后台做**三件事**：

| 功能 | 说明 |
|---|---|
| **查看当前** | 显示实际生效的 JDBC URL（**密码打码**）、profile、Flyway 版本、连接池状态 |
| **测试连接** | 输入 host/port/db/user/password → 后端用 `DriverManager` **临时新建一条连接**做 `SELECT 1` → 返回成功/失败原因（**不落库、不写任何文件**） |
| **生成配置片段** | 把使用者填的信息渲染成 **`application-mysql.yml` 片段 + 环境变量清单**，供其复制到部署环境 |

**为什么不做直接写入**：

1. **死锁风险**：写错连接串 → 下次启动连不上 → **后台也进不去**，只能手工改文件
2. **凭据安全**：配置里的密码属于**部署凭据**，写进业务库/仓库都违反既有约定（凭据只走环境变量、`.gitignore` 排除）
3. **多实例**：将来多实例部署时，"后台改一个库"并不等于"所有实例都改了"

**因此**：MySQL 连接仍然由**部署者**通过环境变量/外部配置文件设定，后台负责**降低试错成本**（先测通再改）。

> **待确认**（见 §11 问题 3）：如果你希望后台**能直接落盘写配置**，我可以做成 `-Dljx.admin.allow-write-config=true` 才启用的**可选开关**，并附带"写入前备份 + 写入后提示重启 + 启动失败自愈提示"。

---

## 5 配置样例

```yaml
# application.yml（新增片段）
ljx:
  admin:
    # 首个管理员由此创建：首次启动且 admin_user 表为空时生效；不设置则不创建，也不使用任何默认密码
    username: ${LJX_ADMIN_USERNAME:}
    password: ${LJX_ADMIN_PASSWORD:}
    # 管理端 JWT（与玩家端共用 secret 也可以，但独立更清晰）
    jwt-secret: ${LJX_ADMIN_JWT_SECRET:${LJX_JWT_SECRET:}}
    token-ttl: 2h
    # 是否允许后台把数据库配置写入外部文件（默认关闭，见 §4.5）
    allow-write-config: ${LJX_ADMIN_ALLOW_WRITE_CONFIG:false}
  cdk:
    # 单码长度与字符集（去掉了易混淆的 0/O/1/I）
    code-length: 16
    code-charset: "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    # 单次最多生成多少个码
    max-batch: 500
```

---

## 6 复用点

| 复用对象 | 用途 |
|---|---|
| `ApiResponse` / `ErrorCode` / `BizException` | 统一响应与错误码（新增 1502–1506、1602 管理端限流） |
| `RateLimitInterceptor` + `RateLimitProperties` | 管理端登录限流（新增一条 `admin-login` 规则） |
| `AuditService.record` + `AuditAction` | 后台操作审计（扩枚举） |
| `ClientIp.resolve` | 审计/登录日志里的 IP |
| `ScoreService.award` | **不用于 CDK**（那是互动分）；CDK 直接加 `account.coins`，但要**同事务** |
| `QuotaRepository.findForUpdate` 的行锁写法 | CDK 兑换的并发控制 |
| `spring-security-crypto`（BCrypt） | 管理员密码 |
| `jjwt`（`JwtService` 的写法） | 管理端令牌（建议独立 `AdminJwtService`，`typ=admin`） |
| `springdoc-openapi` | 管理接口同样导出到契约 |

---

## 7 事件流程

### 7.1 首次部署（管理员从哪来）

```
后端启动
  → 读 LJX_ADMIN_USERNAME / LJX_ADMIN_PASSWORD
  → admin_user 表为空 且 两者非空 → 创建管理员（BCrypt），日志：管理员已创建：admin
  → 表为空 且 未设置变量 → 日志提示：未创建管理员，请设置 LJX_ADMIN_* 环境变量后重启
  → 表非空 → 跳过（改密码走后台"修改密码"）
```

### 7.2 玩家兑换 CDK

```
玩家在商城弹窗输入码 → POST /api/commerce/redeem
  → 玩家 Bearer 认证（1104）
  → 限流（1601）
  → 事务内：锁 cdk 行 → 各项校验 → used_uses+1 → 写 cdk_redeem → account.coins += coins
  → 返回最新金币数 → 前端商城弹窗立即刷新
```

### 7.3 公告下发

```
管理员新增/修改公告 → 事务提交 → 广播（复用 LobbyEvent 机制？见下）
玩家端商城弹窗/大厅 → GET /api/announcements → 右下角轮播
```

> **公告是否要实时推送**：本期建议**不推送**，玩家端**打开时拉一次 + 每 5 分钟轻刷**即可（公告变更频率极低）。
> 若要求"改完立刻在所有客户端生效"，可后续复用现有 `/topic/lobby` 通道加一个 `ANNOUNCEMENT` 事件类型（**本期不做，避免为低频数据引入复杂度**）。

---

## 8 边界与失败处理

| 场景 | 处理 |
|---|---|
| 管理员密码错误 | 1102（复用），限流 5 次/10 分钟；**不区分"用户不存在/密码错"** |
| 管理令牌过期 | 1103，前端跳登录页（**无 refresh，重新登录**） |
| 未设置 `LJX_ADMIN_*` | 不创建管理员，**不内置默认密码**；后台登录页提示"未配置管理员" |
| 同一 CDK 并发兑换 | `SELECT FOR UPDATE` 锁行；同账号重复兑换由 `uk_cdk_redeem` 兜底 |
| CDK 不限次（`total_uses=0`） | **同一账号仍只能兑一次**（`uk_cdk_redeem`）——防刷 |
| CDK 已产生兑换记录后删除 | 拒绝，返回 1502（保留追溯）；只能停用 |
| 改商品价格 | 校验 `>= 0`；改完玩家端立即生效 |
| 测试 MySQL 连接 | **超时 5 秒**；失败返回具体原因（驱动类缺失/认证失败/库不存在/网络不可达）**分开提示** |
| 后台写操作并发 | 公告/价格是"后写覆盖"，无需锁；CDK 生成用唯一约束防重码（碰撞则重试） |
| 管理员表为空且变量未配 | 后台不可用，但**不影响玩家端**（拦截器只在 `/api/admin/**` 生效） |

---

## 9 改动文件清单

### 后端

| 文件 | 改动 |
|---|---|
| `db/migration/V10__admin_console.sql` | **新增**：`admin_user` / `announcement` / `cdk` / `cdk_redeem` |
| `admin/`（新包） | `AdminUser` / `AdminUserRepository` / `AdminJwtService` / `AdminAuthInterceptor` / `AdminAuthController` / `AdminBootstrap`（首启创建管理员） |
| `admin/Announcement{Controller,Service,Repository}`、`entity/Announcement` | **新增** |
| `admin/Cdk{Controller,Service,Repository}`、`entity/Cdk`、`CdkRedeem` | **新增** |
| `admin/DatabaseAdminController` | **新增**：查看当前 / 测试连接 / 生成配置片段 |
| `admin/dto/AdminDtos.java` | **新增**：各请求/响应 record |
| `commerce/CommerceController` | 新增 `POST /api/commerce/redeem` |
| `common/api/ErrorCode` | 新增 1502 CDK 不存在 / 1503 已停用 / 1504 已过期 / 1505 次数用尽 / 1506 已兑换过 / 1602 管理端限流 |
| `common/audit/AuditAction` | 新增 6 个动作 |
| `common/config/WebConfig` | 注册 `AdminAuthInterceptor`（仅 `/api/admin/**`）；`AuthInterceptor` 放行 `/api/announcements` |
| `common/ratelimit/RateLimitProperties` + `application.yml` | 新增 `admin-login` 限流规则与 `ljx.admin.*` 配置 |
| `resources/static/admin/{index.html,admin.js,admin.css}` | **新增**：后台页面 |
| `test/AdminConsoleFlowTest.java` | **新增**测试（见 §10 每阶段） |

### 前端（玩家端，只加两处）

| 文件 | 改动 |
|---|---|
| `shared/api.ts` | 新增 `announcements()`、`redeemCdk(code)` |
| `features/mall/MallDialog.tsx` | 加"CDK 兑换"输入框；底部加公告轮播区（**位置待确认，见 §11 问题 1**） |

---

## 10 分阶段执行计划（每阶段独立可验证）

| 阶段 | 内容 | 验证方式 |
|---|---|---|
| **A** | **后台骨架**：V10 迁移、`admin_user`、首启创建、登录/鉴权、静态页框架（登录 + 侧边栏 + 四个空面板） | `mvn verify` 新增 `AdminConsoleFlowTest`：登录成功/失败/令牌过期；未配变量时不创建管理员 |
| **B** | **公告**：后台 CRUD + 玩家端 `GET /api/announcements` + 前端轮播展示 | 后台加一条 → 玩家端刷新可见；关闭后不可见 |
| **C** | **商品价格**：后台列表 + 改价 | 改价后 `GET /api/commerce/shop` 立即变化 |
| **D** | **CDK**：批量生成 / 列表 / 停用 / 删除 + 玩家端兑换 | 生成 10 码 → 兑 1 个 → 金币 +N；同码同号再兑 → 1506；并发 10 次 → 只成功 1 次 |
| **E** | **数据库配置面板**：查看当前 / 测试连接 / 生成片段 | 填错误密码 → 明确错误；填正确 → 连接成功；**不动数据源** |

**每阶段完成后**：`mvn verify`（含新测试）+ `openapi.json` 重导出 + 回写 `backend-plan.md`；涉及玩家端 UI 的阶段另跑 `npx tsc --noEmit` + `npm run build`。

---

## 11 待确认（需要你拍板）

| # | 问题 | 我的建议 |
|---|---|---|
| 1 | **"右下角轮播公告"具体指哪里？** 我能想到的是**玩家端商城弹窗的底部**或**大厅底部状态栏上方**。请给一张截图或说明位置与样式（是否自动轮播、几秒切换、最多几条） | 先做成**商城弹窗底部 + 大厅右下角浮层**二选一，按你的截图定 |
| 2 | **公告是否要"改完立即全端生效"**（走 WebSocket 推送）？ | **不做推送**，打开时拉一次 + 5 分钟轻刷（公告是低频数据） |
| 3 | **MySQL 配置**：后台**只测试+生成片段**（推荐，安全）还是**允许直接写配置文件**（需可选开关 + 备份 + 重启提示）？ | **只测试 + 生成片段**；要写入就加 `LJX_ADMIN_ALLOW_WRITE_CONFIG` 开关 |
| 4 | **商品价格改了也买不了**（当前无购买通道）：要不要**顺带把"用金币购买"做出来**？ | 本期**只改价**；购买通道单独排期（涉及扣币、发货、幂等，是独立一件事） |
| 5 | **管理员账号**：用环境变量初始化（推荐）还是**首次访问后台时引导创建**（类似"安装向导"）？ | **环境变量**（不引入默认密码，也不开放公网可访问的初始化入口） |
| 6 | **后台是否要限制访问来源**（如只允许本机/内网 IP）？ | 建议加一个 `ljx.admin.allowed-ips`（默认空=不限制），生产环境可锁到内网 |
| 7 | **CDK 是否需要"导出为文本/CSV"**（发码给玩家）？ | 建议做（后台列表支持一键复制该批全部码） |

---

## 12 风险与对策

| 风险 | 对策 |
|---|---|
| 后台是**新的公网入口**，可能被爆破 | 独立管理员表 + BCrypt + 登录限流 + 令牌 2 小时 + 可选 IP 白名单（§11-6） |
| 管理接口误开放给玩家 | 拦截器路径隔离（`/api/admin/**`）+ 测试用例覆盖"玩家令牌不能访问管理接口" |
| 数据库配置被改坏导致起不来 | **不做运行时改数据源**（§4.5）；只测试与生成片段 |
| CDK 被脚本刷 | 玩家端限流（复用）+ 同账号同码唯一 + 单码次数上限 + 生成上限 500/批 |
| 后台数据存在易失库（H2 内存） | §1.3：**先落持久化数据库，再做后台** |


---

## 后台界面视觉重做（2026-10-02）

### 诊断：为什么"丑"

翻了一遍旧 `admin.css`，**技术根因很具体**：

```css
--surface2: #5c4f44;
--border:   #5c4f44;   /* ← 和上面完全相同！ */
```

**边框色和背景色是同一个值** —— 边框完全看不见，卡片、表头、输入框、分隔线全部糊成一片，
**层次感归零**。再加上缺圆角、缺阴影、缺悬停/焦点反馈，界面自然显得又平又闷。

### 重做要点

| 维度 | 改动 |
|---|---|
| **层次** | **4 级背景**（页 `#241f19` → 卡片 `#332c24` → 次级块 `#3d352b` → 悬停 `#4a4034`）+ **2 级边框**（`#4a4034` / `#5f5343`）+ 柔和阴影 |
| **对比** | 正文提亮到 `#ece4d9`、次级 `#c4b6a5`、辅助 `#9c8d7d`，三级文字层次明确 |
| **节奏** | 统一 8px 间距栅格、6/8/12px 圆角、11~19px 字号标尺、140ms 缓动 |
| **导航** | 侧边栏渐变底色；**选中项加左侧 3px 品牌色竖条**（不依赖颜色也能分辨） |
| **表格** | 表头 sticky + 小号加粗；**行悬停高亮**；按钮 hover 变品牌色；危险操作红色语义 |
| **表单** | 输入框**聚焦时品牌色边框 + 辉光**；去掉数字框的上下箭头 |
| **细节** | 自定义深色滚动条；登录页径向渐变背景；焦点环只在键盘操作时出现 |

### ⚠️ 验证时抓到一个会导致功能失效的遗漏

我写了一套**选择器差异比对**（用脚本对比新旧 CSS 的选择器集合），结果发现
**新样式漏掉了 `[hidden]` 规则** —— 这是致命的：

```css
.app   { display: grid; }
.modal { display: grid; }
```

`display` 会**覆盖** `[hidden]` 的浏览器默认样式，所以 `el.hidden = true` 会完全失效 ——
**弹窗关不掉、面板切不掉**。**这正是本项目之前踩过的同一个坑**，如果只靠肉眼比对就会被漏掉。

**已补**：
```css
[hidden] { display: none !important; }
```

> 这条经验值得固化：**重写样式时，`[hidden]` 的兜底规则必须显式保留**，不能指望浏览器默认值。

### 验证

```
选择器覆盖率比对：旧 106 个 → 新 120 个，仅 7 个 id/焦点选择器由通用规则覆盖（等价）
线上 CSS（/admin/admin.css?v=22，17138 字符）关键特性复查：
  [hidden] 兜底 / 次级背景 / 强边框 / 侧边栏选中竖条 / 表格悬停 / 焦点辉光  —— 全部存在
后台 7 个面板接口 code=0；资源版本统一升到 v22
```

> 附带修好一个静默失效：`ADMIN_VERSION` 一直是 `"11"` —— 之前几轮我想改成 21 时，
> sed 因为实际值与预期不符而**静默没匹配**，从此版本号再没动过。现已改为 22。
