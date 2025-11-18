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

fun numberToJasmin(value: Long): String {
    return when (value) {
        -1L -> "    iconst_m1\n"
        0L -> "    iconst_0\n"
        1L -> "    iconst_1\n"
        2L -> "    iconst_2\n"
        3L -> "    iconst_3\n"
        4L -> "    iconst_4\n"
        5L -> "    iconst_5\n"
        in -128L..127L -> "    bipush $value\n"
        in -32768L..32767L -> "    sipush $value\n"
        else -> "    ldc $value\n"
    }
}