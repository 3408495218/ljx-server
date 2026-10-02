package com.ljx.server.quota;

/**
 * 配额类型。
 * <p>
 * <b>上限的唯一来源是后台配置</b>（见 {@link QuotaService#effective}）：
 * <ul>
 *   <li>客户端压缩包上限：有生效 VIP → {@code vip_plan.package_max_mb}；否则 → {@code level_config.upload_mb}；</li>
 *   <li>房间容量：{@link #PLAYER_CAP} 不受限（由服主自设）。</li>
 * </ul>
 * 这里只保留"两条来源都取不到时的兜底值"。
 * <p>
 * 修复前的坑：本枚举曾内置一张按等级的上限表 {@code {5,10,15,20,25,30}}，与后台可配的
 * {@code level_config.upload_mb} 形成**两个来源** —— 实际以配置为准，但 {@code quota} 表里仍会被写入
 * 那张表的数值，排查时容易误判"到底哪个生效"。现在把等级表删掉，只留兜底常量。
 */
public enum QuotaType {

    /** 房间容量：由服主自行设置，服务端不设上限 */
    PLAYER_CAP(true, 0),

    /** 客户端压缩包上传上限（MB）；兜底 30，真实上限来自后台配置 */
    CLIENT_PKG_MB(false, 30);

    /** 不受限类型的生效值；前端见到该值应展示为「不限」 */
    public static final int UNLIMITED = Integer.MAX_VALUE;

    private final boolean unlimited;
    private final int fallback;

    QuotaType(boolean unlimited, int fallback) {
        this.unlimited = unlimited;
        this.fallback = fallback;
    }

    /** 不受限：生效值恒为 {@link #UNLIMITED}，校验恒通过 */
    public boolean unlimited() {
        return unlimited;
    }

    /**
     * 兜底上限（忽略 level 参数）。
     * <p>
     * 保留 {@code level} 形参是为了不破坏既有调用点；**等级差异现在由
     * {@code level_config.upload_mb} 决定**，不再写死在代码里。
     */
    public int baseFor(int level) {
        return unlimited ? UNLIMITED : fallback;
    }

    /** LV0 初始 base，注册时写入配额行 */
    public int base() {
        return baseFor(0);
    }
}
