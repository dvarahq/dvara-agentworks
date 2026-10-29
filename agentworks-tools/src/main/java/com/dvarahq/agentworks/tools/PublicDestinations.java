package com.dvarahq.agentworks.tools;

import java.net.InetAddress;

/** Conservative public-unicast policy, including mapped IPv4 and common transition/metadata ranges. */
final class PublicDestinations {
    private PublicDestinations() {}
    static boolean allowed(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int a = b[0] & 255, c = b[1] & 255, d = b[2] & 255;
            return !(a == 0 || a == 10 || a == 127 || a >= 224 || a == 169 && c == 254
                    || a == 100 && c >= 64 && c <= 127 || a == 172 && c >= 16 && c <= 31
                    || a == 192 && (c == 168 || c == 0 || c == 2 || c == 88)
                    || a == 198 && (c == 18 || c == 19 || c == 51 && d == 100)
                    || a == 203 && c == 0 && d == 113);
        }
        // Only ordinary 2000::/3 global unicast; deny Teredo, documentation, 6to4, NAT64 and ULA.
        return b.length == 16 && (b[0] & 0xe0) == 0x20
                && !((b[0] & 255) == 0x3f && (b[1] & 255) == 0xff)
                && !((b[0] & 255) == 0x20 && (b[1] & 255) == 0x02)
                && !((b[0] & 255) == 0x20 && (b[1] & 255) == 0x01 && (b[2] & 255) < 2)
                && !((b[0] & 255) == 0x20 && (b[1] & 255) == 0x01
                     && (b[2] & 255) == 0x0d && (b[3] & 255) == 0xb8);
    }
}
