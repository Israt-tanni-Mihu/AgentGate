package com.agentgate.gateway.approval;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * FR4 settings under "agentgate.approval".
 *
 * @param timeout    how long a held call waits for a human before it is rejected
 * @param webhookUrl where to POST a notification for every new request; blank = no webhook
 * @param approver   the approver login (role APPROVER, separate from admin and from agents)
 */
@Validated
@ConfigurationProperties("agentgate.approval")
public record ApprovalProperties(
        @NotNull Duration timeout,
        String webhookUrl,
        @Valid @NotNull Approver approver) {

    public record Approver(
            @NotBlank(message = "set the APPROVER_USERNAME environment variable") String username,
            @NotBlank(message = "set the APPROVER_PASSWORD environment variable") String password) {
    }
}
