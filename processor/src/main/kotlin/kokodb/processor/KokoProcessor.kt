package kokodb.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate
import java.util.Locale

class KokoProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        KokoProcessor(environment.codeGenerator, environment.logger)
}

private class KokoProcessor(private val generator: CodeGenerator, private val logger: KSPLogger) : SymbolProcessor {
    private val generated = linkedMapOf<String, KSFile>()
    private val tableNames = mutableSetOf<String>()
    private val generatedRepositories = mutableSetOf<String>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val deferred = mutableListOf<KSAnnotated>()
        for (symbol in resolver.getSymbolsWithAnnotation("kokodb.DbTable")) {
            if (!symbol.validate()) {
                deferred.add(symbol)
                continue
            }
            val model = symbol as? KSClassDeclaration
            if (model == null) {
                logger.error("@DbTable requires a data class", symbol)
                continue
            }
            val qualified = model.qualifiedName?.asString() ?: continue
            if (!qualified.split('.').all(::identifier)) {
                logger.error("Model package and class names must use ASCII identifiers", model)
                continue
            }
            val packageName = model.packageName.asString()
            val adapterName = model.simpleName.asString() + "_KokoAdapter"
            val adapterQualified = if (packageName.isEmpty()) adapterName else "$packageName.$adapterName"
            if (adapterQualified in generated) continue
            if (Modifier.DATA !in model.modifiers || !isPublic(model.modifiers) ||
                model.parentDeclaration != null || model.typeParameters.isNotEmpty() ||
                !isPublic(model.primaryConstructor!!.modifiers)) {
                logger.error("@DbTable requires a public top-level non-generic data class with a public constructor", model)
                continue
            }
            val annotation = model.annotations.first { it.annotationType.resolve().declaration.qualifiedName?.asString() == "kokodb.DbTable" }
            val name = annotation.arguments.first { it.name?.asString() == "name" }.value as String
            if (!identifier(name)) {
                logger.error("Invalid table name '$name'", model)
                continue
            }
            val properties = model.getAllProperties().associateBy { it.simpleName.asString() }
            val fields = model.primaryConstructor!!.parameters.map { parameter ->
                val fieldName = parameter.name!!.asString()
                val property = properties[fieldName]
                val type = parameter.type.resolve()
                val typeName = type.declaration.qualifiedName?.asString()
                if (!identifier(fieldName) || property == null || !isPublic(property.modifiers) ||
                    type.isMarkedNullable || typeName !in setOf("kotlin.Int", "kotlin.String")) {
                    logger.error("@DbTable properties must be public non-null Int/String values with ASCII identifiers", parameter)
                    return@map null
                }
                Field(fieldName, if (typeName == "kotlin.Int") "Int" else "String", hasId(parameter) || hasId(property))
            }
            if (fields.isEmpty() || fields.any { it == null }) {
                if (fields.isEmpty()) logger.error("@DbTable requires at least one property", model)
                continue
            }
            val validFields = fields.filterNotNull()
            val storedNames = validFields.map { it.name }.toSet()
            if (properties.values.any { hasId(it) && it.simpleName.asString() !in storedNames }) {
                logger.error("@Id must annotate a stored constructor property", model)
                continue
            }
            if (validFields.count { it.primaryKey } > 1) {
                logger.error("Only one @Id property is supported", model)
                continue
            }
            if (validFields.map { it.name.lowercase(Locale.ROOT) }.distinct().size != validFields.size) {
                logger.error("Duplicate column names after case normalization", model)
                continue
            }
            if (!tableNames.add(name.lowercase(Locale.ROOT))) {
                logger.error("Duplicate @DbTable table name '$name'", model)
                continue
            }
            val file = model.containingFile!!
            generator.createNewFile(Dependencies(false, file), packageName, adapterName).bufferedWriter().use {
                it.write(adapterSource(packageName, adapterName, qualified, name, validFields))
            }
            generated[adapterQualified] = file
        }
        processRepositories(resolver, deferred)
        return deferred
    }

    private fun processRepositories(resolver: Resolver, deferred: MutableList<KSAnnotated>) {
        for (symbol in resolver.getSymbolsWithAnnotation("kokodb.DbRepository")) {
            if (!symbol.validate()) {
                deferred.add(symbol)
                continue
            }
            val repository = symbol as? KSClassDeclaration
            if (repository == null || repository.classKind != ClassKind.INTERFACE ||
                !isPublic(repository.modifiers) || repository.parentDeclaration != null ||
                repository.typeParameters.isNotEmpty()) {
                logger.error("@DbRepository requires a public top-level non-generic interface", symbol)
                continue
            }
            val qualified = repository.qualifiedName!!.asString()
            if (qualified in generatedRepositories) continue
            if (!qualified.split('.').all(::identifier)) {
                logger.error("Repository package and class names must use ASCII identifiers", repository)
                continue
            }
            val parent = repository.superTypes.singleOrNull()?.resolve()
            if (parent?.declaration?.qualifiedName?.asString() != "kokodb.Repository" || parent.arguments.size != 2) {
                logger.error("Repository interfaces must directly extend Repository<Model, ID>", repository)
                continue
            }
            val modelType = parent.arguments[0].type?.resolve()
            val idType = parent.arguments[1].type?.resolve()
            val model = modelType?.declaration as? KSClassDeclaration
            if (model == null || modelType.isMarkedNullable || !hasAnnotation(model, "kokodb.DbTable")) {
                logger.error("Repository model must be an @DbTable class", repository)
                continue
            }
            val keys = model.getAllProperties().filter { hasId(it) }.toList()
            if (keys.size != 1) {
                logger.error("Repository model must declare exactly one @Id property", repository)
                continue
            }
            val keyType = keys.single().type.resolve()
            val keyName = keyType.declaration.qualifiedName?.asString()
            if (idType == null || idType.isMarkedNullable || keyType.isMarkedNullable ||
                keyName !in setOf("kotlin.Int", "kotlin.String") ||
                idType.declaration.qualifiedName?.asString() != keyName) {
                logger.error("Repository ID type must match the model's non-null Int/String @Id property", repository)
                continue
            }
            val methods = repository.getDeclaredFunctions().toList()
            if (methods.any { it.isAbstract } || repository.getAllProperties().any { it.isAbstract() }) {
                logger.error("Custom repository members must have bodies; derived queries are not supported", repository)
                continue
            }
            if (methods.any { Modifier.OVERRIDE in it.modifiers }) {
                logger.error("Overriding inherited repository operations is not supported", repository)
                continue
            }
            val modelName = model.qualifiedName!!.asString()
            val dependencies = listOfNotNull(repository.containingFile, model.containingFile).distinct()
            generator.createNewFile(
                Dependencies(false, *dependencies.toTypedArray()), repository.packageName.asString(),
                repository.simpleName.asString() + "_KokoRepository"
            ).bufferedWriter().use { it.write(repositorySource(repository, modelName, keyName!!)) }
            generatedRepositories.add(qualified)
        }
    }

    private fun repositorySource(repository: KSClassDeclaration, model: String, id: String): String = buildString {
        val pkg = repository.packageName.asString()
        val name = repository.simpleName.asString()
        val modelType = model.split('.').joinToString(".") { "`$it`" }
        val idType = id.split('.').joinToString(".") { "`$it`" }
        if (pkg.isNotEmpty()) appendLine("package ${pkg.split('.').joinToString(".") { "`$it`" }}")
        appendLine("/** Creates a repository bound to the supplied database instance. */")
        appendLine("public fun `$name`(database: kokodb.Database): `$name` = ${name}_KokoImpl(database)")
        appendLine("private class ${name}_KokoImpl(database: kokodb.Database) : `$name`,")
        appendLine("    kokodb.Repository<$modelType, $idType> by")
        appendLine("    kokodb.mapping.RepositorySupport(database, $modelType::class, $idType::class)")
    }

    override fun finish() {
        if (generated.isEmpty()) return
        // Service discovery must include every model, so the resource depends on all annotated source files.
        generator.createNewFileByPath(
            Dependencies(true, *generated.values.distinct().toTypedArray()),
            "META-INF/services/kokodb.mapping.ModelProvider", ""
        ).bufferedWriter().use { it.write(generated.keys.joinToString("\n", postfix = "\n")) }
    }

    private fun adapterSource(pkg: String, adapter: String, model: String, table: String, fields: List<Field>): String = buildString {
        val modelType = model.split('.').joinToString(".") { "`$it`" }
        if (pkg.isNotEmpty()) appendLine("package ${pkg.split('.').joinToString(".") { "`$it`" }}")
        appendLine("import kokodb.*")
        appendLine("import kokodb.mapping.*")
        appendLine("import kotlin.reflect.KProperty1")
        appendLine("public class $adapter : ModelAdapter<$modelType>, ModelProvider {")
        appendLine("    private object Schema : Table(\"$table\") {")
        fields.forEachIndexed { index, field ->
            val factory = if (field.type == "Int") "int" else "text"
            appendLine("        val column$index = $factory(\"${field.name}\", primaryKey = ${field.primaryKey})")
        }
        appendLine("    }")
        appendLine("    override val modelClass: Class<$modelType> = $modelType::class.java")
        appendLine("    override val table: Table = Schema")
        val keyIndex = fields.indexOfFirst { it.primaryKey }
        val key = fields.getOrNull(keyIndex)
        appendLine("    override val primaryKey: Column<*>? = ${if (key == null) "null" else "Schema.column$keyIndex"}")
        appendLine("    override fun adapters(): List<ModelAdapter<*>> = listOf(this)")
        appendLine("    override fun insert(database: Database, model: $modelType): Int = database.insertInto(Schema) {")
        fields.forEachIndexed { index, field -> appendLine("        set(Schema.column$index, model.`${field.name}`)") }
        appendLine("    }")
        if (key != null) {
            appendLine("    override fun update(database: Database, model: $modelType): Int =")
            appendLine("        database.replaceIn(Schema, Schema.column$keyIndex eq model.`${key.name}`) {")
            fields.forEachIndexed { index, field -> appendLine("            set(Schema.column$index, model.`${field.name}`)") }
            appendLine("        }")
        } else {
            appendLine("    override fun update(database: Database, model: $modelType): Int =")
            appendLine("        throw DatabaseException(\"Model requires one @Id property for update\")")
        }
        appendLine("    override fun read(row: Row): $modelType = $modelType(")
        fields.forEachIndexed { index, field -> appendLine("        `${field.name}` = row[Schema.column$index],") }
        appendLine("    )")
        appendLine("    override fun condition(property: KProperty1<$modelType, *>, value: Any): Condition = when (property) {")
        fields.forEachIndexed { index, field ->
            appendLine("        $modelType::`${field.name}` -> Schema.column$index eq (value as? ${field.type}")
            appendLine("            ?: throw DatabaseException(\"Invalid value type for '${field.name}'\"))")
        }
        appendLine("        else -> throw DatabaseException(\"Property is not a stored field of $model\")")
        appendLine("    }")
        appendLine("}")
    }

    private fun identifier(value: String) = value.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))
    private fun isPublic(modifiers: Set<Modifier>) =
        modifiers.none { it == Modifier.PRIVATE || it == Modifier.INTERNAL || it == Modifier.PROTECTED }
    private fun hasId(symbol: KSAnnotated): Boolean = hasAnnotation(symbol, "kokodb.Id")
    private fun hasAnnotation(symbol: KSAnnotated, name: String): Boolean = symbol.annotations.any {
        it.annotationType.resolve().declaration.qualifiedName?.asString() == name
    }
    private data class Field(val name: String, val type: String, val primaryKey: Boolean)
}
