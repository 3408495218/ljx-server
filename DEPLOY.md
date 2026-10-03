# 垃圾侠后端 · 部署文档

> 适用版本：`lajixia-server 0.1.0`
> 产物：`target/lajixia-server-0.1.0.jar`（可执行 fat jar，约 61 MB，内嵌 Tomcat）

---

## 一、这是什么、和客户端什么关系

```
玩家客户端（Tauri 桌面端）
        │  HTTP + WebSocket
        ▼
   本后端（Spring Boot）  ← 你部署的就是它
        │
        ├── MySQL        业务数据（账号 / 房间 / 订单 / CDK …）
        └── 文件存储      房主上传的客户端压缩包
```

要特别注意一条**架构前提**：

> **Minecraft 服务端跑在房主自己的电脑上**，本平台看不到它。
> 所以房间的「人数」「玩家名单」「是否在线」**全部由房主客户端上报**（心跳 + 成员上报，90 秒无上报视为离线）。
> 后端不玩游戏、不需要开 MC 端口、也不需要很高的配置。

**资源占用参考**：1 核 1G 起步即可（本项目实测在开发机上稳定运行）；
真正的玩家流量（700MB 的游戏资源）**走 Mojang / NeoForge 官方源**，不经过本后端。

---

## 二、前置要求

| 项 | 要求 | 说明 |
|---|---|---|
| **JDK** | **21**（必须） | 运行 jar：`java -jar`；构建 jar：Maven 3.9+ |
| **MySQL** | 8.0+ | 生产**必须**用 MySQL，不要用默认的 H2 内存库（重启即丢数据） |
| **磁盘** | ≥ 20 GB | 客户端压缩包会存在文件存储目录里 |
| **端口** | 8080（可改） | 需要能被玩家客户端访问 |

---

## 三、快速开始（3 步）

### 1. 建库

```sql
CREATE DATABASE lajixia DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'ljx'@'%' IDENTIFIED BY '换成你自己的强密码';
GRANT ALL PRIVILEGES ON lajixia.* TO 'ljx'@'%';
FLUSH PRIVILEGES;
```

> **表结构不用手动建** —— 启动时 Flyway 会自动执行 23 个迁移（`V1` ~ `V23`）。

### 2. 设置环境变量并启动

```bash
export SPRING_PROFILES_ACTIVE=prod

# 数据库
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/lajixia?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
export SPRING_DATASOURCE_USERNAME=ljx
export SPRING_DATASOURCE_PASSWORD='你的数据库密码'

# JWT 密钥（必改，见第四节）
export LJX_JWT_SECRET="$(openssl rand -base64 48)"
export LJX_ADMIN_JWT_SECRET="$(openssl rand -base64 48)"

# 首个管理员（只在"库里还没有管理员"时生效）
export LJX_ADMIN_USERNAME=admin
export LJX_ADMIN_PASSWORD='换成强密码'

# 文件存储目录（必改，默认落在系统临时目录，重启会被清空）
export LJX_STORAGE_DIR=/var/lib/lajixia/storage
mkdir -p "$LJX_STORAGE_DIR"

java -jar lajixia-server-0.1.0.jar
```

启动成功会看到：

```
Started ServerApplication in x.x seconds
```

### 3. 登录后台并**立即改密码**

浏览器打开 `http://<服务器IP>:8080/admin/`，用上一步的管理员账号登录。

> ⚠️ **首次登录会被强制要求修改密码** —— 除「修改密码」外的所有管理接口在改密前都会返回
> `1604 请先修改管理员密码`。这是有意设计：管理员能改商品、发 CDK、看数据库配置，
> 不允许带着初始密码使用。

---

## 四、⚠️ 生产启动自检（这 4 种情况会**拒绝启动**）

后端内置了一个启动自检（`SecurityStartupCheck`）。**在 `prod` / `production` profile 下**，
只要命中以下任意一条就**直接启动失败**并打印原因（其他环境只打警告，方便本地开发）：

