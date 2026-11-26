package com.agentgate.mocktools.shipping;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** QA5: the "new" shipping tool and its own OpenAPI file. */
@SpringBootTest
@AutoConfigureMockMvc
class ShippingRateControllerTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void ratePerStartedKgOutsideDhaka() {
        // 120 base + 2 extra started kg (2.5 kg - 1 = 1.5 → 2) × 20 = 160
        assertThat(mvc.get().uri("/shipping/rate?weightKg=2.5&zone=outside_dhaka"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.rate", v -> v.assertThat().isEqualTo(160))
                .hasPathSatisfying("$.currency", v -> v.assertThat().isEqualTo("BDT"));
    }

    @Test
    void unknownZoneIsRejected() {
        assertThat(mvc.get().uri("/shipping/rate?weightKg=1&zone=mars")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void ownSpecFileIsServedWithTheOperationId() {
        assertThat(mvc.get().uri("/openapi/shipping-rate.json"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.paths['/shipping/rate'].get.operationId",
                        v -> v.assertThat().isEqualTo("getShippingRate"));
    }

    @Test
    void shippingIsNotInTheAutoImportedSpec() {
        // The gateway seeds /v3/api-docs on a fresh start; the QA5 tool must stay "new".
        assertThat(mvc.get().uri("/v3/api-docs"))
                .hasStatusOk()
                .bodyJson()
                .doesNotHavePath("$.paths['/shipping/rate']");
    }
}
