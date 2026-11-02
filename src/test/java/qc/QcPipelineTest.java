package qc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import qc.Models.Anomaly;
import qc.Models.PipelineResult;
import qc.Models.RejectedRow;
import qc.Models.SpecLimit;

/**
 * Unit tests with a tiny built-in runner, so the project needs nothing but a JDK.
 * Each public static void test* method is one test. Run with ./test.sh
 */
public final class QcPipelineTest {

    private static final Map<String, SpecLimit> SPECS = QcPipeline.loadSpecs(List.of(
            "stage,metric,unit,min,max",
            "MIX,viscosity,cP,400,520",
            "MIX,temperature,C,60,75",
            "CURE,temperature,C,140,160",
            "CURE,hardness,ShoreD,55,70"));

    private static final String HEADER = "batch_id,stage,metric,value,unit,technician,timestamp";

    private static PipelineResult run(String... rows) {
        List<String> lines = new ArrayList<>(List.of(HEADER));
        lines.addAll(List.of(rows));
        return new QcPipeline(SPECS).run(lines);
    }

    private static String reasonFor(PipelineResult r, int line) {
        return r.formatRejects().stream().filter(x -> x.lineNumber() == line)
                .map(RejectedRow::reason).findFirst().orElse("<not rejected>");
    }

    // ---------------------------------------------------------------- CSV --
    public static void testCsvHandlesQuotedCommas() {
        assertEquals(List.of("a", "66,9", "c"), CsvLine.split("a,\"66,9\",c"));
        assertEquals(List.of("say \"hi\"", ""), CsvLine.split("\"say \"\"hi\"\"\","));
    }

    // --------------------------------------------------------- validation --
    public static void testCleanRowIsAccepted() {
        PipelineResult r = run("B-10001,MIX,viscosity,450,cP,jreyes,2026-10-20 07:10");
        assertEquals(1, r.accepted().size());
        assertEquals(0, r.formatRejects().size());
    }

    public static void testStageAndMetricAreCaseInsensitive() {
        PipelineResult r = run("B-10001,mix,Viscosity,450,cP,jreyes,2026-10-20 07:10");
        assertEquals("MIX", r.accepted().get(0).stage());
    }

    public static void testFormatProblemsAreRejectedWithReasons() {
        PipelineResult r = run(
                "B-10001,MIX,viscosity,,cP,jreyes,2026-10-20 07:10",          // line 2
                "B-10001,MIX,temperature,\"66,9\",C,jreyes,2026-10-20 07:11",  // line 3
                "B-10001,MIX,temperature,66.9,F,jreyes,2026-10-20 07:12",     // line 4
                "B-10001,COAT,thickness,1.2,mm,jreyes,2026-10-20 07:13",      // line 5
                "B-10001,MIX,viscosity,450,cP,jreyes,20/10/2026 07:14",       // line 6
                "B-10001,MIX,viscosity,450",                                   // line 7
                "X-1,MIX,viscosity,450,cP,jreyes,2026-10-20 07:15",           // line 8
                "B-10001,MIX,viscosity,NaN,cP,jreyes,2026-10-20 07:16");      // line 9
        assertEquals(8, r.formatRejects().size());
        assertContains(reasonFor(r, 2), "missing value in column 4");
        assertContains(reasonFor(r, 3), "comma used as decimal separator");
        assertContains(reasonFor(r, 4), "does not match spec unit 'C'");
        assertContains(reasonFor(r, 5), "unknown stage/metric COAT/thickness");
        assertContains(reasonFor(r, 6), "not yyyy-MM-dd HH:mm");
        assertContains(reasonFor(r, 7), "expected 7 columns, found 4");
        assertContains(reasonFor(r, 8), "does not match B-#####");
        assertContains(reasonFor(r, 9), "not a finite number");
    }

    // ----------------------------------------------- outliers vs anomalies --
    public static void testDecimalShiftIsQuarantinedAsOutlier() {
        PipelineResult r = run(
                "B-10001,MIX,viscosity,452,cP,a,2026-10-20 07:00",
                "B-10002,MIX,viscosity,447,cP,a,2026-10-20 08:00",
                "B-10003,MIX,viscosity,4580,cP,a,2026-10-20 09:00",
                "B-10004,MIX,viscosity,459,cP,a,2026-10-20 10:00",
                "B-10005,MIX,viscosity,449,cP,a,2026-10-20 11:00");
        assertEquals(1, r.outliers().size());
        assertEquals(4, r.outliers().get(0).lineNumber());
        assertEquals(4, r.accepted().size());
        assertEquals(0, r.anomalies().size());
    }

