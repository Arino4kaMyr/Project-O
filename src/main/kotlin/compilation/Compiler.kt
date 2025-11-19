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

}