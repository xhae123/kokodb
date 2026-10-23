rootProject.name = "kokodb"
include("processor", "compiler-plugin", "sample", "benchmarks", "comparison")
include("catalog-example")
project(":catalog-example").projectDir = file("examples/catalog")
include("quickstart")
project(":quickstart").projectDir = file("examples/quickstart")
