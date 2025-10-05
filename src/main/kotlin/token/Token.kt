package token

data class Token(val text: String, val type: TokenType, val errorMessage: String? = null)

