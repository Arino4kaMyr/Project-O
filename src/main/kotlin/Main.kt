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
    println("Choose input source: [1] File, [2] Console")
    val choice = readlnOrNull()?.trim()

    val text = when (choice) {
        "1" -> {
            val resourceStream = object{}.javaClass.getResourceAsStream(MainConstants.TEST_FILE)
                ?: error("File tests.txt doesn't exist")

            var fileText = resourceStream.bufferedReader().use { it.readText() }
            if (fileText.startsWith(MainConstants.DECODE_BYTES)) {
                fileText = fileText.removePrefix(MainConstants.DECODE_BYTES)
            }
            fileText
        }
        "2" -> {
            println("Enter text (end with an empty line):")
            val lines = mutableListOf<String>()
            while (true) {
                val line = readlnOrNull()
                if (line == null || line.isEmpty()) break
                lines.add(line)
            }
            lines.joinToString("\n")
        }
        else -> {
            println("Please, choose the correct one variant")
            exitProcess(0)
        }
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
        
        // Выводим таблицы классов (с полями и методами)
        semanticAnalyzer.classTable.print()
        
        // Выводим оптимизированный AST
        val optimizedProgram = semanticAnalyzer.getOptimizedProgram()
        println("\n" + "=".repeat(30) + "Optimized AST" + "=".repeat(30))
        AstPrinter.print(optimizedProgram)
    } catch (e: SematicException) {
        println("\nSemantic Error: ${e.message}")
        exitProcess(1)
    } catch (e: Exception) {
        println("\nError: ${e.message}")
        exitProcess(1)
    }
}
