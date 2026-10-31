package me.cortex.nvidium.mixin.profile;

import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import org.joml.Vector2i;
import org.joml.Vector2ic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.vivecraft.client_vr.provider.nullvr.NullVRStereoRenderer;

import java.nio.file.Files;
import java.nio.file.Path;

//Debug: with "nvidium-nullvr-quest.txt" in the game directory, Vivecraft's headset-free NullVR renders Quest 3 eyes as
// WiVRn reports them (2270x2378, asymmetric fov), so image comparisons can run without the headset
@Pseudo
@Mixin(value = NullVRStereoRenderer.class, remap = false)
public class MixinNullVRQuestEmulation {
    @Unique
    private static final boolean QUEST = Files.exists(Path.of("nvidium-nullvr-quest.txt"));
    //Tangents from the eye projections Vivecraft built from xrizer/WiVRn on the Quest 3 (left eye; the right one mirrors x)
    @Unique
    private static final float LEFT = (-0.24251266f - 1) / 0.9027383f;
    @Unique
    private static final float RIGHT = (-0.24251266f + 1) / 0.9027383f;
    @Unique
    private static final float DOWN = (-0.19318743f - 1) / 0.8354789f;
    @Unique
    private static final float UP = (-0.19318743f + 1) / 0.8354789f;

    @Inject(method = "getRenderTextureSizes", at = @At("HEAD"), cancellable = true)
    private void nvidium$questSize(CallbackInfoReturnable<Vector2ic> cir) {
        if (QUEST) {
            cir.setReturnValue(new Vector2i(2270, 2378));
        }
    }

    @Inject(method = "getProjectionMatrix", at = @At("HEAD"), cancellable = true)
    private void nvidium$questProjection(int eyeType, float nearClip, float farClip, CallbackInfoReturnable<Matrix4f> cir) {
        if (QUEST) {
            float left = eyeType == 0 ? LEFT : -RIGHT;
            float right = eyeType == 0 ? RIGHT : -LEFT;
            //Same construction as Vivecraft's OpenVRStereoRenderer, so the reversed (far, near) call works the same
            cir.setReturnValue(new Matrix4f().frustum(left * nearClip, right * nearClip, DOWN * nearClip, UP * nearClip,
                    nearClip, farClip, RenderSystem.getDevice().getDeviceInfo().isZZeroToOne()));
        }
    }
}
