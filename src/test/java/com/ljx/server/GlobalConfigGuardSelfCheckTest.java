package com.ljx.server;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守卫自身的验证：**前一个用例故意把全局配置改脏，后一个用例必须看到干净初值**。
 * <p>
 * 用例顺序由 {@code @Order} 固定，所以这条断言能真实反映守卫是否生效 ——
 * 若守卫失效，第二个用例会读到 999 而失败。
 * <p>
 * 之所以需要它：所有 {@code @SpringBootTest} 共用一个 H2 内存库，
 * 而 {@code app_config} 是全局单行数据，任何用例改脏都会影响后续用例的结论。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GlobalConfigGuardSelfCheckTest extends GlobalConfigGuardTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @Order(1)
    void firstCaseDirtyTheGlobalConfig() {
        jdbcTemplate.update(
                "update app_config set config_value = '999' where config_key = 'daily_login_exp'");
        assertThat(currentDailyLoginExp())
                .as("本用例自己把配置改成了 999")
                .isEqualTo(999);
    }

    @Test
    @Order(2)
    void secondCaseSeesCleanValue() {
        // 关键断言：上一个用例把配置改成了 999，但守卫应在每个用例后还原
        assertThat(currentDailyLoginExp())
                .as("上一个用例改脏的配置必须被守卫还原，否则后续用例的结论不可复现")
                .isNotEqualTo(999);
        assertThat(currentDailyLoginExp())
                .as("应回到迁移写入的初值 1")
                .isEqualTo(1);
    }

    @Test
    @Order(3)
    void guardCoversLevelAndShopTablesToo() {
        assertThat(guardedTables())
                .as("被保护的全局表")
                .contains("app_config", "level_config", "vip_plan", "shop_item");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from level_config where level = 0", Integer.class))
                .as("等级配置也应在守卫范围内").isEqualTo(1);
    }
}
