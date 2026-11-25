package com.agentgate.gateway.approval;

import com.agentgate.gateway.identity.LoginAccount;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ApprovalProperties.class)
public class ApprovalConfig {

    /** Gives the approver login to the identity module without identity depending on approval. */
    @Bean
    LoginAccount approverAccount(ApprovalProperties properties) {
        return new LoginAccount(properties.approver().username(), properties.approver().password(), "APPROVER");
    }
}
