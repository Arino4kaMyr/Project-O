import compilation.Compiler
import compilation.jasmin.JasminCodeGenerator
import constants.MainConstants
import exceptions.LexicalException
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

    val tokens = try {
        lexer.scan(text)
    } catch (e: LexicalException) {
        println("\nLexical Error: ${e.message}")
        exitProcess(1)
    }

    tokens.forEach { token ->
        println("${token.text} : ${token.type}" )
    }

    try {
        val syntaxAnalyzer = SyntaxAnalyzer(tokens)
        val program = syntaxAnalyzer.parseProgram()
        
        val semanticAnalyzer = SemanticAnalyzer(program)
        semanticAnalyzer.analyze()
        
        // Выводим таблицы классов (с полями и методами)
        val classTable = semanticAnalyzer.classTable
        classTable.print()
        
        // Выводим оптимизированный AST
        val optimizedProgram = semanticAnalyzer.getOptimizedProgram()
        AstPrinter.print(optimizedProgram, "Optimized AST")


        val compiler = Compiler(optimizedProgram, classTable)
        // run the program(transfer to jasmin files and execute them)
        compiler.compileAndRun()
    } catch (e: SematicException) {
        println("\nSemantic Error: ${e.message}")
        exitProcess(1)
    } catch (e: Exception) {
        println("\nError: ${e.message}")
        exitProcess(1)
    }
}
