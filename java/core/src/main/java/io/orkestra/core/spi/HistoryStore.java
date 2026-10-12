package io.orkestra.core.spi;

import java.util.List;
import java.util.Optional;

public interface HistoryStore extends EventListener {
    record History(String executionId, List<ExecutionEvent> events, boolean incomplete) {
        public History { events = List.copyOf(events); }
    }
    Optional<History> find(String executionId);
    void markIncomplete(String executionId);
    void remove(String executionId);
}
