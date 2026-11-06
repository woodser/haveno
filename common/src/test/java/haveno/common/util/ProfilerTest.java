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

import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfilerTest {

    private static final class Retained {
    }

    // the largest non JDK class in the test JVM
    private static final Retained[] RETAINED = new Retained[200_000];

    static {
        Arrays.setAll(RETAINED, i -> new Retained());
    }

    @Test
    void getsLargestClassesWithHeaderAndTotal() throws Exception {
        String[] lines = Profiler.getClassHistogram(3, 0).split("\n");
        assertEquals(6, lines.length);
        assertTrue(lines[0].contains("class name"));
        assertTrue(lines[2].trim().startsWith("1:"));
        assertTrue(lines[4].trim().startsWith("3:"));
        assertTrue(lines[5].startsWith("Total"));
    }

    @Test
    void getsLargestNonJdkClasses() throws Exception {
        String[] lines = Profiler.getClassHistogram(0, 5).split("\n");
        assertEquals(8, lines.length);
        for (int i = 2; i < 7; i++) assertFalse(lines[i].matches(".*\\((java|jdk)\\..*\\)\\s*$"), lines[i]);
        assertTrue(Arrays.stream(lines).anyMatch(line -> line.endsWith(Retained.class.getName())));
    }
}
