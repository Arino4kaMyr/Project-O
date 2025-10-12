package syntaxer

import exceptions.NotFoundException
import token.Token
import token.TokenStream
import token.TokenType
import java.util.concurrent.atomic.AtomicInteger

class SyntaxAnalyzer(
    val tokens: List<Token>
) {
    private val ts = TokenStream(tokens)

    fun parseProgram(): Program {
        val listOfClasses = mutableListOf<ClassDecl>()
        while (ts.matchTokenType(TokenType.KEYWORD) && ts.peek().text == CLASS) {
            ts.next()
            val classDecl = parseClass()
            listOfClasses.add(classDecl)
        }
        println(listOfClasses.toString())
        return Program(listOfClasses)
    }

    private fun parseClass(): ClassDecl {
        val tokenName = ts.expect(TokenType.IDENTIFIER)
        val className = ClassName.Simple(tokenName.text)

        var parentName: ClassName? = null
        var members: List<MemberDecl> = emptyList()
        if (!ts.matchTokenType(TokenType.KEYWORD)) throw NotFoundException("There is no code block in class")
        if (ts.matchAndNext(TokenType.KEYWORD, EXTENDS)) {
            val parentToken = ts.expect(TokenType.IDENTIFIER)
            parentName = ClassName.Simple(parentToken.text)
        }
        if (ts.matchAndNext(TokenType.KEYWORD, START_OF_CODE_BLOCK)) {
            members = parseClassMembers()
            ts.expectText(END_OF_CODE_BLOCK)
        }
        return ClassDecl(
            className,
            parentName,
            members
        )
    }

    private fun parseClassMembers(): List<MemberDecl> {
        val members = mutableListOf<MemberDecl>()

        while (!(ts.matchTokenType(TokenType.KEYWORD) && ts.peek().text == END_OF_CODE_BLOCK)) {
            if (ts.matchAndNext(TokenType.KEYWORD, VAR)) {
                val v = parseVarDecl()
                members.add(v)
                continue
            } else if (ts.matchAndNext(TokenType.KEYWORD, METHOD)) {
                val m = parseMethodDecl()
                members.add(m)
                continue
            } else if (ts.matchAndNext(TokenType.KEYWORD, CONSTRUCTOR)) {
                val c = parseConstructorDecl()
                members.add(c)
                continue
            } else throw NotFoundException("Expected some class member")
        }
        return members
    }

    private fun parseVarDecl(): MemberDecl.VarDecl {

    }


    companion object {
        private const val CLASS = "class"
        private const val EXTENDS = "extends"
        private const val START_OF_CODE_BLOCK = "is"
        private const val END_OF_CODE_BLOCK = "end"

        //class members
        private const val VAR = "var"
        private const val METHOD = "method"
        private const val CONSTRUCTOR = "this"
    }
}