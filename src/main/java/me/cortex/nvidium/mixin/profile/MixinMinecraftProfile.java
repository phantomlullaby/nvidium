package me.cortex.nvidium.mixin.profile;

import me.cortex.nvidium.debug.FrameProfiler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Minecraft.class, priority = 100)
public class MixinMinecraftProfile {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void nvidium$profileFrame(boolean advanceGameTime, CallbackInfo ci) {
        FrameProfiler.frameBoundary();
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;tick()V"))
    private void nvidium$profileTick(boolean advanceGameTime, CallbackInfo ci) {
        FrameProfiler.mark("tick");
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;tick()V", shift = At.Shift.AFTER))
    private void nvidium$profileAfterTick(boolean advanceGameTime, CallbackInfo ci) {
        FrameProfiler.mark("afterTick");
    }

    @Inject(method = "renderFrame", at = @At("HEAD"))
    private void nvidium$profileRenderFrame(boolean advanceGameTime, CallbackInfo ci) {
        FrameProfiler.mark("renderFrame");
    }

    @Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;update(Lnet/minecraft/client/DeltaTracker;)V"))
    private void nvidium$profileGuiUpdate(boolean advanceGameTime, CallbackInfo ci) {
        FrameProfiler.mark("gui:update");
    }

    @Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;extract(Lnet/minecraft/client/DeltaTracker;Z)V"))
    private void nvidium$profileGuiExtract(boolean advanceGameTime, CallbackInfo ci) {
        FrameProfiler.mark("gui:extract");
    }

    @Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V"))
    private void nvidium$profileGuiRender(boolean advanceGameTime, CallbackInfo ci) {
        FrameProfiler.mark("gui:render");
    }

    @Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;submit()V"))
    private void nvidium$profileSubmit(boolean advanceGameTime, CallbackInfo ci) {
        FrameProfiler.mark("submit");
    }

    @Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V"))
    private void nvidium$profilePresent(boolean advanceGameTime, CallbackInfo ci) {
        FrameProfiler.mark("present");
    }

    @Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V", shift = At.Shift.AFTER))
    private void nvidium$profileAfterPresent(boolean advanceGameTime, CallbackInfo ci) {
        FrameProfiler.mark("afterPresent");
    }
}
