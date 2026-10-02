package com.ljx.server.cdk.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class CdkDtos {

    /** 批量生成；totalUses=0 表示不限次数，validDays 为空表示永不过期 */
    public record CdkBatchRequest(
            @Min(value = 1, message = "至少生成 1 个") @Max(value = 500, message = "单次最多生成 500 个") int count,
            @Min(value = 1, message = "金币数至少为 1") int coins,
            @Min(value = 0, message = "总次数不能为负") int totalUses,
            Integer validDays) {
    }

    public record CdkDto(
            Long id,
            String code,
            int coins,
            int totalUses,
            int usedUses,
            String batchNo,
            boolean enabled,
            LocalDateTime expiresAt,
            LocalDateTime createdAt) {
    }

    /**
     * 批次汇总：后台默认按批次展示（一行一批），避免码多时列表糊成一片。
     * usedCount 是该批已领取次数合计，enabledCount 是仍启用（未停用）的码数。
     */
    public record CdkBatchSummaryDto(
            String batchNo,
            int total,
            int coins,
            int totalUses,
            int usedCount,
            int enabledCount,
            LocalDateTime createdAt,
            LocalDateTime expiresAt) {
    }

    /** 生成结果：直接把整批码返回，便于一次性复制发给玩家 */
    public record CdkBatchResponse(String batchNo, List<CdkDto> items) {
    }

    public record CdkToggleRequest(boolean enabled) {
    }

    /** 玩家端兑换请求 */
    public record RedeemRequest(@NotBlank(message = "请输入兑换码") @Size(max = 32) String code) {
    }

    /** 兑换结果：返回最新金币总数，前端可直接刷新商城显示 */
    public record RedeemResponse(int coins, int gained) {
    }
}
