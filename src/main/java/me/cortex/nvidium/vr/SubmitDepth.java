package me.cortex.nvidium.vr;

import me.cortex.nvidium.Nvidium;
import net.fabricmc.loader.api.FabricLoader;

public final class SubmitDepth {
    //How many renderpearl submits may be pending on the GPU plus the one being recorded (vanilla: 2). Vivecraft submits once
    // per eye and once more per frame, so with 2 the render thread waits for the left eye's GPU work before finishing the
    // right eye, and for the right eye before starting the next frame
    public static final int DEPTH = resolve();

    private SubmitDepth() {}

    private static int resolve() {
        int configured = Nvidium.config.gpu_submit_depth;
        int depth = configured > 0 ? configured : FabricLoader.getInstance().isModLoaded("vivecraft") ? 3 : 2;
        depth = Math.max(2, Math.min(6, depth));
        Nvidium.LOGGER.info("Render submit depth {}", depth);
        return depth;
    }

    //Debug toggle: whether nvidium hands the previous frame off to xrizer after the left eye (XRIZER_DEFERRED_END) or before it,
    // which gives the usual order (end the previous frame, wait, begin, then render)
    public static boolean deferredHandoff = true;
}
