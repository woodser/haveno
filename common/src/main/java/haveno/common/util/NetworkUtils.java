/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.common.util;

import com.google.common.net.InetAddresses;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

public class NetworkUtils {

    public static final String LOOPBACK_HOST = "127.0.0.1"; // local loopback address
    public static final String LOCALHOST = "localhost";

    private NetworkUtils() {
    }

    /**
     * Parse a URI, defaulting to http when no scheme is present.
     */
    public static URI parseUri(String uriString) {
        if (uriString != null && uriString.length() > 0 && !uriString.toLowerCase(Locale.ROOT).matches("^[a-z][a-z0-9+.-]*://.+")) {
            String trimmedUriString = uriString.trim();
            if (trimmedUriString.startsWith("[") || trimmedUriString.indexOf(':') != trimmedUriString.lastIndexOf(':')) {
                try {
                    HostAndPort hostAndPort = parseHostAndPort(trimmedUriString, -1);
                    if (isIpv6Literal(hostAndPort.getHost())) {
                        uriString = "http://" + (hostAndPort.hasPort() ? formatHostAndPort(hostAndPort.getHost(), hostAndPort.getPort()) : formatHost(hostAndPort.getHost()));
                    } else {
                        uriString = "http://" + uriString;
                    }
                } catch (IllegalArgumentException e) {
                    uriString = "http://" + uriString;
                }
            } else {
                uriString = "http://" + uriString;
            }
        }
        try {
            return new URI(uriString);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid URI: " + uriString, e);
        }
    }

    /**
     * Check if the given URI is on local host.
     */
    public static boolean isLocalHost(String uriString) {
        try {
            String host = parseUri(uriString).getHost();
            return LOOPBACK_HOST.equals(host) || LOCALHOST.equals(host) || (isLiteralIp(host) && getLiteralIpAddress(host).isLoopbackAddress());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check if the given URI is local or a private IP address.
     */
    public static boolean isPrivateIp(String uriString) {
        if (uriString == null || uriString.isEmpty()) return false;
        if (isLocalHost(uriString)) return true;
        try {
            String host = stripIpv6Brackets(parseUri(uriString).getHost());
            if (host == null) return false;

            // check if private IP address
            if (!isLiteralIp(host)) return false;
            InetAddress addr = getLiteralIpAddress(host);
            return addr.isAnyLocalAddress()
                        || addr.isLoopbackAddress()
                        || addr.isLinkLocalAddress()
                        || addr.isSiteLocalAddress();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check if the given URI's host is an IPv6 literal.
     */
    public static boolean isIpv6Uri(String uriString) {
        try {
            return isIpv6Literal(parseUri(uriString).getHost());
        } catch (Exception e) {
            return false;
        }
    }

    public static HostAndPort parseHostAndPort(String address, int defaultPort) {
        return parseHostAndPort(address, defaultPort, false);
    }

    public static HostAndPort parseHostAndPort(String address, int defaultPort, boolean portRequired) {
        if (address == null) throw new IllegalArgumentException("Address must not be null");
        String trimmedAddress = address.trim();
        if (trimmedAddress.isEmpty()) throw new IllegalArgumentException("Address must not be empty");

        String host;
        int port = defaultPort;
        if (trimmedAddress.startsWith("[")) {
            int closingBracketIndex = trimmedAddress.indexOf("]");
            if (closingBracketIndex <= 0) throw new IllegalArgumentException("Invalid bracketed IPv6 address");
            host = trimmedAddress.substring(1, closingBracketIndex);
            if (!isIpv6Literal(host)) throw new IllegalArgumentException("Invalid bracketed IPv6 address");

            String remainder = trimmedAddress.substring(closingBracketIndex + 1);
            if (remainder.isEmpty()) {
                if (portRequired) throw new IllegalArgumentException("Missing port");
            } else {
                if (!remainder.startsWith(":") || remainder.length() == 1) throw new IllegalArgumentException("Missing port");
                port = parsePort(remainder.substring(1));
            }
        } else {
            int colonCount = countChars(trimmedAddress, ':');
            if (colonCount == 0) {
                host = trimmedAddress;
                if (portRequired) throw new IllegalArgumentException("Missing port");
            } else if (colonCount == 1) {
                int colonIndex = trimmedAddress.lastIndexOf(':');
                host = trimmedAddress.substring(0, colonIndex);
                if (host.isEmpty()) throw new IllegalArgumentException("Address format not recognised");
                port = parsePort(trimmedAddress.substring(colonIndex + 1));
            } else if (isIpv6Literal(trimmedAddress)) {
                host = trimmedAddress;
                if (portRequired) throw new IllegalArgumentException("Missing port");
            } else {
                throw new IllegalArgumentException("Invalid IPv6 address");
            }
        }

        if (host.isEmpty()) throw new IllegalArgumentException("Address format not recognised");
        return new HostAndPort(stripIpv6Brackets(host), port);
    }

    public static String formatHostAndPort(String host, int port) {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid port: " + port);
        return formatHost(host) + ":" + port;
    }

    private static int parsePort(String portString) {
        try {
            int port = Integer.parseInt(portString);
            if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid port: " + portString);
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port: " + portString, e);
        }
    }

    private static String formatHost(String host) {
        host = stripIpv6Brackets(host);
        return isIpv6Literal(host) ? "[" + host + "]" : host;
    }

    private static String stripIpv6Brackets(String host) {
        return host != null && host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    private static boolean isLiteralIp(String host) {
        host = stripIpv6Brackets(host);
        return host != null && InetAddresses.isInetAddress(host);
    }

    private static boolean isIpv6Literal(String host) {
        host = stripIpv6Brackets(host);
        return host != null && host.indexOf(':') >= 0 && InetAddresses.isInetAddress(host) && InetAddresses.forString(host) instanceof Inet6Address;
    }

    private static InetAddress getLiteralIpAddress(String host) {
        host = stripIpv6Brackets(host);
        if (!isLiteralIp(host)) throw new IllegalArgumentException("Host is not a literal IP address: " + host);
        return InetAddresses.forString(host);
    }

    private static int countChars(String value, char character) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == character) count++;
        }
        return count;
    }

    public static class HostAndPort {
        private final String host;
        private final int port;

        private HostAndPort(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        public boolean hasPort() {
            return port >= 0;
        }
    }
}
