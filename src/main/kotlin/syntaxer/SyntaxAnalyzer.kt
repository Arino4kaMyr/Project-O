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
        ts.next()
        return members
    }

    private fun parseVarDecl(): MemberDecl.VarDecl {
        return MemberDecl.VarDecl("empty", Expr.Identifier("empty"))
    }
    private fun parseMethodDecl(): MemberDecl.MethodDecl {
        val nameTok = ts.expect(TokenType.IDENTIFIER)
        val name = nameTok.text

        val params = parseParams()

        var returnType: ClassName? = null
        if (ts.peek().text == ":") {
            ts.next()
            val returnTok = ts.expect(TokenType.IDENTIFIER)
            returnType = ClassName.Simple(returnTok.text)
        }

        var methodBody: MethodBody? = null
        if (ts.matchAndNext(TokenType.KEYWORD, START_OF_CODE_BLOCK)) {
            methodBody = parseMethodBody()
        }

        return MemberDecl.MethodDecl(name, params, returnType, methodBody)
    }

    private fun parseParams(): List<Param> {
        val params = mutableListOf<Param>()

        if (ts.matchAndNext(TokenType.SPECIAL_SYMBOL, OPEN_BRACKET)) {
            while (ts.peek().text != CLOSE_BRACKET) {
                val nameTok = ts.expect(TokenType.IDENTIFIER)
                val paramName = nameTok.text

                if (ts.peek().text == ":") ts.next() else throw NotFoundException("Expected ':' in parameter")

                val typeTok = ts.expect(TokenType.IDENTIFIER)
                val type = ClassName.Simple(typeTok.text)
                params.add(Param(paramName, type))

                if (ts.peek().text == ",") {
                    ts.next()
                    continue
                }
            }
        } else throw NotFoundException("Expected $OPEN_BRACKET before parameters list")

        return params
    }

    private fun parseMethodBody(): MethodBody.BlockBody {
        return MethodBody.BlockBody(emptyList(), emptyList())
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

        //params
        private const val OPEN_BRACKET = "("
        private const val CLOSE_BRACKET = ")"
    }
}