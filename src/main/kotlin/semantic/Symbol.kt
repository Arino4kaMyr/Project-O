package semantic

import syntaxer.*

// ============ Базовый класс символов ============
sealed class Symbol(val name: String)

// ============ Символ класса ============
class ClassSymbol(
    name: String,
    val astNode: ClassDecl
) : Symbol(name) {
    val fields = mutableMapOf<String, VarSymbol>()
    val methods = mutableMapOf<String, MethodSymbol>()
    var parentClass: ClassSymbol? = null

    // Проверка на наличие поля/метода с учётом наследования
    fun findField(fieldName: String): VarSymbol? {
        return fields[fieldName] ?: parentClass?.findField(fieldName)
    }

    fun findMethod(methodName: String): MethodSymbol? {
        return methods[methodName] ?: parentClass?.findMethod(methodName)
    }

    // Проверка на циклы наследования
    fun hasInheritanceCycle(): Boolean {
        val visited = mutableSetOf<ClassSymbol>()
        var current: ClassSymbol? = this

        while (current != null) {
            if (current in visited) return true
            visited.add(current)
            current = current.parentClass
        }
        return false
    }

    // Проверка на подтип
    fun isSubclassOf(other: ClassSymbol): Boolean {
        var current: ClassSymbol? = this
        while (current != null) {
            if (current == other) return true
            current = current.parentClass
        }
        return false
    }
}

// ============ Символ переменной/поля ============
class VarSymbol(
    name: String,
    val type: ClassName
) : Symbol(name)

// ============ Символ параметра ============
class ParamSymbol(
    name: String,
    val type: ClassName
) : Symbol(name)

// ============ Символ метода ============
class MethodSymbol(
    name: String,
    val params: List<ParamSymbol>,
    val returnType: ClassName?,
    val astNode: MemberDecl.MethodDecl
) : Symbol(name) {
    var ownerClass: ClassSymbol? = null
}

// ============ Символ конструктора ============
class ConstructorSymbol(
    val params: List<ParamSymbol>,
    val astNode: MemberDecl.ConstructorDecl
) : Symbol("this") {
    var ownerClass: ClassSymbol? = null
}
