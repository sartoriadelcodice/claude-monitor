package it.sartoriadelcodice.claudemonitor.push;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests for the push subscription endpoint check. IP addresses only: no DNS lookups. */
class PushEndpointValidatorTest {

    @Test
    void publicHttpsEndpointIsAllowed() {
        assertThat(PushEndpointValidator.isAllowed("https://8.8.8.8/push/abc")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://8.8.8.8/push/abc",
            "https://127.0.0.1/push",
            "https://10.0.0.5/push",
            "https://192.168.1.10/push",
            "https://172.16.0.1/push",
            "https://169.254.169.254/latest/meta-data",
            "https://100.64.0.1/push",
            "https://[::1]/push",
            "https://[fd00::1]/push",
            "https://user@8.8.8.8/push",
            "file:///etc/passwd",
            "not-a-url"
    })
    void unsafeEndpointIsRejected(String endpoint) {
        assertThat(PushEndpointValidator.isAllowed(endpoint)).isFalse();
    }

    @Test
    void overlongEndpointIsRejected() {
        assertThat(PushEndpointValidator.isAllowed("https://8.8.8.8/" + "a".repeat(2000))).isFalse();
    }
}
