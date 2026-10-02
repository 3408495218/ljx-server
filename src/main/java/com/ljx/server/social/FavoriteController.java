package com.ljx.server.social;

import com.ljx.server.auth.AuthInterceptor;
import com.ljx.server.common.api.ApiResponse;
import com.ljx.server.social.dto.SocialDtos.FavoriteStateDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rooms")
@Tag(name = "收藏")
public class FavoriteController {

    private final FavoriteService favoriteService;

    public FavoriteController(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    @Operation(summary = "收藏房间（幂等；重复收藏不重复计数，首次收藏给房主加分）")
    @PostMapping("/{id}/favorite")
    public ApiResponse<FavoriteStateDto> add(
            @RequestAttribute(AuthInterceptor.ATTR_ACCOUNT_ID) Long accountId,
            @PathVariable Long id) {
        return ApiResponse.ok(favoriteService.add(accountId, id));
    }

    @Operation(summary = "取消收藏（幂等；未收藏时同样返回成功）")
    @DeleteMapping("/{id}/favorite")
    public ApiResponse<FavoriteStateDto> remove(
            @RequestAttribute(AuthInterceptor.ATTR_ACCOUNT_ID) Long accountId,
            @PathVariable Long id) {
        return ApiResponse.ok(favoriteService.remove(accountId, id));
    }
}