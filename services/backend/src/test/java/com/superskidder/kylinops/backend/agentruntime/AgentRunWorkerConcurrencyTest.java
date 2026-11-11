package com.superskidder.kylinops.backend.agentruntime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRuntimeStore;
import com.superskidder.kylinops.backend.agentruntime.persistence.LeasedAgentRun;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 执行并发：poll() 一轮要把线程池空位填满，而不是只跑一条 run。
 *
 * <p>此前 poll() 在调度线程里同步跑完整个 ReAct 循环，全平台同一时刻只能执行一个 run；
 * 而 {@code AgentRuntimeStore.leaseNext()} 的 SQL 里早就写了租户并发配额子查询
 * （{@code tenant_runtime_quota.max_concurrent_runs}，默认 8），那段配额因此从未生效。
 * 这两条用例锁的是「租约与执行解耦」这个性质本身。
 */
class AgentRunWorkerConcurrencyTest {

    /** 覆写 execute()，只记录并发度，不跑真实 ReAct 循环（协作者全部传 null 即可）。 */
    private static final class ProbeWorker extends AgentRunWorker {

        private final CountDownLatch allStarted;
        private final CountDownLatch release;
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger peakConcurrency = new AtomicInteger();
        private final AtomicInteger executed = new AtomicInteger();

        private ProbeWorker(AgentRuntimeStore store, CountDownLatch allStarted, CountDownLatch release) {
            super(true, 5L, "R2", store,
                    null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null);
            this.allStarted = allStarted;
            this.release = release;
        }

        @Override
        public void execute(LeasedAgentRun run) {
            int now = inFlight.incrementAndGet();
            peakConcurrency.accumulateAndGet(now, Math::max);
            executed.incrementAndGet();
            allStarted.countDown();
            try {
                // 卡住直到主线程确认所有 run 都已同时在跑——若实现是串行的，这里会超时。
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
        }
    }

    private static LeasedAgentRun run(String id) {
        return new LeasedAgentRun(id, "org-alpha", "user-alpha", "inst-1", "objective",
                "CAUTIOUS", "{}", "{}", "{}", 0L, null, null);
    }

    @Test
    void pollDispatchesMultipleRunsConcurrently() throws Exception {
        AgentRuntimeStore store = mock(AgentRuntimeStore.class);
        when(store.leaseNext()).thenReturn(
                Optional.of(run("run-1")), Optional.of(run("run-2")), Optional.of(run("run-3")),
                Optional.empty());

        CountDownLatch allStarted = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        ProbeWorker worker = new ProbeWorker(store, allStarted, release);
        try {
            worker.poll();

            // 三条 run 必须能同时在跑；串行实现下第 2、3 条永远等不到 latch。
            assertThat(allStarted.await(5, TimeUnit.SECONDS))
                    .as("三条 run 应并发启动（串行实现会在此超时）")
                    .isTrue();
            assertThat(worker.peakConcurrency.get()).isGreaterThanOrEqualTo(3);
        } finally {
            release.countDown();
            worker.shutdown();
        }
    }

    /** poll() 必须及时返回，不能等 run 跑完——否则调度线程被长 run 占死。 */
    @Test
    void pollReturnsWithoutWaitingForRunCompletion() throws Exception {
        AgentRuntimeStore store = mock(AgentRuntimeStore.class);
        when(store.leaseNext()).thenReturn(Optional.of(run("run-slow")), Optional.empty());

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ProbeWorker worker = new ProbeWorker(store, started, release);
        try {
            long beganAt = System.nanoTime();
            worker.poll();
            long elapsedMillis = (System.nanoTime() - beganAt) / 1_000_000;

            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            // run 仍卡在 latch 上（最长 5s），poll() 却应已返回。
            assertThat(elapsedMillis)
                    .as("poll() 不应阻塞到 run 结束")
                    .isLessThan(2_000L);
        } finally {
            release.countDown();
            worker.shutdown();
        }
    }
}
