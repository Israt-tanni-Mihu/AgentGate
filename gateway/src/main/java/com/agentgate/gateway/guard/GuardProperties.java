package com.agentgate.gateway.guard;

import com.agentgate.gateway.guard.redaction.SensitiveDataType;
import com.agentgate.gateway.identity.Clearance;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of the FR5 guards under "agentgate.guards" (defaults in application.yml).
 *
 * @param redactionByClearance which data types each clearance must NOT see; a clearance missing from the map
 *                             gets everything redacted (fail closed)
 * @param truncation           size limit for tool results
 */
@Validated
@ConfigurationProperties("agentgate.guards")
public record GuardProperties(
        @NotNull Map<Clearance, Set<SensitiveDataType>> redactionByClearance,
        @NotNull Truncation truncation) {

    /** @param maxBytes results bigger than this (as JSON, in UTF-8 bytes) are cut */
    public record Truncation(@Min(1) long maxBytes) {
    }
}
