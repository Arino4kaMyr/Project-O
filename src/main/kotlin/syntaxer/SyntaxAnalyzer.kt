package syntaxer

import token.Token

class SyntaxAnalyzer(
    val tokens: List<Token>
) {

    fun parseProgram() {
        val el = tokens.get(0).text == "class"
    }

    //TODO:Implement methods for parse every entity

}