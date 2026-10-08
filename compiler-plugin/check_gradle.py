#!/usr/bin/env python3
"""Verify compiler diagnostics and KSP coexistence in an isolated Gradle consumer."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile


def main():
    root = Path(__file__).resolve().parent.parent
    artifacts = {
        ":": root / "build/libs/kokodb-0.1.0-SNAPSHOT.jar",
        ":processor": root / "processor/build/libs/processor-0.1.0-SNAPSHOT.jar",
        ":compiler-plugin": root / "compiler-plugin/build/libs/kokodb-sql-compiler-plugin.jar",
    }
    for artifact in artifacts.values():
        if not artifact.is_file():
            raise SystemExit("Build the runtime, processor, and compiler-plugin JARs before running this check")

    build = (root / "sample/build.gradle.kts").read_text()
    build = build.replace('kotlin("jvm")', 'kotlin("jvm") version "2.3.20"')
    build = build.replace('id("com.google.devtools.ksp")', 'id("com.google.devtools.ksp") version "2.3.10"')
    for project, artifact in artifacts.items():
        build = build.replace(f'project("{project}")', f'files(uri("{artifact.as_uri()}"))')

    with tempfile.TemporaryDirectory(prefix="kokodb-sql-gradle-") as temporary:
        fixture = Path(temporary)
        plugin = fixture / "sql-compiler-plugin.jar"
        shutil.copyfile(artifacts[":compiler-plugin"], plugin)
        build = build.replace(artifacts[":compiler-plugin"].as_uri(), plugin.as_uri())
        (fixture / "settings.gradle.kts").write_text('rootProject.name = "sql-validation-consumer"\n')
        (fixture / "build.gradle.kts").write_text(build)
        source = fixture / "src/main/kotlin/sample/Query.kt"
        source.parent.mkdir(parents=True)
        template = '''package sample
import kokodb.*
@DbTable("users")
data class User(@Id val id: Int, val name: String)
fun users() = KoKoDB<User>("QUERY * FROM users")
'''
        command = [str(root / "gradlew"), "-p", str(fixture), "compileKotlin", "--console=plain"]
        source.write_text(template.replace("QUERY", "SELEC"))
        failure = subprocess.run(command, capture_output=True, text=True)
        output = failure.stdout + failure.stderr
        if failure.returncode == 0 or "invalid kokodb sql:" not in output.lower() or "Query.kt:5:" not in output:
            raise AssertionError("Expected a located SQL compilation error:\n" + output)
        print("Verified Gradle rejects malformed static SQL at Query.kt:5")

        source.write_text(template.replace("QUERY", "SELECT"))
        success = subprocess.run(command, capture_output=True, text=True)
        if success.returncode != 0:
            raise AssertionError("Corrected SQL did not compile:\n" + success.stdout + success.stderr)
        providers = list((fixture / "build").rglob("User_KokoAdapter.kt"))
        if not providers:
            raise AssertionError("The SQL checker must coexist with KSP model generation")
        print("Verified corrected SQL compiles and KSP still generates the model adapter")

        unchanged = subprocess.run(command, capture_output=True, text=True)
        if unchanged.returncode != 0 or ":compileKotlin UP-TO-DATE" not in unchanged.stdout:
            raise AssertionError("Unchanged fixture did not reuse its compilation:\n" + unchanged.stdout + unchanged.stderr)
        with zipfile.ZipFile(plugin, "a") as archive:
            archive.writestr("META-INF/kokodb-input-fixture", "changed compiler input\n")
        changed = subprocess.run(command, capture_output=True, text=True)
        if changed.returncode != 0 or ":compileKotlin UP-TO-DATE" in changed.stdout:
            raise AssertionError("Compiler-plugin change did not invalidate compilation:\n" + changed.stdout + changed.stderr)
        print("Verified compiler-plugin bytes invalidate an otherwise up-to-date compilation")


if __name__ == "__main__":
    main()
