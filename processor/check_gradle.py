#!/usr/bin/env python3
"""Qualify packaged model discovery through incremental multi-module Gradle edits."""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile


SERVICE = "META-INF/services/kokodb.mapping.ModelProvider"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="Retain each build log and artifact hashes")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    artifacts = {
        "runtime": root / "build/libs/kokodb-0.1.0-SNAPSHOT.jar",
        "processor": root / "processor/build/libs/processor-0.1.0-SNAPSHOT.jar",
    }
    for artifact in artifacts.values():
        if not artifact.is_file():
            raise SystemExit("Build the runtime and processor JARs before running this check")
    if args.output:
        args.output.mkdir(parents=True, exist_ok=False)
        (args.output / "artifacts.json").write_text(json.dumps({
            name: {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
            for name, path in artifacts.items()
        }, indent=2) + "\n")

    with tempfile.TemporaryDirectory(prefix="kokodb-model-gradle-") as temporary:
        fixture = Path(temporary)

        def write(relative, contents):
            path = fixture / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(contents)

        write("settings.gradle.kts", 'rootProject.name = "model-consumer"\ninclude("models", "events", "app")\n')
        write("gradle.properties", "ksp.incremental=true\nksp.incremental.log=true\n")
        write("build.gradle.kts", '''plugins {
    kotlin("jvm") version "2.3.20" apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
}
subprojects { repositories { mavenCentral() } }
''')
        for module in ("models", "events", "app"):
            plugins = 'kotlin("jvm"); ' + ('application' if module == "app" else 'id("com.google.devtools.ksp")')
            dependencies = f'implementation(files(uri("{artifacts["runtime"].as_uri()}")))\n'
            if module == "app":
                dependencies += 'implementation(project(":models"))\nimplementation(project(":events"))\n'
            else:
                dependencies += f'ksp(files(uri("{artifacts["processor"].as_uri()}")))\n'
            write(f"{module}/build.gradle.kts", f'''plugins {{ {plugins} }}
kotlin {{ jvmToolchain(17) }}
dependencies {{ {dependencies} }}
''' + ('application { mainClass.set("consumer.MainKt") }\n' if module == "app" else ""))

        models = {}

        def model(module, name, table, fields, values):
            models[name] = (module, table, fields, values)
            write(f"{module}/src/main/kotlin/consumer/{name}.kt", f'''package consumer
import kokodb.*
@DbTable("{table}")
data class {name}({fields})
''')

        def remove(name):
            module = models.pop(name)[0]
            (fixture / f"{module}/src/main/kotlin/consumer/{name}.kt").unlink()

        def application(duplicate=None):
            expected = ", ".join(f'"consumer.{name}_KokoAdapter"' for name in sorted(models))
            checks = [f'''check(java.util.ServiceLoader.load(kokodb.mapping.ModelProvider::class.java)
        .map {{ it.javaClass.name }}.toSet() == setOf({expected}))''']
            if duplicate:
                checks += [f'''val failure = runCatching {{ KoKoDB.openInMemory() }}.exceptionOrNull()
    check(failure is DatabaseException && failure.message == "Duplicate generated table '{duplicate}'")
    check(runCatching {{ KoKoDB.query("SELECT * FROM {duplicate}") }}.isFailure)''']
            else:
                checks += ["KoKoDB.openInMemory()"]
                for name, (_, table, _, values) in models.items():
                    checks += [f'''KoKoDB("INSERT INTO {table} VALUES ({values[0]})")
    check(KoKoDB<{name}>("SELECT * FROM {table}") == listOf({name}({values[1]})))''']
                checks += ["KoKoDB.close()"]
            write("app/src/main/kotlin/consumer/Main.kt", "package consumer\nimport kokodb.*\nfun main() {\n    " +
                  "\n    ".join(checks) + '\n    println("MODEL_CONSUMER_OK")\n}\n')

        sequence = []

        def build(label, *, error=None, duplicate=None, clean=False, unchanged=False):
            application(duplicate)
            command = [str(root / "gradlew"), "-p", str(fixture), "--console=plain"]
            command += (["clean"] if clean else []) + [":app:run", ":models:jar", ":events:jar"]
            result = subprocess.run(command, capture_output=True, text=True)
            output = result.stdout + result.stderr
            if args.output:
                (args.output / f"{len(sequence) + 1:02d}-{label}.log").write_text(output)
            if error:
                if result.returncode == 0 or error not in output:
                    raise AssertionError(f"{label}: expected {error!r}:\n{output}")
                print(f"PASS {label}: expected compilation diagnostic", flush=True)
            else:
                if result.returncode != 0 or "MODEL_CONSUMER_OK" not in output:
                    raise AssertionError(f"{label}: consumer failed:\n{output}")
                for module in ("models", "events"):
                    expected = {f"consumer.{name}_KokoAdapter" for name, data in models.items() if data[0] == module}
                    with zipfile.ZipFile(fixture / f"{module}/build/libs/{module}.jar") as jar:
                        providers = set(jar.read(SERVICE).decode().split()) if SERVICE in jar.namelist() else set()
                        adapters = {path[:-6].replace("/", ".") for path in jar.namelist() if path.endswith("_KokoAdapter.class")}
                        if providers != expected or adapters != expected:
                            raise AssertionError(f"{label}/{module}: stale or missing packaged providers {providers}, classes {adapters}; expected {expected}")
                if unchanged:
                    for task in (":models:kspKotlin", ":events:kspKotlin", ":app:compileKotlin"):
                        if f"{task} UP-TO-DATE" not in output:
                            raise AssertionError(f"{label}: {task} was not up to date:\n{output}")
                print(f"PASS {label}: packaged providers and runtime queries", flush=True)
            sequence.append({"case": label, "expected_compile_error": error,
                             "expected_duplicate_table": duplicate, "clean": clean})

        model("models", "User", "users", "@Id val id: Int, val name: String", ("1, 'Alice'", '1, "Alice"'))
        model("models", "Note", "notes", "@Id val id: Int, val text: String", ("2, 'Note'", '2, "Note"'))
        model("events", "Event", "events", "@Id val id: Int, val text: String", ("3, 'Event'", '3, "Event"'))
        build("initial")
        build("unchanged", unchanged=True)
        model("models", "User", "users", "val age: Int, @Id val id: Int", ("31, 1", "31, 1"))
        build("change-field-type-order")
        remove("User")
        model("models", "Account", "accounts", "val age: Int, @Id val id: Int", ("31, 1", "31, 1"))
        build("rename-model-table-file")
        remove("Note")
        build("remove-one-model")
        model("models", "Duplicate", "accounts", "@Id val id: Int", ("2", "2"))
        build("duplicate-in-module", error="Duplicate @DbTable table name 'accounts'")
        remove("Duplicate")
        build("recover-after-error")
        write("models/src/main/kotlin/consumer/Marker.kt", "package consumer\nclass Marker\n")
        remove("Account")
        build("remove-all-annotations")
        (fixture / "models/src/main/kotlin/consumer/Marker.kt").unlink()
        build("remove-all-sources")
        model("models", "Account", "accounts", "@Id val id: Int, val name: String", ("4, 'Restored'", '4, "Restored"'))
        build("restore-model")
        remove("Account")
        build("remove-last-source-directly")
        model("models", "Account", "accounts", "@Id val id: Int, val name: String", ("4, 'Restored'", '4, "Restored"'))
        build("restore-after-no-sources")
        model("models", "Collision", "events", "@Id val id: Int, val text: String", ("5, 'Other'", '5, "Other"'))
        build("duplicate-across-modules", duplicate="events")
        remove("Collision")
        build("recover-cross-module-error")
        build("clean-equivalent", clean=True)
        if args.output:
            (args.output / "results.json").write_text(json.dumps(sequence, indent=2) + "\n")
        print(f"Qualified {len(sequence)} multi-module consumer states", flush=True)


if __name__ == "__main__":
    main()
