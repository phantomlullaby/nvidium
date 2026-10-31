package me.cortex.nvidium.iris;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.backend.opengl.GlSampler;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.RenderPipeline;
import me.cortex.nvidium.debug.FrameProfiler;
import me.cortex.nvidium.gl.buffers.IDeviceMappedBuffer;
import me.cortex.nvidium.renderers.IrisDrawBuilder;
import me.cortex.nvidium.util.GPUTiming;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ExtendedShader;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.ARBIndirectParameters;

import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;

import static org.lwjgl.opengl.NVUniformBufferUnifiedMemory.GL_UNIFORM_BUFFER_ADDRESS_NV;
import static org.lwjgl.opengl.NVVertexBufferUnifiedMemory.*;

/**
 * Renders nvidium's terrain with the active Iris shader pack. Only touch this class when Iris is loaded.
 */
public final class IrisCompat {
    public enum Kind {
        PRIMARY(ShaderKey.SODIUM_TERRAIN_CUTOUT, IrisDrawBuilder.MODE_COMPONENT_X),
        TEMPORAL(ShaderKey.SODIUM_TERRAIN_CUTOUT, IrisDrawBuilder.MODE_COMPONENT_Z),
        TRANSLUCENT(ShaderKey.SODIUM_TERRAIN_TRANSLUCENT, IrisDrawBuilder.MODE_TRANSLUCENT | IrisDrawBuilder.MODE_COMPONENT_Y),
        SHADOW_OPAQUE(ShaderKey.SHADOW_SODIUM_TERRAIN_CUTOUT, IrisDrawBuilder.MODE_NO_FACE_CULLING | IrisDrawBuilder.MODE_SLOT_LIST),
        SHADOW_TRANSLUCENT(ShaderKey.SHADOW_SODIUM_TERRAIN_TRANSLUCENT, IrisDrawBuilder.MODE_TRANSLUCENT | IrisDrawBuilder.MODE_SLOT_LIST);

        //Solid and cutout geometry share one buffer in nvidium, so it is all drawn with the pack's cutout program
        private final ShaderKey key;
        //Draw builder mode, see terrain/iris/draw_builder.comp
        public final int mode;

        Kind(ShaderKey key, int mode) {
            this.key = key;
            this.mode = mode;
        }

        private boolean translucent() {
            return (this.mode & IrisDrawBuilder.MODE_TRANSLUCENT) != 0;
        }
    }

    //Size of Iris' sodium u_Globals block (std140)
    public static final int GLOBALS_SIZE = 192;

    /**
     * How a draw uses the depth buffer. Shader pack terrain programs discard (alpha cutout), with depth writes on that makes
     * the GPU run the whole, expensive, program for every fragment and test depth afterwards. Drawing depth first with a
     * program that only keeps the discards, then shading with depth writes off, only shades the visible fragments.
     */
    public enum Phase {
        //Shade and write depth
        FULL,
        //Write depth only (depth pre-pass), with the pack's discards
        DEPTH,
        //Shade the fragments left visible by the DEPTH phase, without writing depth
        COLOR
    }

    //Debug switch for comparing with and without the depth pre-pass (autotest "prepass on|off")
    public static volatile boolean depthPrepass = true;

    private static final Map<IrisRenderingPipeline, Map<ShaderKey, IrisTerrainProgram>> PROGRAMS = new IdentityHashMap<>();
    //Depth pre-pass versions of the main view opaque program, missing if the pack's fragment shader cannot be made depth only
    private static final Map<IrisRenderingPipeline, Map<ShaderKey, IrisTerrainProgram>> DEPTH_PROGRAMS = new IdentityHashMap<>();
    //Pipelines where adapting one of the terrain programs failed
    private static final Map<IrisRenderingPipeline, Boolean> FAILED = new IdentityHashMap<>();
    private static final Map<Kind, GPUTiming> TIMINGS = new EnumMap<>(Kind.class);

    private IrisCompat() {}

    public static boolean isShadowPass() {
        return ShadowRenderingState.areShadowsCurrentlyBeingRendered();
    }

