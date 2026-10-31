package me.cortex.nvidium.mixin.minecraft;

import me.cortex.nvidium.debug.AutoTest;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraftAutoTest {
    @Inject(method = "tick", at = @At("TAIL"))
    private void nvidium$autoTest(CallbackInfo ci) {
        AutoTest.tick((Minecraft) (Object) this);
    }
}
