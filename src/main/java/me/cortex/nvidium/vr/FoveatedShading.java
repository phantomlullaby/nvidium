package me.cortex.nvidium.vr;

import me.cortex.nvidium.Nvidium;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11C.GL_UNPACK_ALIGNMENT;
import static org.lwjgl.opengl.GL30C.GL_RED_INTEGER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL11C.glPixelStorei;
import static org.lwjgl.opengl.GL11C.GL_PACK_ALIGNMENT;
import static org.lwjgl.opengl.GL11C.GL_UNPACK_ROW_LENGTH;
import static org.lwjgl.opengl.GL11C.GL_UNPACK_SKIP_PIXELS;
import static org.lwjgl.opengl.GL11C.GL_UNPACK_SKIP_ROWS;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL21C.GL_PIXEL_PACK_BUFFER;
import static org.lwjgl.opengl.GL21C.GL_PIXEL_PACK_BUFFER_BINDING;
import static org.lwjgl.opengl.GL21C.GL_PIXEL_UNPACK_BUFFER;
import static org.lwjgl.opengl.GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.GL_R8UI;
import static org.lwjgl.opengl.GL45C.glCreateTextures;
import static org.lwjgl.opengl.GL45C.glDeleteTextures;
import static org.lwjgl.opengl.GL45C.glTextureStorage2D;
import static org.lwjgl.opengl.GL45C.glTextureSubImage2D;
import static org.lwjgl.opengl.NVShadingRateImage.*;

/**
 * Foveated shading for Vivecraft eyes streamed through WiVRn. WiVRn's encoder squeezes each rendered eye into a smaller
 * foveated image: per axis, a band around the lens center keeps every rendered pixel, and outwards each streamed pixel
 * averages 2, 3, 4... rendered pixels. This replicates that mapping and uses NV_shading_rate_image to shade each 16x16 tile
 * once per 2 or 4 pixels along an axis wherever WiVRn averages at least that many pixels there, so every streamed pixel still
 * gets at least one full shading sample. Geometry coverage and depth stay per pixel, only the fragment shading is coarser.
 */
public final class FoveatedShading {
    //Rendered-pixels-per-streamed-pixel to treat as one step coarser than WiVRn really uses, so small mismatches in the
    // foveation center (eye distance, fov rounding) never coarsen a tile WiVRn keeps at full resolution
    private static final int SAFETY_MARGIN = 24;
    //Tiles in each corner of the eye that always shade every pixel
    private static final int CORNER_TILES = 2;
    //WiVRn's assumed gaze without eye tracking is 10 degrees below straight ahead (server/compositor/foveation.cpp)
    private static final double GAZE_PITCH_OFFSET = Math.toRadians(10);
    //WiVRn converges the foveation centers of both eyes at 1 meter
    private static final double CONVERGENCE_DISTANCE = 1;

    private static final int[] PALETTE = {
            GL_SHADING_RATE_1_INVOCATION_PER_PIXEL_NV,
            GL_SHADING_RATE_1_INVOCATION_PER_1X2_PIXELS_NV,
            GL_SHADING_RATE_1_INVOCATION_PER_2X1_PIXELS_NV,
            GL_SHADING_RATE_1_INVOCATION_PER_2X2_PIXELS_NV,
            GL_SHADING_RATE_1_INVOCATION_PER_2X4_PIXELS_NV,
            GL_SHADING_RATE_1_INVOCATION_PER_4X2_PIXELS_NV,
            GL_SHADING_RATE_1_INVOCATION_PER_4X4_PIXELS_NV,
    };

    //Rendered pixels WiVRn has to average per streamed pixel, per axis, before a tile is shaded at a coarser rate: a 2 (or 4)
    // pixel coarse shading needs a ratio of at least 2*k (4*k). 1 = every streamed pixel gets one shading sample, higher keeps
    // more of the supersampling WiVRn gets from averaging fully shaded pixels, at a higher cost
    public static double sampleFactor = Nvidium.config.vr_foveation_sample_factor;

    private static Boolean supported;
    public static boolean runtimeEnabled = true;

    private static final EyeImage[] EYES = {new EyeImage(), new EyeImage()};
    private static int activeEye = -1;
    private static Matrix4fc eyeProjection;
    private static double eyeOffsetX;
    private static boolean worldPasses;
    private static int suspendDepth;
    private static boolean fullViewport = true;
    private static boolean glEnabled;

