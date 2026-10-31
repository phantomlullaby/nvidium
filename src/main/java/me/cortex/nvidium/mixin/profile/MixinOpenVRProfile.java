package me.cortex.nvidium.mixin.profile;

import me.cortex.nvidium.debug.FrameProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.vivecraft.client_vr.provider.openvr_lwjgl.MCOpenVR;

@Pseudo
@Mixin(value = MCOpenVR.class, remap = false)
public class MixinOpenVRProfile {
    @Inject(method = "updatePose", at = @At("HEAD"))
    private void nvidium$profileWait(CallbackInfo ci) {
        FrameProfiler.mark("vr:waitGetPoses");
    }

    @Inject(method = "updatePose", at = @At("TAIL"))
    private void nvidium$profileWaitEnd(CallbackInfo ci) {
        FrameProfiler.mark("vr:afterPoses");
    }
}
