package me.cortex.nvidium.mixin.sodium;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.NvidiumWorldRenderer;
import me.cortex.nvidium.debug.FrameProfiler;
import me.cortex.nvidium.iris.IrisCompat;
import me.cortex.nvidium.sodiumCompat.IrisCheck;
import net.minecraft.client.Minecraft;
import me.cortex.nvidium.mixin.minecraft.FrontendCommandEncoderAccessor;
import me.cortex.nvidium.mixin.minecraft.GlCommandEncoderAccessor;
import me.cortex.nvidium.sodiumCompat.INvidiumWorldRendererGetter;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.oit.OitStage;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL33C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SodiumWorldRenderer.class, remap = false)
public abstract class MixinSodiumWorldRenderer implements INvidiumWorldRendererGetter {
    @Shadow
    private RenderSectionManager renderSectionManager;
    @Unique
    private Viewport viewport;
    //Iris sets up and renders the terrain a second time for its shadow map, with the shadow camera's frustum
    @Unique
    private Viewport shadowViewport;

    @Override
    public NvidiumWorldRenderer getRenderer() {
        if (Nvidium.IS_ENABLED) {
            return ((INvidiumWorldRendererGetter) renderSectionManager).getRenderer();
        } else {
            return null;
        }
    }

    @Inject(method = "setupTerrain", at = @At(value = "HEAD"))
    public void trackViewport(Camera camera, Viewport viewport, FogParameters fogParameters, boolean spectator, boolean updateChunksImmediately, Matrix4f cullMatrix, CallbackInfo ci) {
        if (nvidium$isShadowPass()) {
            this.shadowViewport = viewport;
        } else {
            this.viewport = viewport;
            nvidium$checkIrisState();
        }
    }

    @Unique
    private static boolean nvidium$reloadScheduled;

    //Whether nvidium runs, and in which mode, is decided when sodium creates its section manager. If the Iris state changed
    // since then without sodium reloading (e.g. a pack whose terrain programs nvidium could not build), reload once
    @Unique
    private static void nvidium$checkIrisState() {
        if (!IrisCheck.IRIS_LOADED || !Nvidium.IS_COMPATIBLE || Nvidium.FORCE_DISABLE) {
            return;
        }
        boolean enable = IrisCheck.canRenderWithIris();
        boolean stale = enable != Nvidium.IS_ENABLED || (enable && IrisCheck.isShaderPackInUse() != Nvidium.IRIS_MODE);
        if (!stale) {
            nvidium$reloadScheduled = false;
        } else if (!nvidium$reloadScheduled) {
            nvidium$reloadScheduled = true;
            Nvidium.LOGGER.info("Iris state changed, reloading renderer (nvidium {})", enable ? "enabled" : "disabled");
            Minecraft.getInstance().schedule(() -> {
                if (Minecraft.getInstance().levelExtractor != null) {
                    Minecraft.getInstance().levelExtractor.allChanged();
                }
            });
        }
    }

    @Unique
    private static boolean nvidium$isShadowPass() {
        return Nvidium.IRIS_MODE && IrisCompat.isShadowPass();
    }

    @Inject(method = "renderLayer", at = @At(value = "HEAD"), cancellable = true)
    public void renderLayer(ChunkRenderMatrices matrices, TerrainRenderPass pass, double x, double y, double z, FogParameters fogParameters, GpuSampler terrainSampler, RenderPass renderPass, OitStage stage, CallbackInfo ci) {
        if (!Nvidium.IS_ENABLED && FrameProfiler.ENABLED) {
            String name = (IrisCheck.IRIS_LOADED && IrisCompat.isShadowPass() ? "sodium:shadow:" : "sodium:") + (pass.isTranslucent() ? "translucent" : pass == DefaultTerrainRenderPasses.CUTOUT ? "cutout" : "solid");
            FrameProfiler.mark(name);
            FrameProfiler.beginStats(name);
        }
        if (Nvidium.IS_ENABLED) {
            ci.cancel();
            if (pass == DefaultTerrainRenderPasses.CUTOUT) // Early exit, cutout will be rendered with SOLID
                return;

            // Invalidate lastProgram to prevent B3D caching bad program
            ((GlCommandEncoderAccessor)
                    ((FrontendCommandEncoderAccessor) RenderSystem.getDevice().createCommandEncoder())
                            .nvidium$getBackend())
                    .nvidium$setLastProgram(null);

            boolean shadow = nvidium$isShadowPass();
            FrameProfiler.mark((shadow ? "terrain:shadow:" : "terrain:") + (pass.isTranslucent() ? "translucent" : "solid"));
            GlStateManager._disableScissorTest();
            if (shadow) {
                GlStateManager._disableCull();//Iris draws the shadow map without face culling
            } else {
                GlStateManager._enableCull();
            }
            GlStateManager._enableDepthTest();
            GlStateManager._depthFunc(shadow ? GL33C.GL_LEQUAL : GL33C.GL_GEQUAL); // reverse-Z, except for Iris' shadow map
            GlStateManager._colorMask(0, 15);
            GlStateManager._depthMask(true);

            if (shadow) {
                if (this.shadowViewport != null) {
                    this.getRenderer().renderShadow(pass, this.shadowViewport, fogParameters, matrices, x, y, z);
                }
            } else if (pass == DefaultTerrainRenderPasses.SOLID) {
                GlStateManager._disableBlend(0);
                this.getRenderer().renderFrame(pass, viewport, fogParameters, matrices, x, y, z, terrainSampler);
            } else if (pass == DefaultTerrainRenderPasses.TRANSLUCENT) {
                this.getRenderer().renderTranslucent(pass, (FrontendRenderPass) renderPass, terrainSampler, stage);
            }
            FrameProfiler.mark(shadow ? "terrain:afterShadow" : "terrain:after");
        }
    }

    @Inject(method = "renderLayer", at = @At(value = "RETURN"))
    public void renderLayerEnd(ChunkRenderMatrices matrices, TerrainRenderPass pass, double x, double y, double z, FogParameters fogParameters, GpuSampler terrainSampler, RenderPass renderPass, OitStage stage, CallbackInfo ci) {
        if (!Nvidium.IS_ENABLED && FrameProfiler.ENABLED) {
            FrameProfiler.endStats();
            FrameProfiler.mark("sodium:after");
        }
    }
}
