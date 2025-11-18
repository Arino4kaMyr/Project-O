package compilation.jasmin

import semantic.tables.ClassTable
import syntaxer.ClassDecl
import syntaxer.ClassName
import syntaxer.MemberDecl
import syntaxer.Program

class JasminCodeGenerator(
    private val program: Program,
    private val classTable: ClassTable
) {

    // generate jasmin code, one jasmin file per one class!
    fun generate(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        program.classes.forEach { classDecl ->
            val className = (classDecl.name as ClassName.Simple).name
            val code = generateClass(classDecl)
            result["$className.j"] = code
        }
        return result
    }

    private fun generateClass(classDecl: ClassDecl): String {
        val sb = StringBuilder()
        val className = (classDecl.name as ClassName.Simple).name

        sb.append(".class public $className\n")
        val superName = when (val p = classDecl.parent) {
            null -> "java/lang/Object"
            is ClassName.Simple -> p.name.replace('.', '/')
        }
        sb.append(".super $superName\n")

        generateField(classDecl, sb)


        return sb.toString()
    }

    private fun generateField(classDecl: ClassDecl, sb: StringBuilder) {

        val className = (classDecl.name as ClassName.Simple).name
        val classSymbol = classTable.findClass(className)
            ?: throw IllegalStateException("ClassSymbol not found for $className")

        // Идём по полям класса из семантики
        classSymbol.fields.values.forEach { fieldSymbol ->
            val fieldName = fieldSymbol.name
            val jasminType = toJasminType(fieldSymbol.type)
            sb.append(".field private $fieldName $jasminType\n")
        }

        if (classSymbol.fields.isNotEmpty()) {
            sb.append("\n")
        }
    }

}