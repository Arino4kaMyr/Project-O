package semantic.tables

import exceptions.SematicException
import semantic.ClassSymbol
import syntaxer.ClassName

class ClassTable {
    private val classes = mutableMapOf<String, ClassSymbol>()

    fun addClass(classSymbol: ClassSymbol) {
        if (classes.containsKey(classSymbol.name)) {
            throw SematicException("Duplicate class definition: ${classSymbol.name}")
        }
        classes[classSymbol.name] = classSymbol
    }

    fun findClass(name: String): ClassSymbol? = classes[name]

    fun getClass(name: ClassName): ClassSymbol? {
        return when (name) {
            is ClassName.Simple -> classes[name.name]
            is ClassName.Generic -> {
                null
            }
        }
    }

    fun getAllClasses(): Collection<ClassSymbol> = classes.values

    fun contains(name: String): Boolean = classes.containsKey(name)

    fun print() {
        println("\n========== Class Table ==========")
        classes.values.forEach { cls ->
            println("Class: ${cls.name}")
            println("  Parent: ${cls.parentClass?.name ?: "none"}")
            
            println("  Fields:")
            if (cls.fields.isEmpty()) {
                println("    (no fields)")
            } else {
                cls.fields.values.forEach { field ->
                    val typeName = when (field.type) {
                        is ClassName.Simple -> field.type.name
                        is ClassName.Generic -> {
                            val typeArgsStr = field.type.typeArgs.joinToString(", ") { 
                                when (it) {
                                    is ClassName.Simple -> it.name
                                    is ClassName.Generic -> "${it.name}[...]"
                                }
                            }
                            "${field.type.name}[$typeArgsStr]"
                        }
                    }
                    println("    ${field.name} : $typeName")
                }
            }
            
            println("  Methods:")
            if (cls.methods.isEmpty()) {
                println("    (no methods)")
            } else {
                cls.methods.forEach { (methodName, methodList) ->
                    methodList.forEach { method ->
                        val paramsStr = method.params.joinToString(", ") { param ->
                            val paramTypeStr = when (param.type) {
                                is ClassName.Simple -> param.type.name
                                is ClassName.Generic -> {
                                    val typeArgsStr = param.type.typeArgs.joinToString(", ") {
                                        when (it) {
                                            is ClassName.Simple -> it.name
                                            is ClassName.Generic -> "${it.name}[...]"
                                        }
                                    }
                                    "${param.type.name}[$typeArgsStr]"
                                }
                            }
                            "${param.name}: $paramTypeStr"
                        }
                        val returnTypeStr = method.returnType?.let { retType ->
                            when (retType) {
                                is ClassName.Simple -> retType.name
                                is ClassName.Generic -> {
                                    val typeArgsStr = retType.typeArgs.joinToString(", ") {
                                        when (it) {
                                            is ClassName.Simple -> it.name
                                            is ClassName.Generic -> "${it.name}[...]"
                                        }
                                    }
                                    "${retType.name}[$typeArgsStr]"
                                }
                            }
                        } ?: "void"
                        println("    ${method.name}($paramsStr) : $returnTypeStr")
                        
                        println("      Symbol Table:")
                        method.symbolTable.print()
                    }
                }
            }
            println()
        }
        println("=================================\n")
    }
}
