package qc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import qc.Models.Anomaly;
import qc.Models.Measurement;
import qc.Models.PipelineResult;
import qc.Models.RejectedRow;
import qc.Models.SpecLimit;

/**
 * Runs technician logs through: format validation -> outlier quarantine -> spec check.
 *
 * Usage: java -cp out qc.QcPipeline data/technician_log.csv data/spec_limits.csv output/
 */
public final class QcPipeline {
    private final Map<String, SpecLimit> specs;

    public QcPipeline(Map<String, SpecLimit> specs) {
        this.specs = specs;
    }

    public static Map<String, SpecLimit> loadSpecs(List<String> lines) {
        Map<String, SpecLimit> specs = new LinkedHashMap<>();
        for (int i = 1; i < lines.size(); i++) { // skip header
            String line = lines.get(i);
            if (line.isBlank()) continue;
            List<String> f = CsvLine.split(line);
            if (f.size() != 5) {
                throw new IllegalArgumentException("spec line " + (i + 1) + " needs 5 columns: " + line);
            }
            SpecLimit spec = new SpecLimit(f.get(0).toUpperCase(), f.get(1).toLowerCase(), f.get(2),
                    Double.parseDouble(f.get(3)), Double.parseDouble(f.get(4)));
            specs.put(spec.key(), spec);
        }
        return specs;
    }

    /** Process log lines (including header). Line numbers are 1-based, as in a text editor. */
    public PipelineResult run(List<String> logLines) {
        LogValidator validator = new LogValidator(specs);
        List<Measurement> valid = new ArrayList<>();
        List<RejectedRow> formatRejects = new ArrayList<>();
        Map<Integer, String> raw = new HashMap<>();

        for (int i = 1; i < logLines.size(); i++) {
            String line = logLines.get(i);
            if (line.isBlank()) continue;
            int lineNumber = i + 1;
            raw.put(lineNumber, line);
            switch (validator.validate(lineNumber, line)) {
                case LogValidator.Valid v -> valid.add(v.measurement());
                case LogValidator.Invalid inv -> formatRejects.add(inv.row());
            }
        }

        OutlierFilter.Split split = new OutlierFilter(specs).apply(valid, raw);
        return new PipelineResult(split.kept(), split.anomalies(), formatRejects, split.outliers());
    }

    public static void writeOutputs(PipelineResult result, String sourceFile, Path outDir) throws IOException {
        Files.createDirectories(outDir);

        List<String> clean = new ArrayList<>(List.of("line,batch_id,stage,metric,value,unit,technician,measured_at"));
        for (Measurement m : result.accepted()) {
            clean.add(String.join(",", String.valueOf(m.lineNumber()), m.batchId(), m.stage(), m.metric(),
                    String.valueOf(m.value()), m.unit(), m.technician(), m.measuredAt().toString()));
        }
        Files.write(outDir.resolve("clean.csv"), clean, StandardCharsets.UTF_8);

        List<String> rejected = new ArrayList<>(List.of("line,category,reason,raw_line"));
        for (RejectedRow r : result.formatRejects()) rejected.add(rejectLine(r, "FORMAT"));
        for (RejectedRow r : result.outliers()) rejected.add(rejectLine(r, "OUTLIER"));
        Files.write(outDir.resolve("rejected.csv"), rejected, StandardCharsets.UTF_8);

        List<String> anomalies = new ArrayList<>(List.of("line,batch_id,stage,metric,value,direction,deviation"));
        for (Anomaly a : result.anomalies()) {
            Measurement m = a.measurement();
            anomalies.add(String.join(",", String.valueOf(m.lineNumber()), m.batchId(), m.stage(), m.metric(),
                    String.valueOf(m.value()), a.direction(), String.valueOf(a.deviation())));
        }
        Files.write(outDir.resolve("anomalies.csv"), anomalies, StandardCharsets.UTF_8);

        Files.writeString(outDir.resolve("load.sql"), SqlExporter.export(result, sourceFile), StandardCharsets.UTF_8);
    }

    private static String rejectLine(RejectedRow r, String category) {
        return r.lineNumber() + "," + category + "," + quote(r.reason()) + "," + quote(r.rawLine());
    }

    private static String quote(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.err.println("usage: QcPipeline <technician_log.csv> <spec_limits.csv> <output_dir>");
            System.exit(2);
        }
        Path log = Path.of(args[0]);
        QcPipeline pipeline = new QcPipeline(loadSpecs(Files.readAllLines(Path.of(args[1]))));
        PipelineResult result = pipeline.run(Files.readAllLines(log));
        writeOutputs(result, log.getFileName().toString(), Path.of(args[2]));

        System.out.printf("Processed %d readings from %s%n", result.totalRows(), log.getFileName());
        System.out.printf("  accepted        %3d  (%d flagged out of spec)%n", result.accepted().size(), result.anomalies().size());
        System.out.printf("  format rejects  %3d%n", result.formatRejects().size());
        System.out.printf("  outliers        %3d%n%n", result.outliers().size());
        for (RejectedRow r : result.formatRejects()) System.out.printf("  line %2d  FORMAT   %s%n", r.lineNumber(), r.reason());
        for (RejectedRow r : result.outliers()) System.out.printf("  line %2d  OUTLIER  %s%n", r.lineNumber(), r.reason());
        for (Anomaly a : result.anomalies()) {
            Measurement m = a.measurement();
            System.out.printf("  line %2d  ANOMALY  %s %s/%s=%s is %s by %s%n", m.lineNumber(), m.batchId(),
                    m.stage(), m.metric(), m.value(), a.direction(), a.deviation());
        }
        System.out.println("\nOutputs written to " + args[2]);
    }
}
