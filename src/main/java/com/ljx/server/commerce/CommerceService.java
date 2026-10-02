package com.ljx.server.commerce;

import com.ljx.server.auth.entity.Account;
import com.ljx.server.auth.repository.AccountRepository;
import com.ljx.server.common.api.ErrorCode;
import com.ljx.server.common.audit.AuditAction;
import com.ljx.server.common.audit.AuditService;
import com.ljx.server.common.exception.BizException;
import com.ljx.server.commerce.dto.CommerceDtos.PurchaseRequest;
import com.ljx.server.commerce.dto.CommerceDtos.PurchaseResponse;
import com.ljx.server.commerce.dto.CommerceDtos.ShopDto;
import com.ljx.server.commerce.dto.CommerceDtos.ShopItemDto;
import com.ljx.server.commerce.dto.CommerceDtos.VipDto;
import com.ljx.server.commerce.dto.CommerceDtos.VipPlanDto;
import com.ljx.server.commerce.entity.RoomTopCard;
import com.ljx.server.commerce.entity.ShopItem;
import com.ljx.server.commerce.entity.ShopOrder;
import com.ljx.server.commerce.entity.VipPlan;
import com.ljx.server.commerce.repository.RoomTopCardRepository;
import com.ljx.server.commerce.repository.ShopItemRepository;
import com.ljx.server.commerce.repository.ShopOrderRepository;
import com.ljx.server.commerce.repository.VipPlanRepository;
import com.ljx.server.room.entity.Room;
import com.ljx.server.room.repository.RoomRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 商城：档位/商品查询 + **购买**。
 * <p>
 * 商品与图标都由后端发放（前端不硬编码）；购买在一个事务里完成
 * 「**扣钻石 → 记订单 → 发效果**」，任一步失败都整体回滚。
 */
@Service
public class CommerceService {

    private final AccountRepository accountRepository;
    private final ShopItemRepository shopItemRepository;
    private final VipPlanRepository vipPlanRepository;
    private final RoomRepository roomRepository;
    private final RoomTopCardRepository roomTopCardRepository;
    private final ShopOrderRepository shopOrderRepository;
    private final AuditService auditService;

    public CommerceService(AccountRepository accountRepository,
                           ShopItemRepository shopItemRepository,
                           VipPlanRepository vipPlanRepository,
                           RoomRepository roomRepository,
                           RoomTopCardRepository roomTopCardRepository,
                           ShopOrderRepository shopOrderRepository,
                           AuditService auditService) {
        this.accountRepository = accountRepository;
        this.shopItemRepository = shopItemRepository;
        this.vipPlanRepository = vipPlanRepository;
        this.roomRepository = roomRepository;
        this.roomTopCardRepository = roomTopCardRepository;
        this.shopOrderRepository = shopOrderRepository;
        this.auditService = auditService;
    }

