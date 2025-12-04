package compilation.jasmin

import syntaxer.ClassName

fun toJasminType(type: ClassName): String {
    return when (type) {
        is ClassName.Simple -> when (type.name) {
            "Integer", "Int" -> "I"
            "Real", "Double" -> "D"
            "Bool", "Boolean" -> "Z"
            "void", "Void" -> "V"
            else -> "L${type.name};"
        }
        is ClassName.Generic -> {
            when (type.name) {
                "Array" -> {
                    val elementType = type.typeArgs.firstOrNull()
                    when (elementType) {
                        is ClassName.Simple -> when (elementType.name) {
                            "Integer", "Int" -> "[I"
                            "Real", "Double" -> "[D"
                            "Bool", "Boolean" -> "[Z"
                            else -> "[Ljava/lang/Object;"
                        }
                        else -> "[Ljava/lang/Object;"
                    }
                }
                else -> {
                    "L${type.name};"
                }
            }
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

fun isBuiltin(type: ClassName): Boolean {
    return when (type) {
        is ClassName.Simple -> type.name in listOf("Integer", "Real", "Bool", "Boolean")
        is ClassName.Generic -> type.name == "Array"
    }
}

fun getJvmSlotSize(type: ClassName): Int {
    return when (type) {
        is ClassName.Simple -> when (type.name) {
            "Real", "Double" -> 2
            "Integer", "Int", "Bool", "Boolean" -> 1
            else -> 1
        }
        is ClassName.Generic -> {
            1
        }
    }
}

