import java.io.File

fun main() {
    val lexer = Lexer()
    println("Choose input source: [1] File, [2] Console")
    val choice = readLine()?.trim()

    val text = when (choice) {
        "1" -> {
            var fileText = File("/Users/arinazimina/IdeaProjects/Project-O/src/tests.txt").readText()
            if (fileText.startsWith("\uFEFF")) {
                fileText = fileText.removePrefix("\uFEFF")
            }
            fileText
        }
        else -> {
            println("Enter text (end with an empty line):")
            val lines = mutableListOf<String>()
            while (true) {
                val line = readLine()
                if (line == null || line.isEmpty()) break
                lines.add(line)
            }
            lines.joinToString("\n")
        }
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
