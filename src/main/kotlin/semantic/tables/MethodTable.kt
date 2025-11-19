package semantic.tables

import semantic.ParamSymbol
import semantic.VarSymbol
import syntaxer.ClassName

/**
 * Таблица символов для метода
 */
class MethodTable {

    data class LocalInfo(
        val symbol: VarSymbol,
        val index: Int
    )

    private val locals: MutableMap<String, LocalInfo> = mutableMapOf()
    private var nextIndex: Int = 0

    /**
     * Добавить параметр в таблицу
     */
    fun addParam(param: ParamSymbol) {
        val varSymbol = VarSymbol(param.name, param.type)
        val index = nextIndex
        nextIndex += 1
        locals[param.name] = LocalInfo(varSymbol, index)
    }

    /**
     * Добавить локальную переменную в таблицу
     */
    fun addLocalVariable(name: String, type: ClassName) {
        val varSymbol = VarSymbol(name, type)
        val index = nextIndex
        nextIndex += 1
        locals[name] = LocalInfo(varSymbol, index)
    }

    /**
     * Найти символ по имени
     */
    fun findSymbol(name: String): VarSymbol? = locals[name]?.symbol

    /**
     * Получить индекс локальной переменной/параметра по имени. Нужно для написания команд в Jasmin
     */
    fun getIndex(name: String): Int {
        return locals[name]?.index
            ?: throw IllegalStateException("Local variable '$name' not found in MethodTable")
    }

    /**
     * Проверить, существует ли символ с данным именем
     */
    fun contains(name: String): Boolean = locals.containsKey(name)

    /**
     * Получить все символы
     */
    fun getAllSymbols(): Map<String, VarSymbol> =
        locals.mapValues { it.value.symbol }

    /**
     * Получить все параметры (упрощённо)
     */
    fun getParams(): List<VarSymbol> =
        locals.values.map { it.symbol }

    fun clear() {
        locals.clear()
        nextIndex = 0
    }

    fun print(indent: String = "      ") {
        if (locals.isEmpty()) {
            println("${indent}(no variables)")
        } else {
            locals.values.forEach { info ->
                val symbol = info.symbol
                val typeName = when (symbol.type) {
                    is ClassName.Simple -> symbol.type.name
                }
                println("${indent}${symbol.name} : $typeName (index=${info.index})")
            }
        }
    }
}


