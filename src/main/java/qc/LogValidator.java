package qc;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import qc.Models.Measurement;
import qc.Models.RejectedRow;
import qc.Models.SpecLimit;

/**
 * Format validation for raw technician log lines.
 *
 * Checks structure, required values, numeric parsing, timestamp format, known
 * stage/metric, and that the unit matches the spec. It does not judge whether
 * a value is good or bad; that is the job of the outlier filter and spec check.
 */
public final class LogValidator {
    public static final int EXPECTED_COLUMNS = 7;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern BATCH_ID = Pattern.compile("B-\\d{5}");
    private static final Pattern COMMA_DECIMAL = Pattern.compile("-?\\d+,\\d+");

    private final Map<String, SpecLimit> specs;

    public LogValidator(Map<String, SpecLimit> specs) {
        this.specs = specs;
    }

    /** Either a Measurement or a RejectedRow, never both. */
    public sealed interface Result permits Valid, Invalid {}

    public record Valid(Measurement measurement) implements Result {}

    public record Invalid(RejectedRow row) implements Result {}

    public Result validate(int lineNumber, String raw) {
        List<String> f = CsvLine.split(raw);
        if (f.size() != EXPECTED_COLUMNS) {
            return reject(lineNumber, raw, "expected " + EXPECTED_COLUMNS + " columns, found " + f.size());
        }
        String batch = f.get(0), stage = f.get(1).toUpperCase(), metric = f.get(2).toLowerCase();
        String rawValue = f.get(3), unit = f.get(4), tech = f.get(5), rawTime = f.get(6);

        for (int i = 0; i < f.size(); i++) {
            if (f.get(i).isEmpty()) {
                return reject(lineNumber, raw, "missing value in column " + (i + 1));
            }
        }
        if (!BATCH_ID.matcher(batch).matches()) {
            return reject(lineNumber, raw, "batch id '" + batch + "' does not match B-#####");
        }

        SpecLimit spec = specs.get(stage + "/" + metric);
        if (spec == null) {
            return reject(lineNumber, raw, "unknown stage/metric " + stage + "/" + metric);
        }
        if (!spec.unit().equals(unit)) {
            return reject(lineNumber, raw, "unit '" + unit + "' does not match spec unit '" + spec.unit() + "'");
        }

        double value;
        try {
            value = Double.parseDouble(rawValue);
        } catch (NumberFormatException e) {
            String hint = COMMA_DECIMAL.matcher(rawValue).matches() ? " (comma used as decimal separator?)" : "";
            return reject(lineNumber, raw, "value '" + rawValue + "' is not numeric" + hint);
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return reject(lineNumber, raw, "value is not a finite number");
        }

        LocalDateTime when;
        try {
            when = LocalDateTime.parse(rawTime, TIMESTAMP);
        } catch (DateTimeParseException e) {
            return reject(lineNumber, raw, "timestamp '" + rawTime + "' is not yyyy-MM-dd HH:mm");
        }

        return new Valid(new Measurement(lineNumber, batch, stage, metric, value, unit, tech, when));
    }

    private static Invalid reject(int lineNumber, String raw, String reason) {
        return new Invalid(new RejectedRow(lineNumber, raw, reason));
    }
}
