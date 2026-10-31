package me.cortex.nvidium.mixin.vr;

import com.mojang.renderpearl.api.commands.RenderPass;
import me.cortex.nvidium.vr.FoveatedShading;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

//Particles are a few pixels of a mostly transparent sprite (a firefly is one lit texel of 8x8). A coarse fragment samples the
// sprite once for its whole block, so a distant particle either vanishes or lights the full 2x2/4x4 block, and bloom turns
// that into a flickering blob. They cover little of the screen, so shade them at full rate.
@Mixin(QuadParticleFeatureRenderer.class)
public class MixinQuadParticleFeatureRenderer {
    @Inject(method = "executeGroup", at = @At("HEAD"))
    private void nvidium$fullRateParticles(FeatureFrameContext context, OitStage stage, RenderPass renderPass, int groupIndex,
                                           List<QuadParticleFeatureRenderer.Submit> submits, boolean strictlyOrdered, CallbackInfo ci) {
        if (FoveatedShading.fullRateParticles) {
            nvidium$suspended = true;
            FoveatedShading.suspend();
        }
    }

    @Inject(method = "executeGroup", at = @At("RETURN"))
    private void nvidium$endFullRateParticles(FeatureFrameContext context, OitStage stage, RenderPass renderPass, int groupIndex,
                                              List<QuadParticleFeatureRenderer.Submit> submits, boolean strictlyOrdered, CallbackInfo ci) {
        if (nvidium$suspended) {
            nvidium$suspended = false;
            FoveatedShading.resume();
        }
    }

    @Unique
    private static boolean nvidium$suspended;
}
