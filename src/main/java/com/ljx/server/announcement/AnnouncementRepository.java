package com.ljx.server.announcement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    /** 玩家端：只取启用中的，按 sort_order 升序（同序按创建顺序） */
    List<Announcement> findByEnabledTrueOrderBySortOrderAscIdAsc();

    /** 后台：全量，含已关闭的 */
    List<Announcement> findAllByOrderBySortOrderAscIdAsc();
}
