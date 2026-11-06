/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.common.util;

import haveno.common.UserThread;
import lombok.extern.slf4j.Slf4j;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import javax.management.JMException;
import javax.management.ObjectName;

@Slf4j
public class Profiler {

    private static final double HIGH_MEMORY_THRESHOLD = 0.9;
    private static final int CLASS_HISTOGRAM_MAX_CLASSES = 40;
    private static final int CLASS_HISTOGRAM_MAX_NON_JDK_CLASSES = 60;
    private static final Pattern JDK_MODULE = Pattern.compile("\\((java|jdk)\\.[^()]*\\)\\s*$");
    private static final long CLASS_HISTOGRAM_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(10);
    private static long lastClassHistogramNanos = System.nanoTime() - CLASS_HISTOGRAM_INTERVAL_NANOS;
    private static final AtomicBoolean memoryMonitorStarted = new AtomicBoolean();

    public static void printSystemLoadPeriodically(long delay, TimeUnit timeUnit) {
        UserThread.runPeriodically(Profiler::printSystemLoad, delay, timeUnit);
    }

    public static void warnOnHighMemoryUsagePeriodically(long delay, TimeUnit timeUnit) {
        if (!memoryMonitorStarted.compareAndSet(false, true)) return; // one monitor per JVM, since the daemon restarts in process
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "MemoryMonitor");
            thread.setDaemon(true);
            return thread;
        });
        executor.execute(ManagementFactory::getPlatformMBeanServer); // initialize while memory is healthy, for the class histogram
        executor.scheduleWithFixedDelay(Profiler::warnIfMemoryHigh, delay, delay, timeUnit);
    }

    private static void warnIfMemoryHigh() {
        try {
            Runtime runtime = Runtime.getRuntime();
            long max = runtime.maxMemory();
            if (max <= 0) {
                return;
            }
            // measure usage after the last GC (the retained set); transient pre-GC peaks are normal
            long used = getUsedMemoryAfterLastGcInBytes();
            if (used < 0) {
                used = runtime.totalMemory() - runtime.freeMemory();
            }
            double ratio = (double) used / max;
            if (ratio >= HIGH_MEMORY_THRESHOLD) {
                log.warn("Memory usage critically high: {} of {} max ({}%) still used after garbage collection. " +
                        "An OutOfMemoryError may be imminent, which terminates the application immediately.",
                        Utilities.readableFileSize(used), Utilities.readableFileSize(max), Math.round(ratio * 100));

                // name what fills the heap, at most once per interval since the histogram runs a full GC
                long now = System.nanoTime();
                if (now - lastClassHistogramNanos >= CLASS_HISTOGRAM_INTERVAL_NANOS) {
                    lastClassHistogramNanos = now;
                    log.warn("Classes using the most memory, then the largest non JDK classes:\n{}",
                            getClassHistogram(CLASS_HISTOGRAM_MAX_CLASSES, CLASS_HISTOGRAM_MAX_NON_JDK_CLASSES));
                }
            }
        } catch (Throwable t) {
            log.warn("Error checking memory usage", t);
        }
    }

    // live heap by class with names, instance counts and sizes only; non JDK rows name the owners of generic JDK objects
    static String getClassHistogram(int maxClasses, int maxNonJdkClasses) throws JMException {
        String histogram = (String) ManagementFactory.getPlatformMBeanServer().invoke(
                new ObjectName("com.sun.management:type=DiagnosticCommand"), "gcClassHistogram",
                new Object[]{null}, new String[]{String[].class.getName()});
        List<String> lines = new ArrayList<>();
        int numClasses = 0;
        int numNonJdkClasses = 0;
        for (String line : (Iterable<String>) histogram.lines()::iterator) {
            if (lines.size() < 2 || line.startsWith("Total")) {
                lines.add(line); // header and total
            } else if (numClasses++ < maxClasses) {
                lines.add(line);
            } else if (numNonJdkClasses < maxNonJdkClasses && !JDK_MODULE.matcher(line).find()) {
                lines.add(line);
                numNonJdkClasses++;
            }
        }
        return String.join("\n", lines);
    }

    // heap used after the most recent GC, or -1 if the JVM does not report it
    private static long getUsedMemoryAfterLastGcInBytes() {
        long sum = -1;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP) {
                continue;
            }
            MemoryUsage usage = pool.getCollectionUsage();
            if (usage == null) {
                continue;
            }
            sum = Math.max(sum, 0) + usage.getUsed();
        }
        return sum;
    }

    public static void printSystemLoad() {
        Runtime runtime = Runtime.getRuntime();
        long free = runtime.freeMemory();
        long total = runtime.totalMemory();
        long used = total - free;

        log.info("Total memory: {}; Used memory: {}; Free memory: {}; Max memory: {}; No. of threads: {}",
                Utilities.readableFileSize(total),
                Utilities.readableFileSize(used),
                Utilities.readableFileSize(free),
                Utilities.readableFileSize(runtime.maxMemory()),
                Thread.activeCount());
    }

    public static long getUsedMemoryInMB() {
        return getUsedMemoryInBytes() / 1024 / 1024;
    }

    public static long getUsedMemoryInBytes() {
        Runtime runtime = Runtime.getRuntime();
        long free = runtime.freeMemory();
        long total = runtime.totalMemory();
        return total - free;
    }

    public static long getFreeMemoryInMB() {
        return Runtime.getRuntime().freeMemory() / 1024 / 1024;
    }

    public static long getTotalMemoryInMB() {
        return Runtime.getRuntime().totalMemory() / 1024 / 1024;
    }
}
