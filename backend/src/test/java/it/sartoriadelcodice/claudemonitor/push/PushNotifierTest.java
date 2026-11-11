package it.sartoriadelcodice.claudemonitor.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

import it.sartoriadelcodice.claudemonitor.session.SessionDto;
import it.sartoriadelcodice.claudemonitor.session.SessionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.support.ResourceBundleMessageSource;

/** Tests of the push notification texts, in each device language. */
class PushNotifierTest {

    private final ResourceBundleMessageSource messages = messageSource();

    private static ResourceBundleMessageSource messageSource() {
        // Same settings as spring.messages in application.yml
        var source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }

    private static SessionDto session(SessionStatus status, String notificationMessage) {
        Instant now = Instant.parse("2026-11-11T10:00:00Z");
        return new SessionDto("s-1", "laptop", "shop-api", "/work/shop-api", null, null, status, now, now,
                notificationMessage, null, null, false, now, null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> notification(SessionDto session, String language) {
        return (Map<String, Object>) PushNotifier.buildPayload(session, messages, Locale.forLanguageTag(language))
                .get("notification");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "en | Needs attention · shop-api   | Permission or input required (laptop)",
            "it | Richiede attenzione · shop-api | Permesso o input richiesto (laptop)"
    })
    void needsAttentionWithoutMessage(String language, String title, String body) {
        var notification = notification(session(SessionStatus.NEEDS_ATTENTION, null), language);
        assertThat(notification).containsEntry("title", title).containsEntry("body", body);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "en | Response complete · shop-api  | Claude is done, your turn (laptop)",
            "it | Risposta terminata · shop-api | Claude ha finito, tocca a te (laptop)"
    })
    void waitingInput(String language, String title, String body) {
        var notification = notification(session(SessionStatus.WAITING_INPUT, null), language);
        assertThat(notification).containsEntry("title", title).containsEntry("body", body);
    }

    @Test
    void permissionMessageFromClaudeCodeIsShownAsItIs() {
        var notification = notification(session(SessionStatus.NEEDS_ATTENTION, "Claude needs your permission"), "it");
        assertThat(notification).containsEntry("body", "Claude needs your permission (laptop)");
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {"en, en", "it, it", "IT, it", "' it ', it", "fr, en", "'', en", "null, en"})
    void unsupportedLanguagesFallBackToEnglish(String requested, String expected) {
        assertThat(PushNotifier.supportedLanguage(requested)).isEqualTo(expected);
    }

    @Test
    void sameTagPerSessionSoANewNotificationReplacesThePreviousOne() {
        assertThat(notification(session(SessionStatus.WAITING_INPUT, null), "en")).containsEntry("tag", "s-1");
    }
}
