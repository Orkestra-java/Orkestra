package io.orkestra.core.internal;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;

/** Runtime operations available to immutable definitions; no executor implementation dependency. */
public interface ExecutionScope {
    void check() throws Exception;
    void pause(Duration duration) throws Exception;
    void event(String type, String name, Throwable error);
    <C> Object execute(Node<C> node, C context, Object input) throws Exception;
    List<Object> parallel(List<Callable<Object>> jobs, int limit) throws Exception;
}
