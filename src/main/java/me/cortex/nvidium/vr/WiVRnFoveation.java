package me.cortex.nvidium.vr;

import java.util.ArrayList;
import java.util.List;

/**
 * Port of WiVRn's per-axis foveation layout (server/compositor/foveation.cpp: defoveate, solve_foveation, fill_param_2d and
 * fill_ubo, WiVRn 26.9). WiVRn maps each rendered axis onto the stream axis with tan-shaped spacing that is 1:1 at the center,
 * rounds every streamed pixel to an integer number of rendered pixels and sorts those spans so they only grow outwards.
 */
final class WiVRnFoveation {
    private WiVRnFoveation() {}

    /**
     * @param foveated stream pixels along the axis
     * @param source rendered pixels along the axis
     * @param center foveation center in [-1, 1] (left/top = -1)
     * @return for every rendered pixel, how many rendered pixels its streamed pixel averages
     */
    static int[] ratios(int foveated, int source, double center) {
        int[] result = new int[source];
        if (foveated >= source) {
            java.util.Arrays.fill(result, 1);
            return result;
        }
        double scale = foveated / (double) source;
        double[] ab = solve(scale, center);
        double a = ab[0];
        double b = ab[1];

        //Index n-1 counts the streamed pixels that average n rendered pixels, left and right of the center
        List<Integer> left = new ArrayList<>();
        List<Integer> right = new ArrayList<>();
        int last = 0;
        for (int i = 1; i < foveated; i++) {
            double u = (i * 2.0) / foveated - 1;
            double f = scale / a * Math.tan(a * u + b) + center;
            int n = (int) Math.max(0, Math.min(source, (int) ((f * 0.5 + 0.5) * source + 0.5)));
            int count = Math.max(1, n - last);
            add(u < center ? left : right, count);
            last = n;
        }
        add(right, Math.max(1, source - last));

        int k = Math.max(left.size(), right.size());
        int[] out = new int[2 * k - 1];
        int pos = k - left.size();
        for (int i = left.size() - 1; i >= 0; i--) {
            out[pos++] += left.get(i);
        }
        //The last left entry (1:1) and the first right entry share the center slot
        pos--;
        for (int i = 0; i < right.size(); i++) {
            out[pos + i] += right.get(i);
        }

        int nRatio = k - 1;
        int x = 0;
        for (int i = 0; i < out.length && x < source; i++) {
            int span = Math.abs(nRatio - i) + 1;
            for (int j = 0; j < out[i] && x < source; j++) {
                for (int s = 0; s < span && x < source; s++) {
                    result[x++] = span;
                }
            }
        }
        while (x < source) {
            result[x] = result[x - 1];
            x++;
        }
        return result;
    }

    private static void add(List<Integer> counts, int span) {
        while (counts.size() < span) {
            counts.add(0);
        }
        counts.set(span - 1, counts.get(span - 1) + 1);
    }

    //solve_foveation: a, b such that defoveate maps -1 to -1 and 1 to 1
    private static double[] solve(double lambda, double c) {
        java.util.function.DoubleUnaryOperator eq = a -> Math.atan(a * (1 - c) / lambda) + Math.atan(a * (1 + c) / lambda) - 2 * a;
        double a0 = 0;
        double a1 = 1;
        while (eq.applyAsDouble(a1) > 0) {
            a1 *= 2;
        }
        Double fa0 = null;
        double fa1 = eq.applyAsDouble(a1);
        double a = 0;
        int n = 0;
        while (Math.abs(a1 - a0) > 0.0000001 && n++ < 100) {
            if (fa0 == null) {
                a = 0.5 * (a0 + a1);
                double val = eq.applyAsDouble(a);
                if (val > 0) {
                    a0 = a;
                    fa0 = val;
                } else {
                    a1 = a;
                    fa1 = val;
                }
            } else {
                a = a1 - fa1 * (a1 - a0) / (fa1 - fa0);
                a0 = a1;
                a1 = a;
                fa0 = fa1;
                fa1 = eq.applyAsDouble(a);
            }
        }
        double b = Math.atan(a * (1 - c) / lambda) - a;
        return new double[]{a, b};
    }
}
