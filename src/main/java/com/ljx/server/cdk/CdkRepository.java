package com.ljx.server.cdk;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CdkRepository extends JpaRepository<Cdk, Long> {

    Optional<Cdk> findByCode(String code);

    /**
     * 兑换取码：**加悲观写锁**，同一码的并发兑换在这里串行化，
     * 否则两个请求会同时读到"还有名额"而超领（与既有 QuotaRepository 同一套做法）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cdk c where c.code = :code")
    Optional<Cdk> findByCodeForUpdate(@Param("code") String code);

    List<Cdk> findAllByOrderByIdDesc();

    /** 生成时防重码：命中说明撞了，需要重新生成 */
    boolean existsByCode(String code);

    /**
     * 批次汇总（数据库侧聚合，避免把上千个码全查出来再在内存里分组）。
     * 返回列为：batchNo, 总数, 每码钻石, 每码次数, 已用次数合计, 最早创建时间, 最晚过期时间。
     */
    @Query("""
            select c.batchNo, count(c), max(c.coins), max(c.totalUses), sum(c.usedUses),
                   min(c.createdAt), max(c.expiresAt)
            from Cdk c
            where c.batchNo is not null
            group by c.batchNo
            order by min(c.createdAt) desc
            """)
    List<Object[]> summarizeBatches();

    /** 某批仍启用（未停用）的码数量 */
    long countByBatchNoAndEnabledTrue(String batchNo);

    /** 按批次取明细 */
    List<Cdk> findByBatchNoOrderByIdAsc(String batchNo);
}
