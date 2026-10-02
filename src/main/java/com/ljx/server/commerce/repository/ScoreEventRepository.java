package com.ljx.server.commerce.repository;

import com.ljx.server.commerce.entity.ScoreEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 经验流水仓储。
 * <p>
 * 只用于**留档与查询**：等级结算的唯一真源是 {@code level_config}（见 LevelService），
 * 不再由流水反推等级。原先服务于 DailyActiveScheduler 的两个查询已随该调度器一起删除
 * （「每日活跃 +2」与「每日登录 +1」会重复发经验）。
 */
public interface ScoreEventRepository extends JpaRepository<ScoreEvent, Long> {

    List<ScoreEvent> findByAccountIdOrderByIdDesc(Long accountId);
}
