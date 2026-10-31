package me.cortex.nvidium.iris;

import me.cortex.nvidium.Nvidium;

import java.util.regex.Pattern;

/**
 * Makes the depth pre-pass fragment shader from an Iris patched terrain fragment shader: the color outputs become plain
 * globals, so the compiler removes everything that does not decide a discard (lighting, shadows...). The pre-pass then
 * writes exactly the depth the full program would, and the color pass can run with depth writes off, which lets the GPU
 * test depth before shading (with discard and depth writes on, it shades every fragment and tests depth afterwards).
 */
final class IrisDepthPatcher {
    //Fragment outputs as glsl-transformer prints them, one declaration per line
    private static final Pattern OUTPUT = Pattern.compile("(?m)^(\\s*)(?:layout\\s*\\([^)]*\\)\\s*)?out\\s+");
    //Anything with an effect besides color and the discard (running it twice or for fewer fragments would change the result)
    private static final Pattern UNSUPPORTED = Pattern.compile("\\b(?:gl_FragDepth|gl_FragColor|gl_FragData|gl_SampleMask|imageStore|imageAtomic\\w*|atomic\\w*|buffer|early_fragment_tests)\\b");

    private IrisDepthPatcher() {}

    /**
     * @return the depth only fragment shader, or null if the shader cannot be made depth only
     */
    static String patch(String name, String fragment) {
        var unsupported = UNSUPPORTED.matcher(fragment);
        if (unsupported.find()) {
            Nvidium.LOGGER.info("No depth pre-pass for {}, its fragment shader uses {}", name, unsupported.group());
            return null;
        }
        var outputs = OUTPUT.matcher(fragment);
        if (!outputs.find()) {
            Nvidium.LOGGER.info("No depth pre-pass for {}, its fragment shader has no outputs", name);
            return null;
        }
        return outputs.replaceAll("$1");
    }
}