    /**
     * 购买商品。
     * <p>
     * 效果按 {@code effectKind} 分派：VIP → 账号档位；TOP_CARD → 指定房间的置顶记录
     * （**必须是自己名下的房间**，否则等于可以给别人刷效果）。
     */
    @Transactional
    public PurchaseResponse purchase(Long accountId, PurchaseRequest request) {
        ShopItem item = shopItemRepository.findById(request.itemId())
                .filter(ShopItem::isEnabled)
                .orElseThrow(() -> new BizException(ErrorCode.SHOP_ITEM_NOT_FOUND));
        Account account = accountRepository.findByIdAndDeletedAtIsNull(accountId)
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHENTICATED));

        Long targetRoomId = null;
        Integer vipLevel = null;
        // 置顶卡需要房间对象（购买成功后要回写 room.topExpiresAt），提升到 if 之外
        Room targetRoom = null;
        if ("TOP_CARD".equals(item.getEffectKind())) {
            if (request.roomId() == null) {
                throw new BizException(ErrorCode.ROOM_REQUIRED_FOR_ITEM);
            }
            targetRoom = roomRepository.findByIdAndDeletedAtIsNull(request.roomId())
                    .orElseThrow(() -> new BizException(ErrorCode.ROOM_NOT_FOUND));
            if (!targetRoom.getOwnerId().equals(accountId)) {
                throw new BizException(ErrorCode.NOT_ROOM_OWNER);
            }
            targetRoomId = targetRoom.getId();
        } else if ("VIP".equals(item.getEffectKind())) {
            // VIP 不通过商品购买（唯一来源是 vip_plan，见 purchaseVip）
            throw new BizException(ErrorCode.SHOP_ITEM_NOT_FOUND);
        }

        // 先扣费：余额不足会在这里抛异常，后面的效果与订单都不会发生
        account.deductCoins(item.getPriceCoins());

        LocalDateTime now = LocalDateTime.now();
        // 时长语义：null 或 <=0 都表示**永久**（后台「留空」会存成 0）
        LocalDateTime expiresAt = expiryFrom(item.getDurationDays());

        if (targetRoomId != null) {
            // lambda 只能捕获 effectively final 的变量，targetRoomId 上面被赋过值，这里转存一份
            Long cardRoomId = targetRoomId;
            RoomTopCard card = roomTopCardRepository.findById(cardRoomId)
                    .orElseGet(() -> new RoomTopCard(cardRoomId, expiresAt, now));
            if (card.getExpiresAt() != null) {
                card.extendBy(expiresAt, now);
            }
            roomTopCardRepository.save(card);
            // 同步房间上的冗余到期时间：大厅分页排序直接按它排（见 LobbyService 的排序注释）
            if (targetRoom != null) {
                targetRoom.setTopExpiresAt(card.getExpiresAt());
            }
        } else if (vipLevel != null) {
            account.grantVip(vipLevel, expiresAt);
        }

        shopOrderRepository.save(new ShopOrder(accountId, item.getId(), item.getName(),
                item.getPriceCoins(), item.getEffectKind(), targetRoomId, vipLevel, expiresAt, now));
        auditService.record(accountId, AuditAction.SHOP_PURCHASE,
                item.getName() + " -" + item.getPriceCoins() + " 钻石", true);

        return new PurchaseResponse(account.getCoins(), item.getName(),
                effectMessage(item, targetRoomId, expiresAt), expiresAt);
    }

    /** VIP 未配置时长时的兜底天数（后台可在档位上改） */
    private static final int DEFAULT_VIP_DURATION_DAYS = 30;

    /**
     * "永久"效果的到期时间。
     * <p>
     * 为什么不直接用 null 表示永久：
     * <ul>
     *   <li>{@code room_top_card.expires_at} 是 NOT NULL，null 直接插不进去；</li>
     *   <li>{@code account.vip_expires_at} 用 null 表示"从未购买"，若永久也用 null，
     *       {@code effectiveVipLevel} 会把永久 VIP 判成过期（这个 bug 在测试里抓到过）；</li>
     *   <li>大厅置顶排序是 {@code top_expires_at DESC}，远期时间天然排最前 —— 正是"永久"该有的位置。</li>
     * </ul>
     */
    private static final LocalDateTime PERPETUAL = LocalDateTime.of(9999, 12, 31, 23, 59, 59);

    /** 时长 -> 到期时间；null 或 <=0 视为永久（后台「留空」会存成 0） */
    private static LocalDateTime expiryFrom(Integer days) {
        LocalDateTime now = LocalDateTime.now();
        return days == null || days <= 0 ? PERPETUAL : now.plusDays(days);
    }

    /**
     * 购买 VIP 档位。
     * <p>
     * VIP 的**唯一来源是 vip_plan**（不在 shop_item 里重复定义），所以走独立入口而不是商品 id。
     * 效果只有一个可见表现：**账号的 VIP 档位变化 → 房间卡片边框随之改变**。
     */
    @Transactional
    public PurchaseResponse purchaseVip(Long accountId, int level) {
        VipPlan plan = vipPlanRepository.findById(level)
                .filter(one -> one.getLevel() > 0)
                .orElseThrow(() -> new BizException(ErrorCode.VIP_PLAN_NOT_FOUND));
        Account account = accountRepository.findByIdAndDeletedAtIsNull(accountId)
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHENTICATED));

        account.deductCoins(plan.getPriceCoins());

        LocalDateTime now = LocalDateTime.now();
        // 时长由后台在档位上设置；**null 或 <=0 都表示永久**（与商品时长同一口径）
        LocalDateTime expiresAt = expiryFrom(plan.getDurationDays());
        account.grantVip(plan.getLevel(), expiresAt);

        shopOrderRepository.save(new ShopOrder(accountId, null, plan.getName(),
                plan.getPriceCoins(), "VIP", null, plan.getLevel(), expiresAt, now));
        auditService.record(accountId, AuditAction.SHOP_PURCHASE,
                plan.getName() + " -" + plan.getPriceCoins() + " 钻石", true);

        return new PurchaseResponse(account.getCoins(), plan.getName(),
                "VIP 已生效（" + plan.getName() + "），至 " + expiresAt.toLocalDate() + " 到期", expiresAt);
    }

    /** 给前端一句能直接显示的结果说明 */
    private String effectMessage(ShopItem item, Long targetRoomId, LocalDateTime expiresAt) {
        String until = expiresAt == null ? "永久" : "至 " + expiresAt.toLocalDate();
        return switch (item.getEffectKind()) {
            case "TOP_CARD" -> "房间已置顶，" + until + "自动失效";
            case "VIP" -> "VIP 已生效（" + item.getName() + "），" + until + "到期";
            default -> "购买成功";
        };
    }

    @Transactional(readOnly = true)
    public ShopDto shop(Long accountId) {
        Account account = accountRepository.findByIdAndDeletedAtIsNull(accountId).orElse(null);
        int coins = account == null ? 0 : account.getCoins();
        int level = effectiveVipLevel(account);
        String vipName = vipPlanRepository.findById(level)
                .map(VipPlan::getName)
                .orElse("普通用户");
        // 道具商城**只发道具**：VIP 档位是 vip_plan 的事，不在商品列表里重复出现
        // （V11 曾在两边各建一份，导致商城里也冒出 VIP，已由 V13 下架）
        List<ShopItemDto> items = shopItemRepository.findAllByEnabledTrueOrderBySortOrderAscIdAsc().stream()
                .filter(item -> !"VIP".equals(item.getEffectKind()))
                .map(item -> new ShopItemDto(item.getId(), item.getName(), item.getCategory(),
                        item.getPriceCoins(), item.getDescription(), item.getIconUrl(),
                        item.getEffectKind(), item.getDurationDays(), item.getVipLevel()))
                .toList();
        return new ShopDto(coins, level, vipName,
                account == null ? null : account.getVipExpiresAt(), items);
    }

    /**
     * 生效 VIP 档位：**过期即按普通用户**处理。
     * 读时判断而不是靠定时任务清零，避免任务间隔内出现"已过期却仍享受权益"。
     */
    private int effectiveVipLevel(Account account) {
        if (account == null || account.getVipLevel() <= 0) {
            return 0;
        }
        LocalDateTime expiresAt = account.getVipExpiresAt();
        if (expiresAt == null || expiresAt.isBefore(LocalDateTime.now())) {
            return 0;
        }
        return account.getVipLevel();
    }

    @Transactional(readOnly = true)
    public VipDto vip(Long accountId) {
        Account account = accountRepository.findByIdAndDeletedAtIsNull(accountId).orElse(null);
        int level = effectiveVipLevel(account);
        int coins = account == null ? 0 : account.getCoins();
        List<VipPlanDto> plans = vipPlanRepository.findAllByOrderByLevelAsc().stream()
                .map(plan -> new VipPlanDto(plan.getLevel(), plan.getName(),
                        plan.getPriceCoins(), plan.getDescription(),
                        plan.getPackageMaxMb(), plan.getBorderUrl(), plan.getIconUrl(),
                        plan.getDurationDays()))
                .toList();
        String currentName = plans.stream()
                .filter(plan -> plan.level() == level)
                .findFirst()
                .map(VipPlanDto::name)
                .orElse("VIP " + level);
        return new VipDto(level, currentName, coins,
                account == null ? null : account.getVipExpiresAt(), plans);
    }
}