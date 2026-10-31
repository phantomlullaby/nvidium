package me.cortex.nvidium.mixin.minecraft;

import me.cortex.nvidium.vr.SubmitDepth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Array;

//The transient memory used by a submit is recycled once that submit's fence slot comes around again, so it needs as many
// rotation slots as the encoder has fence slots
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlTransientMemory$PersistentMapping", remap = false)
public class MixinGlTransientMemoryDepth {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void nvidium$resizeRotations(CallbackInfo ci) {
        try {
            var field = this.getClass().getDeclaredField("rotations");
            field.setAccessible(true);
            Object rotations = field.get(this);
            field.set(this, Array.newInstance(rotations.getClass().getComponentType(), SubmitDepth.DEPTH));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not resize renderpearl's transient memory rotations", e);
        }
    }
}
