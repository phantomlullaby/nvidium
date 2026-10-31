package me.cortex.nvidium.debug;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import me.cortex.nvidium.Nvidium;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL21C.GL_PIXEL_PACK_BUFFER;
import static org.lwjgl.opengl.GL21C.GL_PIXEL_PACK_BUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30C.GL_MAP_READ_BIT;
import static org.lwjgl.opengl.GL32C.*;
import static org.lwjgl.opengl.GL45C.*;

/**
 * Debug-only: finds regions of a VR eye image that brighten for a single frame. Every frame both eye images are downscaled 4x
 * on the GPU and read back asynchronously, and a worker thread compares the mean luminance of every 32x32 pixel block of frame
 * n with frames n-1 and n+1. A block brighter than both neighbours by more than a threshold is a flash; the three frames around
 * the strongest ones are saved as PNGs in "flashes/".
 * <p>
 * Driven by autotest ("flash on &lt;label&gt;|off|report &lt;label&gt;"), or during normal play when "nvidium-flashab.txt" exists in the
 * game directory (needs the profile hooks, so also "nvidium-profile.txt"): then foveated shading is switched on and off every
 * {@link #AB_PERIOD_NS} and the flashes are counted per eye for each setting, reported to the log every minute.
 */
public final class FlashDetector {
    private static final int SCALE = 4;
    private static final int BLOCK = 8;
    private static final int DEPTH = 3;
    private static final float[] THRESHOLDS = {4, 8, 16, 32};
    private static final float SAVE_THRESHOLD = 16;
    private static final int MAX_SAVED = 60;
    private static final long AB_PERIOD_NS = 15_000_000_000L;
    private static final String[] MODES = {"A: normal", "B: frame handoff before the left eye", "C: no depth pre-pass", "D: culling fix"};
    private static int currentMode = -1;
    private static final long REPORT_PERIOD_NS = 60_000_000_000L;
    //Frames waiting for the worker; more are dropped rather than queued
    private static final int MAX_PENDING = 8;

