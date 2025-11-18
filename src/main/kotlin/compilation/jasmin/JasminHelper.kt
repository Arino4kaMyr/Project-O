package compilation.jasmin

import syntaxer.ClassName

fun toJasminType(type: ClassName): String {
    return when (type) {
        is ClassName.Simple -> when (type.name) {
            "Integer", "Int" -> "I"      // int
            "Real", "Double" -> "D"      // double
            "Bool", "Boolean" -> "Z"     // boolean
            "void", "Void" -> "V"        // void (для returnType, не для полей)
            else -> "L${type.name};"     // объектный тип: LProgram; LArray;
        }
    }
}