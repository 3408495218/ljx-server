package com.ljx.server.admin;

import com.ljx.server.admin.dto.AdminShopDtos.ActiveTopCardDto;
import com.ljx.server.admin.dto.AdminShopDtos.ActiveVipDto;
import com.ljx.server.admin.dto.AdminShopDtos.AdminShopItemDto;
import com.ljx.server.admin.dto.AdminShopDtos.AdminVipPlanDto;
import com.ljx.server.admin.dto.AdminShopDtos.ShopItemUpdateRequest;
import com.ljx.server.admin.dto.AdminShopDtos.VipPlanUpdateRequest;
import com.ljx.server.auth.entity.Account;
import com.ljx.server.auth.repository.AccountRepository;
import com.ljx.server.commerce.entity.RoomTopCard;
import com.ljx.server.commerce.entity.ShopItem;
import com.ljx.server.commerce.entity.VipPlan;
import com.ljx.server.commerce.repository.RoomTopCardRepository;
import com.ljx.server.commerce.repository.ShopItemRepository;
import com.ljx.server.commerce.repository.VipPlanRepository;
import com.ljx.server.common.api.ErrorCode;
import com.ljx.server.common.audit.AuditAction;
import com.ljx.server.common.audit.AuditService;
import com.ljx.server.common.exception.BizException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ljx.server.room.entity.Room;
import com.ljx.server.room.repository.RoomRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 后台商品管理：改价、改时长（**过期时间就是从这里来的**）、上下架，以及各档 VIP 的
 * 上传上限与边框/图标资源键。
 * <p>
 * 商品与档位全部由后台驱动，所以以后调价、调有效期、换图都不需要动代码。
 */
@Service
public class AdminShopService {

    private final ShopItemRepository shopItemRepository;
    private final VipPlanRepository vipPlanRepository;
    private final RoomTopCardRepository roomTopCardRepository;
    private final RoomRepository roomRepository;
    private final AccountRepository accountRepository;
    private final AuditService auditService;

    public AdminShopService(ShopItemRepository shopItemRepository,
                            VipPlanRepository vipPlanRepository,
                            RoomTopCardRepository roomTopCardRepository,
                            RoomRepository roomRepository,
                            AccountRepository accountRepository,
                            AuditService auditService) {
        this.shopItemRepository = shopItemRepository;
        this.vipPlanRepository = vipPlanRepository;
        this.roomTopCardRepository = roomTopCardRepository;
        this.roomRepository = roomRepository;
        this.accountRepository = accountRepository;
        this.auditService = auditService;
    }

    /**
     * 道具商品列表（含已下架）。
     * <p>
     * 会再过滤一次 VIP：VIP 的唯一来源是 vip_plan，若这里也出现 VIP 行，
     * 后台就分不清该去哪改（同一个东西出现在两个面板里）。V17 已把历史遗留的 VIP 行删掉，
     * 这里的过滤是防御性的，防止以后有人再往 shop_item 里塞 VIP。
     */
    public List<AdminShopItemDto> listItems() {
        return shopItemRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                .filter(item -> !"VIP".equals(item.getEffectKind()))
                .map(this::toItemDto)
                .toList();
    }

