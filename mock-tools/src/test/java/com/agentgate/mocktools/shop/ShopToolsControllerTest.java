package com.agentgate.mocktools.shop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@SpringBootTest
@AutoConfigureMockMvc
class ShopToolsControllerTest {

    @Autowired
    MockMvcTester mvc;

    // ---------- getCustomer ----------

    @Test
    void getCustomerReturnsPersonalData() {
        assertThat(mvc.get().uri("/api/customers/1"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.name", v -> v.assertThat().isEqualTo("Rahim Uddin"))
                .hasPathSatisfying("$.phone", v -> v.assertThat().asString().matches("01\\d{9}"))
                .hasPathSatisfying("$.email", v -> v.assertThat().isEqualTo("rahim.uddin@example.com"))
                .hasPathSatisfying("$.nationalId", v -> v.assertThat().asString().matches("\\d{10}|\\d{13}|\\d{17}"));
    }

    @Test
    void unknownCustomerIsProblemDetails404() {
        assertThat(mvc.get().uri("/api/customers/999"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .hasPathSatisfying("$.status", v -> v.assertThat().isEqualTo(404))
                .hasPathSatisfying("$.detail", v -> v.assertThat().isEqualTo("Customer 999 not found"));
    }

    @Test
    void nonPositiveCustomerIdIsRejected() {
        assertThat(mvc.get().uri("/api/customers/0"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    // ---------- getOrder ----------

    @Test
    void getOrderReturnsOrder() {
        assertThat(mvc.get().uri("/api/orders/1001"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.customerId", v -> v.assertThat().isEqualTo(1))
                .hasPathSatisfying("$.currency", v -> v.assertThat().isEqualTo("BDT"))
                .hasPathSatisfying("$.status", v -> v.assertThat().isEqualTo("DELIVERED"));
    }

    @Test
    void unknownOrderIsProblemDetails404() {
        assertThat(mvc.get().uri("/api/orders/42"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .hasPathSatisfying("$.detail", v -> v.assertThat().isEqualTo("Order 42 not found"));
    }

    // ---------- issueRefund ----------

    @Test
    void issueRefundSucceeds() {
        assertThat(postJson("/api/refunds", """
                {"orderId": 1007, "amount": 300.00, "reason": "Fan arrived scratched"}"""))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.status", v -> v.assertThat().isEqualTo("ISSUED"))
                .hasPathSatisfying("$.orderId", v -> v.assertThat().isEqualTo(1007))
                .hasPathSatisfying("$.refundId", v -> v.assertThat().asString().startsWith("RF-"));
    }

    @Test
    void invalidRefundIsValidationProblem() {
        assertThat(postJson("/api/refunds", """
                {"orderId": 1001, "amount": -5, "reason": ""}"""))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .hasPathSatisfying("$.detail", v -> v.assertThat().isEqualTo("Request validation failed"))
                .hasPath("$.errors.amount")
                .hasPath("$.errors.reason");
    }

    @Test
    void refundForUnknownOrderIs404() {
        assertThat(postJson("/api/refunds", """
                {"orderId": 4242, "amount": 10, "reason": "test"}"""))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void refundAboveOrderTotalIs422() {
        assertThat(postJson("/api/refunds", """
                {"orderId": 1006, "amount": 999999, "reason": "too much"}"""))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void refundForCancelledOrderIs409() {
        assertThat(postJson("/api/refunds", """
                {"orderId": 1008, "amount": 10, "reason": "cancelled"}"""))
                .hasStatus(HttpStatus.CONFLICT);
    }

    // ---------- sendSms ----------

    @Test
    void sendSmsSucceeds() {
        assertThat(postJson("/api/sms", """
                {"customerId": 2, "message": "Your order 1003 has been shipped."}"""))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.to", v -> v.assertThat().isEqualTo("01898765432"))
                .hasPathSatisfying("$.status", v -> v.assertThat().isEqualTo("SENT"));
    }

    @Test
    void tooLongSmsIsValidationProblem() {
        String body = "{\"customerId\": 2, \"message\": \"" + "x".repeat(161) + "\"}";
        assertThat(postJson("/api/sms", body))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPath("$.errors.message");
    }

    @Test
    void smsToUnknownCustomerIs404() {
        assertThat(postJson("/api/sms", """
                {"customerId": 77, "message": "hello"}"""))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    private MockMvcTester.MockMvcRequestBuilder postJson(String uri, String json) {
        return mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(json);
    }
}
