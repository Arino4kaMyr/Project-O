package token

import exceptions.NotFoundException

class TokenStream(private val tokens: List<Token>) {

    private var i = 0
    fun peek(): Token = tokens.getOrElse(i) {tokens.last()}
    fun next(): Token = tokens.getOrElse(i++) {tokens.last()} //return current one and up counter
    fun expect(type: TokenType): Token {
        val t = peek()
        if (t.type != type) {
            throw NotFoundException("Expected $type but found '${t.type}' in line ${t.line}")
        }
        return next()
    }
    fun expectText(textValue: String): Token {
        val t = peek()
        if (t.text != textValue) {
            throw NotFoundException("Expected $textValue but found '${t.text} in line ${t.line}")
        }
        return next()
    }
    fun matchTokenType(type: TokenType): Boolean {
        val t = peek()
        return t.type == type
    }
    fun matchAndNext(type: TokenType, textValue: String? = null): Boolean {
        val t = peek()
        if (t.type != type) return false
        if (textValue != null && t.text != textValue) return false
        next()
        return true
    }
}