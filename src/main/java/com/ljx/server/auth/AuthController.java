package com.ljx.server.auth;

import com.ljx.server.auth.dto.AuthDtos.LoginRequest;
import com.ljx.server.auth.dto.AuthDtos.LogoutRequest;
import com.ljx.server.auth.dto.AuthDtos.RefreshRequest;
import com.ljx.server.auth.dto.AuthDtos.RegisterRequest;
import com.ljx.server.auth.dto.AuthDtos.TokenResponse;
import com.ljx.server.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "auth", description = "注册登录与令牌")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "注册（注册即登录，直接返回令牌）")
    @PostMapping("/register")
    public ApiResponse<TokenResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(authService.register(request.username(), request.password()));
    }

    /**
     * 访客注册：未登录也能开服。
     * <p>
     * 客户端在"未登录"状态下自动调用它拿一个匿名身份，用来承载房主权限
     * （改设置 / 删房 / 心跳 / 上传客户端包）。玩家全程无感，不需要填任何东西。
     */
    @Operation(summary = "访客注册（无需登录，自动获得一个匿名房主身份）")
    @PostMapping("/guest")
    public ApiResponse<TokenResponse> guest() {
        return ApiResponse.ok(authService.registerGuest());
    }

    @Operation(summary = "登录")
    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request.username(), request.password()));
    }

    @Operation(summary = "刷新令牌（一次性轮换；重放将失效全部令牌）")
    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(authService.refresh(request.refreshToken()));
    }

    @Operation(summary = "登出（撤销当前 refresh token）")
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@Valid @RequestBody(required = false) LogoutRequest request) {
        authService.logout(request == null ? null : request.refreshToken());
        return ApiResponse.ok();
    }
}
