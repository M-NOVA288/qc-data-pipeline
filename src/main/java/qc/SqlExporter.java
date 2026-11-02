package qc;

import java.util.LinkedHashMap;
import java.util.Map;

import qc.Models.Anomaly;
import qc.Models.Measurement;
import qc.Models.PipelineResult;
import qc.Models.RejectedRow;

/**
 * Turns a pipeline result into a single transactional SQL load script for
 * schema/qc_schema.sql. All values are escaped; nothing from the log is
 * concatenated into SQL unquoted.
 */
public final class SqlExporter {
    private SqlExporter() {}

    public static String export(PipelineResult result, String sourceFile) {
        StringBuilder sql = new StringBuilder("BEGIN;\n\n");

        Map<String, Measurement> firstPerBatch = new LinkedHashMap<>();
        for (Measurement m : result.accepted()) firstPerBatch.putIfAbsent(m.batchId(), m);
        for (Measurement m : firstPerBatch.values()) {
            sql.append(String.format(
                    "INSERT INTO batches (batch_id, product_code, started_at) VALUES (%s, 'UNKNOWN', %s) "
                            + "ON CONFLICT (batch_id) DO NOTHING;%n",
                    q(m.batchId()), q(m.measuredAt().toString().replace('T', ' '))));
        }
        sql.append('\n');

        for (Measurement m : result.accepted()) {
            sql.append(String.format(
                    "INSERT INTO measurements (batch_id, stage_code, metric, value, unit, technician, measured_at) "
                            + "VALUES (%s, %s, %s, %s, %s, %s, %s);%n",
                    q(m.batchId()), q(m.stage()), q(m.metric()), m.value(), q(m.unit()), q(m.technician()),
                    q(m.measuredAt().toString().replace('T', ' '))));
        }
        sql.append('\n');

        for (Anomaly a : result.anomalies()) {
            Measurement m = a.measurement();
            sql.append(String.format(
                    "INSERT INTO anomalies (measurement_id, direction, deviation) SELECT measurement_id, %s, %s "
                            + "FROM measurements WHERE batch_id = %s AND stage_code = %s AND metric = %s "
                            + "AND measured_at = %s;%n",
                    q(a.direction()), a.deviation(), q(m.batchId()), q(m.stage()), q(m.metric()),
                    q(m.measuredAt().toString().replace('T', ' '))));
        }
        sql.append('\n');

        for (RejectedRow r : result.formatRejects()) appendReject(sql, r, sourceFile);
        for (RejectedRow r : result.outliers()) appendReject(sql, r, sourceFile);

        return sql.append("\nCOMMIT;\n").toString();
    }

    private static void appendReject(StringBuilder sql, RejectedRow r, String sourceFile) {
        sql.append(String.format(
                "INSERT INTO rejected_rows (source_file, line_number, raw_line, reason) VALUES (%s, %d, %s, %s);%n",
                q(sourceFile), r.lineNumber(), q(r.rawLine()), q(r.reason())));
    }

    /** SQL string literal with single quotes doubled. */
    static String q(String s) {
        return "'" + s.replace("'", "''") + "'";
    }
}
