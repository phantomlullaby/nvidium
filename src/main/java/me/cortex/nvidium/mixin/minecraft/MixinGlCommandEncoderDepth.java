package me.cortex.nvidium.mixin.minecraft;

import com.mojang.renderpearl.backend.opengl.GlCommandEncoder;
import me.cortex.nvidium.vr.SubmitDepth;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//renderpearl lets only one submit run on the GPU while the next is recorded (2 fence slots). Vivecraft submits after each eye,
// so the CPU could never get more than one eye ahead of the GPU. This makes the number of slots configurable
@Mixin(value = GlCommandEncoder.class, remap = false)
public class MixinGlCommandEncoderDepth {
    @Shadow
    @Final
    @Mutable
    private long[] fences;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void nvidium$resizeFences(CallbackInfo ci) {
        this.fences = new long[SubmitDepth.DEPTH];
    }

    @ModifyConstant(method = {"currentSubmitSlot", "submit", "awaitSubmit"}, constant = @Constant(longValue = 2L))
    private long nvidium$submitDepth(long original) {
        return SubmitDepth.DEPTH;
    }
}
