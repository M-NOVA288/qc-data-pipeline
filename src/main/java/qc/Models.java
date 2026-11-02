package qc;

import java.time.LocalDateTime;
import java.util.List;

/** Plain data types shared across the pipeline. */
public final class Models {
    private Models() {}

    /** One validated technician reading. */
    public record Measurement(
            int lineNumber,
            String batchId,
            String stage,
            String metric,
            double value,
            String unit,
            String technician,
            LocalDateTime measuredAt) {

        public String groupKey() {
            return stage + "/" + metric;
        }
    }

    /** Acceptable range for one metric at one stage. */
    public record SpecLimit(String stage, String metric, String unit, double min, double max) {
        public SpecLimit {
            if (min >= max) {
                throw new IllegalArgumentException("spec min must be below max for " + stage + "/" + metric);
            }
        }

        public String key() {
            return stage + "/" + metric;
        }

        public double width() {
            return max - min;
        }
    }

    /** A log line that was kept out of the clean data, with the reason why. */
    public record RejectedRow(int lineNumber, String rawLine, String reason) {}

    /** A valid reading that falls outside spec: a real product issue to investigate. */
    public record Anomaly(Measurement measurement, String direction, double deviation) {}

    /** Everything one pipeline run produces. */
    public record PipelineResult(
            List<Measurement> accepted,
            List<Anomaly> anomalies,
            List<RejectedRow> formatRejects,
            List<RejectedRow> outliers) {

        public int totalRows() {
            return accepted.size() + formatRejects.size() + outliers.size();
        }
    }
}
