package token

data class Token(val text: String, val type: TokenType, val line: Int, val errorMessage: String? = null)

