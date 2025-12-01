package compilation

import compilation.jasmin.JasminCodeGenerator
import constants.MainConstants.JASMINE_DIRECTORY_PATH
import semantic.tables.ClassTable
import syntaxer.Program
import java.io.File

class Compiler(
    private val program: Program,
    private val classTable: ClassTable
) {

    fun compile() {
        generateJasminCode()
    }

    fun compileAndRun() {
        generateJasminCode()
        compileJasminFiles()
        runProgram()
    }

    private fun generateJasminCode() {
        val jasminCodeGenerator = JasminCodeGenerator(program, classTable)
        val files = jasminCodeGenerator.generate()

        val outDir = File(JASMINE_DIRECTORY_PATH)
        if (!outDir.exists()) {
            outDir.mkdirs()
        }

        files.forEach { (fileName, code) ->
            val jasminFile = File(outDir, fileName)
            jasminFile.writeText(code)
        }
    }

    private fun compileJasminFiles() {
        val outDir = File(JASMINE_DIRECTORY_PATH)
        val jasminJar = File(outDir, "jasmin.jar")
        
        if (!jasminJar.exists()) {
            throw IllegalStateException("jasmin.jar not found at ${jasminJar.absolutePath}")
        }

        val jasminFiles = outDir.listFiles { _, name -> name.endsWith(".j") }
            ?: throw IllegalStateException("No .j files found in ${outDir.absolutePath}")

        jasminFiles.forEach { jasminFile ->
            val processBuilder = ProcessBuilder(
                "java", "-jar", jasminJar.absolutePath, jasminFile.name
            )
            processBuilder.directory(outDir)
            processBuilder.redirectErrorStream(true)
            
            val process = processBuilder.start()
            val exitCode = process.waitFor()
            
            if (exitCode != 0) {
                val errorOutput = process.inputStream.bufferedReader().readText()
                throw IllegalStateException("Failed to compile ${jasminFile.name}: $errorOutput")
            }
        }
    }

    private fun runProgram() {
        val outDir = File(JASMINE_DIRECTORY_PATH)
        val processBuilder = ProcessBuilder("java", "-cp", outDir.absolutePath, "Program")
        
        val process = processBuilder.start()
        
        // Перенаправляем вывод в консоль
        process.inputStream.bufferedReader().use { reader ->
            reader.lineSequence().forEach { println(it) }
        }
        
        process.errorStream.bufferedReader().use { reader ->
            reader.lineSequence().forEach { System.err.println(it) }
        }
        
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IllegalStateException("Program execution failed with exit code $exitCode")
        }
    }

}