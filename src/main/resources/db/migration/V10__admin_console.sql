-- V10：网页管理后台（管理员账号 / 公告 / CDK 兑换）
--
-- 设计文档：plans/admin-console-design.md
-- 注意：列名必须与 Spring 的 CamelCaseToUnderscores 策略一致，否则 ORM 映射失败，
-- 相关接口会集体 500（本项目在 qq_group_id_key 上踩过）。

-- 管理员账号：与玩家 account 表彻底隔离，代码里没有任何注册入口；
-- 首个管理员由 LJX_ADMIN_USERNAME / LJX_ADMIN_PASSWORD 在首次启动时创建。
CREATE TABLE admin_user (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(32)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMP    NULL,
    CONSTRAINT uk_admin_user_username UNIQUE (username)
);

-- 公告：玩家端底部状态栏右侧轮播展示（该位置原本固定显示"问题反馈，请加官方QQ群"）
CREATE TABLE announcement (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    content    VARCHAR(500) NOT NULL,
    sort_order INT          NOT NULL DEFAULT 0,
    enabled    TINYINT      NOT NULL DEFAULT 1,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_announcement_enabled_sort ON announcement (enabled, sort_order);

-- CDK 兑换码：total_uses = 0 表示不限次数（但同一账号仍只能兑一次，见下）
CREATE TABLE cdk (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    code       VARCHAR(32)  NOT NULL,
    coins      INT          NOT NULL,
    total_uses INT          NOT NULL DEFAULT 1,
    used_uses  INT          NOT NULL DEFAULT 0,
    batch_no   VARCHAR(32)  NULL,
    enabled    TINYINT      NOT NULL DEFAULT 1,
    expires_at TIMESTAMP    NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_cdk_code UNIQUE (code)
);
CREATE INDEX idx_cdk_batch ON cdk (batch_no);

-- 兑换记录：同账号同码只能一次（即使该码不限次），防同一人反复领取
CREATE TABLE cdk_redeem (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    cdk_id      BIGINT    NOT NULL,
    account_id  BIGINT    NOT NULL,
    coins       INT       NOT NULL,
    redeemed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_cdk_redeem_cdk FOREIGN KEY (cdk_id) REFERENCES cdk (id),
    CONSTRAINT fk_cdk_redeem_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT uk_cdk_redeem UNIQUE (cdk_id, account_id)
);
