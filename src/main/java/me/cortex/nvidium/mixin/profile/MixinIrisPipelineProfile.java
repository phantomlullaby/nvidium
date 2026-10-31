package me.cortex.nvidium.mixin.profile;

import me.cortex.nvidium.debug.FrameProfiler;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = IrisRenderingPipeline.class, remap = false)
public class MixinIrisPipelineProfile {
    @Inject(method = "renderShadows", at = @At("HEAD"))
    private void nvidium$profileShadow(CallbackInfo ci) {
        FrameProfiler.mark("iris:shadow");
    }

    @Inject(method = "renderShadows", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/pipeline/CompositeRenderer;renderAll()V"))
    private void nvidium$profilePrepare(CallbackInfo ci) {
        FrameProfiler.mark("iris:prepare");
    }

    @Inject(method = "renderShadows", at = @At("TAIL"))
    private void nvidium$profileShadowEnd(CallbackInfo ci) {
        FrameProfiler.mark("iris:afterShadow");
    }

    @Inject(method = "beginLevelRendering", at = @At("HEAD"))
    private void nvidium$profileBegin(CallbackInfo ci) {
        FrameProfiler.mark("iris:begin");
    }

    @Inject(method = "beginLevelRendering", at = @At("TAIL"))
    private void nvidium$profileGbuffers(CallbackInfo ci) {
        FrameProfiler.mark("iris:gbuffers");
    }

    @Inject(method = "beginTranslucents", at = @At("HEAD"))
    private void nvidium$profileDeferred(CallbackInfo ci) {
        FrameProfiler.mark("iris:deferred");
    }

    @Inject(method = "beginTranslucents", at = @At("TAIL"))
    private void nvidium$profileTranslucent(CallbackInfo ci) {
        FrameProfiler.mark("iris:translucent");
    }

    @Inject(method = "finalizeLevelRendering", at = @At("HEAD"))
    private void nvidium$profileComposite(CallbackInfo ci) {
        FrameProfiler.mark("iris:composite");
    }

    @Inject(method = "finalizeLevelRendering", at = @At("TAIL"))
    private void nvidium$profileCompositeEnd(CallbackInfo ci) {
        FrameProfiler.mark("iris:afterComposite");
    }
}
