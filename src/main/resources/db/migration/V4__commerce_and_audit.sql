-- 垃圾侠平台 V4：商业化（VIP / 商城）与审计日志
-- 不含支付闭环：VIP 与商城只提供数据模型与查询接口，购买按钮由前端置灰

-- VIP 档位：容量上限的唯一来源（房主自设容量，超出档位上限即拒绝）
CREATE TABLE vip_plan (
    level        INT          PRIMARY KEY,
    name         VARCHAR(32)  NOT NULL,
    capacity_cap INT          NOT NULL,
    price_coins  INT          NOT NULL,
    description  VARCHAR(255) NULL
);

INSERT INTO vip_plan (level, name, capacity_cap, price_coins, description) VALUES
    (0, '普通用户', 10,  0,     '房间容量上限 10 人'),
    (1, 'VIP 1',   20,  1000,  '房间容量上限 20 人'),
    (2, 'VIP 2',   30,  3000,  '房间容量上限 30 人'),
    (3, 'VIP 3',   50,  8000,  '房间容量上限 50 人'),
    (4, 'VIP 4',   80,  20000, '房间容量上限 80 人'),
    (5, 'VIP 5',   100, 50000, '房间容量上限 100 人');

-- 商城商品目录：仅展示与查询，暂无购买通道
CREATE TABLE shop_item (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL,
    category    VARCHAR(32)  NOT NULL,
    price_coins INT          NOT NULL,
    description VARCHAR(255) NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    CONSTRAINT uk_shop_item_name UNIQUE (name)
);

INSERT INTO shop_item (name, category, price_coins, description, sort_order) VALUES
    ('房间容量扩充卡',      '容量', 500,  '提升单个房间的人数上限（敬请期待）', 10),
    ('房间封面位',          '外观', 800,  '在大厅展示自定义封面（敬请期待）',   20),
    ('昵称颜色',            '外观', 300,  '顶栏昵称显示专属颜色（敬请期待）',   30),
    ('VIP 体验卡（7 天）',  'VIP',  2000, '临时体验更高 VIP 档位（敬请期待）',  40);

-- 审计日志：注册 / 登录 / 邮箱绑定 / 房间增删 / 客户端包增删
CREATE TABLE audit_log (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id BIGINT       NULL,
    ip         VARCHAR(64)  NULL,
    action     VARCHAR(48)  NOT NULL,
    detail     VARCHAR(255) NULL,
    success    TINYINT      NOT NULL DEFAULT 1,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_audit_log_account ON audit_log (account_id, created_at);
CREATE INDEX idx_audit_log_action ON audit_log (action, created_at);