    @Transactional
    public AdminShopItemDto updateItem(Long adminId, Long id, ShopItemUpdateRequest request) {
        ShopItem item = shopItemRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.SHOP_ITEM_NOT_FOUND));
        item.update(request.priceCoins(), request.durationDays(), request.enabled(), request.iconUrl());
        auditService.record(null, AuditAction.SHOP_ITEM_UPDATE,
                "管理员#" + adminId + " 商品「" + item.getName() + "」→ 价格 " + item.getPriceCoins()
                        + "，时长 " + describeDays(item.getDurationDays())
                        + "，状态 " + (item.isEnabled() ? "上架" : "下架"), true);
        return toItemDto(item);
    }

    /** 全部 VIP 档位（含普通用户，便于对照） */
    public List<AdminVipPlanDto> listPlans() {
        // LV0（普通用户）不在这里出现：它代表"没有 VIP"，价格固定 0，
        // 其客户端压缩包上限由后台「等级」面板里的 level_config 决定，
        // 放在 VIP 档位里编辑会造成"两个地方都能改同一个上限"的困惑。
        return vipPlanRepository.findAllByOrderByLevelAsc().stream()
                .filter(plan -> plan.getLevel() > 0)
                .map(this::toPlanDto)
                .toList();
    }

    @Transactional
    public AdminVipPlanDto updatePlan(Long adminId, int level, VipPlanUpdateRequest request) {
        // LV0 是"没有 VIP"的占位档位：价格恒为 0，上传上限由「等级」面板的 level_config 决定。
        // 允许在这里改它会出现"等级与 VIP 两个地方都能改同一个上限"的困惑，直接在接口层拒绝。
        if (level <= 0) {
            throw new BizException(ErrorCode.VIP_PLAN_NOT_FOUND,
                    "LV0 是普通用户占位档位，其上传上限请在「等级」面板里配置");
        }
        if (level <= 0) {
            // 普通用户(0 档)是默认档位、不可购买，改它没有意义，且容易误伤默认权益
            throw new BizException(ErrorCode.VIP_PLAN_NOT_FOUND);
        }
        VipPlan plan = vipPlanRepository.findById(level)
                .orElseThrow(() -> new BizException(ErrorCode.VIP_PLAN_NOT_FOUND));
        plan.update(request.priceCoins(), request.durationDays(), request.packageMaxMb(),
                request.borderUrl(), request.iconUrl(), request.description());
        auditService.record(null, AuditAction.SHOP_ITEM_UPDATE,
                "管理员#" + adminId + " VIP 档位「" + plan.getName() + "」→ 价格 " + plan.getPriceCoins()
                        + "，时长 " + describeDays(plan.getDurationDays())
                        + "，上传上限 " + plan.getPackageMaxMb() + "MB", true);
        return toPlanDto(plan);
    }

    // ---------- 已售出的效果：能看、能改到期、能撤销 ----------

    /** 生效中的置顶卡（哪个房间、房主是谁、到什么时候） */
    public List<ActiveTopCardDto> listActiveTopCards() {
        LocalDateTime now = LocalDateTime.now();
        return roomTopCardRepository.findByExpiresAtAfterOrderByExpiresAtAsc(now).stream()
                .map(card -> {
                    Room room = roomRepository.findByIdAndDeletedAtIsNull(card.getRoomId()).orElse(null);
                    String owner = room == null ? "" : accountRepository
                            .findByIdAndDeletedAtIsNull(room.getOwnerId())
                            .map(Account::getUsername).orElse("");
                    return new ActiveTopCardDto(card.getRoomId(),
                            room == null ? "（房间已删除）" : room.getName(), owner, card.getExpiresAt());
                })
                .toList();
    }

    /** 生效中的 VIP（哪个账号、什么档位、到什么时候） */
    public List<ActiveVipDto> listActiveVips() {
        LocalDateTime now = LocalDateTime.now();
        Map<Integer, String> nameByLevel = vipPlanRepository.findAllByOrderByLevelAsc().stream()
                .collect(Collectors.toMap(VipPlan::getLevel, VipPlan::getName));
        return accountRepository
                .findByVipLevelGreaterThanAndVipExpiresAtAfterOrderByVipExpiresAtAsc(0, now).stream()
                .map(account -> new ActiveVipDto(account.getId(), account.getUsername(),
                        account.getVipLevel(),
                        nameByLevel.getOrDefault(account.getVipLevel(), "VIP " + account.getVipLevel()),
                        account.getVipExpiresAt()))
                .toList();
    }

    /** 允许把效果调整到的最远未来（防止误传一个离谱的时间把效果"永久化"） */
    private static final java.time.Duration MAX_EFFECT_HORIZON = java.time.Duration.ofDays(3650);

    private void ensureExpirySane(LocalDateTime expiresAt) {
        if (expiresAt != null && expiresAt.isAfter(LocalDateTime.now().plus(MAX_EFFECT_HORIZON))) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "到期时间最多只能设置到 10 年后");
        }
    }

    /**
     * 调整置顶到期时间；传 null 表示**立即撤销**。
     * 必须同时改写 room.top_expires_at 这个冗余排序列，否则大厅还会当它置顶。
     */
    @Transactional
    public void updateTopCardExpiry(Long adminId, Long roomId, LocalDateTime expiresAt) {
        ensureExpirySane(expiresAt);
        RoomTopCard card = roomTopCardRepository.findById(roomId)
                .orElseThrow(() -> new BizException(ErrorCode.ROOM_NOT_FOUND));
        if (expiresAt == null) {
            roomTopCardRepository.delete(card);
            roomRepository.findByIdAndDeletedAtIsNull(roomId)
                    .ifPresent(room -> room.setTopExpiresAt(null));
            auditService.record(null, AuditAction.SHOP_ITEM_UPDATE,
                    "管理员#" + adminId + " 撤销房间#" + roomId + " 的置顶", true);
        } else {
            card.overrideExpiry(expiresAt);
            roomRepository.findByIdAndDeletedAtIsNull(roomId)
                    .ifPresent(room -> room.setTopExpiresAt(expiresAt));
            auditService.record(null, AuditAction.SHOP_ITEM_UPDATE,
                    "管理员#" + adminId + " 房间#" + roomId + " 置顶改为 " + expiresAt.toLocalDate() + " 到期", true);
        }
    }

    /** 调整 VIP 到期时间；传 null 表示**立即撤销**（档位与到期一起清掉） */
    @Transactional
    public void updateVipExpiry(Long adminId, Long accountId, LocalDateTime expiresAt) {
        ensureExpirySane(expiresAt);
        Account account = accountRepository.findByIdAndDeletedAtIsNull(accountId)
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHENTICATED));
        if (expiresAt == null) {
            account.revokeVip();
        } else {
            account.setVipExpiry(expiresAt);
        }
        auditService.record(null, AuditAction.SHOP_ITEM_UPDATE,
                "管理员#" + adminId + " 账号「" + account.getUsername() + "」VIP "
                        + (expiresAt == null ? "已撤销" : "改为 " + expiresAt.toLocalDate() + " 到期"), true);
    }

    /** 与购买时的解释保持一致：null 或 <=0 都是永久 */
    private String describeDays(Integer days) {
        return days == null || days <= 0 ? "永久" : days + " 天";
    }

    private AdminShopItemDto toItemDto(ShopItem item) {
        return new AdminShopItemDto(item.getId(), item.getName(), item.getCategory(),
                item.getPriceCoins(), item.getDescription(), item.getIconUrl(), item.getEffectKind(),
                item.getDurationDays(), item.getVipLevel(), item.isEnabled());
    }

    private AdminVipPlanDto toPlanDto(VipPlan plan) {
        return new AdminVipPlanDto(plan.getLevel(), plan.getName(), plan.getPriceCoins(),
                plan.getDescription(), plan.getPackageMaxMb(), plan.getBorderUrl(),
                plan.getIconUrl(), plan.getDurationDays());
    }
}