| 检查项 | 为什么危险 | 怎么修 |
|---|---|---|
| **JWT 仍用内置默认密钥** | 那把密钥**就写在代码仓库里**，任何人都能用它伪造任意玩家的令牌 | 设置 `LJX_JWT_SECRET` |
| **邮件通道未开启** | 邮箱验证码会被打印到应用日志 | 设 `LJX_MAIL_ENABLED=true` 并配 SMTP，或在后台「邮件」面板里填 |
| **文件存储目录在系统临时目录** | Linux 上重启/清理会**删掉全部客户端压缩包** | 设 `LJX_STORAGE_DIR` 到持久盘 |
| **数据库是内置 H2 内存库** | 一重启账号、房间、订单**全丢** | 配 `SPRING_DATASOURCE_URL` 指向 MySQL |

**这四条是"部署时最容易漏、后果最严重"的坑**，所以做成了硬门槛 —— 报错信息里会直接告诉你缺哪个变量。

---

## 五、环境变量完整清单

### 5.1 必填（生产）

| 变量 | 说明 |
|---|---|
| `SPRING_PROFILES_ACTIVE=prod` | 激活生产 profile（**必须**，否则上面那 4 条自检不会拦截） |
| `SPRING_DATASOURCE_URL` | MySQL 连接串 |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | 数据库凭据 |
| `LJX_JWT_SECRET` | 玩家令牌签名密钥（base64） |
| `LJX_ADMIN_JWT_SECRET` | 管理员令牌签名密钥（**建议与玩家密钥不同**） |
| `LJX_ADMIN_USERNAME` / `LJX_ADMIN_PASSWORD` | 首个管理员（**仅在库里没有管理员时创建**，之后改这里不会覆盖已有密码） |
| `LJX_STORAGE_DIR` | 客户端压缩包存储目录（**必须是持久盘**） |

### 5.2 邮件（可选，也可在后台面板里配）

| 变量 | 默认 | 说明 |
|---|---|---|
| `LJX_MAIL_ENABLED` | `false` | 是否启用真实 SMTP |
| `LJX_MAIL_HOST` | `smtp.qq.com` | SMTP 主机 |
| `LJX_MAIL_PORT` | `465` | 端口（465 用隐式 SSL；587 关掉 SSL 走 STARTTLS） |
| `LJX_MAIL_USERNAME` / `LJX_MAIL_PASSWORD` | 空 | **QQ 邮箱填授权码，不是登录密码** |
| `LJX_MAIL_FROM` | 空 | 发件人，留空取 username |
| `LJX_MAIL_SSL` | `true` | 是否用隐式 SSL |

> **优先级：环境变量 > 后台配置**。两种方式都支持 —— 喜欢用环境变量的（生产推荐，凭据不进库），
> 或不方便改环境变量的（在后台「邮件」面板里填，**改完立刻生效，不需要重启**）。

### 5.3 其他

| 变量 | 默认 | 说明 |
|---|---|---|
| `SERVER_PORT` | `8080` | 监听端口 |
| `LJX_ADMIN_ALLOWED_IPS` | 空（不限制） | **管理员接口 IP 白名单**，逗号分隔。留空=不限制；**设了之后连登录页都只能从白名单 IP 访问**（这是故意的）。启动日志会打印实际生效值 |
| `LJX_SECURITY_TRUST_FORWARDED_HEADER` | `false` | 反向代理后**必须设为 `true`**，否则限流按代理 IP 统计、所有玩家算作同一人 |
| `LJX_MC_MIRROR` | 官方源 | 设为 `bmclapi` 可让**客户端**优先走国内镜像（实测官方源多并发更快，故默认官方） |

---

## 六、反向代理（Nginx）

客户端需要 **WebSocket**（大厅实时推送）与 **文件上传**，两者都要在反代里放行：