    public static void testBelievableOutOfSpecIsAnomalyNotOutlier() {
        // 163.5 is statistically extreme in this tight group, but only 3.5 over a 20-wide spec:
        // a real process problem, so it must be loaded and flagged, not hidden.
        PipelineResult r = run(
                "B-10001,CURE,temperature,151.0,C,a,2026-10-20 07:00",
                "B-10002,CURE,temperature,149.8,C,a,2026-10-20 08:00",
                "B-10003,CURE,temperature,152.3,C,a,2026-10-20 09:00",
                "B-10004,CURE,temperature,150.4,C,a,2026-10-20 10:00",
                "B-10005,CURE,temperature,163.5,C,a,2026-10-20 11:00");
        assertEquals(0, r.outliers().size());
        assertEquals(1, r.anomalies().size());
        Anomaly a = r.anomalies().get(0);
        assertEquals("HIGH", a.direction());
        assertEquals(3.5, a.deviation());
    }

    public static void testLowAnomaly() {
        PipelineResult r = run("B-10001,CURE,hardness,52,ShoreD,a,2026-10-20 07:00");
        assertEquals("LOW", r.anomalies().get(0).direction());
        assertEquals(3.0, r.anomalies().get(0).deviation());
    }

    public static void testSmallGroupFallsBackToPlausibilityOnly() {
        PipelineResult r = run(
                "B-10001,MIX,temperature,68,C,a,2026-10-20 07:00",
                "B-10002,MIX,temperature,680,C,a,2026-10-20 08:00");
        assertEquals(1, r.outliers().size());
    }

    public static void testModifiedZHandlesZeroMad() {
        assertEquals(0.0, OutlierFilter.modifiedZ(5, 5, 0));
        assertTrue(Double.isInfinite(OutlierFilter.modifiedZ(6, 5, 0)), "infinite z when MAD is 0");
    }

    public static void testMedian() {
        assertEquals(3.0, OutlierFilter.median(new double[] {5, 1, 3}));
        assertEquals(2.5, OutlierFilter.median(new double[] {4, 1, 2, 3}));
    }

    // --------------------------------------------------------------- specs --
    public static void testInvalidSpecRejected() {
        assertThrows(() -> QcPipeline.loadSpecs(List.of("h", "MIX,viscosity,cP,500,400")));
        assertThrows(() -> QcPipeline.loadSpecs(List.of("h", "MIX,viscosity,cP")));
    }

    // ----------------------------------------------------------------- SQL --
    public static void testSqlEscapesQuotes() {
        PipelineResult r = run("B-10001,MIX,viscosity,450,cP,o'brien,2026-10-20 07:10");
        String sql = SqlExporter.export(r, "log.csv");
        assertContains(sql, "'o''brien'");
        assertTrue(sql.startsWith("BEGIN;") && sql.trim().endsWith("COMMIT;"), "wrapped in a transaction");
    }

    // ------------------------------------------------------ sample dataset --
    public static void testSampleDatasetEndToEnd() throws IOException {
        Map<String, SpecLimit> specs = QcPipeline.loadSpecs(Files.readAllLines(Path.of("data/spec_limits.csv")));
        PipelineResult r = new QcPipeline(specs).run(Files.readAllLines(Path.of("data/technician_log.csv")));
        assertEquals(32, r.totalRows());
        assertEquals(6, r.formatRejects().size());
        assertEquals(1, r.outliers().size());
        assertEquals(25, r.accepted().size());
        assertEquals(2, r.anomalies().size());

        Path out = Files.createTempDirectory("qc-test");
        QcPipeline.writeOutputs(r, "technician_log.csv", out);
        assertEquals(26, Files.readAllLines(out.resolve("clean.csv")).size()); // header + 25
        assertEquals(8, Files.readAllLines(out.resolve("rejected.csv")).size()); // header + 7
        assertEquals(3, Files.readAllLines(out.resolve("anomalies.csv")).size()); // header + 2
    }

    // ============================================================ runner ===
    public static void main(String[] args) throws Exception {
        int passed = 0, failed = 0;
        for (var method : QcPipelineTest.class.getDeclaredMethods()) {
            if (!method.getName().startsWith("test")) continue;
            try {
                method.invoke(null);
                passed++;
                System.out.println("  PASS  " + method.getName());
            } catch (java.lang.reflect.InvocationTargetException e) {
                failed++;
                System.out.println("  FAIL  " + method.getName() + ": " + e.getCause());
            }
        }
        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
    }

    private static void assertTrue(boolean condition, String what) {
        if (!condition) throw new AssertionError("expected true: " + what);
    }

    private static void assertContains(String haystack, String needle) {
        if (!haystack.contains(needle)) throw new AssertionError("<" + haystack + "> does not contain <" + needle + ">");
    }

    private static void assertThrows(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError("expected an exception");
    }
}
