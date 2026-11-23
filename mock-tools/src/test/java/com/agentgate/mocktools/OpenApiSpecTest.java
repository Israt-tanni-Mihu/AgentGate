package com.agentgate.mocktools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** FR1 will import this spec, so we lock down the parts it depends on. */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSpecTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void specListsTheFourToolsWithOperationIdsAndRiskHints() {
        assertThat(mvc.get().uri("/v3/api-docs"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.paths['/api/customers/{id}'].get.operationId",
                        v -> v.assertThat().isEqualTo("getCustomer"))
                .hasPathSatisfying("$.paths['/api/orders/{id}'].get.operationId",
                        v -> v.assertThat().isEqualTo("getOrder"))
                .hasPathSatisfying("$.paths['/api/refunds'].post.operationId",
                        v -> v.assertThat().isEqualTo("issueRefund"))
                .hasPathSatisfying("$.paths['/api/sms'].post.operationId",
                        v -> v.assertThat().isEqualTo("sendSms"))
                .hasPathSatisfying("$.paths['/api/refunds'].post.x-risk-level",
                        v -> v.assertThat().isEqualTo("high"))
                .doesNotHavePath("$.paths['/admin/chaos']");
    }
}
