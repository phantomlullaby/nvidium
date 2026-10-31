package me.cortex.nvidium.mixin.vr;

import me.cortex.nvidium.vr.FoveatedShading;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.vivecraft.api.client.data.RenderPass;
import org.vivecraft.client_vr.ClientDataHolderVR;
import org.vivecraft.client_vr.render.helpers.VRPassHelper;
import org.lwjgl.openvr.VRCompositor;
import org.vivecraft.client_vr.provider.openvr_lwjgl.MCOpenVR;

@Pseudo
@Mixin(value = VRPassHelper.class, remap = false)
public class MixinVRPassHelper {
    @Inject(method = "renderSingleView", at = @At("HEAD"))
    private static void nvidium$beginEye(RenderPass pass, DeltaTracker.Timer deltaTracker, boolean renderLevel, CallbackInfo ci) {
        //Debug: hand the previous frame off before this frame renders, the usual frame order
        if (pass == RenderPass.LEFT && DEFERRED_FRAME_END && !me.cortex.nvidium.vr.SubmitDepth.deferredHandoff && ClientDataHolderVR.getInstance().vr instanceof MCOpenVR) {
            VRCompositor.VRCompositor_PostPresentHandoff();
        }
        int eye = pass == RenderPass.LEFT ? 0 : pass == RenderPass.RIGHT ? 1 : -1;
        if (eye < 0) {
            FoveatedShading.beginEye(-1, null, 0);
            return;
        }
        var dataHolder = ClientDataHolderVR.getInstance();
        var projection = dataHolder.vrRenderer.eyeProj[eye];
        double halfIpd = dataHolder.vr.getEyePosition(RenderPass.LEFT).distance(dataHolder.vr.getEyePosition(RenderPass.RIGHT)) * 0.5;
        if (!(halfIpd > 0.01 && halfIpd < 0.05)) {
            halfIpd = 0.032;
        }
        FoveatedShading.beginEye(eye, projection, eye == 0 ? -halfIpd : halfIpd);
    }

    @Inject(method = "renderSingleView", at = @At("TAIL"))
    private static void nvidium$endEye(RenderPass pass, DeltaTracker.Timer deltaTracker, boolean renderLevel, CallbackInfo ci) {
        FoveatedShading.endEye();
        //With xrizer's deferred frame end, the previous frame is ended (which on GLX waits for the GPU to finish everything
        // queued) once this frame's left eye is queued, so the GPU has work while the CPU waits and the frame end
        if (pass == RenderPass.LEFT && DEFERRED_FRAME_END && me.cortex.nvidium.vr.SubmitDepth.deferredHandoff && ClientDataHolderVR.getInstance().vr instanceof MCOpenVR) {
            VRCompositor.VRCompositor_PostPresentHandoff();
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private static final boolean DEFERRED_FRAME_END = System.getenv("XRIZER_DEFERRED_END") != null;
}
