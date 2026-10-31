package me.cortex.nvidium.debug;

import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.sodiumCompat.INvidiumWorldRendererGetter;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;

/**
 * Scripted testing helper, only active when "nvidium-autotest.txt" exists in the game directory. Once in a world it runs
 * one line per tick: "wait <ticks>", "cmd <server command>", "shot <name>", "nvidium on|off", "prepass on|off", "hud on|off", "pause on|off", "pos", "fps", "debug", "shaders on|off", "irisreload", "vrs on|off", "vrsk <factor>", "vrsfull <programs>|none", "eyeshot <name>", "flash on <label>|off|report <label>", "vrsparticles full|coarse", "vrspipelines default|none", "prof reset|report <label>", "log <text>", "quit".
 */
public final class AutoTest {
    private static Deque<String> lines;
    private static int waitTicks;

    private AutoTest() {}

    public static void tick(Minecraft minecraft) {
        if (lines == null) {
            Path script = minecraft.gameDirectory.toPath().resolve("nvidium-autotest.txt");
            lines = new ArrayDeque<>();
            if (Files.exists(script)) {
                try {
                    Files.readAllLines(script).stream().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).forEach(lines::add);
                    Nvidium.LOGGER.info("[autotest] loaded {} lines", lines.size());
                } catch (IOException e) {
                    Nvidium.LOGGER.error("[autotest] could not read script", e);
                }
            }
        }
        if (lines.isEmpty() || minecraft.player == null || minecraft.getSingleplayerServer() == null) {
            return;
        }
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }

        String line = lines.poll();
        String arg = line.contains(" ") ? line.substring(line.indexOf(' ') + 1) : "";
        Nvidium.LOGGER.info("[autotest] {}", line);
        switch (line.split(" ")[0]) {
            case "wait" -> waitTicks = Integer.parseInt(arg);
            case "cmd" -> {
                var server = minecraft.getSingleplayerServer();
                server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), arg));
            }
            case "shot" -> Screenshot.grab(minecraft.gameDirectory, arg + ".png", minecraft.gameRenderer.mainRenderTarget(), 1,
                    message -> Nvidium.LOGGER.info("[autotest] screenshot {}: {}", arg, message.getString()));
            case "nvidium" -> {
                Nvidium.FORCE_DISABLE = !arg.equals("on");
                minecraft.levelExtractor.allChanged();
            }
            case "hud" -> {
                if (minecraft.gui.hud.isHidden() == arg.equals("on")) {
                    minecraft.gui.hud.toggle();
                }
            }
            case "pause" -> {
                if (arg.equals("on")) {
                    minecraft.pauseGame(false);
                } else {
                    minecraft.gui.setScreen(null);
                }
            }
            case "pos" -> Nvidium.LOGGER.info("[autotest] camera at {}", minecraft.gameRenderer.mainCamera().position());
            case "fps" -> Nvidium.LOGGER.info("[autotest] fps {}", minecraft.getFps());
            case "debug" -> {
                var renderer = ((INvidiumWorldRendererGetter) SodiumWorldRenderer.instance()).getRenderer();
                if (renderer != null) {
                    var info = new ArrayList<String>();
                    renderer.addDebugInfo(info);
                    info.forEach(l -> Nvidium.LOGGER.info("[autotest] {}", l));
                }
            }
            case "shaders" -> {
                try {
                    net.irisshaders.iris.Iris.getIrisConfig().setShadersEnabled(arg.equals("on"));
                    net.irisshaders.iris.Iris.getIrisConfig().save();
                    net.irisshaders.iris.Iris.reload();
                } catch (IOException e) {
                    Nvidium.LOGGER.error("[autotest] toggling shaders failed", e);
                }
            }
            case "irisreload" -> {
                try {
                    net.irisshaders.iris.Iris.reload();
                } catch (IOException e) {
                    Nvidium.LOGGER.error("[autotest] iris reload failed", e);
                }
            }
            case "prof" -> {
                if (arg.startsWith("report")) {
                    FrameProfiler.reportNow(arg.substring("report".length()).strip());
                } else {
                    FrameProfiler.reset();
                }
            }
            case "vrsk" -> me.cortex.nvidium.vr.FoveatedShading.sampleFactor = Double.parseDouble(arg);
            case "vrsfull" -> me.cortex.nvidium.vr.FoveatedShading.fullRatePasses = arg.equals("none") ? java.util.Set.of() : java.util.Set.of(arg.split(","));
            case "eyeshot" -> VivecraftShots.grabEyes(minecraft, arg);
            case "flash" -> FlashDetector.command(arg);
            case "centroid" -> me.cortex.nvidium.vr.CentroidVaryings.enabled = arg.equals("on");
            case "centroidnames" -> me.cortex.nvidium.vr.CentroidVaryings.names = arg.equals("all") ? java.util.Set.of() : java.util.Set.of(arg.split(","));
            case "vrsgeometry" -> me.cortex.nvidium.vr.FoveatedShading.coarseGeometry = arg.equals("on");
            case "vrscomposites" -> me.cortex.nvidium.vr.FoveatedShading.coarseComposites = arg.equals("on");
            case "vrsparticles" -> me.cortex.nvidium.vr.FoveatedShading.fullRateParticles = arg.equals("full");
            case "vrspipelines" -> me.cortex.nvidium.vr.FoveatedShading.fullRatePipelines = arg.equals("none") ? java.util.Set.of() : java.util.Set.copyOf(Nvidium.config.vr_full_rate_pipelines);
            case "prepass" -> me.cortex.nvidium.iris.IrisCompat.depthPrepass = arg.equals("on");
            case "vrs" -> {
                me.cortex.nvidium.vr.FoveatedShading.runtimeEnabled = arg.equals("on");
                Nvidium.LOGGER.info("[autotest] foveated shading {}", arg);
            }
            case "log" -> {}
            case "quit" -> minecraft.stop();
            default -> Nvidium.LOGGER.warn("[autotest] unknown command {}", line);
        }
    }
}
