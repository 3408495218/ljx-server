package com.ljx.server.cdk;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CdkRedeemRepository extends JpaRepository<CdkRedeem, Long> {

    /** 同账号同码是否已领过（即使该码不限次也不允许同人重复领） */
    boolean existsByCdkIdAndAccountId(Long cdkId, Long accountId);

    List<CdkRedeem> findByCdkIdOrderByIdAsc(Long cdkId);

    /** 有兑换记录的码不允许删除，保留追溯 */
    boolean existsByCdkId(Long cdkId);
}
