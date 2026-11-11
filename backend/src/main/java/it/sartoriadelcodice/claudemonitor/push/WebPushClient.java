package it.sartoriadelcodice.claudemonitor.push;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.Security;
import java.time.Duration;

import nl.martijndwars.webpush.AbstractPushService;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.jose4j.lang.JoseException;

/**
 * Sends Web Push messages with the JDK HTTP client.
 * The web-push library is used only to build the request: payload encrypted with aes128gcm (RFC 8291) and
 * VAPID signature (RFC 8292). Its own senders are built on Apache HttpClient 4 and async-http-client, which
 * are excluded in pom.xml.
 */
class WebPushClient extends AbstractPushService<WebPushClient> {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    static {
        // The library loads keys and encrypts through the "BC" provider
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final HttpClient http;

    /**
     * @param publicKey  VAPID public key, base64url
     * @param privateKey VAPID private key, base64url
     * @param subject    contact required by VAPID: mailto: or https URL
     */
    WebPushClient(String publicKey, String privateKey, String subject) throws GeneralSecurityException {
        this(publicKey, privateKey, subject, HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                // The endpoint was checked by PushEndpointValidator: a redirect would bypass that check
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    WebPushClient(String publicKey, String privateKey, String subject, HttpClient http)
            throws GeneralSecurityException {
        super(publicKey, privateKey, subject);
        this.http = http;
    }

    /** Sends the notification and returns the HTTP status of the push service. */
    int send(Notification notification)
            throws GeneralSecurityException, IOException, JoseException, InterruptedException {
        nl.martijndwars.webpush.HttpRequest prepared = prepareRequest(notification, Encoding.AES128GCM);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(prepared.getUrl()))
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(prepared.getBody()));
        prepared.getHeaders().forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
