# Nvidium

[![Modrinth](https://img.shields.io/modrinth/dt/nvidium?logo=modrinth)](https://modrinth.com/mod/nvidium)

Nvidium is an alternate rendering backing for sodium, it uses cutting edge nvidia features to render huge amounts of
terrain geometry at very playable framerates.

### Requires sodium and an nvidia gtx 1600 series or newer to run (turing+ architecture)

## About this fork (branch `26.3-vr`)

This branch adds Iris shader pack and Vivecraft VR support to Nvidium for Minecraft 26.3, and makes Complementary
Reimagined run at 90 fps on a Quest 3 streamed through WiVRn (RTX 4070 SUPER, Linux). The setup, benchmarks and the
reasoning behind each change are documented in
[phantomlullaby/minecraft-vr-shaders](https://github.com/phantomlullaby/minecraft-vr-shaders).

- **Iris:** terrain is drawn with the shader pack's own `gbuffers_terrain` programs (Iris patched shaders adapted to
  Nvidium's draws), including the shadow pass. Opaque terrain gets a depth pre-pass so the expensive shading runs once
  per visible pixel.
- **Vivecraft:** works with Vivecraft's per-eye rendering and per-eye Iris pipelines. The terrain buffer is reused when
  renderers are rebuilt and is kept in video memory, where the driver used to fall back to system memory (~4x slower).
- **Foveated shading (VR only):** with `NV_shading_rate_image`, each eye is shaded at 1/2 or 1/4 rate wherever WiVRn's
  foveated encoder would average several rendered pixels into one streamed pixel anyway (`vr_foveated_shading`,
  `vr_foveation_sample_factor`, `vr_stream_width/height`). Particles, mob eyes and Complementary's bloom fog passes stay
  at full rate, and geometry varyings use centroid interpolation so coarse fragments never extrapolate past a triangle.
- **Frames in flight:** 3 render submits may be in flight with Vivecraft instead of 2 (`gpu_submit_depth`).
- **Deferred frame end:** with the matching xrizer fork
  ([phantomlullaby/xrizer](https://github.com/phantomlullaby/xrizer), `XRIZER_DEFERRED_END=1`), the previous frame is
  handed to the runtime after the left eye is queued, so the CPU waits while the GPU is busy.
- **Culling fixes:** occluded regions record their sections as not visible, and a region leaving the frustum clears all
  256 of its sections, so sections are not skipped for a frame when they come back into view.
- **Debug tooling** (inactive unless marker files exist in the game directory): frame profiler, autotest scripts, one-frame
  flash detector, NullVR Quest emulation.

Vivecraft and Iris are optional: their hooks only apply when the mods are installed.
