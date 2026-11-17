package com.superskidder.kylinops.backend.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DashboardServiceTest {

    @Test
    void shouldWarnWhenRedisStreamCapacityThresholdsAreExceeded() {
        DashboardService service = dashboardService(10, 100, 1);

        assertThat(service.redisCapacityStatus(0, 0, 0, 0, 0)).isEqualTo("UP");
        assertThat(service.redisCapacityStatus(0, 10, 0, 0, 0)).isEqualTo("WARN");
        assertThat(service.redisCapacityStatus(0, 100, 0, 0, 0)).isEqualTo("CRITICAL");
        assertThat(service.redisCapacityStatus(0, 0, 0, 0, 1)).isEqualTo("CRITICAL");
        assertThat(service.redisCapacityStatus(1, 0, 0, 0, 0)).isEqualTo("CRITICAL");
    }

    @Test
    void shouldExposeRedisAlertRulesForExternalAlerting() {
        DashboardService service = dashboardService(10, 100, 1);

        var rules = service.redisAlertRules(1, 42, 0);

        // outbox_event 表随迁移 V9 下线后，redis_outbox_failed 规则一并移除：
        // 没有消费者的表算不出"投递失败"，留着只会让告警看起来有覆盖。
        assertThat(rules).extracting(ScreenDashboardResponse.RedisAlertRule::name)
                .containsExactly(
                        "redis_pending_warn",
                        "redis_pending_critical",
                        "redis_dead_letter",
                        "redis_consumer_failure"
                );
        assertThat(rules)
                .filteredOn(ScreenDashboardResponse.RedisAlertRule::triggered)
                .extracting(ScreenDashboardResponse.RedisAlertRule::name)
                .containsExactly("redis_pending_warn", "redis_consumer_failure");
        assertThat(rules)
                .filteredOn(rule -> rule.name().equals("redis_pending_critical"))
                .singleElement()
                .satisfies(rule -> {
                    assertThat(rule.severity()).isEqualTo("CRITICAL");
                    assertThat(rule.threshold()).isEqualTo(100);
                    assertThat(rule.currentValue()).isEqualTo(42);
                });
    }

    private DashboardService dashboardService(long pendingThreshold, long pendingCriticalThreshold, long deadLetterThreshold) {
        return new DashboardService(
                null,
                null,
                null,
                null,
                pendingThreshold,
                pendingCriticalThreshold,
                deadLetterThreshold
        );
    }
}