    public static boolean enabled;
    private static boolean autoChecked;
    private static boolean autoAB;
    private static long abStart;
    private static long lastReport;
    private static volatile String label = "run";
    private static long frame;
    private static final Eye[] EYES = {new Eye(), new Eye()};

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "nvidium-flash-detector");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicInteger pending = new AtomicInteger();
    private static final ConcurrentLinkedQueue<byte[]> freeBuffers = new ConcurrentLinkedQueue<>();

    //Worker thread state
    private static final Map<String, long[][]> counts = new LinkedHashMap<>();
    private static final Map<String, long[]> analysed = new LinkedHashMap<>();
    private static final List<String> events = new ArrayList<>();
    private static int saved;
    private static final Map<String, Integer> savedPerLabel = new java.util.HashMap<>();
    private static final History[] HISTORY = {new History(), new History()};

    //Render thread state per eye
    private static final class Eye {
        int srcFbo, dstFbo, texture, width, height, sourceWidth, sourceHeight;
        final int[] pbo = new int[DEPTH];
        final long[] fence = new long[DEPTH];
        final long[] pendingFrame = new long[DEPTH];
        final String[] pendingLabel = new String[DEPTH];
    }

    //Worker thread state per eye: the last three analysed frames, oldest first
    private static final class History {
        final byte[][] image = new byte[3][];
        final float[][] luma = new float[3][];
        final long[] frameOf = new long[3];
        final String[] labelOf = new String[3];
        int width, height, sourceHeight;
    }

    private FlashDetector() {}

    private static void applyMode(int mode) {
        me.cortex.nvidium.vr.SubmitDepth.deferredHandoff = mode != 1;
        me.cortex.nvidium.iris.IrisCompat.depthPrepass = mode != 2;
        me.cortex.nvidium.RenderPipeline.cullingFix = mode == 3;
    }

    public static void command(String arg) {
        String[] parts = arg.split(" ");
        switch (parts[0]) {
            case "on" -> {
                enabled = true;
                label = parts.length > 1 ? parts[1] : "run";
                WORKER.execute(FlashDetector::reset);
            }
            case "off" -> enabled = false;
            case "report" -> {
                String name = parts.length > 1 ? parts[1] : label;
                WORKER.execute(() -> {
                    report(name);
                    reset();
                });
            }
            default -> Nvidium.LOGGER.warn("[flash] unknown command {}", arg);
        }
    }

    //Worker thread
    private static void reset() {
        counts.clear();
        analysed.clear();
        events.clear();
        saved = 0;
        savedPerLabel.clear();
    }

    //Worker thread
    private static void report(String name) {
        for (var entry : analysed.entrySet()) {
            String mode = entry.getKey();
            long[][] c = counts.computeIfAbsent(mode, k -> new long[2][THRESHOLDS.length]);
            Nvidium.LOGGER.info("[flash] {}: {} frames analysed per eye", mode, entry.getValue()[0]);
            for (int e = 0; e < 2; e++) {
                StringBuilder line = new StringBuilder("[flash] " + mode + (e == 0 ? " L" : " R") + " frames with a one-frame flash");
                for (int t = 0; t < THRESHOLDS.length; t++) {
                    line.append(String.format(" >%.0f: %d", THRESHOLDS[t], c[e][t]));
                }
                Nvidium.LOGGER.info(line.toString());
            }
        }
        events.stream().limit(80).forEach(event -> Nvidium.LOGGER.info("[flash] {} {}", name, event));
        events.clear();
    }

    /** Called once per frame after both eyes are rendered. */
    public static void frame(RenderTarget left, RenderTarget right) {
        if (!autoChecked) {
            autoChecked = true;
            if (Files.exists(Path.of("nvidium-flashab.txt"))) {
                autoAB = enabled = true;
                abStart = lastReport = System.nanoTime();
                Nvidium.LOGGER.info("[flash] automatic foveated shading A/B started");
            }
        }
        if (!enabled) {
            return;
        }
        if (autoAB) {
            var minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft.level == null || minecraft.player == null) {
                abStart = System.nanoTime();
                return;
            }
            long now = System.nanoTime();
            int mode = (int) (((now - abStart) / AB_PERIOD_NS) % MODES.length);
            if (mode != currentMode) {
                currentMode = mode;
                applyMode(mode);
                Nvidium.LOGGER.info("[flash] mode {}", MODES[mode]);
                minecraft.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal("nvidium test " + MODES[mode]));
            }
            label = MODES[mode].substring(0, 1);
            if (now - lastReport > REPORT_PERIOD_NS) {
                lastReport = now;
                WORKER.execute(() -> report("auto"));
            }
        }
        frame++;
        int slot = (int) (frame % DEPTH);
        int packBuffer = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        int packAlignment = glGetInteger(GL_PACK_ALIGNMENT);
        int packRowLength = glGetInteger(GL_PACK_ROW_LENGTH);
        int packSkipRows = glGetInteger(GL_PACK_SKIP_ROWS);
        int packSkipPixels = glGetInteger(GL_PACK_SKIP_PIXELS);
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glPixelStorei(GL_PACK_ROW_LENGTH, 0);
        glPixelStorei(GL_PACK_SKIP_ROWS, 0);
        glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
        glDisable(GL_SCISSOR_TEST);
        try {
            capture(0, EYES[0], left, slot);
            capture(1, EYES[1], right, slot);
        } finally {
            if (scissor) {
                glEnable(GL_SCISSOR_TEST);
            }
            glPixelStorei(GL_PACK_ALIGNMENT, packAlignment);
            glPixelStorei(GL_PACK_ROW_LENGTH, packRowLength);
            glPixelStorei(GL_PACK_SKIP_ROWS, packSkipRows);
            glPixelStorei(GL_PACK_SKIP_PIXELS, packSkipPixels);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, packBuffer);
        }
    }

    private static void capture(int index, Eye eye, RenderTarget target, int slot) {
        if (target == null || !(target.getColorTexture() instanceof GlTexture texture)) {
            return;
        }
        if (eye.texture == 0 || eye.sourceWidth != target.width || eye.sourceHeight != target.height) {
            init(eye, target.width, target.height);
        }
        if (eye.pendingFrame[slot] != 0) {
            collect(index, eye, slot);
        }
        glNamedFramebufferTexture(eye.srcFbo, GL_COLOR_ATTACHMENT0, texture.glId(), 0);
        glBlitNamedFramebuffer(eye.srcFbo, eye.dstFbo, 0, 0, eye.sourceWidth, eye.sourceHeight, 0, 0, eye.width, eye.height, GL_COLOR_BUFFER_BIT, GL_LINEAR);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, eye.pbo[slot]);
        glGetTextureImage(eye.texture, 0, GL_RGBA, GL_UNSIGNED_BYTE, eye.width * eye.height * 4, 0L);
        eye.fence[slot] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        eye.pendingFrame[slot] = frame;
        eye.pendingLabel[slot] = label;
    }

    private static void init(Eye eye, int sourceWidth, int sourceHeight) {
        if (eye.texture != 0) {
            glDeleteTextures(eye.texture);
            glDeleteFramebuffers(eye.srcFbo);
            glDeleteFramebuffers(eye.dstFbo);
            glDeleteBuffers(eye.pbo);
            for (int i = 0; i < DEPTH; i++) {
                if (eye.fence[i] != 0) {
                    glDeleteSync(eye.fence[i]);
                }
                eye.fence[i] = 0;
                eye.pendingFrame[i] = 0;
            }
        }
        eye.sourceWidth = sourceWidth;
        eye.sourceHeight = sourceHeight;
        eye.width = sourceWidth / SCALE;
        eye.height = sourceHeight / SCALE;
        eye.texture = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(eye.texture, 1, GL_RGBA8, eye.width, eye.height);
        eye.srcFbo = glCreateFramebuffers();
        eye.dstFbo = glCreateFramebuffers();
        glNamedFramebufferTexture(eye.dstFbo, GL_COLOR_ATTACHMENT0, eye.texture, 0);
        for (int i = 0; i < DEPTH; i++) {
            eye.pbo[i] = glCreateBuffers();
            glNamedBufferStorage(eye.pbo[i], (long) eye.width * eye.height * 4, GL_MAP_READ_BIT);
        }
    }

    private static void collect(int index, Eye eye, int slot) {
        glClientWaitSync(eye.fence[slot], GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L);
        glDeleteSync(eye.fence[slot]);
        eye.fence[slot] = 0;
        long frameNumber = eye.pendingFrame[slot];
        String frameLabel = eye.pendingLabel[slot];
        eye.pendingFrame[slot] = 0;
        if (pending.get() >= MAX_PENDING) {
            return;
        }
        int bytes = eye.width * eye.height * 4;
        ByteBuffer mapped = glMapNamedBufferRange(eye.pbo[slot], 0, bytes, GL_MAP_READ_BIT);
        if (mapped == null) {
            return;
        }
        byte[] pixels = freeBuffers.poll();
        if (pixels == null || pixels.length != bytes) {
            pixels = new byte[bytes];
        }
        mapped.get(0, pixels, 0, bytes);
        glUnmapNamedBuffer(eye.pbo[slot]);
        byte[] image = pixels;
        int width = eye.width, height = eye.height, sourceHeight = eye.sourceHeight;
        pending.incrementAndGet();
        WORKER.execute(() -> {
            try {
                analyse(index, frameNumber, frameLabel, image, width, height, sourceHeight);
            } catch (Throwable t) {
                Nvidium.LOGGER.error("[flash] analysis failed", t);
            } finally {
                pending.decrementAndGet();
            }
        });
    }

    //Worker thread
    private static void analyse(int index, long frameNumber, String frameLabel, byte[] pixels, int width, int height, int sourceHeight) {
        History history = HISTORY[index];
        if (history.width != width || history.height != height) {
            history.width = width;
            history.height = height;
            java.util.Arrays.fill(history.image, null);
            java.util.Arrays.fill(history.luma, null);
            java.util.Arrays.fill(history.frameOf, 0);
        }
        history.sourceHeight = sourceHeight;
        int blocksX = width / BLOCK, blocksY = height / BLOCK;
        float[] luma = history.luma[0] != null ? history.luma[0] : new float[blocksX * blocksY];
        java.util.Arrays.fill(luma, 0);
        for (int y = 0; y < blocksY * BLOCK; y++) {
            int row = y * width * 4;
            int blockRow = (y / BLOCK) * blocksX;
            for (int x = 0; x < blocksX * BLOCK; x++) {
                int p = row + x * 4;
                luma[blockRow + x / BLOCK] += 0.2126f * (pixels[p] & 0xFF) + 0.7152f * (pixels[p + 1] & 0xFF) + 0.0722f * (pixels[p + 2] & 0xFF);
            }
        }
        for (int i = 0; i < luma.length; i++) {
            luma[i] /= BLOCK * BLOCK;
        }
        if (history.image[0] != null) {
            freeBuffers.offer(history.image[0]);
        }
        history.image[0] = history.image[1];
        history.image[1] = history.image[2];
        history.image[2] = pixels;
        history.luma[0] = history.luma[1];
        history.luma[1] = history.luma[2];
        history.luma[2] = luma;
        history.frameOf[0] = history.frameOf[1];
        history.frameOf[1] = history.frameOf[2];
        history.frameOf[2] = frameNumber;
        history.labelOf[0] = history.labelOf[1];
        history.labelOf[1] = history.labelOf[2];
        history.labelOf[2] = frameLabel;
        //Only consecutive frames rendered with the same setting
        if (history.image[0] == null || history.frameOf[2] - history.frameOf[0] != 2
                || !frameLabel.equals(history.labelOf[0]) || !frameLabel.equals(history.labelOf[1])) {
            return;
        }
        analysed.computeIfAbsent(frameLabel, k -> new long[2])[index]++;
        float best = 0;
        int bestBlock = 0;
        float[] before = history.luma[0], flash = history.luma[1], after = history.luma[2];
        for (int i = 0; i < flash.length; i++) {
            float rise = Math.min(flash[i] - before[i], flash[i] - after[i]);
            if (rise > best) {
                best = rise;
                bestBlock = i;
            }
        }
        long[][] c = counts.computeIfAbsent(frameLabel, k -> new long[2][THRESHOLDS.length]);
        for (int t = 0; t < THRESHOLDS.length; t++) {
            if (best > THRESHOLDS[t]) {
                c[index][t]++;
            }
        }
        if (best > THRESHOLDS[1]) {
            //Pixel position in the full eye image, y counted from the top
            int x = (bestBlock % blocksX) * BLOCK * SCALE + BLOCK * SCALE / 2;
            int y = sourceHeight - ((bestBlock / blocksX) * BLOCK * SCALE + BLOCK * SCALE / 2);
            String event = String.format("%s frame %d eye %s rise %.1f at (%d, %d) block luma %.1f -> %.1f -> %.1f", frameLabel, history.frameOf[1],
                    index == 0 ? "L" : "R", best, x, y, before[bestBlock], flash[bestBlock], after[bestBlock]);
            events.add(event);
            int savedForLabel = savedPerLabel.merge(frameLabel, 1, Integer::sum);
            if ((best > SAVE_THRESHOLD || !autoAB) && saved < MAX_SAVED && savedForLabel <= MAX_SAVED / 4) {
                saved++;
                save(history, String.format("flash_%s_%02d_%s.png", frameLabel, saved, index == 0 ? "L" : "R"));
                Nvidium.LOGGER.info("[flash] saved #{}: {}", saved, event);
            }
        }
    }

    //Worker thread
    private static void save(History history, String name) {
        int width = history.width, height = history.height;
        try (NativeImage image = new NativeImage(width * 3, height, false)) {
            for (int f = 0; f < 3; f++) {
                byte[] pixels = history.image[f];
                for (int y = 0; y < height; y++) {
                    int row = (height - 1 - y) * width * 4;
                    for (int x = 0; x < width; x++) {
                        int p = row + x * 4;
                        int argb = 0xFF000000 | (pixels[p] & 0xFF) << 16 | (pixels[p + 1] & 0xFF) << 8 | (pixels[p + 2] & 0xFF);
                        image.setPixel(f * width + x, y, argb);
                    }
                }
            }
            Path dir = Path.of("flashes");
            Files.createDirectories(dir);
            image.writeToFile(dir.resolve(name));
        } catch (Exception e) {
            Nvidium.LOGGER.error("[flash] could not save {}", name, e);
        }
    }
}
