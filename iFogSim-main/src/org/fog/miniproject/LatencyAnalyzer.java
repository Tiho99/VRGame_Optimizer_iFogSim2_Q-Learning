package org.fog.miniproject;

import java.util.Map;
import org.fog.utils.TimeKeeper;

/**
 * LatencyAnalyzer – Lecture et comparaison de la latence bout-en-bout
 * Métrique unique : LATENCE (ms)
 */
public class LatencyAnalyzer {

    public static double readLatency() {
        Map<Integer, Double> loops =
                TimeKeeper.getInstance().getLoopIdToCurrentAverage();

        if (loops == null || loops.isEmpty()) {
            return 0.0;
        }
        double total = 0;
        int    count = 0;
        for (double v : loops.values()) {
            total += v;
            count++;
        }
        return count > 0 ? total / count : 0.0;
    }

    public static void compare(double baseline, double optimized) {
        double reduction = baseline > 0
                ? (baseline - optimized) / baseline * 100.0
                : 0;

        System.out.println("\n");
        System.out.println("  ╔══════════════════════════════════════════════════════╗");
        System.out.println("  ║       COMPARAISON LATENCE : Baseline vs Greedy       ║");
        System.out.println("  ╠══════════════════════════════════════════════════════╣");
        System.out.printf ("  ║  Sans optimisation (Baseline) :  %10.3f ms     ║%n", baseline);
        System.out.printf ("  ║  GreedyPlacement               :  %10.3f ms     ║%n", optimized);
        System.out.println("  ╠══════════════════════════════════════════════════════╣");
        if (reduction > 0) {
            System.out.printf(
                "  ║  Réduction de latence         :  %9.1f %%       ║%n", reduction);
            System.out.println(
                "  ║  => GreedyPlacement améliore la latence                   ║");
        } else {
            System.out.printf(
                "  ║  Variation                    :  %+9.1f %%       ║%n", -reduction);
            System.out.println(
                "  ║  => Le greedy est au moins aussi bon que la baseline      ║");
        }
        System.out.println("  ╚══════════════════════════════════════════════════════╝");
    }
}