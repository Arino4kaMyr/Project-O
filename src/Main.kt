import java.io.File
enum class State {
    START, NUM, IDEN, SYM
}

enum class TokenType {
    NUMBER, IDENTIFIER, SYMBOL, SPECIAL_SYMBOL, ERROR
}

data class Token(val text: String, val type: TokenType, val errorMessage: String? = null)

class Lexer {
    var state = State.START

    fun isLetter(c: Char) = c.isLetter() || c == '_'
    fun isDigit(c: Char) = c.isDigit()
    fun isEmpty(c: Char) = c.isWhitespace() || c == '\n'
    val symbols = setOf(':', ';', '.', ',', '(', ')', '[', ']', '{', '}', '"', '=')

    private fun addToken(tokens: MutableList<Token>, text: String, type: TokenType, error: String? = null) {
        tokens.add(Token(text, type, error))
    }

    fun scan(text: String): List<Token> {
        var i = 0
        val token = StringBuilder()
        val tokens = mutableListOf<Token>()
        var errorMode = false

        while (i < text.length) {
            val c = text[i]

            if (errorMode) {
                if (isEmpty(c)) {
                    addToken(tokens, token.toString(), TokenType.ERROR, "Invalid token")
                    token.clear()
                    errorMode = false
                    i++
                } else {
                    token.append(c)
                    i++
                }
                continue
            }

            when (state) {
                State.START -> {
                    token.clear()
                    when {
                        isLetter(c) -> {
                            token.append(c)
                            state = State.IDEN
                            i++
                        }
                        isDigit(c) -> {
                            token.append(c)
                            state = State.NUM
                            i++
                        }
                        c in symbols -> {
                            token.append(c)
                            if (c == ':' && i + 1 < text.length && text[i + 1] == '=') {
                                token.append('=')
                                addToken(tokens, token.toString(), TokenType.SPECIAL_SYMBOL)
                                i += 2
                                state = State.START
                                token.clear()
                            } else {
                                addToken(tokens, token.toString(), TokenType.SYMBOL)
                                i++
                                state = State.START
                                token.clear()
                            }
                        }
                        isEmpty(c) -> i++
                        else -> {
                            token.append(c)
                            errorMode = true
                            i++
                        }
                    }
                }
                State.NUM -> {
                    when {
                        isDigit(c) -> {
                            token.append(c)
                            i++
                        }
                        isLetter(c) -> {
                            token.append(c)
                            errorMode = true
                            state = State.START
                            i++
                        }
                        else -> {
                            addToken(tokens, token.toString(), TokenType.NUMBER)
                            token.clear()
                            state = State.START
                        }
                    }
                }
                State.IDEN -> {
                    when {
                        isLetter(c) || isDigit(c) -> {
                            token.append(c)
                            i++
                        }
                        isEmpty(c) || c in symbols-> {
                            addToken(tokens, token.toString(), TokenType.IDENTIFIER)
                            token.clear()
                            state = State.START
                        }
                        else -> {
                            token.append(c)
                            errorMode = true
                            state = State.START
                            i++
                        }
                    }
                }
                else -> {
                    state = State.START
                }
            }
        }

        if (token.isNotEmpty()) {
            if (errorMode) {
                addToken(tokens, token.toString(), TokenType.ERROR, "Invalid token")
            } else {
                val finalType = when (state) {
                    State.NUM -> TokenType.NUMBER
                    State.IDEN -> TokenType.IDENTIFIER
                    else -> TokenType.SYMBOL
                }
                addToken(tokens, token.toString(), finalType)
            }
        }

        return tokens
    }
}

fun main() {
    val lexer = Lexer()
    val text = "a_square := a.Mult(a) 7h7 xyz@123"
    val tokens = lexer.scan(text)
    tokens.forEach { token ->
        if (token.type == TokenType.ERROR) {
            println("Error: '${token.text}' - ${token.errorMessage}")
        } else {
            println("${token.text} : ${token.type}")
        }
    }
}
