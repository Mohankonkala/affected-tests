package io.affectedtests.core;

import io.affectedtests.core.AffectedTestsEngine.DiscoveryWorkItem;
import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.config.Mode;
import io.affectedtests.core.discovery.DiscoveryProfile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the engine's strategy-exception propagation contract, exercising
 * {@link AffectedTestsEngine#runDiscovery} directly with synthetic
 * {@link DiscoveryWorkItem}s rather than corrupting a real project
 * layout. The contract:
 *
 * <ol>
 *   <li>A {@link RuntimeException} thrown by a strategy is re-thrown
 *       unchanged (same instance, same message, same stack) — no
 *       wrapping in {@code j.u.c.ExecutionException} or
 *       {@code RuntimeException("future failed: …")}.</li>
 *   <li>A checked exception is wrapped in a {@link RuntimeException}
 *       whose message names the strategy that threw, in BOTH parallel
 *       and serial paths — adopters debugging a corrupt config should
 *       not have to read the stack trace to learn which strategy is
 *       broken.</li>
 *   <li>If multiple strategies throw on the parallel path, every
 *       failure surfaces — the first-failure wins the top of the
 *       stack and subsequent failures attach as {@code suppressed}.
 *       Without this, an adopter with two simultaneous bugs only
 *       sees the first one and would have to fix it before the
 *       second became visible.</li>
 *   <li>An {@link Error} (e.g. {@code OutOfMemoryError}) propagates
 *       immediately — Errors aren't recoverable so we surface them
 *       even on the parallel path's accumulator branch.</li>
 * </ol>
 *
 * <p>Tests use the package-private {@link
 * AffectedTestsEngine#runDiscovery} entry point directly. That keeps
 * the test focused on the propagation contract instead of needing a
 * real diff to drive the engine end-to-end.
 */
class AffectedTestsEngineErrorPropagationTest {

    private static AffectedTestsConfig configWithParallel(boolean parallel) {
        return AffectedTestsConfig.builder()
                .baseRef("HEAD")
                .mode(Mode.LOCAL)
                .parallelDiscovery(parallel)
                .build();
    }

    @Test
    void parallelPathReThrowsRuntimeExceptionUnchanged() {
        IllegalStateException thrown = new IllegalStateException("boom-from-strategy");
        List<DiscoveryWorkItem> items = List.of(
                workThatReturns("naming", Set.of("a.NamingTest")),
                workThatThrows("usage", thrown)
        );

        IllegalStateException re = assertThrows(IllegalStateException.class,
                () -> AffectedTestsEngine.runDiscovery(items,
                        new LinkedHashSet<>(), configWithParallel(true)));
        assertSame(thrown, re,
                "RuntimeException must propagate unchanged — adopters reading the "
                        + "stack should see the original exception, not a "
                        + "RuntimeException(cause) wrapper");
    }

    @Test
    void serialPathReThrowsRuntimeExceptionUnchanged() {
        IllegalStateException thrown = new IllegalStateException("boom-from-strategy");
        List<DiscoveryWorkItem> items = List.of(
                workThatReturns("naming", Set.of("a.NamingTest")),
                workThatThrows("usage", thrown)
        );

        IllegalStateException re = assertThrows(IllegalStateException.class,
                () -> AffectedTestsEngine.runDiscovery(items,
                        new LinkedHashSet<>(), configWithParallel(false)));
        assertSame(thrown, re,
                "Serial path must propagate RuntimeExceptions unchanged so the "
                        + "two paths show identical stacks to adopters");
    }

    @Test
    void parallelPathWrapsCheckedExceptionWithStrategyName() {
        IOException checked = new IOException("disk-failed");
        List<DiscoveryWorkItem> items = List.of(
                workThatReturns("naming", Set.of("a.NamingTest")),
                workThatThrows("transitive", checked)
        );

        RuntimeException re = assertThrows(RuntimeException.class,
                () -> AffectedTestsEngine.runDiscovery(items,
                        new LinkedHashSet<>(), configWithParallel(true)));
        assertNotNull(re.getMessage());
        assertTrue(re.getMessage().contains("transitive"),
                "Parallel-path checked-exception wrap must name the strategy "
                        + "(message was: " + re.getMessage() + ")");
        assertSame(checked, re.getCause(),
                "Checked exception must be the cause so adopters see the "
                        + "original stack");
    }

    @Test
    void serialPathWrapsCheckedExceptionWithStrategyName() {
        IOException checked = new IOException("disk-failed");
        List<DiscoveryWorkItem> items = List.of(
                workThatReturns("naming", Set.of("a.NamingTest")),
                workThatThrows("transitive", checked)
        );

        RuntimeException re = assertThrows(RuntimeException.class,
                () -> AffectedTestsEngine.runDiscovery(items,
                        new LinkedHashSet<>(), configWithParallel(false)));
        assertTrue(re.getMessage().contains("transitive"),
                "Serial-path checked-exception wrap must name the strategy too "
                        + "— adopters comparing serial vs parallel debug runs "
                        + "should see the same diagnostic shape (message was: "
                        + re.getMessage() + ")");
        assertSame(checked, re.getCause());
    }

    /**
     * Two simultaneous failures on the parallel path must both surface
     * — the first wins the top of the stack and the second attaches
     * as {@code suppressed}. We can't pin which strategy "wins"
     * because future ordering depends on thread scheduling, but we
     * CAN pin that BOTH failure messages reach the operator.
     */
    @Test
    void parallelPathSurfacesEverySimultaneousFailure() {
        IllegalStateException firstFailure = new IllegalStateException("first-bad-strategy");
        IllegalArgumentException secondFailure = new IllegalArgumentException("second-bad-strategy");
        List<DiscoveryWorkItem> items = List.of(
                workThatThrows("naming", firstFailure),
                workThatReturns("usage", Set.of("a.UsageTest")),
                workThatThrows("transitive", secondFailure)
        );

        RuntimeException top = assertThrows(RuntimeException.class,
                () -> AffectedTestsEngine.runDiscovery(items,
                        new LinkedHashSet<>(), configWithParallel(true)));

        Set<Throwable> seen = new LinkedHashSet<>();
        seen.add(top);
        for (Throwable suppressed : top.getSuppressed()) {
            seen.add(suppressed);
        }

        assertTrue(seen.contains(firstFailure),
                "First failure must surface (top or suppressed)");
        assertTrue(seen.contains(secondFailure),
                "Second simultaneous failure must surface as suppressed — "
                        + "swallowing it would force adopters to fix the "
                        + "first bug before the second one becomes visible");
    }

    @Test
    void errorsPropagateImmediatelyOnParallelPath() {
        OutOfMemoryError oom = new OutOfMemoryError("synthetic-oom");
        List<DiscoveryWorkItem> items = List.of(
                workThatReturns("naming", Set.of("a.NamingTest")),
                workThatThrowsError("usage", oom)
        );

        OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class,
                () -> AffectedTestsEngine.runDiscovery(items,
                        new LinkedHashSet<>(), configWithParallel(true)));
        assertSame(oom, thrown, "Errors must propagate unchanged");
    }

    @Test
    void successfulParallelRunReturnsCompleteProfile() {
        AtomicInteger namingCalls = new AtomicInteger();
        AtomicInteger usageCalls = new AtomicInteger();
        Set<String> candidate = new LinkedHashSet<>();
        DiscoveryProfile profile = AffectedTestsEngine.runDiscovery(
                List.of(
                        new DiscoveryWorkItem("naming", () -> {
                            namingCalls.incrementAndGet();
                            return Set.of("a.NamingTest");
                        }),
                        new DiscoveryWorkItem("usage", () -> {
                            usageCalls.incrementAndGet();
                            return Set.of("a.UsageTest");
                        })),
                candidate, configWithParallel(true));

        assertEquals(1, namingCalls.get(), "naming work item must run exactly once");
        assertEquals(1, usageCalls.get(), "usage work item must run exactly once");
        assertEquals(Set.of("a.NamingTest", "a.UsageTest"), candidate);
        assertTrue(profile.parallelEnabled() || profile.concurrencyLevel() == 0,
                "Either parallel ran (parallelEnabled=true) or we collapsed "
                        + "to serial on a 1-vCPU host (concurrencyLevel=0)");
    }

    private static DiscoveryWorkItem workThatReturns(String name, Set<String> result) {
        return new DiscoveryWorkItem(name, () -> result);
    }

    private static DiscoveryWorkItem workThatThrows(String name, Exception toThrow) {
        return new DiscoveryWorkItem(name, () -> { throw toThrow; });
    }

    private static DiscoveryWorkItem workThatThrowsError(String name, Error toThrow) {
        return new DiscoveryWorkItem(name, () -> { throw toThrow; });
    }
}