    static boolean irisDebugEnabled() {
        return Iris.getIrisConfig().areDebugOptionsEnabled();
    }

    public static boolean isNvidiumKey(ShaderKey key) {
        for (Kind kind : Kind.values()) {
            if (kind.key == key) {
                return true;
            }
        }
        return false;
    }

    /**
     * Called when Iris built the program for one of the sodium terrain shader keys, builds nvidium's version of it.
     * @param shaderFactory makes an Iris shader object (uniforms, samplers, framebuffer...) for the given GL program, like Iris does for its own
     */
    public static void onSodiumProgramCreated(IrisRenderingPipeline pipeline, ShaderKey key, String name, Map<PatchShaderType, String> sources,
                                              IntFunction<ExtendedShader> shaderFactory) {
        if (FAILED.containsKey(pipeline)) {
            return;
        }
        try {
            if (sources.get(PatchShaderType.GEOMETRY) != null || sources.get(PatchShaderType.TESS_CONTROL) != null || sources.get(PatchShaderType.TESS_EVAL) != null) {
                throw new UnsupportedOperationException("geometry and tessellation shaders are not supported");
            }
            VertexFormat format = WorldRenderingSettings.INSTANCE.getVertexFormat().getVertexFormat();
            String vertex = IrisVertexPatcher.patch(sources.get(PatchShaderType.VERTEX));
            var program = IrisTerrainProgram.compile(name + "_nvidium", vertex, sources.get(PatchShaderType.FRAGMENT), format, shaderFactory);
            var old = PROGRAMS.computeIfAbsent(pipeline, p -> new IdentityHashMap<>()).put(key, program);
            if (old != null) {
                old.delete();
            }
            if (key == ShaderKey.SODIUM_TERRAIN_CUTOUT) {
                createDepthProgram(pipeline, key, name, vertex, sources.get(PatchShaderType.FRAGMENT), format, shaderFactory);
            }
        } catch (Throwable e) {
            Nvidium.LOGGER.error("Could not build the nvidium version of Iris program {}, nvidium will be disabled with this shader pack", name, e);
            onPipelineDestroyed(pipeline);
            FAILED.put(pipeline, true);
        }
    }

    //The pre-pass is only an optimization, without it the terrain is drawn in one FULL phase
    private static void createDepthProgram(IrisRenderingPipeline pipeline, ShaderKey key, String name, String vertex, String fragment, VertexFormat format,
                                           IntFunction<ExtendedShader> shaderFactory) {
        var old = DEPTH_PROGRAMS.computeIfAbsent(pipeline, p -> new IdentityHashMap<>()).remove(key);
        if (old != null) {
            old.delete();
        }
        String depthFragment = IrisDepthPatcher.patch(name, fragment);
        if (depthFragment == null) {
            return;
        }
        try {
            DEPTH_PROGRAMS.get(pipeline).put(key, IrisTerrainProgram.compile(name + "_nvidium_depth", vertex, depthFragment, format, shaderFactory));
        } catch (Throwable e) {
            Nvidium.LOGGER.warn("Could not build the depth pre-pass version of Iris program {}, drawing without it", name, e);
        }
    }

    public static void onPipelineDestroyed(IrisRenderingPipeline pipeline) {
        FAILED.remove(pipeline);
        var programs = PROGRAMS.remove(pipeline);
        if (programs != null) {
            programs.values().forEach(IrisTerrainProgram::delete);
        }
        var depthPrograms = DEPTH_PROGRAMS.remove(pipeline);
        if (depthPrograms != null) {
            depthPrograms.values().forEach(IrisTerrainProgram::delete);
        }
    }

    private static IrisRenderingPipeline currentPipeline() {
        WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
        if (!(pipeline instanceof IrisRenderingPipeline irisPipeline) || FAILED.containsKey(irisPipeline)) {
            return null;
        }
        return irisPipeline;
    }

    private static Map<ShaderKey, IrisTerrainProgram> current() {
        var pipeline = currentPipeline();
        return pipeline == null ? null : PROGRAMS.get(pipeline);
    }

