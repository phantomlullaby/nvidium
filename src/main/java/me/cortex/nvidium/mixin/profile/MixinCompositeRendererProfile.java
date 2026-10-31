package me.cortex.nvidium.mixin.profile;

import me.cortex.nvidium.debug.FrameProfiler;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.irisshaders.iris.pipeline.CompositePass;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = CompositeRenderer.class, remap = false)
public class MixinCompositeRendererProfile {
    @Shadow
    @Final
    private CompositePass compositePass;

    @Unique
    private int nvidium$passIndex;

    @Inject(method = "renderAll", at = @At("HEAD"))
    private void nvidium$profileStart(CallbackInfo ci) {
        nvidium$passIndex = 0;
    }

    @Inject(method = "renderAll", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/gl/program/Program;use()V"))
    private void nvidium$profilePass(CallbackInfo ci) {
        FrameProfiler.mark("pass:" + this.compositePass.name().toLowerCase() + nvidium$passIndex++);
    }

    @Inject(method = "renderAll", at = @At("TAIL"))
    private void nvidium$profileEnd(CallbackInfo ci) {
        FrameProfiler.mark("pass:" + this.compositePass.name().toLowerCase() + ":end");
    }
}
