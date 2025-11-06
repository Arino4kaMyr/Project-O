package semantic.tables

import exceptions.SematicException
import semantic.ClassSymbol
import syntaxer.ClassName

class ClassTable {
    private val classes = mutableMapOf<String, ClassSymbol>()

    /**
     * Добавить класс в таблицу
     */
    fun addClass(classSymbol: ClassSymbol) {
        if (classes.containsKey(classSymbol.name)) {
            throw SematicException("Duplicate class definition: ${classSymbol.name}")
        }
        classes[classSymbol.name] = classSymbol
    }

    /**
     * Найти класс по имени
     */
    fun findClass(name: String): ClassSymbol? = classes[name]

    /**
     * Получить класс по типу имени
     */
    fun getClass(name: ClassName): ClassSymbol? {
        return when (name) {
            is ClassName.Simple -> classes[name.name] //only simple class exists in compiler
        }
    }

    /**
     * Получить все классы
     */
    fun getAllClasses(): Collection<ClassSymbol> = classes.values

    /**
     * Проверить, существует ли класс
     */
    fun contains(name: String): Boolean = classes.containsKey(name)

    /**
     * Напечатать таблицу классов
     */
    fun print() {
        println("\n========== Class Table ==========")
        classes.values.forEach { cls ->
            println("Class: ${cls.name}")
            println("  Parent: ${cls.parentClass?.name ?: "none"}")
            
            // Таблица переменных класса (поля)
            println("  Fields:")
            if (cls.fields.isEmpty()) {
                println("    (no fields)")
            } else {
                cls.fields.values.forEach { field ->
                    val typeName = when (field.type) {
                        is ClassName.Simple -> field.type.name
                    }
                    println("    ${field.name} : $typeName")
                }
            }
            
            // Таблица методов класса
            println("  Methods:")
            if (cls.methods.isEmpty()) {
                println("    (no methods)")
            } else {
                cls.methods.forEach { (methodName, methodList) ->
                    methodList.forEach { method ->
                        val paramsStr = method.params.joinToString(", ") { "${it.name}: ${when(it.type) { is ClassName.Simple -> it.type.name } }" }
                        val returnTypeStr = method.returnType?.let { when(it) { is ClassName.Simple -> it.name } } ?: "void"
                        println("    ${method.name}($paramsStr) : $returnTypeStr")
                        
                        // Таблица символов метода
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
