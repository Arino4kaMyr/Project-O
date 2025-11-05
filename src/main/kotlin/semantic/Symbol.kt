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
    val methods = mutableMapOf<String, MutableList<MethodSymbol>>()  // Поддержка перегрузки методов
    var parentClass: ClassSymbol? = null

    // Проверка на наличие поля/метода с учётом наследования
    fun findField(fieldName: String): VarSymbol? {
        return fields[fieldName] ?: parentClass?.findField(fieldName)
    }

    // Найти все методы с данным именем (с учетом наследования)
    fun findMethods(methodName: String): List<MethodSymbol> {
        val localMethods = methods[methodName] ?: emptyList()
        val parentMethods = parentClass?.findMethods(methodName) ?: emptyList()
        return localMethods + parentMethods
    }

    // Найти метод по имени (для обратной совместимости, возвращает первый)
    fun findMethod(methodName: String): MethodSymbol? {
        return methods[methodName]?.firstOrNull() ?: parentClass?.findMethod(methodName)
    }
    
    // Проверка, есть ли метод с такой же сигнатурой
    fun hasMethodWithSignature(methodName: String, paramTypes: List<ClassName>): Boolean {
        val methodsWithName = findMethods(methodName)
        return methodsWithName.any { method ->
            if (method.params.size != paramTypes.size) {
                false
            } else {
                method.params.mapIndexed { index, param -> param.type }.zip(paramTypes).all { (type1, type2) ->
                    type1 is ClassName.Simple && type2 is ClassName.Simple && type1.name == type2.name
                }
            }
        }
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
    val symbolTable = semantic.tables.MethodTable()  // Таблица символов метода
}

// ============ Символ конструктора ============
class ConstructorSymbol(
    val params: List<ParamSymbol>,
    val astNode: MemberDecl.ConstructorDecl
) : Symbol("this") {
    var ownerClass: ClassSymbol? = null
}
