@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package kokodb.compiler

import kokodb.SqlSyntaxException
import kokodb.query.Statement
import kokodb.sql.Parser
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrStringConcatenation
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

@OptIn(ExperimentalCompilerApi::class)
class KokoSqlCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId = "kokodb.sql"
    override val supportsK2 = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val messages = configuration.get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
        IrGenerationExtension.registerExtension(SqlValidationExtension(messages))
    }
}

private class SqlValidationExtension(private val messages: MessageCollector) : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        moduleFragment.files.forEach { it.acceptChildrenVoid(SqlCallVisitor(it, messages)) }
    }
}

private val databaseOwners = setOf("kokodb.KoKoDB", "kokodb.Database", "kokodb.Transaction")
private val sqlMethods = setOf("invoke", "query", "queryModels", "queryRows")

private class SqlCallVisitor(private val file: IrFile, private val messages: MessageCollector) : IrVisitorVoid() {
    override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

    override fun visitCall(expression: IrCall) {
        expression.acceptChildrenVoid(this)
        val function = expression.symbol.owner
        val owner = (function.parent as? IrClass)?.fqNameWhenAvailable?.asString()
        if (owner !in databaseOwners) return
        val name = function.name.asString()
        if (name !in sqlMethods) return
        val parameter = function.parameters.firstOrNull { it.name.asString() == "sql" } ?: return
        val argument = expression.arguments[parameter.indexInParameters] ?: return
        val sql = constantString(argument)
        if (sql == null) {
            report(CompilerMessageSeverity.WARNING, "KoKoDB SQL is dynamic or unsupported as a constant expression; checked only at runtime", argument)
            return
        }
        val statement = try {
            Parser(sql).parse()
        } catch (error: SqlSyntaxException) {
            report(CompilerMessageSeverity.ERROR, "Invalid KoKoDB SQL: ${error.message}", argument)
            return
        }
        val write = name == "invoke" && function.typeParameters.isEmpty()
        if (write && statement is Statement.Select) {
            report(CompilerMessageSeverity.ERROR, "KoKoDB write calls cannot run SELECT; supply a model type or use query()", argument)
        } else if (!write && statement !is Statement.Select) {
            report(CompilerMessageSeverity.ERROR, "KoKoDB query calls require SELECT; use the database call without a model type for writes", argument)
        }
    }

    private fun report(severity: CompilerMessageSeverity, message: String, argument: IrExpression) {
        val offset = argument.startOffset.takeIf { it >= 0 } ?: return messages.report(severity, message)
        val entry = file.fileEntry
        messages.report(severity, message, CompilerMessageLocation.create(
            entry.name, entry.getLineNumber(offset) + 1, entry.getColumnNumber(offset) + 1, null,
        ))
    }
}

private fun constantString(expression: IrExpression, depth: Int = 0): String? {
    if (depth > 32) return null
    return when (expression) {
        is IrConst -> expression.value as? String
        is IrStringConcatenation -> {
            val parts = expression.arguments.map { part ->
                if (part is IrConst) part.value.toString() else constantString(part, depth + 1)
            }
            if (parts.any { it == null }) null else parts.joinToString("")
        }
        is IrGetField -> expression.symbol.owner.takeIf { it.correspondingPropertySymbol?.owner?.isConst == true }
            ?.initializer?.expression?.let { constantString(it, depth + 1) }
        is IrCall -> {
            val function = expression.symbol.owner
            val property = function.correspondingPropertySymbol?.owner
            if (property?.isConst == true) {
                property.backingField?.initializer?.expression?.let { constantString(it, depth + 1) }
            } else when (function.fqNameWhenAvailable?.asString()) {
                "kotlin.String.plus" -> {
                    val parts = expression.arguments.filterNotNull().map { constantString(it, depth + 1) }
                    if (parts.size != 2 || parts.any { it == null }) null else parts.joinToString("")
                }
                "kotlin.text.trimIndent" -> expression.arguments.firstOrNull()?.let { constantString(it, depth + 1)?.trimIndent() }
                else -> null
            }
        }
        else -> null
    }
}
