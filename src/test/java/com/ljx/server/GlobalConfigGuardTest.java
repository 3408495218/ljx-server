package com.ljx.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * 全局配置守卫（供会改动全局配置的测试类继承）。
 * <p>
 * 背景：所有 {@code @SpringBootTest} 共用同一个 H2 内存库（{@code jdbc:h2:mem:ljx}，上下文还会被缓存），
 * 而 {@code app_config} / {@code level_config} 是**全局单行数据**。只要有一个用例改了它们没复原，
 * 后面所有用例看到的初值就变了 —— 结论不可复现（真实踩过：{@code daily_login_exp} 跑完一轮停在 5，初值是 1）。
 * <p>
 * 做法：**每个用例开始前拍一次快照、结束后整表还原**。
 * 快照在还原后会清空，所以下一个用例会重新拍 —— 即每个用例都从"干净起点"开始，
 * 用例内部的修改不会外溢。
 */
public abstract class GlobalConfigGuardTest {

    /**
     * 需要保护的全局数据。共同点：都是**后台可改的全局配置/目录**，且都被测试改动过。
     * 顺序按依赖关系排列（被引用的表先插回）。
     */
    private static final List<String> GUARDED_TABLES =
            List.of("level_config", "vip_plan", "shop_item", "app_config");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final Map<String, List<Map<String, Object>>> snapshots = new java.util.LinkedHashMap<>();

    @BeforeEach
    void takeGlobalConfigSnapshot() {
        snapshots.clear();
        for (String table : GUARDED_TABLES) {
            snapshots.put(table, jdbcTemplate.queryForList("select * from " + table));
        }
    }

    @AfterEach
    void restoreGlobalConfig() {
        for (String table : GUARDED_TABLES) {
            restore(table, snapshots.get(table));
        }
        snapshots.clear();
    }

    /**
     * 整表还原：先清空再按快照插回。
     * 用「清空 + 插回」而不是「逐行比对」，是因为用例可能新增或删除配置行（例如新增等级档）。
     */
    private void restore(String table, List<Map<String, Object>> snapshot) {
        if (snapshot == null) {
            return;
        }
        try {
            jdbcTemplate.update("delete from " + table);
            for (Map<String, Object> row : snapshot) {
                StringBuilder columns = new StringBuilder();
                StringBuilder placeholders = new StringBuilder();
                Object[] values = new Object[row.size()];
                int index = 0;
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    if (index > 0) {
                        columns.append(", ");
                        placeholders.append(", ");
                    }
                    columns.append(entry.getKey());
                    placeholders.append("?");
                    values[index++] = entry.getValue();
                }
                jdbcTemplate.update("insert into " + table + " (" + columns + ") values (" + placeholders + ")",
                        values);
            }
        } catch (RuntimeException e) {
            // 还原失败不该让用例本身失败，但要留痕（否则会静默污染后续用例）
            System.err.println("[GlobalConfigGuardTest] 还原全局配置表 " + table + " 失败：" + e.getMessage());
        }
    }

    /** 便于子类断言"配置没被改脏" */
    protected int currentDailyLoginExp() {
        Integer value = jdbcTemplate.queryForObject(
                "select config_value from app_config where config_key = 'daily_login_exp'", Integer.class);
        return value == null ? 0 : value;
    }

    /** 供子类确认守卫覆盖了哪些表 */
    protected List<String> guardedTables() {
        return GUARDED_TABLES;
    }
}
