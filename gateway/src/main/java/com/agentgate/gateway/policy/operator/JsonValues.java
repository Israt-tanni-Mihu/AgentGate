package com.agentgate.gateway.policy.operator;

import java.math.BigDecimal;
import tools.jackson.databind.JsonNode;

/** Small helpers shared by the operators. */
final class JsonValues {

    private JsonValues() {
    }

    static boolean isAbsent(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull();
    }

    /**
     * The value as a number, or null if it is not one.
     * A numeric STRING like "5000" also counts, because the tool would accept it as 5000 too:
     * otherwise an agent could bypass "amount <= 2000" just by sending the amount as text.
     */
    static BigDecimal number(JsonNode node) {
        if (isAbsent(node)) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isString()) {
            try {
                return new BigDecimal(node.asString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** Same value? Numbers compare by value (1 == 1.0 == "1"); everything else by its text. */
    static boolean same(JsonNode a, JsonNode b) {
        if (isAbsent(a) || isAbsent(b) || !a.isValueNode() || !b.isValueNode()) {
            return false;
        }
        BigDecimal numberA = number(a);
        BigDecimal numberB = number(b);
        if (numberA != null && numberB != null) {
            return numberA.compareTo(numberB) == 0;
        }
        return a.asString().equals(b.asString());
    }

    static String show(JsonNode node) {
        return isAbsent(node) ? "missing" : node.toString();
    }
}