```nginx
server {
    listen 80;
    server_name lajixia.example.com;

    # 反向代理的请求体上限。注意：**后端自身也有上限**（spring.servlet.multipart,
    # 默认 40MB，见 application.yml 的 max-file-size/max-request-size），
    # 要放大上传上限必须**两边一起改**，只改 Nginx 无效。
    client_max_body_size 512m;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;

        # WebSocket 升级（大厅推送依赖它）
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";

        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # 上传大文件时别超时
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
    }
}
```

启用后请把 **`LJX_SECURITY_TRUST_FORWARDED_HEADER=true`**（让后端按 `X-Forwarded-For` 取真实 IP），
否则限流会按代理 IP 统计，把所有玩家算成同一个人。

---

## 七、Linux 常驻服务（systemd）

`/etc/systemd/system/lajixia.service`：

```ini
[Unit]
Description=垃圾侠后端
After=network.target mysql.service

[Service]
Type=simple
User=lajixia
WorkingDirectory=/opt/lajixia

Environment=SPRING_PROFILES_ACTIVE=prod
EnvironmentFile=/etc/lajixia/env        # ← 敏感变量放这里，权限设 600
ExecStart=/usr/bin/java -Xms256m -Xmx1g -jar /opt/lajixia/lajixia-server.jar
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now lajixia
sudo journalctl -u lajixia -f        # 看日志
```

**`/etc/lajixia/env`**（权限 `600`，属主 `root`）：

```bash
SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/lajixia?...
SPRING_DATASOURCE_USERNAME=ljx
SPRING_DATASOURCE_PASSWORD=...
LJX_JWT_SECRET=...
LJX_ADMIN_JWT_SECRET=...
LJX_STORAGE_DIR=/var/lib/lajixia/storage
LJX_MAIL_ENABLED=true
LJX_MAIL_USERNAME=xxx@qq.com
LJX_MAIL_PASSWORD=授权码
```

> ⚠️ **凭据只放环境变量文件或后台配置，绝不提交到版本库**。
> 仓库里所有配置文件一律用 `${LJX_xxx:默认值}` 引用，不放真实凭据。

---

## 八、升级

```bash
# 1. 备份数据库（重要）
mysqldump -u ljx -p lajixia > lajixia_$(date +%Y%m%d_%H%M%S).sql

# 2. 备份文件存储
tar czf storage_$(date +%Y%m%d).tar.gz -C /var/lib/lajixia storage

# 3. 替换 jar 并重启
systemctl stop lajixia
cp lajixia-server-新版本.jar /opt/lajixia/lajixia-server.jar
systemctl start lajixia
```

**数据库迁移（Flyway）在启动时自动完成**，无需手工执行 SQL。

> ⚠️ **一条重要注意事项**：如果你曾经**修改过已经执行过的迁移文件**（例如改注释、改措辞），
> Flyway 会因为 **checksum 不一致**而拒绝启动。此时要么恢复到原文件，
> 要么手工刷新 `flyway_schema_history` 表里对应行的 checksum。
> **已经执行过的迁移文件不要再改。**

---

## 九、排错

