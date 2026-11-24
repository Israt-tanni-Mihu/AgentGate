package com.agentgate.gateway.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Plain unit test of the X-On-Behalf-Of rules — no Spring needed. */
class CallContextArgumentResolverTest {

    @Test
    void missingOrBlankHeaderMeansNoEndUser() {
        assertThat(CallContextArgumentResolver.readEndUser(null)).isNull();
        assertThat(CallContextArgumentResolver.readEndUser(new String[0])).isNull();
        assertThat(CallContextArgumentResolver.readEndUser(new String[] {"   "})).isNull();
    }

    @Test
    void simpleIdsAreAccepted() {
        assertThat(CallContextArgumentResolver.readEndUser(new String[] {" cust-1001 "})).isEqualTo("cust-1001");
        assertThat(CallContextArgumentResolver.readEndUser(new String[] {"rahim@shopbd.com"})).isEqualTo("rahim@shopbd.com");
    }

    @Test
    void smugglingAttemptsAreRejected() {
        assertThatThrownBy(() -> CallContextArgumentResolver.readEndUser(new String[] {"cust-1", "cust-2"}))
                .isInstanceOf(InvalidOnBehalfOfException.class);
        assertThatThrownBy(() -> CallContextArgumentResolver.readEndUser(new String[] {"cust-1,cust-2"}))
                .isInstanceOf(InvalidOnBehalfOfException.class);
        assertThatThrownBy(() -> CallContextArgumentResolver.readEndUser(new String[] {"x' OR '1'='1"}))
                .isInstanceOf(InvalidOnBehalfOfException.class);
        assertThatThrownBy(() -> CallContextArgumentResolver.readEndUser(new String[] {"a".repeat(129)}))
                .isInstanceOf(InvalidOnBehalfOfException.class);
    }
}