    private static IrisTerrainProgram depthProgram(Kind kind) {
        var pipeline = currentPipeline();
        var programs = pipeline == null ? null : DEPTH_PROGRAMS.get(pipeline);
        return programs == null ? null : programs.get(kind.key);
    }

    //Whether the kind can be drawn as a DEPTH then a COLOR phase, only for the main view opaque terrain
    public static boolean canDrawDepthPrepass(Kind kind) {
        return depthPrepass && (kind == Kind.PRIMARY || kind == Kind.TEMPORAL) && depthProgram(kind) != null;
    }

    //Whether the current pack's main view terrain programs are available (shadow programs are optional)
    public static boolean hasTerrainPrograms() {
        var programs = current();
        return programs != null && programs.containsKey(ShaderKey.SODIUM_TERRAIN_CUTOUT) && programs.containsKey(ShaderKey.SODIUM_TERRAIN_TRANSLUCENT);
    }

    public static void setChunkVertexFormat(VertexFormat format) {
        VertexFormat irisFormat = WorldRenderingSettings.INSTANCE.getVertexFormat().getVertexFormat();
        if (!format.equals(irisFormat)) {
            Nvidium.LOGGER.warn("Sodium is meshing with {} but the Iris terrain programs were built for {}", format, irisFormat);
        }
    }

    /**
     * Draws terrain with the shader pack program for the given kind, using draws made by the {@link IrisDrawBuilder}.
     * Expects nvidium's unified memory client states to be enabled, as they are during its terrain passes.
     * @param globalsAddress device address of the u_Globals data for this view
     * @param phase DEPTH and COLOR only if {@link #canDrawDepthPrepass}, with the DEPTH phase of all draws sharing the depth buffer before their COLOR phase
     * @return false if the pack has no program for it
     */
    public static boolean draw(Kind kind, Phase phase, GpuTextureView atlas, IDeviceMappedBuffer globals, int globalsOffset, IrisDrawBuilder.Output draws, IDeviceMappedBuffer quadIndices,
                               IDeviceMappedBuffer terrain, int vertexStride) {
        var programs = current();
        IrisTerrainProgram program = programs == null ? null : phase == Phase.DEPTH ? depthProgram(kind) : programs.get(kind.key);
        if (program == null) {
            return false;
        }

        //Blend state as the sodium pipeline would set it, Iris then applies the pack's blend overrides
        if (kind.translucent()) {
            IrisRenderSystem.enableBlend();
            GlStateManager._blendFuncSeparate(GL33C.GL_SRC_ALPHA, GL33C.GL_ONE_MINUS_SRC_ALPHA, GL33C.GL_ONE, GL33C.GL_ONE_MINUS_SRC_ALPHA);
        } else {
            IrisRenderSystem.disableBlend();
        }

        //Terrain is not part of an entity, but these can still hold the last rendered entity (packs fade/discard by entityId)
        CapturedRenderingState.INSTANCE.setCurrentEntity(0);
        CapturedRenderingState.INSTANCE.setCurrentBlockEntity(0);
        CapturedRenderingState.INSTANCE.setCurrentRenderedItem(0);
        program.begin();

        //Texture units Iris assigns to the sodium terrain samplers (see IrisBindings), Iris always samples the atlas with nearest filtering
        bindTexture(atlas, 0, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST, true));
        bindTexture(Minecraft.getInstance().gameRenderer.lightmap(), 2, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        GlStateManager._activeTexture(GL32C.GL_TEXTURE0);
        org.lwjgl.opengl.GL11.glDisableClientState(org.lwjgl.opengl.NVUniformBufferUnifiedMemory.GL_UNIFORM_BUFFER_UNIFIED_NV);
        org.lwjgl.opengl.GL30C.glBindBufferRange(org.lwjgl.opengl.GL31C.GL_UNIFORM_BUFFER, IrisTerrainProgram.GLOBALS_BINDING, globals.getId(), globalsOffset, GLOBALS_SIZE);

        //Classic buffer bindings for these draws, indirect count draws do not work with nvidium's unified memory vertex pulling
        org.lwjgl.opengl.GL11.glDisableClientState(GL_VERTEX_ATTRIB_ARRAY_UNIFIED_NV);
        org.lwjgl.opengl.GL11.glDisableClientState(GL_ELEMENT_ARRAY_UNIFIED_NV);
        org.lwjgl.opengl.GL11.glDisableClientState(RenderPipeline.GL_DRAW_INDIRECT_UNIFIED_NV);
        program.bindBuffers(terrain.getId(), vertexStride, draws.offsets().getId(), quadIndices.getId());
        GlStateManager._glBindVertexArray(program.vertexArray());
        GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, draws.commands().getId());
        GL15C.glBindBuffer(ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB, draws.drawCount().getId());

