-- V11：商城完善（商品由后端发放 / VIP 权益与边框 / 置顶卡 / 订单）
--
-- 设计要点：
-- 1. 商品的效果类型放在 shop_item.effect_kind（TOP_CARD / VIP / NONE），
--    图标 URL 也在商品行里 —— 前端只认后端给的字段，不再硬编码商品。
-- 2. VIP 权益挂在 vip_plan（档位）上：package_max_mb（客户端压缩包上限）、border_url（房间边框图）。
--    V5 曾删掉 capacity_cap（容量与 VIP 解耦），本次加的是**上传限额**，用途不同。
-- 3. 置顶卡是**房间维度**的时效效果；VIP 是**账号维度**（account.vip_level + vip_expires_at）。
-- 4. 订单表记录"谁、何时、买了什么、作用于哪个房间、何时过期"，便于追溯与退款排查。

-- ---------- 1) 商品：补效果字段 ----------
ALTER TABLE shop_item ADD COLUMN effect_kind VARCHAR(24) NOT NULL DEFAULT 'NONE';
ALTER TABLE shop_item ADD COLUMN icon_url VARCHAR(160) NULL;
ALTER TABLE shop_item ADD COLUMN duration_days INT NULL;
ALTER TABLE shop_item ADD COLUMN vip_level INT NULL;
ALTER TABLE shop_item ADD COLUMN enabled TINYINT NOT NULL DEFAULT 1;

-- 旧的 4 件占位商品（"敬请期待"）换成真实商品
DELETE FROM shop_item;
INSERT INTO shop_item
    (name, category, price_coins, description, sort_order, effect_kind, icon_url, duration_days, vip_level, enabled)
VALUES
    ('置顶卡',  '推广', 800,  '将房间置顶到大厅前列，按到期时间自动失效',        10, 'TOP_CARD', '/shop/top-card.png',    7,  NULL, 1),
    ('铁块VIP', 'VIP',  1000, '房间边框变为铁块风格；客户端压缩包上限 50MB',      20, 'VIP',      '/shop/vip-iron.png',    30, 1,    1),
    ('金块VIP', 'VIP',  3000, '房间边框变为金块风格；客户端压缩包上限 75MB',      30, 'VIP',      '/shop/vip-gold.png',    30, 2,    1),
    ('钻石VIP', 'VIP',  8000, '房间边框变为钻石风格；客户端压缩包上限 100MB',     40, 'VIP',      '/shop/vip-diamond.png', 30, 3,    1);

-- ---------- 2) VIP 档位：三档 + 上传限额 + 边框图 ----------
-- 老库里 level 4/5 没有对应商品，删掉避免出现"买不到的档位"
DELETE FROM vip_plan WHERE level > 3;

ALTER TABLE vip_plan ADD COLUMN package_max_mb INT NOT NULL DEFAULT 30;
ALTER TABLE vip_plan ADD COLUMN border_url VARCHAR(160) NULL;

UPDATE vip_plan SET name = '普通用户', price_coins = 0, package_max_mb = 30,
       border_url = NULL, description = '默认档位'
 WHERE level = 0;
UPDATE vip_plan SET name = '铁块VIP', price_coins = 1000, package_max_mb = 50,
       border_url = '/shop/border-iron.png', description = '房间边框：铁块；客户端压缩包上限 50MB'
 WHERE level = 1;
UPDATE vip_plan SET name = '金块VIP', price_coins = 3000, package_max_mb = 75,
       border_url = '/shop/border-gold.png', description = '房间边框：金块；客户端压缩包上限 75MB'
 WHERE level = 2;
UPDATE vip_plan SET name = '钻石VIP', price_coins = 8000, package_max_mb = 100,
       border_url = '/shop/border-diamond.png', description = '房间边框：钻石；客户端压缩包上限 100MB'
 WHERE level = 3;

-- 兜底：万一老库里 1..3 档缺失，补上（保证商品一定买得到）
INSERT INTO vip_plan (level, name, price_coins, description, package_max_mb, border_url)
SELECT 1, '铁块VIP', 1000, '房间边框：铁块；客户端压缩包上限 50MB', 50, '/shop/border-iron.png'
 WHERE NOT EXISTS (SELECT 1 FROM vip_plan WHERE level = 1);
INSERT INTO vip_plan (level, name, price_coins, description, package_max_mb, border_url)
SELECT 2, '金块VIP', 3000, '房间边框：金块；客户端压缩包上限 75MB', 75, '/shop/border-gold.png'
 WHERE NOT EXISTS (SELECT 1 FROM vip_plan WHERE level = 2);
INSERT INTO vip_plan (level, name, price_coins, description, package_max_mb, border_url)
SELECT 3, '钻石VIP', 8000, '房间边框：钻石；客户端压缩包上限 100MB', 100, '/shop/border-diamond.png'
 WHERE NOT EXISTS (SELECT 1 FROM vip_plan WHERE level = 3);

-- ---------- 3) 账号 VIP 到期时间 ----------
-- 空 = 从未购买；过期后按普通用户处理（读的时候判断，不依赖定时任务）
ALTER TABLE account ADD COLUMN vip_expires_at TIMESTAMP NULL;

-- ---------- 4) 置顶卡（房间维度） ----------
CREATE TABLE room_top_card (
    room_id      BIGINT PRIMARY KEY,
    expires_at   TIMESTAMP NOT NULL,
    purchased_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_room_top_card_room FOREIGN KEY (room_id) REFERENCES room (id)
);
CREATE INDEX idx_room_top_card_expires ON room_top_card (expires_at);

-- ---------- 5) 订单 ----------
CREATE TABLE shop_order (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id     BIGINT      NOT NULL,
    item_id        BIGINT      NOT NULL,
    item_name      VARCHAR(64) NOT NULL,
    price_coins    INT         NOT NULL,
    effect_kind    VARCHAR(24) NOT NULL,
    target_room_id BIGINT      NULL,
    vip_level      INT         NULL,
    expires_at     TIMESTAMP   NULL,
    created_at     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_shop_order_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT fk_shop_order_item FOREIGN KEY (item_id) REFERENCES shop_item (id)
);
CREATE INDEX idx_shop_order_account ON shop_order (account_id, id);
