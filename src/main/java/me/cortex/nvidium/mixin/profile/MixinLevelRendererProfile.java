package me.cortex.nvidium.mixin.profile;

import me.cortex.nvidium.debug.FrameProfiler;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class MixinLevelRendererProfile {
    @Inject(method = "render", at = @At("HEAD"))
    private void nvidium$profileLevel(CallbackInfo ci) {
        FrameProfiler.mark("level:start");
    }

    @Inject(method = "render", at = @At(value = "CONSTANT", args = "stringValue=submitFeatures"))
    private void nvidium$profileSubmitFeatures(CallbackInfo ci) {
        FrameProfiler.mark("level:submitFeatures");
    }

    @Inject(method = "render", at = @At(value = "CONSTANT", args = "stringValue=prepareFeatures"))
    private void nvidium$profilePrepareFeatures(CallbackInfo ci) {
        FrameProfiler.mark("level:prepareFeatures");
    }

    @Inject(method = "render", at = @At(value = "CONSTANT", args = "stringValue=setupFrameGraph"))
    private void nvidium$profileSetupFrameGraph(CallbackInfo ci) {
        FrameProfiler.mark("level:setupFrameGraph");
    }

    @Inject(method = "render", at = @At(value = "CONSTANT", args = "stringValue=executeFrameGraph"))
    private void nvidium$profileExecuteFrameGraph(CallbackInfo ci) {
        FrameProfiler.mark("level:executeFrameGraph");
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void nvidium$profileLevelEnd(CallbackInfo ci) {
        FrameProfiler.mark("level:end");
    }

    @Inject(method = "executeSolid", at = @At(value = "CONSTANT", args = "stringValue=renderSolidFeatures"))
    private void nvidium$profileSolidFeatures(CallbackInfo ci) {
        FrameProfiler.mark("level:solidFeatures");
    }

    @Inject(method = "executeSolid", at = @At("TAIL"))
    private void nvidium$profileSolidEnd(CallbackInfo ci) {
        FrameProfiler.mark("level:afterSolid");
    }
}
