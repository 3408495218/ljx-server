package com.ljx.server.snapshot;

import com.ljx.server.auth.AuthInterceptor;
import com.ljx.server.common.api.ApiResponse;
import com.ljx.server.snapshot.dto.SnapshotDtos.CreateSnapshotRequest;
import com.ljx.server.snapshot.dto.SnapshotDtos.SnapshotDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/rooms/{roomId}/snapshots")
@Tag(name = "快照")
public class SnapshotController {

    private final SnapshotService snapshotService;

    public SnapshotController(SnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    @Operation(summary = "快照列表（仅房主，新建优先）")
    @GetMapping
    public ApiResponse<List<SnapshotDto>> list(
            @RequestAttribute(AuthInterceptor.ATTR_ACCOUNT_ID) Long accountId,
            @PathVariable Long roomId) {
        return ApiResponse.ok(snapshotService.list(accountId, roomId));
    }

    @Operation(summary = "登记快照元数据（仅房主；同名幂等，重复登记刷新大小）")
    @PostMapping
    public ApiResponse<SnapshotDto> create(
            @RequestAttribute(AuthInterceptor.ATTR_ACCOUNT_ID) Long accountId,
            @PathVariable Long roomId,
            @Valid @RequestBody CreateSnapshotRequest request) {
        return ApiResponse.ok(snapshotService.create(accountId, roomId, request));
    }

    @Operation(summary = "删除快照元数据（仅房主；按名称幂等，返回余下列表）")
    @DeleteMapping("/{name}")
    public ApiResponse<List<SnapshotDto>> delete(
            @RequestAttribute(AuthInterceptor.ATTR_ACCOUNT_ID) Long accountId,
            @PathVariable Long roomId,
            @PathVariable String name) {
        return ApiResponse.ok(snapshotService.delete(accountId, roomId, name));
    }
}