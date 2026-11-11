package it.sartoriadelcodice.claudemonitor.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.Utils;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests of the Web Push requests sent with the JDK HTTP client, against a local push service. */
class WebPushClientTest {

    private static final String PAYLOAD = "{\"notification\":{\"title\":\"Needs attention\"}}";
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    /** Requests received by the local push service. */
    private record Received(String method, String path, Headers headers, byte[] body) {
    }

    private final List<Received> received = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private String origin;
    private KeyPair vapid;
    private WebPushClient client;

    @BeforeAll
    static void registerBouncyCastle() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @BeforeEach
    void startPushService() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getPath();
            received.add(new Received(exchange.getRequestMethod(), path, exchange.getRequestHeaders(), body));
            int status = switch (path) {
                case "/gone" -> 410;
                case "/moved" -> {
                    exchange.getResponseHeaders().add("Location", origin + "/elsewhere");
                    yield 301;
                }
                default -> 201;
            };
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();

        vapid = generateKeyPair();
        client = new WebPushClient(encodePublic(vapid), encodePrivate(vapid), "mailto:admin@example.com");
    }

    @AfterEach
    void stopPushService() {
        server.stop(0);
    }

    @Test
    void sendsAnEncryptedRequestSignedWithVapid() throws Exception {
        int status = client.send(notification("/push/device-1"));

        assertThat(status).isEqualTo(201);
        assertThat(received).hasSize(1);
        Received request = received.getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/push/device-1");
        assertThat(request.headers().getFirst("Content-Encoding")).isEqualTo("aes128gcm");
        assertThat(request.headers().getFirst("Content-Type")).isEqualTo("application/octet-stream");
        assertThat(request.headers().getFirst("TTL")).isNotBlank();
        // The payload travels encrypted
        assertThat(request.body()).hasSizeGreaterThan(PAYLOAD.length());
        assertThat(new String(request.body(), StandardCharsets.ISO_8859_1)).doesNotContain("Needs attention");

        // Authorization: vapid t=<JWT signed with the VAPID private key>, k=<VAPID public key>
        String authorization = request.headers().getFirst("Authorization");
        assertThat(authorization).startsWith("vapid t=").endsWith(", k=" + encodePublic(vapid));
        String jwt = authorization.substring("vapid t=".length(), authorization.indexOf(", k="));
        // The library builds the audience from scheme and host: the origin of push services, which use the
        // default https port (the local test service has its own port)
        JwtClaims claims = new JwtConsumerBuilder()
                .setVerificationKey(vapid.getPublic())
                .setExpectedAudience("http://127.0.0.1")
                .setRequireExpirationTime()
                .build()
                .processToClaims(jwt);
        assertThat(claims.getSubject()).isEqualTo("mailto:admin@example.com");
    }

    @Test
    void returnsTheStatusOfThePushService() throws Exception {
        assertThat(client.send(notification("/gone"))).isEqualTo(410);
    }

    @Test
    void doesNotFollowRedirects() throws Exception {
        assertThat(client.send(notification("/moved"))).isEqualTo(301);
        assertThat(received).extracting(Received::path).containsExactly("/moved");
    }

    private Notification notification(String path) throws Exception {
        KeyPair device = generateKeyPair();
        byte[] auth = new byte[16];
        new SecureRandom().nextBytes(auth);
        return new Notification(origin + path, encodePublic(device), BASE64URL.encodeToString(auth), PAYLOAD);
    }

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static String encodePublic(KeyPair keyPair) {
        return BASE64URL.encodeToString(Utils.encode((ECPublicKey) keyPair.getPublic()));
    }

    private static String encodePrivate(KeyPair keyPair) {
        return BASE64URL.encodeToString(Utils.encode((ECPrivateKey) keyPair.getPrivate()));
    }
}
