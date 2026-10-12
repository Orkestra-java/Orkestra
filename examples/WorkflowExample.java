
import io.orkestra.core.Failures;
import io.orkestra.core.Orchestrator;
import io.orkestra.core.composition.Compositions;
import io.orkestra.core.composition.TaskRef;
import io.orkestra.core.task.Compensation;
import io.orkestra.core.local.cache.MemoryCache;
import io.orkestra.core.local.history.MemoryHistory;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Runnable orchestration, policies, compensation, and history example. */
public final class WorkflowExample {
    record Prepared(String customer, int stock) {}
    record Receipt(String customer) {}
    record Context(String customer, boolean rejectConfirmation, List<String> actions, AtomicInteger charges) {}

    static Receipt reserve(Context context, Receipt input) {
        context.actions().add("reserve"); return input;
    }
    static void releaseReservation(Context context, Receipt input, Receipt output) {
        context.actions().add("release-reservation");
    }
    static Receipt charge(Context context, Receipt input) throws IOException {
        if (context.charges().incrementAndGet() == 1) throw new IOException("Transient payment failure");
        context.actions().add("charge"); return input;
    }
    static void refund(Context context, Receipt input, Receipt output) {
        context.actions().add("refund");
    }
    static Receipt confirm(Context context, Receipt input) {
        if (context.rejectConfirmation()) throw new IllegalArgumentException("Confirmation rejected");
        context.actions().add("confirm"); return input;
    }

    public static void main(String[] args) throws Exception {
        var customer = TaskRef.<Context, Integer, String>of((context, input) -> context.customer())
                .named("customer").cache(cache -> cache.named("customers").key(Context::customer));
        var stock = TaskRef.<Context, Integer, Integer>of((context, input) -> input * 2).named("stock");
        var prepare = Compositions.<Context, Integer>create()
                .parallel(group -> group.concurrently(2).perform(customer).perform(stock))
                .join((context, results) -> new Prepared(results.get(customer), results.get(stock))).build();
        var workflow = Compositions.<Context, Integer>create().named("place-order")
                .within(Duration.ofSeconds(3)).require((context, quantity) -> quantity > 0)
                .perform(prepare)
                .<Receipt>situation(choice -> choice.when((context, input) -> input.stock() > 0)
                        .then((context, input) -> new Receipt(input.customer()))
                        .otherwise((context, input) -> { throw new IllegalArgumentException("Out of stock"); }))
                .withCompensation(group -> group
                        .perform(WorkflowExample::reserve).compensateWith(WorkflowExample::releaseReservation)
                        .perform(WorkflowExample::charge).retry(retry -> retry.upTo(2).retryIf(IOException.class::isInstance))
                        .compensateWith(WorkflowExample::refund)
                        .perform(WorkflowExample::confirm))
                .build().onFailure((context, cause) -> new IllegalStateException("Order failed", cause));
        var prices = Compositions.<Context, List<Integer>>create()
                .forEach(items -> items, item -> item.concurrently(2).perform((context, input) -> input * 2))
                .build();
        var history = new MemoryHistory(32, 512, Duration.ofMinutes(10));
        try (var orchestrator = Orchestrator.builder().cache("customers", new MemoryCache(32)).events(history).build()) {
            var accepted = new Context("customer-123", false, new ArrayList<>(), new AtomicInteger());
            var result = orchestrator.perform(workflow, accepted, 2);
            System.out.println("Confirmed: " + result.output().customer());
            System.out.println("Actions: " + accepted.actions());
            System.out.println("Prices: " + orchestrator.perform(prices, accepted, List.of(1, 2, 3)).output());
            System.out.println("History events: " + history.find(result.id()).orElseThrow().events().size());
            var rejected = new Context("customer-123", true, new ArrayList<>(), new AtomicInteger());
            try { orchestrator.perform(workflow, rejected, 2); throw new AssertionError("Expected confirmation failure"); }
            catch (IllegalStateException failure) {
                System.out.println("Failure: " + Failures.details(failure).orElseThrow().kind());
                System.out.println("Compensated: " + rejected.actions());
                if (!rejected.actions().equals(List.of("reserve", "charge", "refund", "release-reservation"))) {
                    throw new AssertionError("Compensation sequence is incorrect");
                }
            }
        }
    }
}
