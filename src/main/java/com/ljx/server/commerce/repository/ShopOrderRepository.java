package com.ljx.server.commerce.repository;

import com.ljx.server.commerce.entity.ShopOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShopOrderRepository extends JpaRepository<ShopOrder, Long> {

    List<ShopOrder> findByAccountIdOrderByIdDesc(Long accountId);
}
