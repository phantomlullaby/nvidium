# Nvidium

[![Modrinth](https://img.shields.io/modrinth/dt/nvidium?logo=modrinth)](https://modrinth.com/mod/nvidium)

Nvidium is an alternate rendering backing for sodium, it uses cutting edge nvidia features to render huge amounts of
terrain geometry at very playable framerates.

### Requires sodium and an nvidia gtx 1600 series or newer to run (turing+ architecture)

## About this fork, branch `26.3-vr`

This branch adds Iris shader pack and Vivecraft VR support to Nvidium for Minecraft 26.3, and gets Complementary
Reimagined to 90 fps on a Quest 3 streamed through WiVRn from an RTX 4070 SUPER on Linux. The setup, benchmarks and the
reasoning behind each change are in
[phantomlullaby/minecraft-vr-shaders](https://github.com/phantomlullaby/minecraft-vr-shaders).

- **Iris.** Terrain, translucent terrain and the shadow map are drawn with the shader pack's own programs, as Iris
  patched them for Sodium, adapted to Nvidium's draws. Opaque terrain gets a depth pre-pass, so the pack's shading runs
  once per visible pixel.
- **Vivecraft.** Works with Vivecraft's per-eye rendering and per-eye Iris pipelines. The terrain buffer is reused when
  renderers are rebuilt and stays in video memory. The driver used to place a fresh one in system memory, about 4 times
  slower.
- **Foveated shading for streamed VR.** `NV_shading_rate_image` shades each eye at 1/2 or 1/4 rate wherever WiVRn's
  foveated encoder averages several rendered pixels into one streamed pixel anyway. See the `vr_` options in
  `NvidiumConfig`. Particles, mob eyes and Complementary's bloom fog passes keep full rate, and geometry inputs use
  centroid interpolation so coarse fragments never extrapolate past small triangles.
- **Three render submits in flight** with Vivecraft instead of 2, option `gpu_submit_depth`.
- **Deferred frame end.** With the matching [xrizer fork](https://github.com/phantomlullaby/xrizer/tree/vr-perf) and
  `XRIZER_DEFERRED_END=1`, the previous frame is handed to the runtime after the left eye is queued, so the CPU waits
  while the GPU has work.
- **Culling fixes.** Occluded regions record their sections as not visible, and a region leaving the view clears all 256
  of its sections. Before, a section could be skipped for a frame when it came back into view.
- **Debug tooling.** A frame profiler, autotest scripts, a one-frame flash detector and a NullVR Quest 3 emulation, all
  inactive unless marker files exist in the game directory.

Vivecraft and Iris are optional. Their hooks only apply when the mods are installed.
