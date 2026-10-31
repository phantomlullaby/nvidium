package me.cortex.nvidium.mixin.profile;

import me.cortex.nvidium.debug.FrameProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.vivecraft.client_vr.provider.openvr_lwjgl.OpenVRStereoRenderer;

@Pseudo
@Mixin(value = OpenVRStereoRenderer.class, remap = false)
public class MixinOpenVRStereoRendererProfile {
    @Inject(method = "endFrame", at = @At("HEAD"))
    private void nvidium$profileSubmit(CallbackInfo ci) {
        FrameProfiler.mark("vr:submit");
    }

    @Inject(method = "endFrame", at = @At(value = "INVOKE", target = "Lorg/lwjgl/openvr/VRCompositor;VRCompositor_PostPresentHandoff()V"))
    private void nvidium$profileHandoff(CallbackInfo ci) {
        FrameProfiler.mark("vr:postPresentHandoff");
    }

    @Inject(method = "endFrame", at = @At("TAIL"))
    private void nvidium$profileSubmitEnd(CallbackInfo ci) {
        FrameProfiler.mark("vr:afterSubmit");
    }
}
