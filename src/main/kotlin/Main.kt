import compilation.Compiler
import compilation.jasmin.JasminCodeGenerator
import constants.MainConstants
import exceptions.SematicException
import lexer.Lexer
import semantic.SemanticAnalyzer
import syntaxer.AstPrinter
import syntaxer.SyntaxAnalyzer
import token.TokenType
import kotlin.system.exitProcess

fun main() {
    val lexer = Lexer()

    val resourceStream = object{}.javaClass.getResourceAsStream(MainConstants.TEST_FILE)
        ?: error("File ${MainConstants.TEST_FILE} doesn't exist")

    var text = resourceStream.bufferedReader().use { it.readText() }
    if (text.startsWith(MainConstants.DECODE_BYTES)) {
        text = text.removePrefix(MainConstants.DECODE_BYTES)
    }

    val tokens = lexer.scan(text)

    tokens.forEach { token ->
        if (token.type == TokenType.ERROR) {
            println("Error: '${token.text}' at line ${token.line} - ${token.errorMessage}")
        } else {
            println("${token.text} : ${token.type}" )
        }
    }

    try {
        val syntaxAnalyzer = SyntaxAnalyzer(tokens)
        val program = syntaxAnalyzer.parseProgram()
        
        val semanticAnalyzer = SemanticAnalyzer(program)
        semanticAnalyzer.analyze()
        
        val classTable = semanticAnalyzer.classTable
        classTable.print()
        
        val optimizedProgram = semanticAnalyzer.getOptimizedProgram()
        AstPrinter.print(optimizedProgram, "Optimized AST")


        val compiler = Compiler(optimizedProgram, classTable)
        compiler.compileAndRun()
    } catch (e: SematicException) {
        println("\nSemantic Error: ${e.message}")
        exitProcess(1)
    } catch (e: Exception) {
        println("\nError: ${e.message}")
        exitProcess(1)
    }
}