    //Shader pack program being drawn by an Iris composite renderer ("deferred1", "composite3", "final"), null otherwise
    private static String currentPass;
    //Programs always shaded at full rate
    public static java.util.Set<String> fullRatePasses = java.util.Set.copyOf(Nvidium.config.vr_full_rate_programs);
    //Particles are always shaded at full rate (see MixinQuadParticleFeatureRenderer)
    public static boolean fullRateParticles = true;
    //Debug toggles: coarse shading for the geometry passes (gbuffers, terrain) and for the shader pack's composite passes
    public static boolean coarseGeometry = true;
    public static boolean coarseComposites = true;
    //Render pipelines always shaded at full rate, and whether the current render pass draws with one of them
    public static java.util.Set<String> fullRatePipelines = java.util.Set.copyOf(Nvidium.config.vr_full_rate_pipelines);
    private static boolean fullRatePipeline;
    private static final java.util.Set<String> LOGGED_PIPELINES = new java.util.HashSet<>();

    private FoveatedShading() {}

    private static final class EyeImage {
        int texture;
        int width;
        int height;
        int streamWidth;
        int streamHeight;
        double centerX;
        double centerY;
        double coarseFraction;
        double sampleFactor;
    }

    private static boolean isSupported() {
        if (supported == null) {
            supported = GL.getCapabilities().GL_NV_shading_rate_image;
            Nvidium.LOGGER.info("NV_shading_rate_image {}", supported ? "supported, foveated VR shading available" : "not supported");
        }
        return supported;
    }

    private static boolean wanted() {
        return runtimeEnabled && Nvidium.config.vr_foveated_shading && isSupported();
    }

    /**
     * A Vivecraft pass starts rendering.
     * @param eye 0 for the left eye, 1 for the right eye, -1 for any other pass
     * @param projection the eye's projection matrix
     * @param offsetX the eye's horizontal offset from the head center in meters (negative for the left eye)
     */
    public static void beginEye(int eye, Matrix4fc projection, double offsetX) {
        activeEye = eye;
        eyeProjection = projection;
        eyeOffsetX = offsetX;
        worldPasses = false;
        suspendDepth = 0;
        fullViewport = true;
        apply();
    }

    public static void endEye() {
        activeEye = -1;
        eyeProjection = null;
        worldPasses = false;
        suspendDepth = 0;
        fullViewport = true;
        apply();
    }

    /** The eye's world passes (after the shadow map) start drawing into the eye-sized targets. */
    public static void beginWorld(int width, int height) {
        if (activeEye < 0 || eyeProjection == null || !wanted()) {
            return;
        }
        EyeImage image = EYES[activeEye];
        double[] centers = foveationCenters(eyeProjection, eyeOffsetX);
        int streamWidth = Nvidium.config.vr_stream_width;
        int streamHeight = Nvidium.config.vr_stream_height;
        if (image.texture == 0 || image.sampleFactor != sampleFactor || image.width != width || image.height != height || image.streamWidth != streamWidth
                || image.streamHeight != streamHeight || Math.abs(image.centerX - centers[0]) > 1e-3 || Math.abs(image.centerY - centers[1]) > 1e-3) {
            build(image, width, height, streamWidth, streamHeight, centers[0], centers[1]);
        }
        worldPasses = true;
        apply();
    }

    /** A render pass switched to the pipeline with this name, null when the render pass ends. */
    public static void setPipeline(String name) {
        boolean full = name != null && fullRatePipelines.contains(name);
        if (full && LOGGED_PIPELINES.add(name)) {
            Nvidium.LOGGER.info("Foveated shading keeps pipeline {} at full rate", name);
        }
        if (full != fullRatePipeline) {
            fullRatePipeline = full;
            apply();
        }
    }

    public static void setPass(String pass) {
        if (!java.util.Objects.equals(currentPass, pass)) {
            currentPass = pass;
            apply();
        }
    }

    public static void endWorld() {
        worldPasses = false;
        apply();
    }

    /** For passes that don't draw into the eye-sized targets (shadow map, scaled composite passes). */
    public static void suspend() {
        suspendDepth++;
        apply();
    }

    public static void resume() {
        if (suspendDepth > 0) {
            suspendDepth--;
        }
        apply();
    }

    /** Whether the current composite pass covers the whole eye target. */
    public static void setFullViewport(boolean full) {
        if (fullViewport != full) {
            fullViewport = full;
            apply();
        }
    }

    private static void apply() {
        boolean enable = activeEye >= 0 && worldPasses && suspendDepth == 0 && fullViewport && !fullRatePipeline && wanted() && EYES[activeEye].texture != 0;
        enable &= currentPass == null || !fullRatePasses.contains(currentPass);
        enable &= currentPass == null ? coarseGeometry : coarseComposites;
        if (enable) {
            glBindShadingRateImageNV(EYES[activeEye].texture);
            if (!glEnabled) {
                glEnable(GL_SHADING_RATE_IMAGE_NV);
            }
        } else if (glEnabled) {
            glDisable(GL_SHADING_RATE_IMAGE_NV);
        }
        glEnabled = enable;
    }

