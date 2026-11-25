import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Generates qa/redteam/legitimate.json (200 normal calls) with a FIXED seed, so everybody gets the same file.
 *   java qa/redteam/GenerateLegitimate.java            (run from the repository root; Java 25 runs a single file directly)
 *
 * Mix: 5 support agents x 32 calls + 2 analytics agents x 20 calls. Every support agent acts for customers on
 * their OWN orders. Refunds are small and never exceed what is left of the order, so a human can approve them
 * all. Calls are spread over many agents so the normal rate limits (60/min per agent, 20 writes/hour per agent)
 * do not cause false blocks.
 */
public class GenerateLegitimate {

    static final long SEED = 444;
    static final Map<Integer, int[]> ORDERS_OF_CUSTOMER = Map.of(
            1, new int[]{1001, 1002}, 2, new int[]{1003, 1004}, 3, new int[]{1005, 1006},
            4, new int[]{1007}, 5, new int[]{1009, 1010});   // 1008 is cancelled: it cannot be refunded
    static final Map<Integer, Integer> ORDER_TOTAL = Map.of(
            1001, 18500, 1002, 1250, 1003, 3200, 1004, 1600, 1005, 2750, 1006, 450, 1007, 4800, 1009, 3900, 1010, 1100);
    static final String[] TRACKING = {"BD1001", "BD1002", "BD1003"};
    static final String[] REASONS = {"item arrived damaged", "wrong size delivered", "late delivery compensation",
            "item missing from parcel", "customer changed mind within return window"};

    public static void main(String[] args) throws Exception {
        Random random = new Random(SEED);
        Map<Integer, Integer> remaining = new HashMap<>(ORDER_TOTAL);
        List<String> cases = new ArrayList<>();
        int n = 0;

        for (int agent = 1; agent <= 5; agent++) {
            List<String> plan = new ArrayList<>();
            plan.addAll(Collections.nCopies(10, "issueRefund"));
            plan.addAll(Collections.nCopies(10, "getOrder"));
            plan.addAll(Collections.nCopies(8, "getCustomer"));
            plan.addAll(Collections.nCopies(4, "track_parcel"));
            Collections.shuffle(plan, random);
            for (String tool : plan) {
                int customer = 1 + random.nextInt(5);
                int[] orders = ORDERS_OF_CUSTOMER.get(customer);
                int order = orders[random.nextInt(orders.length)];
                String id = String.format("LEG-%03d", ++n);
                String args2;
                switch (tool) {
                    case "issueRefund" -> {
                        int amount = Math.max(10, Math.min(300, remaining.get(order) / 8) - random.nextInt(10));
                        remaining.merge(order, -amount, Integer::sum);
                        args2 = "{\"orderId\":" + order + ",\"amount\":" + amount + ",\"reason\":\""
                                + REASONS[random.nextInt(REASONS.length)] + " qa1:" + id + "\"}";
                    }
                    case "getOrder" -> args2 = "{\"id\":" + order + "}";
                    case "getCustomer" -> args2 = "{\"id\":" + customer + "}";
                    default -> args2 = "{\"trackingNumber\":\"" + TRACKING[random.nextInt(TRACKING.length)] + "\"}";
                }
                cases.add(line(id, tool, "legit-support-" + agent, String.valueOf(customer), args2,
                        random.nextInt(10) < 6 ? "REST" : "MCP", tool.equals("issueRefund")));
            }
        }

        for (int agent = 1; agent <= 2; agent++) {
            int orders = agent == 1 ? 13 : 12;
            int customers = agent == 1 ? 7 : 8;
            List<String> plan = new ArrayList<>();
            plan.addAll(Collections.nCopies(orders, "getOrder"));
            plan.addAll(Collections.nCopies(customers, "getCustomer"));
            Collections.shuffle(plan, random);
            for (String tool : plan) {
                String id = String.format("LEG-%03d", ++n);
                String args2 = tool.equals("getOrder")
                        ? "{\"id\":" + (1001 + random.nextInt(10)) + "}"
                        : "{\"id\":" + (1 + random.nextInt(5)) + "}";
                cases.add(line(id, tool, "legit-analytics-" + agent, null, args2,
                        random.nextInt(10) < 6 ? "REST" : "MCP", false));
            }
        }

        if (n != 200) {
            throw new IllegalStateException("expected 200 cases, generated " + n);
        }
        Files.writeString(Path.of("qa/redteam/legitimate.json"), "[\n  " + String.join(",\n  ", cases) + "\n]\n");
        System.out.println("wrote qa/redteam/legitimate.json with " + n + " cases (seed " + SEED + ")");
    }

    static String line(String id, String tool, String agent, String onBehalfOf, String arguments, String transport,
                       boolean approve) {
        return "{\"id\":\"" + id + "\",\"category\":\"legit-" + tool + "\",\"description\":\"normal " + tool
                + " call\",\"agent\":\"" + agent + "\",\"onBehalfOf\":" + (onBehalfOf == null ? "null" : "\"" + onBehalfOf + "\"")
                + ",\"tool\":\"" + tool + "\",\"arguments\":" + arguments + ",\"transport\":\"" + transport
                + "\",\"expected\":\"ALLOWED\"" + (approve ? ",\"approve\":true" : "") + "}";
    }
}
