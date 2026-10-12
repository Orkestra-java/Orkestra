package io.orkestra.core.local.history;

import io.orkestra.core.spi.ExecutionEvent;
import io.orkestra.core.spi.HistoryStore;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Optional;

/** Bounded histories with TTL, explicit loss marking, and deletion. */
public final class MemoryHistory implements HistoryStore {
    private static final class Record {
        final long created = System.nanoTime();
        final ArrayList<ExecutionEvent> events = new ArrayList<>();
        boolean incomplete;
    }
    private final int executions;
    private final int events;
    private final long ttl;
    private final LinkedHashMap<String, Record> records = new LinkedHashMap<>();
    public MemoryHistory(int executions, int events, Duration ttl) {
        if (executions < 1 || events < 1 || ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("Invalid history limits");
        this.executions = executions; this.events = events; this.ttl = ttl.toNanos();
    }
    private void expire() { records.values().removeIf(record -> System.nanoTime() - record.created >= ttl); }
    private Record record(String id) {
        expire();
        var record = records.computeIfAbsent(id, ignored -> new Record());
        while (records.size() > executions) records.remove(records.keySet().iterator().next());
        return record;
    }
    @Override public synchronized void onEvent(ExecutionEvent event) {
        var record = record(event.executionId());
        if (record.events.isEmpty() && !(event.type().equals("STARTED") && event.kind().equals("EXECUTION"))) record.incomplete = true;
        if (record.events.size() == events) { record.incomplete = true; return; }
        record.events.add(event);
    }
    @Override public synchronized Optional<History> find(String id) {
        expire(); var record = records.get(id);
        return record == null ? Optional.empty() : Optional.of(new History(id, record.events, record.incomplete));
    }
    @Override public synchronized void markIncomplete(String id) { record(id).incomplete = true; }
    @Override public synchronized void remove(String id) { records.remove(id); }
}
