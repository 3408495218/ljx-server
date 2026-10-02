-- V12：房间置顶的排序冗余列
--
-- 为什么需要它：置顶卡记录在 room_top_card（另一张表），而大厅分页排序发生在数据库层。
-- 若"先分页再在内存里把置顶房提到前面"，置顶房可能落在第 2 页，根本排不到"前列"。
-- 因此把到期时间冗余到 room 上，排序可以直接 `top_expires_at DESC`（过期的由定时任务置回 NULL）。
--
-- 排序里空值最大还是最小要与下面保持一致：本平台用 DESC + NULL 排在最后（H2/MySQL 默认）。
-- 所以过期记录必须先被清成 NULL，否则"昨天到期的置顶房"会排在无置顶房间之前。

ALTER TABLE room ADD COLUMN top_expires_at TIMESTAMP NULL;
CREATE INDEX idx_room_top_expires ON room (top_expires_at);
