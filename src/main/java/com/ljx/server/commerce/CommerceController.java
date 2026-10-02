package com.ljx.server.commerce;

import com.ljx.server.auth.AuthInterceptor;
import com.ljx.server.commerce.dto.CommerceDtos.ShopDto;
import com.ljx.server.commerce.dto.CommerceDtos.VipDto;
import com.ljx.server.cdk.CdkService;
import com.ljx.server.commerce.dto.CommerceDtos.PurchaseRequest;
import com.ljx.server.commerce.dto.CommerceDtos.PurchaseResponse;
import com.ljx.server.commerce.dto.CommerceDtos.VipPurchaseRequest;
import com.ljx.server.cdk.dto.CdkDtos.RedeemRequest;
import com.ljx.server.cdk.dto.CdkDtos.RedeemResponse;
import com.ljx.server.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商业化展示接口：商城目录与 VIP 档位只读。
 * 不含购买端点——本期不做支付闭环，客户端购买按钮置灰。
 */
@Tag(name = "commerce", description = "商城与 VIP（展示为主，无支付闭环）")
@RestController
@RequestMapping("/api/commerce")
public class CommerceController {

    private final CommerceService commerceService;
    private final CdkService cdkService;

    public CommerceController(CommerceService commerceService, CdkService cdkService) {
        this.commerceService = commerceService;
        this.cdkService = cdkService;
    }

    @Operation(summary = "购买商品（扣钻石并发效果；置顶卡需传自己名下的 roomId）")
    @PostMapping("/purchase")
    public ApiResponse<PurchaseResponse> purchase(
            @RequestAttribute(AuthInterceptor.ATTR_ACCOUNT_ID) Long accountId,
            @Valid @RequestBody PurchaseRequest request) {
        return ApiResponse.ok(commerceService.purchase(accountId, request));
    }

    @Operation(summary = "购买 VIP 档位（VIP 唯一来源是档位表，不走商品 id）")
    @PostMapping("/vip/purchase")
    public ApiResponse<PurchaseResponse> purchaseVip(
            @RequestAttribute(AuthInterceptor.ATTR_ACCOUNT_ID) Long accountId,
            @Valid @RequestBody VipPurchaseRequest request) {
        return ApiResponse.ok(commerceService.purchaseVip(accountId, request.level()));
    }

    @Operation(summary = "兑换 CDK（发放金币/钻石）；返回最新金币总数")
    @PostMapping("/redeem")
    public ApiResponse<RedeemResponse> redeem(
            @RequestAttribute(AuthInterceptor.ATTR_ACCOUNT_ID) Long accountId,
            @Valid @RequestBody RedeemRequest request) {
        return ApiResponse.ok(cdkService.redeem(accountId, request.code()));
    }

    @Operation(summary = "商城目录 + 当前金币")
    @GetMapping("/shop")
    public ApiResponse<ShopDto> shop(@RequestAttribute(AuthInterceptor.ATTR_ACCOUNT_ID) Long accountId) {
        return ApiResponse.ok(commerceService.shop(accountId));
    }

    @Operation(summary = "VIP 档位一览 + 当前档位")
    @GetMapping("/vip")
    public ApiResponse<VipDto> vip(@RequestAttribute(AuthInterceptor.ATTR_ACCOUNT_ID) Long accountId) {
        return ApiResponse.ok(commerceService.vip(accountId));
    }
}