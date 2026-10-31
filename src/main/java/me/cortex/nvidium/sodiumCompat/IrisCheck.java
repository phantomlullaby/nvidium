package me.cortex.nvidium.sodiumCompat;

import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.iris.IrisCompat;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.api.v0.IrisApi;

public class IrisCheck {
    public static final boolean IRIS_LOADED = FabricLoader.getInstance().isModLoaded("iris");

    public static boolean isShaderPackInUse() {
        return IRIS_LOADED && IrisApi.getInstance().isShaderPackInUse();
    }

    //Whether nvidium can run with the current Iris state: no shader pack, or a pack whose terrain programs nvidium could build
    public static boolean canRenderWithIris() {
        if (!isShaderPackInUse()) {
            return true;
        }
        return Nvidium.config.iris_shader_support && IrisCompat.hasTerrainPrograms();
    }
}