| 现象 | 原因与处理 |
|---|---|
| **启动失败，提示"仍在使用内置的 JWT 默认密钥"** | 生产自检拦下了。设置 `LJX_JWT_SECRET` 即可 |
| **启动失败，提示"文件存储目录落在系统临时目录"** | 设置 `LJX_STORAGE_DIR` 到持久盘 |
| **启动失败，提示"H2 内存库"** | 没配 `SPRING_DATASOURCE_URL`，或没激活 `prod` profile |
| **登录后台返回 1603「未配置管理员账号」** | 库里没有管理员，且没设 `LJX_ADMIN_USERNAME/PASSWORD`。**注意这两个变量只在"库里没有管理员"时生效**，加完重启即可创建 |
| **后台登录返回 1604「请先修改管理员密码」** | 这是**正常的强制流程**：当前账号必须改一次密码才能用其它接口 |
| **后台登录返回 1602「该来源不允许访问管理后台」** | 你设了 `LJX_ADMIN_ALLOWED_IPS`，但当前 IP 不在白名单里。**登录接口同样受白名单限制**（避免白名单外的人还能试密码）。改白名单或换到白名单内的网络访问 |
| **忘记管理员密码** | 直接改数据库：`UPDATE admin_user SET must_change_password = TRUE;` 然后用 `LJX_ADMIN_*` 重建该账号（先 `DELETE` 掉旧行） |
| **玩家端看不到大厅 / 一直转圈** | 检查客户端设置里的服务器地址；`curl http://<IP>:8080/api/rooms?view=all` 应能**免登录**返回 JSON |
| **房间人数一直是 0** | 人数由**房主客户端上报**。确认房主客户端在运行、且「我的游戏」页处于打开状态（心跳 30 秒一次，90 秒无上报即判离线） |
| **验证码收不到邮件** | 看后台「邮件」面板：状态是否为「已配置」；面板里有「发送测试邮件」可以直接验证 |
| **上传客户端包失败** | ① 检查 `LJX_STORAGE_DIR` 存在且可写；② **后端默认只允许 40MB**（`spring.servlet.multipart.max-file-size`），要放大必须同时改**后端配置与 Nginx 的 `client_max_body_size`** |
| **限流误伤（同一网络下多人被限）** | 反代场景要设 `LJX_SECURITY_TRUST_FORWARDED_HEADER=true` |

**日志位置**：标准输出（`journalctl -u lajixia`）。日志级别在 `application.yml` 的 `logging.level` 里调整。

---

## 十、上线前安全清单

- [ ] `SPRING_PROFILES_ACTIVE=prod` 已设置（**否则第四节的自检不会生效**）
- [ ] `LJX_JWT_SECRET` / `LJX_ADMIN_JWT_SECRET` 已改为随机强值（**不要用仓库里的默认值**）
- [ ] 管理员登录后**已强制改过一次密码**
- [ ] `LJX_STORAGE_DIR` 指向持久盘（不是 `/tmp`）
- [ ] 数据库已用 MySQL（不是 H2 内存库）
- [ ] 邮件通道已开启并**发过测试邮件**
- [ ] `LJX_ADMIN_ALLOWED_IPS` 已限制管理员接口来源（强烈建议）
      → ⚠️ 设置后**必须从白名单内的 IP 访问后台**，否则连登录都会被拒（返回 1602）；
        启动日志里搜「管理端 IP 白名单」可确认实际生效的值
- [ ] HTTPS 已配置（Nginx 证书），并把 `LJX_SECURITY_TRUST_FORWARDED_HEADER=true`
- [ ] 数据库与文件存储已**配置定期备份**
- [ ] 已确认**不再修改任何已执行过的迁移文件**

---

## 十一、附录：接口一览

启动后可访问：

| 地址 | 说明 |
|---|---|
| `/api/rooms?view=all` | **免登录**的大厅列表（未登录也能看） |
| `/admin/` | 管理后台 |
| `/swagger-ui/index.html` | 接口文档（**生产建议关闭**，见下） |
| `/v3/api-docs` | OpenAPI JSON |

**生产建议关闭 Swagger**（避免对外暴露全部内部接口）：

```bash
export SPRINGDOC_API_DOCS_ENABLED=false
export SPRINGDOC_SWAGGER_UI_ENABLED=false
```

> 另外 `ljx.security.trust-forwarded-header` 目前是**写在 application.yml 里的固定值 `false`**，
> 需要改的话用环境变量 `LJX_SECURITY_TRUST_FORWARDED_HEADER=true` 覆盖即可（Spring Boot 的
> 松散绑定会自动映射到 `ljx.security.trust-forwarded-header`）。

---

*最后更新：2026-10-02*
