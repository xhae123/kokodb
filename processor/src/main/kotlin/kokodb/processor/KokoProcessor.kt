package kokodb.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
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
        return deferred
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
    private fun hasId(symbol: KSAnnotated): Boolean = symbol.annotations.any {
        it.annotationType.resolve().declaration.qualifiedName?.asString() == "kokodb.Id"
    }
    private data class Field(val name: String, val type: String, val primaryKey: Boolean)
}
