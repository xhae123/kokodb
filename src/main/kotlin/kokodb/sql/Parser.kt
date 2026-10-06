package kokodb.sql

import kokodb.SqlSyntaxException
import kokodb.storage.Column
import kokodb.storage.DataType
import kokodb.storage.Value
import java.util.Locale

internal class Parser(sql: String) {
    private val tokens = Lexer(sql).tokenize()
    private var offset = 0
    private val current get() = tokens[offset]

    fun parse(): Statement {
        val statement = when {
            keyword("CREATE") -> parseCreate()
            keyword("INSERT") -> parseInsert()
            keyword("SELECT") -> parseSelect()
            else -> fail("Expected CREATE, INSERT, or SELECT")
        }
        accept(TokenKind.SEMICOLON)
        expect(TokenKind.END)
        return statement
    }

    private fun parseCreate(): Statement.CreateTable {
        expectKeyword("TABLE")
        val table = identifier()
        expect(TokenKind.LEFT_PAREN)
        val columns = commaSeparated {
            val name = identifier()
            val type = when {
                keyword("INT") -> DataType.INT
                keyword("TEXT") -> DataType.TEXT
                else -> fail("Expected INT or TEXT")
            }
            Column(name, type)
        }
        expect(TokenKind.RIGHT_PAREN)
        return Statement.CreateTable(table, columns)
    }

    private fun parseInsert(): Statement.Insert {
        expectKeyword("INTO")
        val table = identifier()
        expectKeyword("VALUES")
        expect(TokenKind.LEFT_PAREN)
        val values = commaSeparated { expression() }
        expect(TokenKind.RIGHT_PAREN)
        return Statement.Insert(table, values)
    }

    private fun parseSelect(): Statement.Select {
        val projection = if (accept(TokenKind.STAR)) Projection.All
        else Projection.Columns(commaSeparated { identifier() })
        expectKeyword("FROM")
        val table = identifier()
        val condition = if (keyword("WHERE")) {
            val column = identifier()
            expect(TokenKind.EQUALS)
            Equality(column, expression())
        } else null
        return Statement.Select(table, projection, condition)
    }

    private fun expression(): Expression {
        val token = current
        val result = when (token.kind) {
            TokenKind.INTEGER -> {
                val value = token.text.toIntOrNull()
                    ?: fail("Integer literal is outside the INT range")
                Expression.Literal(Value.IntValue(value))
            }
            TokenKind.STRING -> Expression.Literal(Value.TextValue(token.text))
            TokenKind.PARAMETER -> Expression.Parameter(token.text)
            else -> fail("Expected integer, string, or named parameter")
        }
        offset++
        return result
    }

    private fun identifier(): String = expect(TokenKind.WORD).text.lowercase(Locale.ROOT)

    private fun <T> commaSeparated(read: () -> T): List<T> = buildList {
        add(read())
        while (accept(TokenKind.COMMA)) add(read())
    }

    private fun keyword(text: String): Boolean {
        if (current.kind != TokenKind.WORD || !current.text.equals(text, ignoreCase = true)) return false
        offset++
        return true
    }

    private fun expectKeyword(text: String) {
        if (!keyword(text)) fail("Expected $text")
    }

    private fun accept(kind: TokenKind): Boolean {
        if (current.kind != kind) return false
        offset++
        return true
    }

    private fun expect(kind: TokenKind): Token {
        if (current.kind != kind) fail("Expected $kind, found ${current.kind}")
        return tokens[offset++]
    }

    private fun fail(message: String): Nothing = throw SqlSyntaxException(message, current.position)
}
