package me.cortex.nvidium.mixin.vr;

import me.cortex.nvidium.vr.FoveatedShading;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class MixinLevelRendererFoveation {
    //Iris renders its shadow map before the frame graph runs, everything after draws into eye-sized targets
    @Inject(method = "render", at = @At(value = "CONSTANT", args = "stringValue=executeFrameGraph"))
    private void nvidium$beginFoveation(CallbackInfo ci) {
        var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        FoveatedShading.beginWorld(target.width, target.height);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void nvidium$endFoveation(CallbackInfo ci) {
        FoveatedShading.endWorld();
    }
}
