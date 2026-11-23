package com.agentgate.mocktools.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@SpringBootTest
@AutoConfigureMockMvc
class ChaosControllerTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ChaosState chaosState;

    @AfterEach
    void turnChaosOff() {
        chaosState.reset(); // the Spring context is shared between test classes, so always clean up
    }

    @Test
    void defaultIsNoChaos() {
        assertThat(mvc.get().uri("/admin/chaos"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.delayMs", v -> v.assertThat().isEqualTo(0))
                .hasPathSatisfying("$.failRate", v -> v.assertThat().isEqualTo(0.0));
    }

    @Test
    void failRateOneMakesEveryToolCallFailWith503() {
        assertThat(setChaos("{\"delayMs\": 0, \"failRate\": 1.0}")).hasStatusOk();

        assertThat(mvc.get().uri("/api/orders/1001"))
                .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        // the admin endpoint itself is never affected, so chaos can always be switched off
        assertThat(mvc.get().uri("/admin/chaos")).hasStatusOk();
    }

    @Test
    void delayIsAddedToToolCalls() {
        assertThat(setChaos("{\"delayMs\": 300, \"failRate\": 0}")).hasStatusOk();

        long start = System.nanoTime();
        assertThat(mvc.get().uri("/api/orders/1001")).hasStatusOk();
        long tookMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(tookMs).isGreaterThanOrEqualTo(300);
    }

    @Test
    void invalidFailRateIsRejected() {
        assertThat(setChaos("{\"delayMs\": 0, \"failRate\": 1.5}"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPath("$.errors.failRate");
    }

    private MockMvcTester.MockMvcRequestBuilder setChaos(String json) {
        return mvc.post().uri("/admin/chaos").contentType(MediaType.APPLICATION_JSON).content(json);
    }
}
