package kokodb.sql

import kokodb.SqlSyntaxException

internal class Lexer(private val sql: String) {
    private var offset = 0

    fun tokenize(): List<Token> = buildList {
        while (offset < sql.length) {
            val start = offset
            val char = sql[offset]
            when {
                char.isWhitespace() -> offset++
                isNameStart(char) -> {
                    offset++
                    while (offset < sql.length && isNamePart(sql[offset])) offset++
                    add(Token(TokenKind.WORD, sql.substring(start, offset), start))
                }
                char in '0'..'9' || char == '-' -> {
                    offset++
                    if (char == '-' && (offset == sql.length || sql[offset] !in '0'..'9')) {
                        throw SqlSyntaxException("Expected digits after '-'", start)
                    }
                    while (offset < sql.length && sql[offset] in '0'..'9') offset++
                    add(Token(TokenKind.INTEGER, sql.substring(start, offset), start))
                }
                char == '\'' -> add(readString())
                char == ':' -> {
                    offset++
                    if (offset == sql.length || !isNameStart(sql[offset])) {
                        throw SqlSyntaxException("Expected parameter name after ':'", start)
                    }
                    val nameStart = offset++
                    while (offset < sql.length && isNamePart(sql[offset])) offset++
                    add(Token(TokenKind.PARAMETER, sql.substring(nameStart, offset), start))
                }
                else -> {
                    val kind = when (char) {
                        '(' -> TokenKind.LEFT_PAREN
                        ')' -> TokenKind.RIGHT_PAREN
                        ',' -> TokenKind.COMMA
                        '*' -> TokenKind.STAR
                        '=' -> TokenKind.EQUALS
                        ';' -> TokenKind.SEMICOLON
                        else -> throw SqlSyntaxException("Unexpected character '$char'", start)
                    }
                    offset++
                    add(Token(kind, char.toString(), start))
                }
            }
        }
        add(Token(TokenKind.END, "", sql.length))
    }

    private fun readString(): Token {
        val start = offset++
        val value = StringBuilder()
        while (offset < sql.length) {
            val char = sql[offset++]
            if (char != '\'') {
                value.append(char)
            } else if (offset < sql.length && sql[offset] == '\'') {
                offset++
                value.append('\'')
            } else {
                return Token(TokenKind.STRING, value.toString(), start)
            }
        }
        throw SqlSyntaxException("Unterminated string literal", start)
    }

    private fun isNameStart(char: Char) = char in 'a'..'z' || char in 'A'..'Z' || char == '_'
    private fun isNamePart(char: Char) = isNameStart(char) || char in '0'..'9'
}