    /**
     * WiVRn's foveation centers in its normalized [-1, 1] image coordinates (x left to right, y top to bottom), from
     * compute_params(): x converges the eyes at 1 m, y is the default gaze 10 degrees below straight ahead.
     */
    private static double[] foveationCenters(Matrix4fc projection, double eyeOffsetX) {
        //Tangents of the eye's fov from a GL projection matrix
        double left = (projection.m20() - 1) / projection.m00();
        double right = (projection.m20() + 1) / projection.m00();
        double down = (projection.m21() - 1) / projection.m11();
        double up = (projection.m21() + 1) / projection.m11();

        double angleX = Math.atan2(-eyeOffsetX, CONVERGENCE_DISTANCE);
        double centerX = toCenter(Math.tan(angleX), left, right);
        //WiVRn: angles_to_center(-angle_y, up, down) with angle_y = 10 degrees, tangents measured downwards from up
        double centerY = toCenter(Math.tan(-GAZE_PITCH_OFFSET), up, down);
        return new double[]{centerX, centerY};
    }

    private static double toCenter(double e, double l, double r) {
        double res = (e - l) / (r - l) * 2 - 1;
        return Double.isNaN(res) ? 0 : Math.max(-1, Math.min(1, res));
    }

    private static void build(EyeImage image, int width, int height, int streamWidth, int streamHeight, double centerX, double centerY) {
        if (image.texture != 0) {
            glDeleteTextures(image.texture);
        }
        int texelWidth = glGetInteger(GL_SHADING_RATE_IMAGE_TEXEL_WIDTH_NV);
        int texelHeight = glGetInteger(GL_SHADING_RATE_IMAGE_TEXEL_HEIGHT_NV);
        int tilesX = (width + texelWidth - 1) / texelWidth;
        int tilesY = (height + texelHeight - 1) / texelHeight;

        int[] ratioX = WiVRnFoveation.ratios(streamWidth, width, centerX);
        //WiVRn computes rows top to bottom and flips them for GL images, whose row 0 is the bottom
        int[] ratioYTop = WiVRnFoveation.ratios(streamHeight, height, centerY);
        int[] ratioY = new int[height];
        for (int i = 0; i < height; i++) {
            ratioY[i] = ratioYTop[height - 1 - i];
        }

        int[] rateX = tileRates(ratioX, tilesX, texelWidth);
        int[] rateY = tileRates(ratioY, tilesY, texelHeight);

        ByteBuffer data = ByteBuffer.allocateDirect(tilesX * tilesY);
        long invocations = 0;
        for (int y = 0; y < tilesY; y++) {
            for (int x = 0; x < tilesX; x++) {
                //Shader packs keep per-frame data in corner pixels, selected by exact gl_FragCoord tests (Complementary stores its
                // volumetric light factor in the top right pixel), which a coarse fragment never passes
                boolean corner = (x < CORNER_TILES || x >= tilesX - CORNER_TILES) && (y < CORNER_TILES || y >= tilesY - CORNER_TILES);
                int rx = corner ? 1 : rateX[x];
                int ry = corner ? 1 : rateY[y];
                data.put(y * tilesX + x, (byte) paletteIndex(rx, ry));
                invocations += (long) texelWidth * texelHeight / ((long) effectiveX(rx, ry) * effectiveY(rx, ry));
            }
        }

        image.texture = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(image.texture, 1, GL_R8UI, tilesX, tilesY);
        upload(image.texture, tilesX, tilesY, data);

        //Check that the image holds what was computed
        ByteBuffer readBack = ByteBuffer.allocateDirect(tilesX * tilesY);
        int packBuffer = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        int packAlignment = glGetInteger(GL_PACK_ALIGNMENT);
        int packRowLength = glGetInteger(org.lwjgl.opengl.GL11C.GL_PACK_ROW_LENGTH);
        int packSkipRows = glGetInteger(org.lwjgl.opengl.GL11C.GL_PACK_SKIP_ROWS);
        int packSkipPixels = glGetInteger(org.lwjgl.opengl.GL11C.GL_PACK_SKIP_PIXELS);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glPixelStorei(org.lwjgl.opengl.GL11C.GL_PACK_ROW_LENGTH, 0);
        glPixelStorei(org.lwjgl.opengl.GL11C.GL_PACK_SKIP_ROWS, 0);
        glPixelStorei(org.lwjgl.opengl.GL11C.GL_PACK_SKIP_PIXELS, 0);
        org.lwjgl.opengl.GL45C.glGetTextureImage(image.texture, 0, GL_RED_INTEGER, GL_UNSIGNED_BYTE, readBack);
        glPixelStorei(GL_PACK_ALIGNMENT, packAlignment);
        glPixelStorei(org.lwjgl.opengl.GL11C.GL_PACK_ROW_LENGTH, packRowLength);
        glPixelStorei(org.lwjgl.opengl.GL11C.GL_PACK_SKIP_ROWS, packSkipRows);
        glPixelStorei(org.lwjgl.opengl.GL11C.GL_PACK_SKIP_PIXELS, packSkipPixels);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, packBuffer);
        int mismatches = 0;
        for (int i = 0; i < tilesX * tilesY; i++) {
            if (readBack.get(i) != data.get(i)) {
                mismatches++;
            }
        }
        if (mismatches != 0) {
            Nvidium.LOGGER.error("Foveated shading image upload mismatch in {} of {} texels", mismatches, tilesX * tilesY);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer palette = stack.ints(PALETTE);
            glShadingRateImagePaletteNV(0, 0, palette);
        }

