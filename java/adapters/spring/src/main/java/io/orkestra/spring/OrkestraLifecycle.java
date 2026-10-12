package io.orkestra.spring;

import io.orkestra.core.Orchestrator;
import io.orkestra.core.execution.ShutdownReport;

import org.springframework.context.SmartLifecycle;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Spring lifecycle bridge. Configure ingress at a higher phase to stop requests first. */
public final class OrkestraLifecycle implements SmartLifecycle {
    private final Orchestrator orchestrator;
    private final Duration grace;
    private final int phase;
    private volatile boolean running;
    private volatile boolean stopped;
    private volatile ShutdownReport report;
    public OrkestraLifecycle(Orchestrator orchestrator, Duration grace) { this(orchestrator, grace, 0); }
    public OrkestraLifecycle(Orchestrator orchestrator, Duration grace, int phase) {
        if (Objects.requireNonNull(grace).isNegative()) throw new IllegalArgumentException("Negative grace period");
        this.orchestrator = Objects.requireNonNull(orchestrator); this.grace = grace; this.phase = phase;
    }
    @Override public synchronized void start() {
        if (stopped) throw new IllegalStateException("A stopped Orchestrator cannot be restarted");
        running = true;
    }
    @Override public synchronized void stop() {
        if (stopped) return;
        stopped = true;
        try { report = orchestrator.shutdown(grace); }
        finally { running = false; }
    }
    @Override public void stop(Runnable callback) { try { stop(); } finally { callback.run(); } }
    @Override public boolean isRunning() { return running; }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return phase; }
    public Optional<ShutdownReport> report() { return Optional.ofNullable(report); }
}
