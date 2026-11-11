package it.sartoriadelcodice.claudemonitor.push;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

/**
 * Validation of a Web Push subscription endpoint. The backend sends requests to that address:
 * without validation a user could make them reach internal services (SSRF).
 * Only https URLs pointing to public addresses are accepted.
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
            // fc00::/7: unique local addresses
            return (bytes[0] & 0xfe) != 0xfc;
        }
        // 100.64.0.0/10: carrier-grade NAT shared address space (CGNAT), also used by private networks
        return !((bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64);
    }
}
