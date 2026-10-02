package com.ljx.server.commerce.repository;

import com.ljx.server.commerce.entity.VipPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VipPlanRepository extends JpaRepository<VipPlan, Integer> {

    List<VipPlan> findAllByOrderByLevelAsc();
}