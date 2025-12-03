package lexer

import token.Token
import token.TokenType

class Lexer {
    var state = State.START

    fun isLetter(c: Char) = c.isLetter() || c == '_'
    fun isDigit(c: Char) = c.isDigit()
    fun isEmpty(c: Char) = c.isWhitespace() || c == '\n'
    val symbols = setOf(':', ';', '.', ',', '(', ')', '[', ']', '{', '}', '"', '=')
    val numRegex = Regex("^-?\\d+(\\.\\d+)?$")

    private fun addToken(tokens: MutableList<Token>, text: String, type: TokenType, line: Int, error: String? = null) {
        tokens.add(Token(text, type, line, error))
    }

    fun scan(text: String): List<Token> {
        var i = 0
        val token = StringBuilder()
        val tokens = mutableListOf<Token>()
        var tokenStartIndex = 0
        var errorMode = false
        
        fun getLineNumber(pos: Int): Int {
            var line = 1
            for (j in 0 until pos) {
                if (j < text.length && text[j] == '\n') line++
            }
            return line
        }

        while (i < text.length) {
            val c = text[i]
            
            if (errorMode) {
                if (isEmpty(c)) {
                    addToken(tokens, token.toString(), TokenType.ERROR, getLineNumber(tokenStartIndex), "Invalid token")
                    token.clear()
                    errorMode = false
                    i++
                } else {
                    token.append(c)
                    i++
                }
                continue
            }
            if (c == '#') {
                while (i < text.length && text[i] != '\n'){
                    i++
                }
                if (i < text.length && text[i] == '\n') {
                    i++
                }
                continue
            }


            when (state) {
                State.START -> {
                    token.clear()
                    tokenStartIndex = i
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
                        c == '-' && i + 1 < text.length && isDigit(text[i + 1]) -> {
                            // Отрицательное число: минус, за которым следует цифра
                            token.append(c)
                            token.append(text[i + 1])
                            state = State.NUM
                            i += 2
                        }
                        c in symbols -> {
                            token.append(c)
                            if (c == ':' && i + 1 < text.length && text[i + 1] == '=') {
                                token.append('=')
                                addToken(tokens, token.toString(), TokenType.SPECIAL_SYMBOL, getLineNumber(tokenStartIndex))
                                i += 2
                                state = State.START
                                token.clear()
                            } else {
                                addToken(tokens, token.toString(), TokenType.SPECIAL_SYMBOL, getLineNumber(tokenStartIndex))
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
                        isDigit(c) || c == '.' -> {
                            token.append(c)
                            i++
                        }
                        isEmpty(c) || c in symbols-> {
                            if(numRegex.matches(token.toString())) {
                                addToken(tokens, token.toString(), TokenType.NUMBER, getLineNumber(tokenStartIndex))
                                token.clear()
                                state = State.START
                            } else {
                                errorMode = true
                                state = State.START
                            }
                        }
                        else -> {
                            token.append(c)
                            errorMode = true
                            state = State.START
                            i++
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
                            if (token.toString() in KEYWORDS){
                                addToken(tokens, token.toString(), TokenType.KEYWORD, getLineNumber(tokenStartIndex))
                                token.clear()
                                state = State.START
                            } else {
                                addToken(tokens, token.toString(), TokenType.IDENTIFIER, getLineNumber(tokenStartIndex))
                                token.clear()
                                state = State.START
                            }
                        }
                        else -> {
                            token.append(c)
                            errorMode = true
                            state = State.START
                            i++
                        }
                    }
                }
            }

        }

        if (token.isNotEmpty()) {
            if (errorMode) {
                addToken(tokens, token.toString(), TokenType.ERROR, getLineNumber(tokenStartIndex), "Invalid token")
            } else {
                val finalType = when (state) {
                    State.NUM -> TokenType.NUMBER
                    State.IDEN -> TokenType.IDENTIFIER
                    else -> TokenType.SPECIAL_SYMBOL
                }
                addToken(tokens, token.toString(), finalType, getLineNumber(tokenStartIndex))
            }
        }

        return tokens
    }
    companion object {
        private val KEYWORDS = setOf(
            "class", "extends", "is", "end", "var", "method", "this",
            "return", "while", "loop", "if", "then", "else", "true", "false",
            "private", "public"
        )

    }
}

