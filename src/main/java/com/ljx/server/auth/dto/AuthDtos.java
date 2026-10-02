package com.ljx.server.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank(message = "用户名不能为空")
            @Size(min = 2, max = 32, message = "用户名长度须在 2-32 之间")
            String username,
            @NotBlank(message = "密码不能为空")
            @Size(min = 6, max = 64, message = "密码至少 6 位")
            String password) {
    }

    public record LoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password) {
    }

    public record RefreshRequest(@NotBlank(message = "refreshToken 不能为空") String refreshToken) {
    }

    public record LogoutRequest(String refreshToken) {
    }

    /**
     * 登录/刷新响应。
     * <p>
     * {@code dailyReward} 只在**登录**时可能非空（每天首次登录发一次经验）；
     * 刷新令牌不会重复发放，所以 refresh 时这里是 null。
     */
    public record TokenResponse(String accessToken, String refreshToken, AccountDto account,
                                DailyRewardDto dailyReward) {
    }

    /** 每日登录经验的发放结果，供客户端提示"今日经验 +N / 升到 N 级" */
    public record DailyRewardDto(boolean granted, int expGained, int level, int score,
                                 int levelsUp, int expToNext) {
    }

    public record AccountDto(
            Long id,
            String username,
            int level,
            int score,
            /** 当前等级升到下一级还需多少经验；0 表示已封顶 */
            int expToNext,
            int vipLevel,
            /** VIP 到期时间；null 表示未购买或已过期 */
            java.time.LocalDateTime vipExpiresAt,
            /** 是否为访客（未登录时自动创建的匿名身份）；前端据此显示「访客」并放宽部分引导 */
            boolean anonymous,
            int coins,
            String email,
            Map<String, Integer> quotas) {
    }

    public record SendCodeRequest(@NotBlank(message = "邮箱不能为空") String email) {
    }

    public record BindEmailRequest(
            @NotBlank(message = "邮箱不能为空") String email,
            @NotBlank(message = "验证码不能为空") String code) {
    }
}
