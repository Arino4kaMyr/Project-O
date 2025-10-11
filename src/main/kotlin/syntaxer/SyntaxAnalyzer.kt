package syntaxer

import exceptions.NotFoundException
import token.Token
import token.TokenType
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.atomics.AtomicInt

class SyntaxAnalyzer(
    val tokens: List<Token>
) {
    private val currInd = AtomicInteger(0)

    fun parseProgram() {
        val listOfClasses = mutableListOf<ClassDecl>()
        if (tokens[currInd.get()].text == CLASS) {
            val classDecl = parseClass()
            listOfClasses.add(classDecl)
        }
        println(listOfClasses.toString())
    }

    private fun parseClass(): ClassDecl {
        val className = if (tokens[currInd.incrementAndGet()].type == TokenType.IDENTIFIER) {
            tokens[currInd.incrementAndGet()].text

        } else {
            throw NotFoundException("Class name must be exist")
        }

        var parentName: String? = null
        var members: List<MemberDecl> = emptyList()
        if (tokens[currInd.get()].type == TokenType.KEYWORD) {

            if (tokens[currInd.get()].text == EXTENDS) {
                parentName = tokens[currInd.incrementAndGet()].text
                currInd.incrementAndGet()
            }
            if (tokens[currInd.get()].text == START_OF_CODE_BLOCK) {
                members = parseClassMembers()
            }
            return ClassDecl(
                ClassName.Simple(className),
                parentName?.let { ClassName.Simple(it) },
                members
            )
        } else {
            throw NotFoundException("There is no code block in class")
        }
    }

    private fun parseClassMembers(): List<MemberDecl> {
        currInd.incrementAndGet()
        return emptyList()
    }
    //TODO:Implement methods for parse every entity

    companion object {
        private const val CLASS = "class"
        private const val EXTENDS = "extends"
        private const val START_OF_CODE_BLOCK = "is"
    }
}