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
            is ClassName.Simple -> classes[name.name] //only simple class exists in compiler
        }
    }

    fun getAllClasses(): Collection<ClassSymbol> = classes.values

    fun contains(name: String): Boolean = classes.containsKey(name)

    fun print() {
        println("\n========== Class Table ==========")
        classes.values.forEach { cls ->
            println("Class: ${cls.name}")
            println("  Parent: ${cls.parentClass?.name ?: "none"}")
            println("  Fields: ${cls.fields.keys.joinToString()}")
            println("  Methods: ${cls.methods.keys.joinToString()}")
            println()
        }
        println("=================================\n")
    }
}