        image.width = width;
        image.height = height;
        image.streamWidth = streamWidth;
        image.streamHeight = streamHeight;
        image.centerX = centerX;
        image.centerY = centerY;
        image.sampleFactor = sampleFactor;
        image.coarseFraction = invocations / (double) ((long) tilesX * texelWidth * tilesY * texelHeight);
        Nvidium.LOGGER.info("Foveated shading image {}x{} ({}x{} texels of {}x{}, sample factor {}) for stream {}x{}, center ({}, {}): {}% of full-rate fragment shading",
                width, height, tilesX, tilesY, texelWidth, texelHeight, sampleFactor, streamWidth, streamHeight, String.format("%.3f", centerX), String.format("%.3f", centerY),
                String.format("%.1f", image.coarseFraction * 100));
    }

    //Minecraft leaves unpack state (row length, skips, alignment, a bound unpack buffer) set from its own texture uploads
    private static void upload(int texture, int width, int height, ByteBuffer data) {
        int unpackBuffer = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
        int rowLength = glGetInteger(GL_UNPACK_ROW_LENGTH);
        int skipRows = glGetInteger(GL_UNPACK_SKIP_ROWS);
        int skipPixels = glGetInteger(GL_UNPACK_SKIP_PIXELS);
        int alignment = glGetInteger(GL_UNPACK_ALIGNMENT);
        if (unpackBuffer != 0 || rowLength != 0 || skipRows != 0 || skipPixels != 0) {
            Nvidium.LOGGER.info("Foveated shading upload: overriding unpack state buffer={} rowLength={} skipRows={} skipPixels={}", unpackBuffer, rowLength, skipRows, skipPixels);
        }
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTextureSubImage2D(texture, 0, 0, 0, width, height, GL_RED_INTEGER, GL_UNSIGNED_BYTE, data);
        glPixelStorei(GL_UNPACK_ALIGNMENT, alignment);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, skipPixels);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, skipRows);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, rowLength);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
    }

    //Per tile: the coarsest rate (1, 2 or 4) not exceeding the smallest WiVRn span ratio over the tile plus a safety margin
    private static int[] tileRates(int[] ratio, int tiles, int texelSize) {
        int[] rates = new int[tiles];
        for (int t = 0; t < tiles; t++) {
            int min = Integer.MAX_VALUE;
            int from = Math.max(0, t * texelSize - SAFETY_MARGIN);
            int to = Math.min(ratio.length, (t + 1) * texelSize + SAFETY_MARGIN);
            for (int i = from; i < to; i++) {
                min = Math.min(min, ratio[i]);
            }
            int rate = min >= 4 * sampleFactor ? 4 : min >= 2 * sampleFactor ? 2 : 1;
            //A coarse fragment straddling the right/top edge of the target would have its gl_FragCoord outside it, and packs
            // read their buffers at ivec2(gl_FragCoord), so only use rates that tile the last, partial tile exactly
            int span = Math.min(texelSize, ratio.length - t * texelSize);
            while (span % rate != 0) {
                rate /= 2;
            }
            rates[t] = rate;
        }
        return rates;
    }

    //The palette has no 1x4 or 4x1 rates
    private static int effectiveX(int x, int y) {
        return (x == 4 && y == 1) ? 2 : x;
    }

    private static int effectiveY(int x, int y) {
        return (y == 4 && x == 1) ? 2 : y;
    }

    private static int paletteIndex(int x, int y) {
        int ex = effectiveX(x, y);
        int ey = effectiveY(x, y);
        if (ex == 1 && ey == 1) return 0;
        if (ex == 1) return 1;
        if (ey == 1) return 2;
        if (ex == 2 && ey == 2) return 3;
        if (ex == 2) return 4;
        if (ey == 2) return 5;
        return 6;
    }

}
