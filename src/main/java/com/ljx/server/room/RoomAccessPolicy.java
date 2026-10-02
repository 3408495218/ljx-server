package com.ljx.server.room;

import com.ljx.server.auth.entity.Account;
import com.ljx.server.auth.entity.EmailBinding;
import com.ljx.server.auth.repository.AccountRepository;
import com.ljx.server.auth.repository.EmailBindingRepository;
import com.ljx.server.common.api.ErrorCode;
import com.ljx.server.common.exception.BizException;
import com.ljx.server.room.entity.Room;
import org.springframework.stereotype.Component;

/**
 * 房间准入校验：抽取成独立组件，避免 RoomService ↔ MemberService 循环依赖。
 * <p>
 * 口径：房主自己建的房不受上锁 / 在线 / 邮箱门槛限制；其余账号进入房间（或"进入房间"页）时校验。
 */
@Component
public class RoomAccessPolicy {

    private final EmailBindingRepository emailBindingRepository;
    private final AccountRepository accountRepository;

    public RoomAccessPolicy(EmailBindingRepository emailBindingRepository,
                            AccountRepository accountRepository) {
        this.emailBindingRepository = emailBindingRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * 是否「游客」：未登录（accountId 为空）或匿名账号（未登录时自动创建的访客身份）。
     * <p>
     * 注意：这里**不能**用"有没有绑邮箱"来近似判断游客 —— 那样会让「禁止游客」
     * 与「需要邮箱」变成同一个开关，房主会困惑两者到底有什么区别。
     */
    private boolean isGuest(Long accountId) {
        if (accountId == null) {
            return true;
        }
        return accountRepository.findByIdAndDeletedAtIsNull(accountId)
                .map(Account::isAnonymous)
                .orElse(true);
    }

    /** 房间的两个准入开关，语义互相独立 */
    private void checkRoomGates(Room room, Long accountId) {
        if (room.isNoGuest() && isGuest(accountId)) {
            // 禁止游客：只有正式注册账号能进
            throw new BizException(ErrorCode.ROOM_NO_GUEST);
        }
        if (room.isNeedEmail()) {
            // 需要邮箱：不论是不是游客，都得先绑定邮箱（防小号/骚扰）
            requireVerifiedEmail(accountId, ErrorCode.ROOM_EMAIL_REQUIRED);
        }
    }

    /** 上锁（1303）/ 在线（1304）/ 邮箱门槛（1305）；房主直接放行 */
    public void checkJoinable(Room room, Long accountId) {
        if (room.getOwnerId().equals(accountId)) {
            return;
        }
        if (room.isLocked()) {
            throw new BizException(ErrorCode.ROOM_LOCKED);
        }
        if (!room.isOnline()) {
            throw new BizException(ErrorCode.ROOM_OFFLINE);
        }
        checkRoomGates(room, accountId);
    }

    /**
     * 「进入房间」的校验：只查上锁（1303）与邮箱门槛（1305），**不查房间是否在线**。
     * <p>
     * 房间离线只意味着"服务端没在跑、暂时玩不了"，不等于"不能进房间等"：
     * 玩家进入房间页就该计入房间人数（这也是产品口径：只要有人进房间，人数就实时+1）。
     * 在线校验（1304）只属于真正要连服务端的 {@code POST /api/rooms/{id}/join}。
     */
    public void checkEnterable(Room room, Long accountId) {
        if (room.getOwnerId().equals(accountId)) {
            return;
        }
        if (room.isLocked()) {
            throw new BizException(ErrorCode.ROOM_LOCKED);
        }
        checkRoomGates(room, accountId);
    }

    /** 邮箱绑定门槛；不同入口错误码不同（建房 1306 / 进房 1305） */
    public void requireVerifiedEmail(Long accountId, ErrorCode errorCode) {
        boolean verified = emailBindingRepository.findById(accountId)
                .filter(EmailBinding::isVerified)
                .isPresent();
        if (!verified) {
            throw new BizException(errorCode);
        }
    }
}
