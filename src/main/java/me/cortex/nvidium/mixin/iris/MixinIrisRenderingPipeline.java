package me.cortex.nvidium.mixin.iris;

import me.cortex.nvidium.iris.IrisCompat;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = IrisRenderingPipeline.class, remap = false)
public class MixinIrisRenderingPipeline {
    @Inject(method = "destroy", at = @At("HEAD"))
    private void nvidium$destroyPrograms(CallbackInfo ci) {
        IrisCompat.onPipelineDestroyed((IrisRenderingPipeline) (Object) this);
    }
}
