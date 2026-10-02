package com.ljx.server.admin;

import com.ljx.server.cdk.CdkService;
import com.ljx.server.cdk.dto.CdkDtos.CdkBatchRequest;
import com.ljx.server.cdk.dto.CdkDtos.CdkBatchSummaryDto;
import com.ljx.server.cdk.dto.CdkDtos.CdkBatchResponse;
import com.ljx.server.cdk.dto.CdkDtos.CdkDto;
import com.ljx.server.cdk.dto.CdkDtos.CdkToggleRequest;
import com.ljx.server.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/cdk")
@Tag(name = "管理后台-CDK")
public class AdminCdkController {

    private final CdkService cdkService;

    public AdminCdkController(CdkService cdkService) {
        this.cdkService = cdkService;
    }

    @Operation(summary = "批量生成兑换码（返回整批码，便于一次复制发给玩家）")
    @PostMapping("/batches")
    public ApiResponse<CdkBatchResponse> createBatch(
            @RequestAttribute(AdminAuthInterceptor.ATTR_ADMIN_ID) Long adminId,
            @Valid @RequestBody CdkBatchRequest request) {
        return ApiResponse.ok(cdkService.createBatch(adminId, request));
    }

    @Operation(summary = "批次汇总（后台默认视图：一行一批，避免码多时看不清）")
    @GetMapping("/batches")
    public ApiResponse<List<CdkBatchSummaryDto>> listBatches() {
        return ApiResponse.ok(cdkService.listBatches());
    }

    @Operation(summary = "某批兑换码明细（展开某批时按需加载）")
    @GetMapping("/batches/{batchNo}")
    public ApiResponse<List<CdkDto>> listBatch(@PathVariable String batchNo) {
        return ApiResponse.ok(cdkService.listByBatch(batchNo));
    }

    @Operation(summary = "全部兑换码（新的在前；后台默认不用它，走批次视图）")
    @GetMapping
    public ApiResponse<List<CdkDto>> list() {
        return ApiResponse.ok(cdkService.list());
    }

    @Operation(summary = "启用 / 停用兑换码")
    @PutMapping("/{id}")
    public ApiResponse<CdkDto> toggle(
            @RequestAttribute(AdminAuthInterceptor.ATTR_ADMIN_ID) Long adminId,
            @PathVariable Long id,
            @Valid @RequestBody CdkToggleRequest request) {
        return ApiResponse.ok(cdkService.toggle(adminId, id, request.enabled()));
    }

    @Operation(summary = "删除兑换码（已有领取记录的只能停用，不能删除）")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @RequestAttribute(AdminAuthInterceptor.ATTR_ADMIN_ID) Long adminId,
            @PathVariable Long id) {
        cdkService.delete(adminId, id);
        return ApiResponse.ok();
    }
}
