package com.ljx.server.commerce;

/**
 * 互动经验事件。
 * <p>
 * 分值**不再硬编码在枚举里**（原来写死会让"后台可配"变成"一半可配"），
 * 而是每个事件带一个 {@code app_config} 的键，由后台配置；枚举上的默认值只作为兜底。
 * <p>
 * 注意：原来的 {@code DAILY_ACTIVE}（每日活跃补发 +2）已删除 ——
 * 「每天登录一次获得一次经验」由 {@link com.ljx.server.level.LevelService#claimDailyLogin} 负责，
 * 两者同时存在会在同一天重复发经验。
 */
public enum ScoreEventType {

    ROOM_CREATED("exp_room_created", 10),
    JOINED_BY_OTHER("exp_joined_by_other", 1),
    FAVORITE_RECEIVED("exp_favorite_received", 3);

    private final String configKey;
    private final int defaultDelta;

    ScoreEventType(String configKey, int defaultDelta) {
        this.configKey = configKey;
        this.defaultDelta = defaultDelta;
    }

    /** app_config 里的键名 */
    public String configKey() {
        return configKey;
    }

    /** 配置缺失时的兜底分值 */
    public int defaultDelta() {
        return defaultDelta;
    }
}
