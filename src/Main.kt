enum class State {
    START, NUM, IDEN, SYM
}

val symbols = setOf(':',';', '.', ',', '(', ')', '[', ']', '{', '}','"', '=')

class Lexer{
    var state = State.START

    fun isLetter(c: Char) = c.isLetter() || c == '_'
    fun isDigit(c: Char) = c.isDigit()
    fun isEmpty(c: Char) = c.isWhitespace() || c == '\n'

    fun scan(text: String): List<Pair<String, String>> {
        var i = 0
        var token = StringBuilder()
        val tokens = mutableListOf<Pair<String, String>>()

        while (i < text.length) {
            val c = text[i]
            when (state) {
                State.START -> {
                    token = StringBuilder()
                    when {
                        isLetter(c) -> {
                            token.append(c)
                            state = State.IDEN
                            i++;
                        }
                        isDigit(c) -> {
                            token.append(c)
                            state = State.NUM
                            i++;
                        }
                        c in symbols -> {
                            token.append(c)
                            state = State.SYM
                            i++
                        }
                        isEmpty(c) -> {
                            i++
                        }
                        else -> {
                            tokens.add(Pair(c.toString(), "UNKNOWN SYMBOL"))
                            i++
                        }
                    }
                }
                State.NUM -> {
                    when {
                        isDigit(c) -> {
                            token.append(c)
                            i++;
                        }
                        c in symbols -> {
                            tokens.add(Pair(token.toString(), "NUMBER"))
                            token = StringBuilder()
                            token.append(c)

                            state = State.SYM
                            i++
                        }
                        isEmpty(c) -> {
                            state = State.START
                            tokens.add(Pair(token.toString(), "NUMBER"))
                            i++
                        }
                        else -> {
                            state = State.START
                            tokens.add(Pair(c.toString(), "WRONG CHARACTER"))
                            i++
                        }
                    }
                }
                State.IDEN -> {
                    when {
                        isLetter(c) || isDigit(c) -> {
                            token.append(c)
                            i++;
                        }
                        c in symbols -> {
                            tokens.add(Pair(token.toString(), "IDENTIFIER"))
                            token = StringBuilder()
                            token.append(c)

                            state = State.SYM
                            i++
                        }
                        isEmpty(c) -> {
                            state = State.START
                            tokens.add(Pair(token.toString(), "IDENTIFIER"))
                            i++
                        }
                        else -> {
                            state = State.START
                            tokens.add(Pair(c.toString(), "WRONG CHARACTER"))
                            i++
                        }
                    }
                }
                State.SYM -> {
                    when {
                        c in symbols -> {
                            if (token.append(c).toString() == ":="){
                                tokens.add(Pair(token.toString(), "SPECIAL SYMBOL"))
                                token = StringBuilder()

                                state = State.SYM
                                i++
                            } else {
                                tokens.add(Pair(c.toString(), "WRONG CHARACTER"))
                                i++
                            }
                        }
                        isEmpty(c) -> {
                            state = State.START
                            tokens.add(Pair(token.toString(), "IDENTIFIER"))
                            i++
                        }
                        isLetter(c) || isDigit(c) -> {
                            state = State.START
                        }
                        else -> {
                            state = State.START
                            tokens.add(Pair(c.toString(), "WRONG CHARACTER"))
                            i++
                        }
                    }
                }
            }
        }
        return tokens
    }
}

fun main() {
    val lexer = Lexer()
    val text = "a_square := a.Mult(a)"
    val tokens = lexer.scan(text)
    tokens.forEach { println(it) }
}