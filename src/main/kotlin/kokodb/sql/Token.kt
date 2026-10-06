package kokodb.sql

internal enum class TokenKind {
    WORD, INTEGER, STRING, PARAMETER, LEFT_PAREN, RIGHT_PAREN, COMMA, STAR, EQUALS, SEMICOLON, END
}

internal data class Token(val kind: TokenKind, val text: String, val position: Int)
