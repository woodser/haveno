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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NetworkUtilsTest {

    @Test
    public void testParseUriSupportsIpv6WithoutScheme() {
        assertEquals("[::1]", NetworkUtils.parseUri("[::1]:18081").getHost());
        assertEquals(18081, NetworkUtils.parseUri("[::1]:18081").getPort());
        assertEquals("[2607:3c40:1900:33e0::1]", NetworkUtils.parseUri("2607:3c40:1900:33e0::1").getHost());
    }

    @Test
    public void testIsLocalHostSupportsIpv6LoopbackWithoutScheme() {
        assertTrue(NetworkUtils.isLocalHost("[::1]:18081"));
        assertTrue(NetworkUtils.isLocalHost("http://[::1]:18081"));
    }

    @Test
    public void testIsPrivateIpSupportsIpv6WithoutScheme() {
        assertTrue(NetworkUtils.isPrivateIp("fe80::1"));
        assertTrue(NetworkUtils.isPrivateIp("[fe80::1]:18081"));
        assertTrue(NetworkUtils.isPrivateIp("http://[fe80::1]:18081"));
        assertFalse(NetworkUtils.isPrivateIp("[2607:3c40:1900:33e0::1]:18089"));
    }

    @Test
    public void testIsIpv6Uri() {
        assertTrue(NetworkUtils.isIpv6Uri("http://[2607:3c40:1900:33e0::1]:18089"));
        assertFalse(NetworkUtils.isIpv6Uri("http://127.0.0.1:18081"));
        assertFalse(NetworkUtils.isIpv6Uri("http://example.com:18081"));
    }

    @Test
    public void testParseHostAndPortParsesBracketedIpv6() {
        NetworkUtils.HostAndPort hostAndPort = NetworkUtils.parseHostAndPort("[2607:3c40:1900:33e0::1]:18089", -1);
        assertEquals("2607:3c40:1900:33e0::1", hostAndPort.getHost());
        assertEquals(18089, hostAndPort.getPort());
        assertTrue(hostAndPort.hasPort());
    }

    @Test
    public void testParseHostAndPortAppliesDefaultPort() {
        NetworkUtils.HostAndPort hostAndPort = NetworkUtils.parseHostAndPort("feder8.me", 18081);
        assertEquals("feder8.me", hostAndPort.getHost());
        assertEquals(18081, hostAndPort.getPort());
    }

    @Test
    public void testParseHostAndPortRejectsBracketedNonIpv6() {
        assertThrows(IllegalArgumentException.class, () -> NetworkUtils.parseHostAndPort("[localhost]:9050", -1));
    }

    @Test
    public void testParseHostAndPortRejectsUnbracketedIpv6WithPort() {
        assertThrows(IllegalArgumentException.class, () -> NetworkUtils.parseHostAndPort("2a0b:f4c2:2::63:18081", -1, true));
    }

    @Test
    public void testParseHostAndPortRejectsMissingPortWhenRequired() {
        assertThrows(IllegalArgumentException.class, () -> NetworkUtils.parseHostAndPort("127.0.0.1", -1, true));
    }

    @Test
    public void testParseHostAndPortRejectsPortOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> NetworkUtils.parseHostAndPort("[::1]:65536", -1));
    }

    @Test
    public void testParseHostAndPortRejectsNullAndEmpty() {
        assertThrows(IllegalArgumentException.class, () -> NetworkUtils.parseHostAndPort(null, -1));
        assertThrows(IllegalArgumentException.class, () -> NetworkUtils.parseHostAndPort("   ", -1));
    }

    @Test
    public void testFormatHostAndPort() {
        assertEquals("127.0.0.1:9050", NetworkUtils.formatHostAndPort("127.0.0.1", 9050));
        assertEquals("[::1]:9050", NetworkUtils.formatHostAndPort("::1", 9050));
        assertEquals("[::1]:9050", NetworkUtils.formatHostAndPort("[::1]", 9050));
        assertEquals("feder8.me:18089", NetworkUtils.formatHostAndPort("feder8.me", 18089));
    }
}
