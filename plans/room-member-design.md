# 房间成员与在线状态 · 设计文档

> 状态：**已实施**（2026-09-30；进度记录见 `backend-plan.md` §9 B4 补充（四）与 `desktop-plan.md` §8 P5 修订（八））
> 起因：房间卡片与「当前加入」页的人数语义与实际预期不符
> 相关：`lajixia-desktop` 仓库的 `plans/desktop-plan.md` P5 修订（四）（五）、本仓库 `plans/backend-plan.md` B4 补充（三）

## 1 背景与目标

### 1.1 现状（已核实）

| 位置 | 现在的数据来源 |
|---|---|
| 封面下 `在线 X/Y` | `room.players` = **服务端里的在线玩家数**（房主端解析服务端日志得出），`Y` = `capacity` |
| 「当前加入」玩家列表 | 房主上报的服务端在线名单 `playerNames` + 前端本地渲染的"自己"一行 |
| 玩家列表「状态」列 | 纯前端本地状态 `prep`（waiting / joining / ready），**只表示操作者自己** |

问题：房主开了房、人还没进游戏时 `X = 0`，但用户期望此时**房主已在房间里，应显示 1**。

### 1.2 目标语义（已与用户确认）

| 位置 | 新语义 |
|---|---|
| `在线 X/Y` | `X` = **房间成员数**（进入房间即计数，房主与玩家一致），`Y` = `capacity` |
| 玩家列表 | **房间成员**名单（含尚未进入游戏的人） |
| 「状态」列 | 该成员**是否已进入游戏**：名字出现在房主上报的服务端在线名单里 → 「游戏中」，否则 → 「等待」 |

### 1.3 术语

- **成员（member）**：在客户端里"进入房间"的人 —— 玩家在大厅点房间卡片进入「当前加入」页，房主同理；由客户端上报，客户端离开或 90 秒未续期则移除
- **服务端在线（in game）**：该成员的 Minecraft 账号出现在房主上报的 `playerNames` 里
- 二者是**正交**概念：成员可能是「等待」状态（进了房间但没连服务端）

## 2 数据模型

### 2.1 新表 `room_member`（V9 迁移）

```sql
-- V9：房间成员（易失，90 秒超时清理）；与 join_record（足迹，永久）职责分离
CREATE TABLE room_member (
    room_id    BIGINT    NOT NULL,
    account_id BIGINT    NOT NULL,
    joined_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen  TIMESTAMP NOT NULL,
    PRIMARY KEY (room_id, account_id),
    CONSTRAINT fk_room_member_room    FOREIGN KEY (room_id)    REFERENCES room (id),
    CONSTRAINT fk_room_member_account FOREIGN KEY (account_id) REFERENCES account (id)
);

-- 超时清理按 last_seen 扫描
CREATE INDEX idx_room_member_last_seen ON room_member (last_seen);
```

**为什么不复用 `join_record`**：它是足迹（永久保留、用于大厅「足迹」视图），成员是易失数据（90 秒超时删除）。两者生命周期相反，混用会让足迹被清理或被幽灵成员污染。

**主键 `(room_id, account_id)`**：同一账号多设备/多窗口只算 1 个成员。

### 2.2 实体与仓库

- `room/entity/RoomMember.java` + `RoomMemberId.java`（复合主键）
- `room/repository/RoomMemberRepository.java`：`findByIdRoomId`、`countByIdRoomId`、`deleteById`、`deleteStale(lastSeenBefore)`（批量）

## 3 协议

### 3.1 新增端点

| 方法 | 路径 | 语义 |
|---|---|---|
| `PUT` | `/api/rooms/{id}/presence` | **进入房间 / 续期**（幂等 upsert + 刷新 `last_seen`），返回最新成员视图 |
| `DELETE` | `/api/rooms/{id}/presence` | **主动离开**（幂等，不存在也返回成功） |

用 `PUT` 而非 `POST` 的理由：「进入」与「续期」是同一动作，天然幂等；前端用同一个方法做首次登记与周期心跳。

**请求体**：无（身份取自 Bearer token）

