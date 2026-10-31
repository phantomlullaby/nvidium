package me.cortex.nvidium.mixin.vr;

import me.cortex.nvidium.vr.FoveatedShading;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = IrisRenderingPipeline.class, remap = false)
public class MixinIrisPipelineFoveation {
    //The shadow map and the prepare passes don't draw in eye space
    @Inject(method = "renderShadows", at = @At("HEAD"))
    private void nvidium$suspendForShadows(CallbackInfo ci) {
        FoveatedShading.suspend();
    }

    @Inject(method = "renderShadows", at = @At("TAIL"))
    private void nvidium$resumeAfterShadows(CallbackInfo ci) {
        FoveatedShading.resume();
    }

    @Inject(method = "finalizeLevelRendering", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/pipeline/FinalPassRenderer;renderFinalPass()V"))
    private void nvidium$passFinal(CallbackInfo ci) {
        FoveatedShading.setPass("final");
    }

    //Composite and final passes are the last eye-sized draws of the frame
    @Inject(method = "finalizeLevelRendering", at = @At("TAIL"))
    private void nvidium$endFoveation(CallbackInfo ci) {
        FoveatedShading.setPass(null);
        FoveatedShading.endWorld();
    }
}
