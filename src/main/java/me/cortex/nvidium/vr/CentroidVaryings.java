package me.cortex.nvidium.vr;

import me.cortex.nvidium.Nvidium;

import java.util.regex.Pattern;

/**
 * With coarse shading, a fragment shader invocation covers a 2x2 or 4x4 pixel block and its inputs are interpolated at the
 * block center, which for a small triangle (a distant leaf) can lie far outside it. The extrapolated varyings (lightmap
 * coordinates, colors, view positions...) then reach values the pack never sees otherwise, and Complementary turned some into
 * single pixels bright enough to bloom into a glowing blob for a frame. Centroid interpolation keeps the sample inside the
 * covered part of the triangle. It changes nothing for full rate pixels (there is no multisampling).
 */
public final class CentroidVaryings {
    //Varying declarations at global scope: optional layout(...), optional smooth/noperspective, then in/out, a type and names.
    // flat varyings are not interpolated, interface blocks ("{") and already qualified declarations are left alone
    private static final Pattern IN = Pattern.compile("(?m)^([ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?(?:(?:smooth|noperspective)\\s+)?)in(\\s+[A-Za-z_]\\w*\\s+[A-Za-z_][^;{()\\n]*;)");
    private static final Pattern OUT = Pattern.compile("(?m)^([ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?(?:(?:smooth|noperspective)\\s+)?)out(\\s+[A-Za-z_]\\w*\\s+[A-Za-z_][^;{()\\n]*;)");

    public static boolean enabled = Nvidium.config.vr_foveated_shading;
    //Only varyings with these names get centroid interpolation, all of them when empty
    public static java.util.Set<String> names = java.util.Set.of();

    private CentroidVaryings() {}

    public static String inputs(String source) {
        return patch(IN, source, "in");
    }

    public static String outputs(String source) {
        return patch(OUT, source, "out");
    }

    private static String patch(Pattern pattern, String source, String storage) {
        return pattern.matcher(source).replaceAll(match -> {
            if (!names.isEmpty() && !declaresAny(match.group(2))) {
                return java.util.regex.Matcher.quoteReplacement(match.group());
            }
            return java.util.regex.Matcher.quoteReplacement(match.group(1) + "centroid " + storage + match.group(2));
        });
    }

    //Whether a declaration ("vec2 a, b[2];") declares one of the selected names
    private static boolean declaresAny(String declaration) {
        String list = declaration.strip();
        list = list.substring(list.indexOf(' ') + 1, list.length() - 1);
        for (String name : list.split(",")) {
            int bracket = name.indexOf('[');
            if (names.contains((bracket >= 0 ? name.substring(0, bracket) : name).strip())) {
                return true;
            }
        }
        return false;
    }
}
