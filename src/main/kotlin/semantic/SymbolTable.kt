package semantic

class SymbolTable {
    private val symbols: MutableList<Symbol> = mutableListOf()

    fun addSymbol(symbol: Symbol) {
        symbols.add(symbol)
    }

    fun getAllSymbols(): List<Symbol> = symbols.toList()

    fun findSymbol(name: String): Symbol? = symbols.firstOrNull { it.name == name }

    fun contains(name: String): Boolean = symbols.any { it.name == name }

    fun clear() {
        symbols.clear()
    }

    /**
     * Печать таблицы символов
     */
    fun print() {
        println("\n========== Symbol Table ==========")
        if (symbols.isEmpty()) {
            println("Тable is empty")
        } else {
            symbols.forEach { symbol ->
                val typeName = when (symbol.type) {
                    is syntaxer.ClassName.Simple -> symbol.type.name
                }
                println("  ${symbol.name} : $typeName")
            }
            println("\nNum of var: ${symbols.size}")
        }
        println("=====================================\n")
    }
}

