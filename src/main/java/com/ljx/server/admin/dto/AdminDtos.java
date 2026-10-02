package com.ljx.server.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AdminDtos {

    public record AdminLoginRequest(
            @NotBlank(message = "请输入管理员账号") @Size(max = 32) String username,
            @NotBlank(message = "请输入密码") @Size(max = 64) String password) {
    }

    /**
     * @param mustChangePassword 为 true 时，除「修改密码」外的管理接口都会被拒绝，
     *                           前端必须强制弹出改密码窗口（首次创建或被重置的账号）
     */
    public record AdminLoginResponse(String token, String username, long expiresInSeconds,
                                     boolean mustChangePassword) {
    }

    public record AdminMeResponse(Long id, String username, boolean mustChangePassword) {
    }

    /**
     * 修改管理员密码。
     * <p>
     * 新密码要求至少 8 位：后台是平台最高权限入口，弱口令风险远高于玩家账号。
     * 上限 64 位是为了避免异常长的输入拖慢 BCrypt。
     */
    public record ChangePasswordRequest(
            @NotBlank(message = "请输入当前密码") @Size(max = 64) String oldPassword,
            @NotBlank(message = "请输入新密码")
            @Size(min = 8, max = 64, message = "新密码长度需在 8-64 位之间") String newPassword) {
    }
}
