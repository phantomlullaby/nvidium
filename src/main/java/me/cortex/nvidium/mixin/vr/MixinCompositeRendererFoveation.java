package me.cortex.nvidium.mixin.vr;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.cortex.nvidium.vr.FoveatedShading;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Pseudo
@Mixin(value = CompositeRenderer.class, remap = false)
public class MixinCompositeRendererFoveation {
    @Shadow
    @Final
    private ImmutableList<?> passes;

    //Program names ("composite2") of the passes that draw, in draw order (compute-only passes don't set a viewport)
    @Unique
    private List<String> nvidium$passNames;
    @Unique
    private int nvidium$passIndex;

    @Inject(method = "renderAll", at = @At("HEAD"))
    private void nvidium$startPasses(CallbackInfo ci) {
        nvidium$passIndex = 0;
        if (nvidium$passNames == null) {
            nvidium$passNames = nvidium$collectPassNames();
        }
    }

    //A pass with a scaled or offset viewport doesn't map 1:1 onto the eye's shading rate image, shade it at full rate
    @WrapOperation(method = "renderAll", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;_viewport(IIII)V"))
    private void nvidium$checkPassViewport(int x, int y, int width, int height, Operation<Void> original) {
        original.call(x, y, width, height);
        var main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        FoveatedShading.setFullViewport(x == 0 && y == 0 && width == main.width && height == main.height);
        int index = nvidium$passIndex++;
        FoveatedShading.setPass(index < nvidium$passNames.size() ? nvidium$passNames.get(index) : null);
    }

    @Inject(method = "renderAll", at = @At("TAIL"))
    private void nvidium$endPasses(CallbackInfo ci) {
        FoveatedShading.setFullViewport(true);
        FoveatedShading.setPass(null);
    }

    //Iris' Pass and ComputeOnlyPass are private, read their name field reflectively
    @Unique
    private List<String> nvidium$collectPassNames() {
        var names = new ArrayList<String>();
        for (Object pass : this.passes) {
            Class<?> type = pass.getClass();
            if (type.getSimpleName().equals("ComputeOnlyPass")) {
                continue;
            }
            while (type != null && !type.getSimpleName().equals("Pass")) {
                type = type.getSuperclass();
            }
            try {
                var field = (type != null ? type : pass.getClass()).getDeclaredField("name");
                field.setAccessible(true);
                names.add((String) field.get(pass));
            } catch (ReflectiveOperationException e) {
                names.add(null);
            }
        }
        return names;
    }
}
