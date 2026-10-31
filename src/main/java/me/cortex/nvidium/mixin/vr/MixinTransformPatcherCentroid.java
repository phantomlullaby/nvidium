package me.cortex.nvidium.mixin.vr;

import me.cortex.nvidium.vr.CentroidVaryings;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.EnumMap;
import java.util.Map;

//Centroid interpolation for the varyings of the geometry programs (see CentroidVaryings). Both sides of each stage boundary get
// the qualifier, older GLSL versions require them to match. Nvidium's terrain programs are made from patchSodium's output
@Mixin(value = TransformPatcher.class, remap = false)
public class MixinTransformPatcherCentroid {
    @Inject(method = {"patchVanilla", "patchSodium"}, at = @At("RETURN"), cancellable = true)
    private static void nvidium$centroidVaryings(CallbackInfoReturnable<Map<PatchShaderType, String>> cir) {
        Map<PatchShaderType, String> sources = cir.getReturnValue();
        if (!CentroidVaryings.enabled || sources == null || sources.get(PatchShaderType.TESS_CONTROL) != null || sources.get(PatchShaderType.TESS_EVAL) != null) {
            return;
        }
        Map<PatchShaderType, String> patched = new EnumMap<>(PatchShaderType.class);
        sources.forEach((type, source) -> {
            if (source == null) {
                patched.put(type, null);
                return;
            }
            patched.put(type, switch (type) {
                case VERTEX -> CentroidVaryings.outputs(source);
                case GEOMETRY -> CentroidVaryings.outputs(CentroidVaryings.inputs(source));
                case FRAGMENT -> CentroidVaryings.inputs(source);
                default -> source;
            });
        });
        cir.setReturnValue(patched);
    }
}
