-- V19：等级系统统一（修复两套并行系统互相覆盖 account.level / score 的问题）
--
-- 修复前的状况：
--   * 旧：ScoreService.award() 用 LevelTable（硬编码阈值 {0,50,200,500,1200,3000}）算等级，
--         并把 score 当"累计总分"，用 applyScore(score, level) 直接整体覆盖；
--   * 新：LevelService.claimDailyLogin() 用 level_config（后台可配）算等级，
--         并把 score 当"当前等级内的进度"（升级时扣掉阈值）。
-- 两者写同一份数据、语义还相反 → 等级与经验反复横跳，后台配的阈值有一半不作数。
--
-- 修复后：**level_config + app_config 是唯一真源**
--   * 升级阈值与各等级上传上限：level_config（已是后台可配）
--   * 各类加分值：app_config（本次新增，键名见下）
--   * LevelTable 与 DailyActiveScheduler 删除；account.applyScore 删除（只保留 addScore / setLevel）

INSERT INTO app_config (config_key, config_value)
SELECT 'exp_room_created', '10'
 WHERE NOT EXISTS (SELECT 1 FROM app_config WHERE config_key = 'exp_room_created');

INSERT INTO app_config (config_key, config_value)
SELECT 'exp_joined_by_other', '1'
 WHERE NOT EXISTS (SELECT 1 FROM app_config WHERE config_key = 'exp_joined_by_other');

INSERT INTO app_config (config_key, config_value)
SELECT 'exp_favorite_received', '3'
 WHERE NOT EXISTS (SELECT 1 FROM app_config WHERE config_key = 'exp_favorite_received');
