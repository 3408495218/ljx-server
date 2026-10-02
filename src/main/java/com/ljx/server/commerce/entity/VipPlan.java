package com.ljx.server.commerce.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** VIP 档位：仅档位名与价格，由 V4 种子数据维护；容量由服主自设，与档位无关 */
@Entity
@Table(name = "vip_plan")
public class VipPlan {

    @Id
    private Integer level;

    private String name;

    private int priceCoins;

    private String description;

    /** 该档位的客户端压缩包上传上限（MB），由后台设置 */
    @Column(name = "package_max_mb", nullable = false)
    private int packageMaxMb;

    /** 房间卡片外框图的**资源键**（如 border-iron）；图片打包在桌面端，前端按此映射 */
    @Column(name = "border_url", length = 160)
    private String borderUrl;

    /** 档位图标的**资源键**（如 vip-iron）；普通用户为 null（不显示图标） */
    @Column(name = "icon_url", length = 160)
    private String iconUrl;

    /** 购买后的有效天数；null 表示永久。由后台设置，与商品时长同一口径 */
    @Column(name = "duration_days")
    private Integer durationDays;

    /** 后台改价 / 改时长 / 改权益（null 表示该字段不变） */
    public void update(Integer priceCoins, Integer durationDays, Integer packageMaxMb,
                       String borderUrl, String iconUrl, String description) {
        if (priceCoins != null) {
            this.priceCoins = priceCoins;
        }
        if (durationDays != null) {
            this.durationDays = durationDays;
        }
        if (packageMaxMb != null) {
            this.packageMaxMb = packageMaxMb;
        }
        if (borderUrl != null) {
            this.borderUrl = borderUrl.isBlank() ? null : borderUrl;
        }
        if (iconUrl != null) {
            this.iconUrl = iconUrl.isBlank() ? null : iconUrl;
        }
        if (description != null) {
            this.description = description.isBlank() ? null : description;
        }
    }

    protected VipPlan() {
    }

    public Integer getLevel() {
        return level;
    }

    public String getName() {
        return name;
    }

    public int getPriceCoins() {
        return priceCoins;
    }

    public int getPackageMaxMb() {
        return packageMaxMb;
    }

    public String getBorderUrl() {
        return borderUrl;
    }

    public String getIconUrl() {
        return iconUrl;
    }

    public Integer getDurationDays() {
        return durationDays;
    }

    public String getDescription() {
        return description;
    }
}