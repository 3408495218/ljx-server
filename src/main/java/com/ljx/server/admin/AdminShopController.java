package com.ljx.server.admin;

import com.ljx.server.admin.dto.AdminShopDtos.ActiveTopCardDto;
import com.ljx.server.admin.dto.AdminShopDtos.ActiveVipDto;
import com.ljx.server.admin.dto.AdminShopDtos.AdminShopItemDto;
import com.ljx.server.admin.dto.AdminShopDtos.EffectExpiryRequest;
import com.ljx.server.admin.dto.AdminShopDtos.AdminVipPlanDto;
import com.ljx.server.admin.dto.AdminShopDtos.ShopItemUpdateRequest;
import com.ljx.server.admin.dto.AdminShopDtos.VipPlanUpdateRequest;
import com.ljx.server.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 后台商品管理：商品（道具）与 VIP 档位的价格、时长、上下架与权益都在这里改。 */
@RestController
@RequestMapping("/api/admin/shop")
@Tag(name = "管理后台-商品")
public class AdminShopController {

    private final AdminShopService adminShopService;

    public AdminShopController(AdminShopService adminShopService) {
        this.adminShopService = adminShopService;
    }

    @Operation(summary = "商品列表（含已下架）")
    @GetMapping("/items")
    public ApiResponse<List<AdminShopItemDto>> items() {
        return ApiResponse.ok(adminShopService.listItems());
    }

    @Operation(summary = "修改商品（价格 / 时长 / 上下架 / 图标键；null 字段不变）")
    @PutMapping("/items/{id}")
    public ApiResponse<AdminShopItemDto> updateItem(
            @RequestAttribute(AdminAuthInterceptor.ATTR_ADMIN_ID) Long adminId,
            @PathVariable Long id,
            @Valid @RequestBody ShopItemUpdateRequest request) {
        return ApiResponse.ok(adminShopService.updateItem(adminId, id, request));
    }

    @Operation(summary = "生效中的置顶卡（可改到期时间或撤销）")
    @GetMapping("/effects/top-cards")
    public ApiResponse<List<ActiveTopCardDto>> activeTopCards() {
        return ApiResponse.ok(adminShopService.listActiveTopCards());
    }

    @Operation(summary = "生效中的 VIP（可改到期时间或撤销）")
    @GetMapping("/effects/vips")
    public ApiResponse<List<ActiveVipDto>> activeVips() {
        return ApiResponse.ok(adminShopService.listActiveVips());
    }

    @Operation(summary = "调整房间置顶到期时间（expiresAt 为空 = 立即撤销）")
    @PutMapping("/effects/top-cards/{roomId}")
    public ApiResponse<Void> updateTopCardExpiry(
            @RequestAttribute(AdminAuthInterceptor.ATTR_ADMIN_ID) Long adminId,
            @PathVariable Long roomId,
            @Valid @RequestBody EffectExpiryRequest request) {
        adminShopService.updateTopCardExpiry(adminId, roomId, request.expiresAt());
        return ApiResponse.ok();
    }

    @Operation(summary = "调整账号 VIP 到期时间（expiresAt 为空 = 立即撤销）")
    @PutMapping("/effects/vips/{accountId}")
    public ApiResponse<Void> updateVipExpiry(
            @RequestAttribute(AdminAuthInterceptor.ATTR_ADMIN_ID) Long adminId,
            @PathVariable Long accountId,
            @Valid @RequestBody EffectExpiryRequest request) {
        adminShopService.updateVipExpiry(adminId, accountId, request.expiresAt());
        return ApiResponse.ok();
    }

    @Operation(summary = "VIP 档位列表（含普通用户对照）")
    @GetMapping("/vip-plans")
    public ApiResponse<List<AdminVipPlanDto>> vipPlans() {
        return ApiResponse.ok(adminShopService.listPlans());
    }

    @Operation(summary = "修改 VIP 档位（价格 / 时长 / 上传上限 / 边框与图标键；null 字段不变）")
    @PutMapping("/vip-plans/{level}")
    public ApiResponse<AdminVipPlanDto> updateVipPlan(
            @RequestAttribute(AdminAuthInterceptor.ATTR_ADMIN_ID) Long adminId,
            @PathVariable int level,
            @Valid @RequestBody VipPlanUpdateRequest request) {
        return ApiResponse.ok(adminShopService.updatePlan(adminId, level, request));
    }
}
