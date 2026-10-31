package me.cortex.nvidium.debug;

import me.cortex.nvidium.Nvidium;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.vivecraft.client_vr.ClientDataHolderVR;

/** Kept apart from AutoTest so AutoTest never loads Vivecraft classes when Vivecraft isn't installed. */
final class VivecraftShots {
    private VivecraftShots() {}

    //Saves the last submitted image of both eyes at full resolution
    static void grabEyes(Minecraft minecraft, String name) {
        var renderer = ClientDataHolderVR.getInstance().vrRenderer;
        if (renderer == null || renderer.framebufferEye[0] == null) {
            Nvidium.LOGGER.warn("[autotest] no VR eye framebuffers");
            return;
        }
        for (int eye = 0; eye < 2; eye++) {
            String file = name + (eye == 0 ? "_L" : "_R") + ".png";
            Screenshot.grab(minecraft.gameDirectory, file, renderer.framebufferEye[eye], 1,
                    message -> Nvidium.LOGGER.info("[autotest] eyeshot {}: {}", file, message.getString()));
        }
    }
}
