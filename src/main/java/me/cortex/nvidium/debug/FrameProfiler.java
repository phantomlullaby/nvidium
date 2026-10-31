package me.cortex.nvidium.debug;

import me.cortex.nvidium.Nvidium;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL15C.GL_QUERY_RESULT;
import static org.lwjgl.opengl.GL15C.GL_SAMPLES_PASSED;
import static org.lwjgl.opengl.GL15C.glBeginQuery;
import static org.lwjgl.opengl.GL15C.glEndQuery;
import static org.lwjgl.opengl.GL15C.GL_QUERY_RESULT_AVAILABLE;
import static org.lwjgl.opengl.GL15C.glGenQueries;
import static org.lwjgl.opengl.GL15C.glGetQueryObjecti;
import static org.lwjgl.opengl.GL33C.GL_TIMESTAMP;
import static org.lwjgl.opengl.GL33C.glGetQueryObjectui64;
import static org.lwjgl.opengl.GL33C.glQueryCounter;

/**
 * Debug-only frame profiler, active when "nvidium-profile.txt" exists in the game directory. {@link #mark} records a CPU time
 * and a GPU timestamp; the time until the next mark is attributed to the mark's name. Averages per frame are logged every
 * few seconds, so the cost of each stage of a (VR) frame can be read from the log.
 */
public final class FrameProfiler {
    public static final boolean ENABLED = Files.exists(Path.of("nvidium-profile.txt"));

    private static final int MAX_MARKS = 256;
    private static final int FRAMES_IN_FLIGHT = 8;
    private static final long REPORT_INTERVAL_NS = 5_000_000_000L;
    //When set, reports are only written on request (autotest "prof report <label>") instead of every few seconds
    private static boolean manualReports;
    private static String reportLabel = "";

    private static final class Frame {
        final String[] names = new String[MAX_MARKS];
        final long[] cpu = new long[MAX_MARKS];
        final int[] queries = new int[MAX_MARKS];
        int count;
        boolean pending;
    }

    private static final class Stat {
        double cpu;
        double gpu;
        long hits;
    }

    private static Frame[] frames;
    private static int current;
    private static final Map<String, Stat> stats = new LinkedHashMap<>();
    private static long framesCounted;
    private static double cpuFrameSum;
    private static double gpuFrameSum;
    private static long reportStart;
    private static long lastBoundary;
    private static double wallSum;
    private static double wallMax;
    private static final List<String> extraLines = new ArrayList<>();

    //Pipeline statistics samples (see beginStats), read back once available
    private static final int GL_FRAGMENT_SHADER_INVOCATIONS = 0x82F4;
    private static final int GL_CLIPPING_OUTPUT_PRIMITIVES = 0x82F7;
    private record StatSample(String name, int[] queries) {}
    private static final List<StatSample> pendingSamples = new ArrayList<>();
    private static final List<int[]> freeQueries = new ArrayList<>();
    private static final Map<String, long[]> sampleSums = new LinkedHashMap<>();
    private static StatSample activeSample;

    private FrameProfiler() {}

    /** Counts fragment shader invocations, samples passed and clipped primitives of the draws until {@link #endStats}. */
    public static void beginStats(String name) {
        if (!ENABLED || frames == null || activeSample != null) {
            return;
        }
        int[] queries = freeQueries.isEmpty() ? null : freeQueries.remove(freeQueries.size() - 1);
        if (queries == null) {
            queries = new int[3];
            glGenQueries(queries);
        }
        activeSample = new StatSample(name, queries);
        glBeginQuery(GL_FRAGMENT_SHADER_INVOCATIONS, queries[0]);
        glBeginQuery(GL_SAMPLES_PASSED, queries[1]);
        glBeginQuery(GL_CLIPPING_OUTPUT_PRIMITIVES, queries[2]);
    }

    public static void endStats() {
        if (activeSample == null) {
            return;
        }
        glEndQuery(GL_FRAGMENT_SHADER_INVOCATIONS);
        glEndQuery(GL_SAMPLES_PASSED);
        glEndQuery(GL_CLIPPING_OUTPUT_PRIMITIVES);
        pendingSamples.add(activeSample);
        activeSample = null;
    }

    private static void collectSamples() {
        for (int i = 0; i < pendingSamples.size(); i++) {
            StatSample sample = pendingSamples.get(i);
            if (glGetQueryObjecti(sample.queries[2], GL_QUERY_RESULT_AVAILABLE) == 0) {
                continue;
            }
            long[] sum = sampleSums.computeIfAbsent(sample.name, k -> new long[4]);
            for (int q = 0; q < 3; q++) {
                sum[q] += glGetQueryObjectui64(sample.queries[q], GL_QUERY_RESULT);
            }
            sum[3]++;
            freeQueries.add(sample.queries);
            pendingSamples.remove(i--);
        }
    }