        //The depth only program has no color outputs, what it would write to the color buffers is undefined
        if (phase == Phase.DEPTH) {
            GL11C.glColorMask(false, false, false, false);
        } else if (phase == Phase.COLOR) {
            GL11C.glDepthMask(false);
        }
        GPUTiming timing = TIMINGS.computeIfAbsent(kind, k -> new GPUTiming());
        timing.marker();
        FrameProfiler.beginStats(kind.name().toLowerCase(Locale.ROOT) + ":" + phase.name().toLowerCase(Locale.ROOT));
        //The ARB entry point, the context minecraft makes is 3.3 so lwjgl does not load the core 4.6 one
        ARBIndirectParameters.glMultiDrawElementsIndirectCountARB(GL11C.GL_TRIANGLES, GL11C.GL_UNSIGNED_INT, 0, 0, IrisDrawBuilder.MAX_DRAWS, IrisDrawBuilder.DRAW_COMMAND_SIZE);
        FrameProfiler.endStats();
        timing.marker();
        timing.tick();
        //renderLayer set both to true before the terrain passes
        if (phase == Phase.DEPTH) {
            GL11C.glColorMask(true, true, true, true);
        } else if (phase == Phase.COLOR) {
            GL11C.glDepthMask(true);
        }

        GL15C.glBindBuffer(ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB, 0);
        GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, 0);
        GlStateManager._glBindVertexArray(0);
        org.lwjgl.opengl.GL11.glEnableClientState(GL_VERTEX_ATTRIB_ARRAY_UNIFIED_NV);
        org.lwjgl.opengl.GL11.glEnableClientState(GL_ELEMENT_ARRAY_UNIFIED_NV);
        org.lwjgl.opengl.GL11.glEnableClientState(RenderPipeline.GL_DRAW_INDIRECT_UNIFIED_NV);
        org.lwjgl.opengl.GL11.glEnableClientState(org.lwjgl.opengl.NVUniformBufferUnifiedMemory.GL_UNIFORM_BUFFER_UNIFIED_NV);

        program.end();
        GL33C.glBindSampler(0, 0);
        GL33C.glBindSampler(2, 0);
        return true;
    }

    public static void addDebugInfo(List<String> info) {
        TIMINGS.forEach((kind, timing) -> info.add("Iris " + kind.name().toLowerCase(Locale.ROOT) + " time: " + String.format("%.03f", timing.getAverageMs()) + "ms"));
    }

    private static void bindTexture(GpuTextureView view, int unit, GpuSampler sampler) {
        GlTexture texture = (GlTexture) view.texture();
        GlStateManager._activeTexture(GL32C.GL_TEXTURE0 + unit);
        GlStateManager._bindTexture(texture.glId());
        GlStateManager._texParameter(GL32C.GL_TEXTURE_2D, GL32C.GL_TEXTURE_BASE_LEVEL, view.baseMipLevel());
        GlStateManager._texParameter(GL32C.GL_TEXTURE_2D, GL32C.GL_TEXTURE_MAX_LEVEL, view.baseMipLevel() + view.mipLevels() - 1);
        GL33C.glBindSampler(unit, ((GlSampler) sampler).getId());
    }
}
