package me.cortex.nvidium.mixin.vr;

import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import me.cortex.nvidium.vr.FoveatedShading;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//Lets foveated shading keep some pipelines at full rate (vr_full_rate_pipelines). Iris' replacement pipelines keep the vanilla
// name, and the GL backend draws immediately, so the rate set here applies to exactly the draws made with the pipeline
@Mixin(FrontendRenderPass.class)
public class MixinFrontendRenderPass {
    @Inject(method = "setPipeline", at = @At("TAIL"))
    private void nvidium$pipelineShadingRate(CompiledRenderPipeline pipeline, CallbackInfo ci) {
        FoveatedShading.setPipeline(pipeline instanceof FrontendRenderPipeline frontend ? frontend.name() : null);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void nvidium$endPipelineShadingRate(CallbackInfo ci) {
        FoveatedShading.setPipeline(null);
    }
}
