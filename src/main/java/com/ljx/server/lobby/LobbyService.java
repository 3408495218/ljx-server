package com.ljx.server.lobby;

import com.ljx.server.auth.entity.Account;
import com.ljx.server.auth.repository.AccountRepository;
import com.ljx.server.commerce.entity.VipPlan;
import com.ljx.server.commerce.repository.VipPlanRepository;
import com.ljx.server.common.api.PageResult;
import com.ljx.server.room.MemberService;
import com.ljx.server.room.dto.RoomDtos.RoomSummaryDto;
import com.ljx.server.room.entity.Room;
import com.ljx.server.room.repository.RoomRepository;
import com.ljx.server.social.repository.JoinRecordRepository;
import com.ljx.server.social.repository.RoomFavoriteRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class LobbyService {

    private static final int MAX_PAGE_SIZE = 100;

    private final RoomRepository roomRepository;
    private final VipPlanRepository vipPlanRepository;
    private final AccountRepository accountRepository;
    private final RoomFavoriteRepository favoriteRepository;
    private final JoinRecordRepository joinRecordRepository;
    private final MemberService memberService;

    public LobbyService(RoomRepository roomRepository,
                        AccountRepository accountRepository,
                        RoomFavoriteRepository favoriteRepository,
                        JoinRecordRepository joinRecordRepository,
                        MemberService memberService,
                        VipPlanRepository vipPlanRepository) {
        this.roomRepository = roomRepository;
        this.accountRepository = accountRepository;
        this.favoriteRepository = favoriteRepository;
        this.joinRecordRepository = joinRecordRepository;
        this.memberService = memberService;
        this.vipPlanRepository = vipPlanRepository;
    }

    /**
     * 大厅列表：view=all 全部 / favorite 收藏 / history 足迹，叠加核心、版本、关键词筛选。
     * 排序：人数降序、其次新建优先（在线优先排序留待后续按产品确认）。
     */
    public PageResult<RoomSummaryDto> list(Long accountId, String view, String core, String mcVersion,
                                           String mode, String q, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

        String scope = view == null ? "all" : view;
        // 未登录（accountId == null）时，"收藏/足迹"没有依据，直接返回空列表；
        // "全部"视图不依赖身份，照常返回。
        if (accountId == null && ("favorite".equals(scope) || "history".equals(scope))) {
            return new PageResult<>(List.of(), safePage, 0L);
        }
        List<Long> scopeIds = switch (scope) {
            case "favorite" -> favoriteRepository.findRoomIdsByAccountId(accountId);
            case "history" -> joinRecordRepository.findRoomIdsByAccountIdOrderByLatest(accountId);
            default -> null;
        };
        if (scopeIds != null && scopeIds.isEmpty()) {
            return new PageResult<>(List.of(), safePage, 0L);
        }

        // 排序：置顶优先 → 人数多 → 新房间。
        // 置顶用 room.top_expires_at（room_top_card 的冗余列）——分页在数据库层做，
        // 若改成"先分页再在内存里把置顶房提前"，置顶房可能落在第 2 页，根本排不到前列。
        // 空值在 DESC 下排最后，所以过期的置顶记录必须被 TopCardScheduler 清成 null。
        Sort sort = Sort.by(Sort.Direction.DESC, "topExpiresAt")
                .and(Sort.by(Sort.Direction.DESC, "players"))
                .and(Sort.by(Sort.Direction.DESC, "id"));
        Page<Room> result = roomRepository.findAll(
                filter(scopeIds, core, mcVersion, mode, q), PageRequest.of(safePage - 1, safeSize, sort));

        Map<Long, Integer> vipByOwner = vipLevels(result.getContent());
        // 档位 → 房间边框资源键；前端按档位换卡片外框
        Map<Integer, String> borderByLevel = vipPlanRepository.findAllByOrderByLevelAsc().stream()
                .collect(Collectors.toMap(VipPlan::getLevel,
                        plan -> plan.getBorderUrl() == null ? "" : plan.getBorderUrl()));
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        // 人数以成员表实时统计为准（排序仍用 room.players 物化值，避免 join 排序的复杂度）
        Map<Long, Integer> memberCounts = memberService.countByRoomIds(
                result.getContent().stream().map(Room::getId).toList());
        List<RoomSummaryDto> items = result.getContent().stream()
                .map(room -> {
                    int vip = vipByOwner.getOrDefault(room.getOwnerId(), 0);
                    return new RoomSummaryDto(room.getId(), room.getName(), room.getCoverUrl(),
                            memberCounts.getOrDefault(room.getId(), 0), room.getCapacity(),
                            vip, borderByLevel.getOrDefault(vip, ""),
                            // 置顶看冗余列：过期由 TopCardScheduler 清成 null，这里再判一次防止清理延迟
                            room.getTopExpiresAt() != null && room.getTopExpiresAt().isAfter(now),
                            room.isOnline(), room.getCore(), room.getMcVersion(), room.getMode());
                })
                .toList();
        return new PageResult<>(items, safePage, result.getTotalElements());
    }

    /** 搜索关键字长度上限：超长关键字会让 like 扫全表，没有实际价值 */
    private static final int MAX_KEYWORD_LENGTH = 50;

    /** like 的转义字符（与下面 cb.like 的第三个参数保持一致） */
    private static final char LIKE_ESCAPE = '\\';

    /**
     * 转义 like 通配符：用户输入里的 % 与 _ 是字面字符。
     * 不转义时搜一个 % 会命中全部房间（Criteria 本身是参数化的，没有注入风险，纯粹是语义问题）。
     */
    private static String escapeLike(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (char c : raw.toCharArray()) {
            if (c == LIKE_ESCAPE || c == '%' || c == '_') {
                sb.append(LIKE_ESCAPE);
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private Specification<Room> filter(List<Long> scopeIds, String core, String mcVersion, String mode, String q) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isNull(root.get("deletedAt")));
            if (scopeIds != null) {
                predicates.add(root.get("id").in(scopeIds));
            }
            if (core != null && !core.isBlank()) {
                predicates.add(cb.equal(root.get("core"), core.trim()));
            }
            if (mcVersion != null && !mcVersion.isBlank()) {
                predicates.add(cb.equal(root.get("mcVersion"), mcVersion.trim()));
            }
            if (mode != null && !mode.isBlank()) {
                predicates.add(cb.equal(root.get("mode"), mode.trim()));
            }
            if (q != null && !q.isBlank()) {
                String keyword = q.trim();
                if (keyword.length() > MAX_KEYWORD_LENGTH) {
                    keyword = keyword.substring(0, MAX_KEYWORD_LENGTH);
                }
                predicates.add(cb.like(root.get("name"),
                        "%" + escapeLike(keyword) + "%", LIKE_ESCAPE));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * 房主的**生效** VIP 档位。
     * <p>
     * 必须判过期：VIP 到期后 {@code account.vip_level} 不会自动归零（读时判断的设计），
     * 这里若直接取该字段，过期的钻石 VIP 会一直给别人展示钻石边框。
     * 口径与 {@code CommerceService.effectiveVipLevel} 保持一致：过期即按普通用户。
     */
    private Map<Long, Integer> vipLevels(List<Room> rooms) {
        Set<Long> ownerIds = rooms.stream().map(Room::getOwnerId).collect(Collectors.toSet());
        if (ownerIds.isEmpty()) {
            return Map.of();
        }
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        return accountRepository.findAllById(ownerIds).stream()
                .collect(Collectors.toMap(Account::getId, account -> {
                    if (account.getVipLevel() <= 0) {
                        return 0;
                    }
                    java.time.LocalDateTime expiresAt = account.getVipExpiresAt();
                    return expiresAt != null && expiresAt.isAfter(now) ? account.getVipLevel() : 0;
                }));
    }
}