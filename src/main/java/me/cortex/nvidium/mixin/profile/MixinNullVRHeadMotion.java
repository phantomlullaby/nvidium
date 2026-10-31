package me.cortex.nvidium.mixin.profile;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.vivecraft.client_vr.provider.nullvr.NullVR;

import java.nio.file.Files;
import java.nio.file.Path;

//Debug: with "nvidium-nullvr-motion.txt" in the game directory, NullVR's headset sways and turns slowly like a head that is
// held still by hand (a few cm, under 10 degrees), so per-frame rendering issues that need head motion show up without a headset
@Pseudo
@Mixin(value = NullVR.class, remap = false)
public class MixinNullVRHeadMotion {
    @Unique
    private static final boolean MOTION = Files.exists(Path.of("nvidium-nullvr-motion.txt"));
    @Unique
    private static final int HEAD = 2;

    @Shadow
    @Final
    private Vector3f[] deviceOffsets;
    @Shadow
    @Final
    private Quaternionf[] deviceRotations;

    @Unique
    private Vector3f nvidium$baseOffset;
    @Unique
    private Quaternionf nvidium$baseRotation;
    @Unique
    private long nvidium$start;

    @Inject(method = "poll", at = @At("HEAD"))
    private void nvidium$moveHead(long frameIndex, CallbackInfo ci) {
        if (!MOTION) {
            return;
        }
        if (nvidium$baseOffset == null) {
            nvidium$baseOffset = new Vector3f(deviceOffsets[HEAD]);
            nvidium$baseRotation = new Quaternionf(deviceRotations[HEAD]);
            nvidium$start = System.nanoTime();
        }
        double t = (System.nanoTime() - nvidium$start) * 1e-9;
        deviceOffsets[HEAD].set(nvidium$baseOffset).add((float) (0.03 * Math.sin(t * 2.1)), (float) (0.015 * Math.sin(t * 1.3)), (float) (0.02 * Math.sin(t * 1.7)));
        deviceRotations[HEAD].set(nvidium$baseRotation)
                .rotateY((float) Math.toRadians(8 * Math.sin(t * 0.9)))
                .rotateX((float) Math.toRadians(4 * Math.sin(t * 1.4)));
    }
}
