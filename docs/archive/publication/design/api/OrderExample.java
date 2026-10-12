package io.orkestra.design;

import java.time.Duration;
import io.orkestra.design.OrkestraApi.*;

/** Consumer example for checking typed pipelines and nested composition. */
public final class OrderExample {
    record OrderRequest(String customerId) {}
    record ValidatedOrder(String customerId) {}
    record Customer(String id) {}
    record Stock(boolean available) {}
    record Shipping(int days) {}
    record PreparedOrder(Customer customer, Stock stock, Shipping shipping) {}
    record ConfirmedOrder(PreparedOrder prepared) {}

    interface OrderContext {}

    interface Services {
        ValidatedOrder validate(OrderContext context, OrderRequest input) throws Exception;
        Customer loadCustomer(OrderContext context, ValidatedOrder input) throws Exception;
        Stock checkStock(OrderContext context, ValidatedOrder input) throws Exception;
        Shipping calculateShipping(OrderContext context, ValidatedOrder input) throws Exception;
        ConfirmedOrder confirm(OrderContext context, PreparedOrder input) throws Exception;
    }

    static Composition<OrderContext, OrderRequest, ConfirmedOrder> define(
            Compositions compositions, Services services) {

        var customer = TaskRef.<OrderContext, ValidatedOrder, Customer>of(services::loadCustomer);
        var stock = TaskRef.<OrderContext, ValidatedOrder, Stock>of(services::checkStock);
        var shipping = TaskRef.<OrderContext, ValidatedOrder, Shipping>of(services::calculateShipping);

        var prepareOrder = compositions.<OrderContext, ValidatedOrder>create()
            .parallel(group -> group
                .concurrently(3)
                .within(Duration.ofSeconds(5))
                .perform(customer)
                .perform(stock)
                .perform(shipping)
            )
            .join((context, results) -> new PreparedOrder(
                results.get(customer),
                results.get(stock),
                results.get(shipping)
            ))
            .build();

        return compositions.<OrderContext, OrderRequest>create()
            .named("place-order")
            .within(Duration.ofSeconds(30))
            .perform(services::validate)
            .perform(prepareOrder)
            .perform(services::confirm)
            .build();
    }
}