    private static void init() {
        frames = new Frame[FRAMES_IN_FLIGHT];
        for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
            frames[i] = new Frame();
            glGenQueries(frames[i].queries);
        }
        reportStart = System.nanoTime();
    }

    public static void mark(String name) {
        if (!ENABLED || frames == null) {
            return;
        }
        Frame frame = frames[current];
        if (frame.pending || frame.count >= MAX_MARKS) {
            return;
        }
        frame.names[frame.count] = name;
        frame.cpu[frame.count] = System.nanoTime();
        glQueryCounter(frame.queries[frame.count], GL_TIMESTAMP);
        frame.count++;
    }

    /** Called at the start of every client frame (Minecraft.runTick), closes the previous frame. */
    public static void frameBoundary() {
        if (!ENABLED) {
            return;
        }
        if (frames == null) {
            init();
        }
        long now = System.nanoTime();
        if (lastBoundary != 0) {
            double wall = (now - lastBoundary) / 1e6;
            wallSum += wall;
            wallMax = Math.max(wallMax, wall);
        }
        lastBoundary = now;

        Frame frame = frames[current];
        if (!frame.pending && frame.count > 0) {
            mark("<end>");
            frame.pending = true;
            logHitch(frame);
        }
        collect();
        collectSamples();
        current = (current + 1) % FRAMES_IN_FLIGHT;
        Frame next = frames[current];
        if (next.pending) {
            //Results still not available after a full ring of frames, drop that frame
            next.pending = false;
        }
        next.count = 0;

        if (!manualReports && now - reportStart > REPORT_INTERVAL_NS && framesCounted > 0) {
            report(now);
        }
        mark("frame");
    }

    /** Starts a fresh measurement window, reports then only happen through {@link #reportNow}. */
    public static void reset() {
        if (!ENABLED || frames == null) {
            return;
        }
        manualReports = true;
        collect();
        report(System.nanoTime(), false);
    }

    public static void reportNow(String label) {
        if (!ENABLED || frames == null || framesCounted == 0) {
            return;
        }
        manualReports = true;
        reportLabel = label;
        report(System.nanoTime(), true);
        reportLabel = "";
    }

    //Logs the slowest CPU segments of a frame that took far longer than a VR frame
    private static void logHitch(Frame frame) {
        long total = frame.cpu[frame.count - 1] - frame.cpu[0];
        if (total < 40_000_000L) {
            return;
        }
        var segments = new ArrayList<String>();
        for (int i = 0; i < frame.count - 1; i++) {
            long duration = frame.cpu[i + 1] - frame.cpu[i];
            if (duration > 5_000_000L) {
                segments.add(String.format("%s %.1f ms", frame.names[i], duration / 1e6));
            }
        }
        Nvidium.LOGGER.info("[profile] hitch {} ms cpu: {}", String.format("%.1f", total / 1e6), segments);
    }

    public static void addLine(String line) {
        if (ENABLED) {
            extraLines.add(line);
        }
    }

    private static void collect() {
        for (int i = 1; i <= FRAMES_IN_FLIGHT; i++) {
            Frame frame = frames[(current + i) % FRAMES_IN_FLIGHT];
            if (!frame.pending) {
                continue;
            }
            if (glGetQueryObjecti(frame.queries[frame.count - 1], GL_QUERY_RESULT_AVAILABLE) == 0) {
                continue;
            }
            long first = glGetQueryObjectui64(frame.queries[0], GL_QUERY_RESULT);
            long prev = first;
            for (int m = 0; m < frame.count - 1; m++) {
                long next = glGetQueryObjectui64(frame.queries[m + 1], GL_QUERY_RESULT);
                Stat stat = stats.computeIfAbsent(frame.names[m], k -> new Stat());
                stat.cpu += (frame.cpu[m + 1] - frame.cpu[m]) / 1e6;
                stat.gpu += (next - prev) / 1e6;
                stat.hits++;
                prev = next;
            }
            cpuFrameSum += (frame.cpu[frame.count - 1] - frame.cpu[0]) / 1e6;
            gpuFrameSum += (prev - first) / 1e6;
            framesCounted++;
            frame.pending = false;
        }
    }

    private static void report(long now) {
        report(now, true);
    }

    private static void report(long now, boolean print) {
        double seconds = (now - reportStart) / 1e9;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[profile] %s%.1f fps over %.1fs, wall %.2f ms (max %.1f), cpu %.2f ms, gpu %.2f ms per frame%n",
                reportLabel.isEmpty() ? "" : reportLabel + ": ", framesCounted / seconds, seconds, wallSum / framesCounted, wallMax, cpuFrameSum / framesCounted, gpuFrameSum / framesCounted));
        for (var entry : stats.entrySet()) {
            Stat stat = entry.getValue();
            sb.append(String.format("[profile]   %-28s cpu %7.3f  gpu %7.3f  (x%.1f)%n", entry.getKey(),
                    stat.cpu / framesCounted, stat.gpu / framesCounted, stat.hits / (double) framesCounted));
        }
        for (var entry : sampleSums.entrySet()) {
            long[] sum = entry.getValue();
            sb.append(String.format("[profile]   stats %-22s fs %7.2fM  samples %7.2fM  prims %7.2fM per frame (x%.1f)%n", entry.getKey(),
                    sum[0] / 1e6 / framesCounted, sum[1] / 1e6 / framesCounted, sum[2] / 1e6 / framesCounted, sum[3] / (double) framesCounted));
        }
        sampleSums.clear();
        for (String line : extraLines) {
            sb.append("[profile]   ").append(line).append('\n');
        }
        if (print) {
            Nvidium.LOGGER.info("\n{}", sb);
        }
        stats.clear();
        extraLines.clear();
        framesCounted = 0;
        cpuFrameSum = 0;
        gpuFrameSum = 0;
        wallSum = 0;
        wallMax = 0;
        reportStart = now;
    }
}