**响应**（两个端点同形）：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "members": [
      { "accountId": 1, "username": "Drbiaodi", "vip": 0, "level": 3, "inGame": true,  "owner": true,  "joinedAt": "2026-09-30T22:10:00" },
      { "accountId": 9, "username": "Steve",    "vip": 0, "level": 0, "inGame": false, "owner": false, "joinedAt": "2026-09-30T22:11:30" }
    ],
    "memberCount": 2,
    "capacity": 5
  }
}
```

### 3.2 既有接口的改动

| 接口 / DTO | 改动 |
|---|---|
| `RoomSummaryDto.players` | 语义由「服务端在线数」改为「**成员数**」（大厅卡片 `X/Y` 直接用，前端无需改动） |
| `RoomDetailDto` | 新增 `members`（同上列表）；`players` 同步改为成员数；**保留** `capacity`、`playerNames`（后者仍是 `inGame` 判定的数据源，前端不再直接渲染） |
| `POST /api/rooms/{id}/join` | 容量校验由「在线人数」改为「**成员数**」；不再隐式写入成员（成员由 `presence` 负责，职责单一） |
| `POST /api/rooms/{id}/heartbeat` | **不变**（房主上报服务端人数/名单/地址），仅其广播触发条件增加「成员数或 inGame 集合变化」 |
| `DELETE /api/rooms/{id}/presence` | 房间离线时也允许调用（玩家仍能退出本地房间列表） |

### 3.3 状态计算

```
成员.status = (成员.username ∈ room.playerNames) ? "游戏中" : "等待"
```

> **已知限制**：`playerNames` 是 Minecraft 玩家名，成员是平台账号名，此处按「同名」匹配（离线模式服务器 + 平台用户名一致的既有惯例）。名字不一致时该成员会一直显示「等待」，不影响人数统计。

## 4 配置样例

无需新增配置项。复用既有超时口径，仅在代码中定义常量：

```java
// MemberScheduler
private static final Duration MEMBER_TIMEOUT = Duration.ofSeconds(90);  // 与房间心跳超时一致
@Scheduled(fixedDelay = 15_000L, initialDelay = 15_000L)
```

前端上报周期沿用既有 `useRoomHeartbeat` 的 30 秒（远小于 90 秒超时，允许两次丢包）。

## 5 复用点

| 复用对象 | 用途 |
|---|---|
| `RoomHeartbeatScheduler` + `expireStale` | 超时清理的**同构实现**：批量查出待删 → 批量删 → 发事件 |
| `LobbyEvent` / `LobbyBroadcaster` | 成员进出广播 `PLAYERS`（事务提交后推送，前端大厅卡片随之刷新） |
| `useRoomHeartbeat.ts` | 前端周期上报的**写法模板**（`setInterval` + 事件触发立即补报） |
| `AuditService.record` | 是否需要审计？（**建议不加**：成员进出是高频易失动作，与"关键操作"口径不符） |
| `QuotaService.check` | 容量校验入口（现为「不限」，保留入口便于将来定上限） |

## 6 事件流程

### 6.1 进入房间

```
玩家在大厅点房间卡片
  → App.tsx openRoom() 切到「当前加入」页
  → JoinPage 挂载：PUT /api/rooms/{id}/presence
      · 校验：房间存在(1301) / 已登录(1104) / 上锁(1303，仅非房主) / 在线(1304，仅非房主) / 容量(1401)
      · upsert 一行（已存在则只刷 last_seen，joined_at 不变）
      · 广播 PLAYERS（成员数变化）
  → 返回成员列表 → 渲染玩家列表（自己「等待」）
```
房主路径相同：房主从大厅点自己房间，或从「我的游戏」点「启动游戏」进入「当前加入」页 → 同样登记为成员。

### 6.2 续期（心跳）

```
JoinPage 挂载期间：每 30 秒 PUT /presence
  · 刷新 last_seen
  · 返回最新成员列表（顺带完成玩家列表刷新，无需额外请求）
```

### 6.3 离开

```
点「退出房间」 → DELETE /presence → 广播 PLAYERS → App.tsx 清空 selectedRoomId
窗口关闭（beforeunload / Tauri close）→ 尽力 DELETE，失败由 90 秒超时兜底
```

### 6.4 超时清理

```
MemberScheduler 每 15 秒
  → 找 last_seen < now-90s 的行（客户端崩溃 / 断网 / 强杀）
  → 批量删除 → 按房间聚合广播 PLAYERS
```

### 6.5 状态变化

```
房主心跳上报 playerNames（含某人）→ 后端重算 inGame 集合
  → 若「成员数」或「inGame 集合」变化 → 广播 PLAYERS
  → 前端详情页 15 秒轮询 / 大厅 30 秒轮询 → 状态列由「等待」变「游戏中」
