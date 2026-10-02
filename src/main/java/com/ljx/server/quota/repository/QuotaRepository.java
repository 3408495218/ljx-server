package com.ljx.server.quota.repository;

import com.ljx.server.quota.QuotaType;
import com.ljx.server.quota.entity.Quota;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface QuotaRepository extends JpaRepository<Quota, Long> {

    List<Quota> findByAccountId(Long accountId);

    Optional<Quota> findByAccountIdAndType(Long accountId, QuotaType type);

    /**
     * 加行锁读取配额，用于「先锁后算用量」的并发校验：
     * 同一账号同一配额的并发请求在此串行化，后到者能看到先到者已提交的用量。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from Quota q where q.accountId = :accountId and q.type = :type")
    Optional<Quota> findForUpdate(@Param("accountId") Long accountId, @Param("type") QuotaType type);
}
