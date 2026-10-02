package com.ljx.server.auth.repository;

import com.ljx.server.auth.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    /** 生效中的 VIP 账号（后台"生效中的效果"用） */
    List<com.ljx.server.auth.entity.Account> findByVipLevelGreaterThanAndVipExpiresAtAfterOrderByVipExpiresAtAsc(
            int vipLevel, java.time.LocalDateTime now);

    Optional<Account> findByUsernameAndDeletedAtIsNull(String username);

    Optional<Account> findByIdAndDeletedAtIsNull(Long id);

    boolean existsByUsernameAndDeletedAtIsNull(String username);
}