```

## 7 边界与失败处理

| 场景 | 处理 |
|---|---|
| 客户端崩溃 / 断网 / 强杀 | 不依赖关闭事件，`last_seen` 90 秒超时清理（与房间离线同一口径） |
| 同一账号多设备 | 主键去重，只算 1 个成员；任一端续期即保活 |
| 房主自己进入房间 | 与玩家走同一套逻辑（成员表写一行），**不特殊化**——符合"房主进入房间后才算 1 个成员" |
| 房主不开客户端、房间离线 | 成员是否清空？**建议保留**（玩家还在房间里等房主），仅靠 90 秒超时自然淘汰；房间离线期间大厅卡片显示"离线"（沿用现状） |
| 成员数已达 `capacity` | `PUT /presence` 返回 **1401**；**房主不受容量限制**（自己建的房必须能进） |
| 房间被删除（软删除） | `presence` 返回 1301；成员行随房间清理（`DELETE /api/rooms/{id}` 时一并删除成员行） |
| 成员退出服务端但仍在房间页 | 状态自动回「等待」（`playerNames` 里消失），人数不变 |
| 平台用户名 ≠ MC 玩家名 | 该成员恒为「等待」（已知限制，见 §3.3） |
| 并发进入 | 主键冲突按 upsert 处理（先查后插同事务）；容量校验与插入放在同一事务，避免超员 |
| 房间在线名单为空（刚开服） | 所有成员显示「等待」——正确语义 |

## 8 改动文件清单

### 后端（`lajixia-server`）

| 文件 | 改动 |
|---|---|
| `src/main/resources/db/migration/V9__room_member.sql` | **新增**（§2.1） |
| `room/entity/RoomMember.java`、`RoomMemberId.java` | **新增** |
| `room/repository/RoomMemberRepository.java` | **新增** |
| `room/MemberService.java` | **新增**：enter / renew / leave / expireStale / view（成员 + inGame） |
| `room/MemberScheduler.java` | **新增**：15 秒扫描、90 秒超时 |
| `room/RoomController.java` | 新增 `PUT` / `DELETE /api/rooms/{id}/presence` |
| `room/dto/RoomDtos.java` | 新增 `MemberDto` / `PresenceResponse`；`RoomDetailDto` 加 `members`；`players` 语义改成员数 |
| `room/RoomService.java` | `join` 容量校验改成员数；`detail` / `summary` 装配成员数；删除房间时清理成员行 |
| `lobby/LobbyService.java` | 列表人数取成员数 |
| `test/.../RoomMemberFlowTest.java` | **新增**测试（见 §9） |

### 前端（`lajixia-desktop`）

| 文件 | 改动 |
|---|---|
| `shared/api.ts` | 新增 `enterRoom` / `leaveRoom` 与 `MemberInfo`、`PresencePayload` 类型 |
| `shared/useRoomPresence.ts` | **新增** hook：挂载即进入、30 秒续期、卸载/退出时离开（写法参考 `useRoomHeartbeat`） |
| `features/join/JoinPage.tsx` | 接入 hook；玩家列表改渲染 `detail.members` + `inGame` 状态；「退出房间」先调 `leaveRoom` 再回调 |
| `features/join/JoinPage.tsx` | 「状态」列：`inGame ? "游戏中" : "等待"`（自己的 `prep` 状态保留给底部按钮用） |

**不改动**：`RoomCard` / 大厅列表（`players` 语义已在大厅侧自动生效）、Rust 侧（零改动）、`openapi.json` 需重导出。

## 9 验证计划

| 层次 | 内容 |
|---|---|
| 后端 `mvn verify` | 新增 `RoomMemberFlowTest`：① 进入即算 1（房主）② 第二人进入算 2 ③ 重复进入不重复计数且 `joined_at` 不变 ④ 主动离开后计数减少 ⑤ 伪造 `last_seen` 过期后调度清理 ⑥ 满员返回 1401、房主例外 ⑦ `inGame` 由 `playerNames` 驱动（心跳带名后变「游戏中」）⑧ 玩家侧读到成员列表 |
| 前端 | `npx tsc --noEmit`；`npm run build` |
| Rust | 无改动，跑一次 `cargo test` 确认未受影响 |
| 端到端 | 起后端 → 模拟两个账号进入同一房间 → 用协议客户端把其中一个"送进"服务端 → 校验 `X/Y` 与状态列（我可自动执行并给证据） |

## 10 待确认

1. **房主是否占容量**：建议「房主不受容量限制」，其余成员按 `capacity` 卡
2. **房间离线时是否清空成员**：建议保留（靠 90 秒超时自然淘汰）
3. **成员名单是否对未登录/非成员可见**：建议「登录即可见」（与房间详情口径一致，`playerNames` 目前也是这样）
4. **是否要审计成员进出**：建议不审计（高频易失，不符合"关键操作"口径）
