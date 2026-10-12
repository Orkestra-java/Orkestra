package io.orkestra.core.spi;

@FunctionalInterface
public interface EventListener {
    void onEvent(ExecutionEvent event);
}
