# Quality Control Data Pipeline

Validates incoming technician lab logs before they reach the QC database. It separates **data errors**, which get quarantined, from **real product problems**, which get loaded and flagged for review.

**Stack:** Java 21 (no external dependencies), relational schema (PostgreSQL), unit tests.

## Why the distinction matters

A naive filter treats every out-of-range number the same. But a viscosity of `4580` when every other batch reads about `450` is almost certainly a typo for `458`. Loading it corrupts trend charts. A cure temperature of `163.5` against a spec ceiling of `160` is believable, and that's a real process deviation. Filtering it out would **hide a quality problem**. This pipeline treats them differently:

| Category | Example | What happens |
|---|---|---|
| Format error | blank value, `66,9`, `°F` where spec says `°C`, bad timestamp, unknown stage | Rejected with a plain-English reason |
| Outlier (implausible) | viscosity `4580` vs. median `452` | Quarantined, never loaded |
| Anomaly (out of spec) | cure temp `163.5`, spec max `160` | **Loaded** and flagged `HIGH by 3.5` |

### Outlier rule

A reading is quarantined only when both conditions hold:

1. **Statistically extreme:** its modified z-score is above 3.5 within its stage/metric group. The score uses the median and MAD (median absolute deviation) instead of mean and standard deviation, so one wild value can't inflate the spread and hide itself.
2. **Physically implausible:** it sits more than one full spec-width outside the limits.

Rule 2 prevents a tight, well-controlled process from turning every genuine deviation into an "outlier." Groups with fewer than 4 readings rely on rule 2 alone. Both cases have tests.

## Run it

```bash
./run.sh          # requires JDK 21+
```

```
Processed 32 readings from technician_log.csv
  accepted         25  (2 flagged out of spec)
  format rejects    6
  outliers          1

  line 19  FORMAT   missing value in column 4
  line 21  FORMAT   value '66,9' is not numeric (comma used as decimal separator?)
  line 25  FORMAT   timestamp '21/10/2026 14:21' is not yyyy-MM-dd HH:mm
  line 27  FORMAT   unit 'F' does not match spec unit 'C'
  line 30  FORMAT   unknown stage/metric COAT/thickness
  line 33  FORMAT   expected 7 columns, found 4
  line 14  OUTLIER  statistical outlier: viscosity=4580 vs group median 452 (spec 400-520)
  line 17  ANOMALY  B-24103 CURE/hardness=52.0 is LOW by 3.0
  line 28  ANOMALY  B-24105 CURE/temperature=163.5 is HIGH by 3.5
```

The pipeline writes four files to `output/`:

- `clean.csv`: accepted readings
- `rejected.csv`: format errors and outliers, with reasons and the raw line, for audit
- `anomalies.csv`: out-of-spec readings for QC review
- `load.sql`: one transactional load script for the schema, with all values escaped

## Schema

`schema/qc_schema.sql` models stages, spec limits per stage/metric, batches, measurements, anomalies, and an audit table of rejected rows. Foreign keys make it impossible to store a reading for a stage/metric without a spec. Indexes cover batch lookups, metric trends over time, and a partial index for open anomalies.

## Tests

```bash
./test.sh
```

The 13 tests cover CSV quoting, all eight format-rejection reasons, outlier vs. anomaly separation (including the tight-process edge case), the small-group fallback, zero-MAD handling, invalid specs, SQL escaping, and the full sample dataset end to end. The tests use a tiny built-in runner so the project needs nothing but a JDK. The test methods map one-to-one onto JUnit if you move this into a Maven or Gradle build.

## Layout

```
src/main/java/qc/
  LogValidator.java   format rules
  OutlierFilter.java  outlier quarantine and spec anomalies
  QcPipeline.java     orchestration and CLI
  SqlExporter.java    escaped transactional load script
  CsvLine.java        quoted-field CSV splitting
  Models.java         records
src/test/java/qc/     unit tests
data/                 sample technician log and spec limits
schema/               PostgreSQL DDL
```
