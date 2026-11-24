package com.agentgate.gateway.registry.importer;

import java.util.List;

/** Summary returned by the import endpoint. */
public record ImportResult(
        String format,
        int created,
        int updated,
        int skipped,
        List<Item> items) {

    public enum Outcome { CREATED, UPDATED, SKIPPED }

    /** One line per operation. {@code name} is null if the operation had no usable name. */
    public record Item(String name, Outcome outcome, String reason) {
    }

    public static ImportResult of(String format, List<Item> items) {
        return new ImportResult(format,
                count(items, Outcome.CREATED), count(items, Outcome.UPDATED), count(items, Outcome.SKIPPED),
                items);
    }

    private static int count(List<Item> items, Outcome outcome) {
        return (int) items.stream().filter(item -> item.outcome() == outcome).count();
    }
}
