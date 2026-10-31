package me.cortex.nvidium.mixin.profile;

import me.cortex.nvidium.debug.FrameProfiler;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.vivecraft.api.client.data.RenderPass;
import org.vivecraft.client_vr.render.helpers.VRPassHelper;

@Pseudo
@Mixin(value = VRPassHelper.class, remap = false)
public class MixinVRPassHelperProfile {
    @org.spongepowered.asm.mixin.Unique
    private static final long[] nvidium$eyeLogCounter = new long[2];

    @Inject(method = "renderSingleView", at = @At("HEAD"))
    private static void nvidium$profileEye(RenderPass eye, DeltaTracker.Timer deltaTracker, boolean renderLevel, CallbackInfo ci) {
        FrameProfiler.mark(eye + ":update");
        if ((eye == RenderPass.LEFT || eye == RenderPass.RIGHT) && System.getenv("NVIDIUM_LOG_EYES") != null) {
            int index = eye == RenderPass.LEFT ? 0 : 1;
            if (++nvidium$eyeLogCounter[index] % 90 == 1) {
                var dataHolder = org.vivecraft.client_vr.ClientDataHolderVR.getInstance();
                org.joml.Matrix4fc projection = dataHolder.vrRenderer.eyeProj[index];
                if (projection == null || dataHolder.vr == null) {
                    return;
                }
                me.cortex.nvidium.Nvidium.LOGGER.info("[eyes] {} proj m00 {} m11 {} m20 {} m21 {} m22 {} m32 {} m23 {} pos {}", eye,
                        projection.m00(), projection.m11(), projection.m20(), projection.m21(), projection.m22(), projection.m32(), projection.m23(),
                        dataHolder.vr.getEyePosition(eye));
            }
        }
    }

    @Inject(method = "renderSingleView", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;extract(Lnet/minecraft/client/DeltaTracker;Z)V"))
    private static void nvidium$profileEyeExtract(RenderPass eye, DeltaTracker.Timer deltaTracker, boolean renderLevel, CallbackInfo ci) {
        FrameProfiler.mark(eye + ":extract");
    }

    @Inject(method = "renderSingleView", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V"))
    private static void nvidium$profileEyeRender(RenderPass eye, DeltaTracker.Timer deltaTracker, boolean renderLevel, CallbackInfo ci) {
        FrameProfiler.mark(eye + ":render");
    }

    @Inject(method = "renderSingleView", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V", shift = At.Shift.AFTER))
    private static void nvidium$profileEyePost(RenderPass eye, DeltaTracker.Timer deltaTracker, boolean renderLevel, CallbackInfo ci) {
        FrameProfiler.mark(eye + ":post");
    }

    @Inject(method = "renderSingleView", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/RenderBuffers;endFrame()V"))
    private static void nvidium$profileBuffersEndFrame(RenderPass eye, DeltaTracker.Timer deltaTracker, boolean renderLevel, CallbackInfo ci) {
        FrameProfiler.mark(eye + ":buffersEndFrame");
    }

    @Inject(method = "renderSingleView", at = @At(value = "INVOKE", target = "Lorg/vivecraft/client_vr/render/helpers/ShaderHelper;renderFullscreenQuad(Ljava/util/function/Supplier;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Ljava/util/function/Consumer;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V"))
    private static void nvidium$profileAlphaClear(RenderPass eye, DeltaTracker.Timer deltaTracker, boolean renderLevel, CallbackInfo ci) {
        FrameProfiler.mark(eye + ":alphaClear");
    }

    @Inject(method = "renderSingleView", at = @At(value = "INVOKE", target = "Lorg/vivecraft/client_vr/render/helpers/ShaderHelper;doVrPostProcess(Lorg/vivecraft/api/client/data/RenderPass;Lcom/mojang/blaze3d/pipeline/RenderTarget;Lcom/mojang/blaze3d/pipeline/RenderTarget;Lorg/vivecraft/client_vr/render/renderstates/PostProcessRenderState;)V"))
    private static void nvidium$profileVrPost(RenderPass eye, DeltaTracker.Timer deltaTracker, boolean renderLevel, CallbackInfo ci) {
        FrameProfiler.mark(eye + ":vrPostProcess");
    }

    @Inject(method = "renderSingleView", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;submit()V"))
    private static void nvidium$profileEyeSubmit(RenderPass eye, DeltaTracker.Timer deltaTracker, boolean renderLevel, CallbackInfo ci) {
        FrameProfiler.mark(eye + ":submit");
    }

    @Inject(method = "renderSingleView", at = @At("TAIL"))
    private static void nvidium$profileEyeEnd(RenderPass eye, DeltaTracker.Timer deltaTracker, boolean renderLevel, CallbackInfo ci) {
        FrameProfiler.mark(eye + ":end");
        if (eye == RenderPass.RIGHT) {
            var renderer = org.vivecraft.client_vr.ClientDataHolderVR.getInstance().vrRenderer;
            me.cortex.nvidium.debug.FlashDetector.frame(renderer.framebufferEye[0], renderer.framebufferEye[1]);
        }
    }

    @Inject(method = "renderAndSubmit", at = @At("HEAD"))
    private static void nvidium$profilePasses(boolean renderLevel, DeltaTracker.Timer deltaTracker, CallbackInfo ci) {
        FrameProfiler.mark("vr:guis");
    }
}
