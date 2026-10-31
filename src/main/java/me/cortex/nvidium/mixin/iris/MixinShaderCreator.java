package me.cortex.nvidium.mixin.iris;

import com.google.common.collect.ImmutableSet;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.backend.opengl.GlProgram;
import me.cortex.nvidium.iris.IrisCompat;
import me.cortex.nvidium.iris.IrisShaderFactory;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ExtendedShader;
import net.irisshaders.iris.pipeline.programs.ShaderCreator;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.programs.ShaderSupplier;
import net.irisshaders.iris.pipeline.transform.Patch;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.Supplier;

//Builds nvidium's versions of the shader pack's sodium terrain programs whenever Iris creates them
@Mixin(value = ShaderCreator.class, remap = false)
public class MixinShaderCreator {
    @ModifyReturnValue(method = "create", at = @At("RETURN"))
    private static ShaderSupplier nvidium$createPrograms(ShaderSupplier original, WorldRenderingPipeline pipeline, String name, ShaderKey shaderKey,
                                                             ProgramSource source, ProgramId programId, GlFramebuffer writingToBeforeTranslucent,
                                                             GlFramebuffer writingToAfterTranslucent, AlphaTest fallbackAlpha, VertexFormat vertexFormat,
                                                             ShaderAttributeInputs inputs, FrameUpdateNotifier updateNotifier, IrisRenderingPipeline parent,
                                                             Supplier<ImmutableSet<Integer>> flipped, FogMode fogMode, boolean isIntensity,
                                                             boolean isFullbright, boolean isShadowPass, boolean isLines, CustomUniforms customUniforms,
                                                             Patch patch, @Local Map<PatchShaderType, String> transformed) {
        if (patch != Patch.SODIUM || !IrisCompat.isNvidiumKey(shaderKey)) {
            return original;
        }
        var factory = IrisShaderFactory.create(name, shaderKey, source, programId, fallbackAlpha, () -> writingToBeforeTranslucent,
                () -> writingToAfterTranslucent, inputs, parent, flipped, isShadowPass, customUniforms, patch);
        return nvidium$wrap(original, parent, shaderKey, name, transformed, factory);
    }

    @ModifyReturnValue(method = "createShadow", at = @At("RETURN"))
    private static ShaderSupplier nvidium$createShadowPrograms(ShaderSupplier original, WorldRenderingPipeline pipeline, String name, ShaderKey shaderKey,
                                                                   ProgramSource source, ProgramId programId, Supplier<ShadowRenderTargets> shadowSupplier,
                                                                   AlphaTest fallbackAlpha, VertexFormat vertexFormat, ShaderAttributeInputs inputs,
                                                                   FrameUpdateNotifier updateNotifier, IrisRenderingPipeline parent,
                                                                   Supplier<ImmutableSet<Integer>> flipped, FogMode fogMode, boolean isIntensity,
                                                                   boolean isFullbright, boolean isShadowPass, boolean isLines, CustomUniforms customUniforms,
                                                                   Patch patchType, @Local Map<PatchShaderType, String> transformed) {
        if (patchType != Patch.SODIUM || !IrisCompat.isNvidiumKey(shaderKey)) {
            return original;
        }
        Supplier<GlFramebuffer> framebuffer = () -> shadowSupplier.get().createShadowFramebuffer(ImmutableSet.of(),
                source.getDirectives().hasUnknownDrawBuffers() ? new int[]{0, 1} : source.getDirectives().getDrawBuffers());
        var factory = IrisShaderFactory.create(name, shaderKey, source, programId, fallbackAlpha, framebuffer, framebuffer, inputs, parent,
                flipped, isShadowPass, customUniforms, patchType);
        return nvidium$wrap(original, parent, shaderKey, name, transformed, factory);
    }

    @Unique
    private static ShaderSupplier nvidium$wrap(ShaderSupplier original, IrisRenderingPipeline parent, ShaderKey key, String name,
                                               Map<PatchShaderType, String> sources, IntFunction<ExtendedShader> factory) {
        return new ShaderSupplier(original.key(), original.id(), () -> {
            GlProgram program = original.shader().get();
            IrisCompat.onSodiumProgramCreated(parent, key, name, sources, factory);
            return program;
        });
    }
}
