package compilation

import compilation.jasmin.JasminCodeGenerator
import semantic.tables.ClassTable
import syntaxer.Program

class Compiler(
    private val program: Program,
    private val classTable: ClassTable
) {

    fun compile() {
        val jasminCodeGenerator = JasminCodeGenerator(program, classTable)
        val files = jasminCodeGenerator.generate()



    }

}