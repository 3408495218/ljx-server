package com.ljx.server.commerce.repository;

import com.ljx.server.commerce.entity.ShopItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShopItemRepository extends JpaRepository<ShopItem, Long> {

    List<ShopItem> findAllByOrderBySortOrderAscIdAsc();

    /** 玩家端只看到上架商品；后台可看全部 */
    List<ShopItem> findAllByEnabledTrueOrderBySortOrderAscIdAsc();
}