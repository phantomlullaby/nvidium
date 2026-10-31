package me.cortex.nvidium.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.cortex.nvidium.Nvidium;
import net.fabricmc.loader.api.FabricLoader;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

public class NvidiumConfig {
    //The options
    public boolean enable_temporal_coherence = true;
    public int max_geometry_memory = 2048;
    public boolean automatic_memory = true;

    public int region_keep_distance = 32;

    public boolean render_fog = true;
    public boolean use_sodium_vertex_format = false;
    public boolean cull_degenerate_triangles = true;
    public boolean use_nv_fragment_shader_barycentric = true;

    //Render terrain with nvidium while an Iris shader pack is active (with the pack's terrain programs)
    public boolean iris_shader_support = true;

    //In VR (Vivecraft streaming through WiVRn), shade each eye's periphery at a coarser rate wherever WiVRn's foveated video
    // stream averages several rendered pixels into one anyway, so every streamed pixel still gets at least one full shading sample
    public boolean vr_foveated_shading = true;
    //Per-eye size of WiVRn's foveated stream, the "size" of the nvenc encoders in the WiVRn server log
    public int vr_stream_width = 1152;
    public int vr_stream_height = 1216;
    //How many rendered pixels WiVRn must average into one streamed pixel (per axis) per coarse shading step: 1 shades each
    // streamed pixel at least once, 2 at least twice (sharper periphery, more GPU time). With Complementary Reimagined, 1
    // visibly softened the outer periphery of the stream, 1.5 stays close to full-rate shading
    public double vr_foveation_sample_factor = 1.5;
    //Shader pack composite programs shaded at full rate even in the periphery. Coarse shading in Complementary Reimagined's
    // composite3/composite4 (bloom fog) shifted the colors of the whole image slightly (measured on the simulated WiVRn
    // stream), the other programs stay within frame-to-frame noise
    public java.util.List<String> vr_full_rate_programs = java.util.List.of("composite3", "composite4");
    //Render pipelines shaded at full rate even in the periphery. Mob eyes (phantoms, spiders, endermen, breezes) are a few
    // bright texels of a texture without mipmaps: a coarse fragment samples them once for its whole block, so far away eyes
    // either vanish or light the full block, and bloom turns that into a flickering glow
    public java.util.List<String> vr_full_rate_pipelines = java.util.List.of("minecraft:pipeline/eyes", "minecraft:pipeline/oit_depth_bounds_eyes",
            "minecraft:pipeline/oit_transmittance_eyes", "minecraft:pipeline/oit_accumulate_eyes", "minecraft:pipeline/entity_translucent_emissive");
    //How many render submits may be in flight on the GPU (vanilla 2), 0 = automatic: 3 with Vivecraft, so the CPU can prepare
    // the next eye/frame while the GPU renders. Takes effect after a restart
    public int gpu_submit_depth = 0;

    public TranslucencySortingLevel translucency_sorting_level = TranslucencySortingLevel.SODIUM;

    public StatisticsLoggingLevel statistics_level = StatisticsLoggingLevel.NONE;


    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    private NvidiumConfig() {}
    public static NvidiumConfig loadOrCreate() {
        var path = getConfigPath();
        if (Files.exists(path)) {
            try (FileReader reader = new FileReader(path.toFile())) {
                return GSON.fromJson(reader, NvidiumConfig.class);
            } catch (IOException e) {
                Nvidium.LOGGER.error("Could not parse config", e);
            }
        }
        return new NvidiumConfig();
    }

    public void save() {
        //Unsafe, todo: fixme! needs to be atomic!
        try {
            Files.writeString(getConfigPath(), GSON.toJson(this));
        } catch (IOException e) {
            Nvidium.LOGGER.error("Failed to write config file", e);
        }
    }

    private static Path getConfigPath() {
        return FabricLoader.getInstance()
                .getConfigDir()
                .resolve("nvidium-config.json");
    }
}
