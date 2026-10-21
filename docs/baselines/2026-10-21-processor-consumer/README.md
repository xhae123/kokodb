# Incremental Multi-Module Model Qualification

A clean library build cannot establish whether a consuming application retains stale generated adapters after model edits. This fixture builds two independent model modules and one application against the actual runtime and processor JARs, then changes the sources without cleaning between edits.

The current processor passes all 15 states without an implementation change. The improvement is a reproducible consumer regression gate, run on both Linux and macOS CI.

## What is checked

- Exact generated adapter classes and service-provider entries in each model JAR, including absence after deletion.
- Combined discovery from both modules and actual typed `KoKoDB<Model>` query results.
- Unchanged KSP/compilation tasks remain up to date.
- Changes to field names, types and constructor order; model/table/file rename; deletion of one or all models.
- Removal of all annotations while an ordinary Kotlin source remains, removal of all sources, direct removal of the final annotated source, and restoration after each path.
- Duplicate tables within one module fail compilation. Duplicates across modules fail shared database startup at runtime, and subsequent implicit calls stay blocked.
- Removing the erroneous declaration restores the complete model set. A final clean rebuild satisfies the same exact provider-set and query-result assertions as the final incremental build.

The existing isolating adapter outputs depend on their model source. The aggregating service resource depends on all processed model sources. These dependency categories follow [KSP's incremental processing contract](https://kotlinlang.org/docs/ksp-incremental.html). The fixture verifies observable consumer behavior rather than treating those declarations alone as evidence of correctness.

## Reproduction and evidence

```sh
./gradlew :jar :processor:jar
python3 processor/check_gradle.py --output /path/to/new-output-directory
```

The optional output directory must not already exist. It retains every build's output, input artifact hashes and the completed state list. An intentional failed compilation is a passing case only when the expected duplicate-table diagnostic appears. The runtime duplicate case requires its specific error and a blocked shared facade.

`consumer-output.txt`, `results.json`, `artifacts.json` and `environment.json` preserve the local result and identity. Detailed logs and the earlier 13-state exploratory run are retained under `.local/improvements/2026-10-21-processor-consumer/`. The final two cases were added to distinguish direct last-source deletion from deleting an ordinary source after annotation removal.

## Qualification boundary

This result qualifies Kotlin 2.3.20, KSP 2.3.10, Gradle 9.2.1 and JDK 17 with ordinary JVM modules. It does not qualify Android, other toolchain versions, shaded service-resource merging, arbitrary third-party processors, cross-version model/schema migration or byte-identical clean/incremental artifacts. SQL compiler-plugin behavior remains covered by the separate `compiler-plugin/check_gradle.py` consumer gate. No performance claim uses build elapsed time.
