package it.sartoriadelcodice.claudemonitor.push;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

/**
 * Controllo dell'endpoint di una subscription Web Push. Il backend invia richieste a quell'indirizzo:
 * senza controllo un utente potrebbe farle arrivare a servizi interni (SSRF).
 * Si accettano solo URL https verso indirizzi pubblici.
 */
final class PushEndpointValidator {

    static final int MAX_LENGTH = 2000;

    private PushEndpointValidator() {
    }

    static boolean isAllowed(String endpoint) {
        if (endpoint == null || endpoint.length() > MAX_LENGTH) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(endpoint);
        } catch (URISyntaxException e) {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            return false;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (!isPublic(address)) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet6Address) {
            // fc00::/7: indirizzi locali univoci
            return (bytes[0] & 0xfe) != 0xfc;
        }
        // 100.64.0.0/10: spazio condiviso dei provider (CGNAT), usato anche da reti private
        return !((bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64);
    }
}
