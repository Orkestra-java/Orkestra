
import io.orkestra.core.Failures;
import io.orkestra.core.Orchestrator;
import io.orkestra.core.composition.Compositions;
import io.orkestra.core.execution.Execution;

/** Runnable consumer of the initial sequential core. */
public final class PlaceOrder {
    record OrderRequest(int quantity) {}
    record ValidatedOrder(int quantity) {}
    record Receipt(String message) {}
    record OrderContext(String customer) {}

    public static void main(String[] args) throws Exception {
        var validate = Compositions.<OrderContext, OrderRequest>create()
                .perform((context, input) -> {
                    if (input.quantity() <= 0) {
                        throw new IllegalArgumentException("Quantity must be positive");
                    }
                    return new ValidatedOrder(input.quantity());
                })
                .build();

        var placeOrder = Compositions.<OrderContext, OrderRequest>create()
                .named("place-order")
                .perform(validate)
                .perform((context, input) -> new Receipt(
                        context.customer() + ": confirmed " + input.quantity() + " items"))
                .build()
                .onFailure((context, cause) -> new IllegalStateException(
                        "Order failed for " + context.customer(), cause));

        var context = new OrderContext("customer-123");
        try (var orchestrator = new Orchestrator()) {
            var execution = orchestrator.perform(placeOrder, context, new OrderRequest(2));
            System.out.println(execution.output().message());
            System.out.println("Execution: " + execution.id());
        } catch (Exception error) {
            Failures.details(error).ifPresent(details ->
                    System.err.println("Failed execution: " + details.executionId()));
            throw error;
        }
    }
}
