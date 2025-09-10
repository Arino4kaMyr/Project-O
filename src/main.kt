import java.io.File

fun main() {
    val lexer = Lexer()
    val filePath = "/Users/arinazimina/IdeaProjects/Project-O/src/tests.txt" // путь к вашему файлу с исходным текстом
    var text = File(filePath).readText()
    if (text.startsWith("\uFEFF")) {
        text = text.removePrefix("\uFEFF")
    }

    val tokens = lexer.scan(text)
    tokens.forEach { token ->
        if (token.type == TokenType.ERROR) {
            println("Error: '${token.text}' - ${token.errorMessage}")
        } else {
            println("${token.text} : ${token.type}")
        }
    }
}
