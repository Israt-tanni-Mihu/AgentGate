package com.agentgate.gateway.registry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AccessModeTest {

    @Test
    void getIsReadAndEveryOtherMethodIsWriteByDefault() {
        assertThat(AccessMode.defaultFor(ToolKind.REST, "GET")).isEqualTo(AccessMode.READ);
        for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE"}) {
            assertThat(AccessMode.defaultFor(ToolKind.REST, method)).as(method).isEqualTo(AccessMode.WRITE);
        }
    }

    @Test
    void mcpToolsAreWriteByDefaultBecauseWeCannotSeeWhatTheyDo() {
        assertThat(AccessMode.defaultFor(ToolKind.MCP, null)).isEqualTo(AccessMode.WRITE);
    }
}
