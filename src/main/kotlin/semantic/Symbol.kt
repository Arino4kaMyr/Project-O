package semantic

import syntaxer.*

sealed class Symbol(val name: String)

class ClassSymbol(
    name: String,
    val astNode: ClassDecl
) : Symbol(name) {
    val fields = mutableMapOf<String, VarSymbol>()
    val methods = mutableMapOf<String, MutableList<MethodSymbol>>()
    var parentClass: ClassSymbol? = null

    fun findField(fieldName: String): VarSymbol? {
        return fields[fieldName] ?: parentClass?.findField(fieldName)
    }

    fun findMethods(methodName: String): List<MethodSymbol> {
        val localMethods = methods[methodName] ?: emptyList()
        return localMethods
    }

    fun findMethod(methodName: String): MethodSymbol? {
        return methods[methodName]?.firstOrNull() ?: parentClass?.findMethod(methodName)
    }
    
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

    fun findMethodBySignature(name: String, paramTypes: List<ClassName>): MethodSymbol? {
        val list = methods[name] ?: return null
        return list.firstOrNull { m ->
            m.params.map { it.type } == paramTypes
        }
    }

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

    fun isSubclassOf(other: ClassSymbol): Boolean {
        var current: ClassSymbol? = this
        while (current != null) {
            if (current == other) return true
            current = current.parentClass
        }
        return false
    }
}

class VarSymbol(
    name: String,
    val type: ClassName
) : Symbol(name)

class ParamSymbol(
    name: String,
    val type: ClassName
) : Symbol(name)

class MethodSymbol(
    name: String,
    val params: List<ParamSymbol>,
    val returnType: ClassName?,
    val astNode: MemberDecl.MethodDecl?
) : Symbol(name) {
    var ownerClass: ClassSymbol? = null
    val symbolTable = semantic.tables.MethodTable()
}

class ConstructorSymbol(
    val params: List<ParamSymbol>,
    val astNode: MemberDecl.ConstructorDecl
) : Symbol("this") {
    var ownerClass: ClassSymbol? = null
}
