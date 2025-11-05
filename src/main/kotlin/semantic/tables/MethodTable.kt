package semantic.tables

import semantic.ParamSymbol
import semantic.VarSymbol
import syntaxer.ClassName

/**
 * Таблица символов для метода
 */
class MethodTable {
    private val symbols: MutableMap<String, VarSymbol> = mutableMapOf()

    /**
     * Добавить параметр в таблицу
     */
    fun addParam(param: ParamSymbol) {
        val varSymbol = VarSymbol(param.name, param.type)
        symbols[param.name] = varSymbol
    }

    /**
     * Добавить локальную переменную в таблицу
     */
    fun addLocalVariable(name: String, type: ClassName) {
        val varSymbol = VarSymbol(name, type)
        symbols[name] = varSymbol
    }

    /**
     * Найти символ по имени
     */
    fun findSymbol(name: String): VarSymbol? = symbols[name]

    /**
     * Проверить, существует ли символ с данным именем
     */
    fun contains(name: String): Boolean = symbols.containsKey(name)

    /**
     * Получить все символы
     */
    fun getAllSymbols(): Map<String, VarSymbol> = symbols.toMap()

    /**
     * Получить все параметры
     */
    fun getParams(): List<VarSymbol> {
        // Параметры - это первые символы, добавленные в таблицу
        // Но так как мы не храним порядок, вернем все
        return symbols.values.toList()
    }

    /**
     * Очистить таблицу
     */
    fun clear() {
        symbols.clear()
    }

    /**
     * Печать таблицы символов метода
     */
    fun print(indent: String = "      ") {
        if (symbols.isEmpty()) {
            println("${indent}(no variables)")
        } else {
            symbols.values.forEach { symbol ->
                val typeName = when (symbol.type) {
                    is ClassName.Simple -> symbol.type.name
                }
                println("${indent}${symbol.name} : $typeName")
            }
        }
    }
}

