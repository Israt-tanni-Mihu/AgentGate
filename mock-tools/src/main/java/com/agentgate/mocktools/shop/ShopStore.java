package com.agentgate.mocktools.shop;

import com.agentgate.mocktools.shop.Order.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * In-memory "database" of the fake shop. No real DB on purpose: mock-tools must start instantly
 * and always begin from the same known data, so tests and demos are repeatable.
 *
 * All personal data below is FAKE. Emails use example.com (a domain reserved for examples, RFC 2606).
 */
@Component
public class ShopStore {

    public static final String CURRENCY = "BDT";

    private final Map<Long, Customer> customers = byId(List.of(
            new Customer(1, "Rahim Uddin", "rahim.uddin@example.com", "01712345678", "1987654321", "Dhaka"),
            new Customer(2, "Fatema Begum", "fatema.begum@example.com", "01898765432", "2690412345678", "Chattogram"),
            new Customer(3, "Tanvir Hasan", "tanvir.hasan@example.com", "01556677889", "19901234567890123", "Sylhet"),
            new Customer(4, "Nusrat Akter", "nusrat.akter@example.com", "01911223344", "4567890123", "Rajshahi"),
            new Customer(5, "Arif Chowdhury", "arif.chowdhury@example.com", "01622334455", "5512345678901", "Khulna")),
            Customer::id);

    // Some totals are above 2000 BDT on purpose, so a later policy "refund <= 2000 BDT" has something to block.
    private final Map<Long, Order> orders = byId(List.of(
            order(1001, 1, List.of("Walton smartphone"), "18500.00", OrderStatus.DELIVERED, "2026-10-02", "01712345678"),
            order(1002, 1, List.of("Rice 5kg", "Lentils 2kg"), "1250.00", OrderStatus.DELIVERED, "2026-10-15", "01712345678"),
            order(1003, 2, List.of("Cotton saree"), "3200.00", OrderStatus.SHIPPED, "2026-11-01", "01898765432"),
            order(1004, 2, List.of("Hilsa fish 1kg"), "1600.00", OrderStatus.DELIVERED, "2026-11-05", "01898765432"),
            order(1005, 3, List.of("Laptop bag", "USB mouse"), "2750.00", OrderStatus.PROCESSING, "2026-11-10", "01556677889"),
            order(1006, 3, List.of("Tea leaves 500g"), "450.00", OrderStatus.DELIVERED, "2026-09-20", "01556677889"),
            order(1007, 4, List.of("Ceiling fan"), "4800.00", OrderStatus.DELIVERED, "2026-08-30", "01911223344"),
            order(1008, 4, List.of("School books set"), "950.00", OrderStatus.CANCELLED, "2026-11-12", "01911223344"),
            order(1009, 5, List.of("Rice cooker"), "3900.00", OrderStatus.SHIPPED, "2026-11-18", "01622334455"),
            order(1010, 5, List.of("Mango 5kg"), "1100.00", OrderStatus.DELIVERED, "2026-07-08", "01622334455")),
            Order::id);

    /** How much has already been refunded per order id. Refunds can never exceed the order total. */
    private final Map<Long, BigDecimal> refundedSoFar = new ConcurrentHashMap<>();
    private final AtomicLong nextRefundId = new AtomicLong(5001);
    private final AtomicLong nextSmsId = new AtomicLong(9001);

    public Customer getCustomer(long id) {
        Customer customer = customers.get(id);
        if (customer == null) {
            throw new NotFoundException("Customer", id);
        }
        return customer;
    }

    public Order getOrder(long id) {
        Order order = orders.get(id);
        if (order == null) {
            throw new NotFoundException("Order", id);
        }
        return order;
    }

    public Refund issueRefund(RefundRequest request) {
        Order order = getOrder(request.orderId());
        if (order.status() == OrderStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Order " + order.id() + " is cancelled and cannot be refunded");
        }
        // compute() runs atomically per key, so two parallel refunds cannot both pass the limit check.
        refundedSoFar.compute(order.id(), (id, alreadyRefunded) -> {
            BigDecimal before = alreadyRefunded == null ? BigDecimal.ZERO : alreadyRefunded;
            BigDecimal after = before.add(request.amount());
            if (after.compareTo(order.total()) > 0) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "Refund of " + request.amount() + " BDT exceeds the refundable amount "
                                + order.total().subtract(before) + " BDT for order " + order.id());
            }
            return after;
        });
        return new Refund("RF-" + nextRefundId.getAndIncrement(), order.id(), request.amount(), CURRENCY,
                request.reason(), "ISSUED", Instant.now());
    }

    public SmsMessage sendSms(SmsRequest request) {
        Customer customer = getCustomer(request.customerId());
        // Nothing is really sent: this is a fake tool. We just report what "would" be sent.
        return new SmsMessage("SMS-" + nextSmsId.getAndIncrement(), customer.id(), customer.phone(),
                request.message(), "SENT", Instant.now());
    }

    private static Order order(long id, long customerId, List<String> items, String total, OrderStatus status,
                               String placedOn, String deliveryPhone) {
        return new Order(id, customerId, items, new BigDecimal(total), CURRENCY, status,
                LocalDate.parse(placedOn), deliveryPhone);
    }

    private static <T> Map<Long, T> byId(List<T> list, Function<T, Long> idOf) {
        return list.stream().collect(Collectors.toUnmodifiableMap(idOf, Function.identity()));
    }
}
