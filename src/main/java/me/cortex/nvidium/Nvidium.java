package me.cortex.nvidium;

import me.cortex.nvidium.config.NvidiumConfig;
import me.cortex.nvidium.config.TranslucencySortingLevel;
import me.cortex.nvidium.sodiumCompat.NvidiumCompactChunkVertex;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.util.Util;
import org.lwjgl.opengl.GL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Nvidium {
    public static final String MOD_VERSION;
    public static final Logger LOGGER = LoggerFactory.getLogger("Nvidium");
    public static boolean IS_COMPATIBLE = false;
    public static boolean IS_ENABLED = false;
    public static boolean IS_DEBUG = System.getProperty("nvidium.isDebug", "false").equals("TRUE");
    public static boolean SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER = true;
    public static boolean FORCE_DISABLE = false;
    //Set when the current world renderer draws terrain with the active Iris shader pack programs
    public static boolean IRIS_MODE = false;
    //Vertex stride of the chunk mesh format sodium is currently building with
    public static volatile int VERTEX_STRIDE = NvidiumCompactChunkVertex.STRIDE;
    //True when the chunk mesh format uses sodium's (and Iris's) position/texture encoding
    public static volatile boolean SODIUM_VERTEX_LAYOUT = false;

    public static NvidiumConfig config = NvidiumConfig.loadOrCreate();

    static {
        ModContainer mod = (ModContainer) FabricLoader.getInstance().getModContainer("nvidium").orElseThrow(NullPointerException::new);
        var version = mod.getMetadata().getVersion().getFriendlyString();
        var commit = mod.getMetadata().getCustomValue("commit").getAsString();
        MOD_VERSION = version+"-"+commit;
    }

    //Iris mode draws translucent quads in their stored order, so sodium's sort data (an index list per section) is not usable.
    // Sort the quads when the section is built instead, like the sections sorting level does
    public static TranslucencySortingLevel translucencySortingLevel() {
        var level = config.translucency_sorting_level;
        if (IRIS_MODE && (level == TranslucencySortingLevel.SODIUM || level == TranslucencySortingLevel.QUADS)) {
            return TranslucencySortingLevel.SECTIONS;
        }
        return level;
    }

    public static void checkSystemIsCapable() {
        var cap = GL.getCapabilities();
        boolean supported = cap.GL_NV_mesh_shader &&
                cap.GL_NV_uniform_buffer_unified_memory &&
                cap.GL_NV_vertex_buffer_unified_memory &&
                cap.GL_NV_representative_fragment_test &&
                cap.GL_ARB_sparse_buffer &&
                cap.GL_NV_bindless_multi_draw_indirect;
        IS_COMPATIBLE = supported;
        if (IS_COMPATIBLE) {
            LOGGER.info("All capabilities met");
        } else {
            LOGGER.warn("Not all requirements met, disabling nvidium");
        }
        if (IS_COMPATIBLE && Util.getPlatform() == Util.OS.LINUX) {
            LOGGER.warn("Linux currently uses fallback terrain buffer due to driver inconsistencies, expect increase vram usage");
            SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER = false;
        }

        if (IS_COMPATIBLE) {
            LOGGER.info("Enabling Nvidium");
        }
        IS_ENABLED = IS_COMPATIBLE;
    }
}
