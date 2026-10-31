package me.cortex.nvidium.iris;

import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.blending.BufferBlendOverride;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ExtendedShader;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.transform.Patch;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.VanillaUniforms;
import net.irisshaders.iris.uniforms.builtin.BuiltinReplacementUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Builds Iris shader objects for nvidium's GL programs the same way Iris' ShaderCreator does for the sodium terrain programs.
 */
public final class IrisShaderFactory {
    private IrisShaderFactory() {}

    public static IntFunction<ExtendedShader> create(String name, ShaderKey key, ProgramSource source, ProgramId programId, AlphaTest fallbackAlpha,
                                                     Supplier<GlFramebuffer> beforeTranslucent, Supplier<GlFramebuffer> afterTranslucent,
                                                     ShaderAttributeInputs inputs, IrisRenderingPipeline parent, Supplier<ImmutableSet<Integer>> flipped,
                                                     boolean isShadowPass, CustomUniforms customUniforms, Patch patch) {
        AlphaTest alpha = source.getDirectives().getAlphaTestOverride().orElse(fallbackAlpha);
        BlendModeOverride blendModeOverride = source.getDirectives().getBlendModeOverride().orElse(programId.getBlendModeOverride());
        VertexFormat format = WorldRenderingSettings.INSTANCE.getVertexFormat().getVertexFormat();

        List<BufferBlendOverride> overrides = new ArrayList<>();
        source.getDirectives().getBufferBlendOverrides().forEach(information -> {
            int index = Ints.indexOf(source.getDirectives().getDrawBuffers(), information.index());
            if (index > -1) {
                overrides.add(new BufferBlendOverride(index, information.blendMode()));
            }
        });

        return program -> {
            try {
                return new ExtendedShader(program, name + "_nvidium", format, false, beforeTranslucent.get(), afterTranslucent.get(),
                        blendModeOverride, alpha, uniforms -> {
                            CommonUniforms.addDynamicUniforms(uniforms, FogMode.PER_VERTEX);
                            customUniforms.assignTo(uniforms);
                            BuiltinReplacementUniforms.addBuiltinReplacementUniforms(uniforms);
                            VanillaUniforms.addVanillaUniforms(uniforms);
                        }, (samplerHolder, imageHolder) -> parent.addGbufferOrShadowSamplers(samplerHolder, imageHolder, flipped, isShadowPass,
                                inputs.hasTex(), inputs.hasLight(), inputs.hasOverlay()),
                        key, parent, overrides, customUniforms, patch);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
    }
}
