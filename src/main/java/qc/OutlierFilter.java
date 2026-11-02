package qc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import qc.Models.Anomaly;
import qc.Models.Measurement;
import qc.Models.RejectedRow;
import qc.Models.SpecLimit;

/**
 * Separates two very different kinds of "bad" readings.
 *
 *   Outlier   : physically implausible, almost certainly a data-entry or
 *               instrument error (e.g. 4580 cP typed instead of 458). These
 *               are quarantined and never loaded.
 *   Anomaly   : a believable reading that is out of spec. That's a real
 *               product problem, so it IS loaded and flagged for review.
 *
 * A reading is an outlier only when both are true:
 *   1. It is statistically extreme within its stage/metric group
 *      (modified z-score above 3.5, using median and MAD so one bad value
 *      can't hide itself by inflating the standard deviation), and
 *   2. It sits more than one full spec-width outside the spec limits.
 * Rule 2 stops a tight, well-controlled process from turning every genuine
 * out-of-spec reading into an "outlier". Groups with fewer than 4 readings
 * rely on rule 2 alone.
 */
public final class OutlierFilter {
    public static final double Z_THRESHOLD = 3.5;
    public static final int MIN_GROUP_SIZE = 4;

    private final Map<String, SpecLimit> specs;

    public OutlierFilter(Map<String, SpecLimit> specs) {
        this.specs = specs;
    }

    public record Split(List<Measurement> kept, List<RejectedRow> outliers, List<Anomaly> anomalies) {}

    public Split apply(List<Measurement> rows, Map<Integer, String> rawLines) {
        Map<String, List<Measurement>> groups = new LinkedHashMap<>();
        for (Measurement m : rows) {
            groups.computeIfAbsent(m.groupKey(), k -> new ArrayList<>()).add(m);
        }

        List<Measurement> kept = new ArrayList<>();
        List<RejectedRow> outliers = new ArrayList<>();
        for (List<Measurement> group : groups.values()) {
            double[] values = group.stream().mapToDouble(Measurement::value).toArray();
            double median = median(values);
            double mad = median(Arrays.stream(values).map(v -> Math.abs(v - median)).toArray());
            for (Measurement m : group) {
                SpecLimit spec = specs.get(m.groupKey());
                boolean implausible = distanceOutsideSpec(m.value(), spec) > spec.width();
                boolean extreme = group.size() < MIN_GROUP_SIZE || modifiedZ(m.value(), median, mad) > Z_THRESHOLD;
                if (implausible && extreme) {
                    outliers.add(new RejectedRow(m.lineNumber(), rawLines.getOrDefault(m.lineNumber(), ""),
                            String.format("statistical outlier: %s=%s vs group median %s (spec %s-%s)",
                                    m.metric(), fmt(m.value()), fmt(median), fmt(spec.min()), fmt(spec.max()))));
                } else {
                    kept.add(m);
                }
            }
        }
        kept.sort((a, b) -> Integer.compare(a.lineNumber(), b.lineNumber()));
        outliers.sort((a, b) -> Integer.compare(a.lineNumber(), b.lineNumber()));

        List<Anomaly> anomalies = new ArrayList<>();
        for (Measurement m : kept) {
            SpecLimit spec = specs.get(m.groupKey());
            if (m.value() < spec.min()) {
                anomalies.add(new Anomaly(m, "LOW", round(spec.min() - m.value())));
            } else if (m.value() > spec.max()) {
                anomalies.add(new Anomaly(m, "HIGH", round(m.value() - spec.max())));
            }
        }
        return new Split(kept, outliers, anomalies);
    }

    static double modifiedZ(double value, double median, double mad) {
        if (mad == 0) {
            return value == median ? 0 : Double.POSITIVE_INFINITY;
        }
        return 0.6745 * Math.abs(value - median) / mad;
    }

    static double distanceOutsideSpec(double value, SpecLimit spec) {
        if (value < spec.min()) return spec.min() - value;
        if (value > spec.max()) return value - spec.max();
        return 0;
    }

    static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(round(v));
    }
}
