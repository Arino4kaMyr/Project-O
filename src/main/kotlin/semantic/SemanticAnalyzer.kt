package semantic

import exceptions.SematicException
import semantic.tables.ClassTable
import syntaxer.*

class SemanticAnalyzer(private var program: Program) {
    val classTable = ClassTable()
    
    /**
     * Получить оптимизированный AST
     */
    fun getOptimizedProgram(): Program {
        return program
    }

    fun analyze() {
        // Этап 1: Создаем все классы
        program.classes.forEach { classDecl ->
            val className = (classDecl.name as ClassName.Simple).name
            val classSymbol = ClassSymbol(className, classDecl)
            classTable.addClass(classSymbol)
        }

        // Проверка, что существует класс Program
        if (!classTable.contains("Program")) throw SematicException("Program class doesn't exist")

        // Этап 2: Разрешение наследования
        resolveInheritance()

        // Этап 3: Заполняем таблицы символов каждого класса
        program.classes.forEach { classDecl ->
            val className = (classDecl.name as ClassName.Simple).name
            val classSymbol = classTable.findClass(className)!!
            analyzeClass(classDecl, classSymbol)
        }

        // Этап 4: Разрешение ссылок (Name Resolution)
        resolveReferences()

        // Этап 5: Проверка типов (Type Checking)
        typeCheck()

        // Этап 6: Оптимизации AST
        optimize()
    }

    /**
     * Этап 2: Разрешение наследования
     * - Связывает parentClass в ClassSymbol
     * - Проверяет существование родительских классов
     * - Проверяет циклы наследования
     */
    private fun resolveInheritance() {
        program.classes.forEach { classDecl ->
            val className = (classDecl.name as ClassName.Simple).name
            val classSymbol = classTable.findClass(className)!!

            // Если у класса есть родитель
            classDecl.parent?.let { parentName ->
                when (parentName) {
                    is ClassName.Simple -> {
                        // Проверка на самонаследование
                        if (parentName.name == className) {
                            throw exceptions.SematicException(
                                "Class '${className}' cannot extend itself"
                            )
                        }

                        val parentClass = classTable.findClass(parentName.name)

                        // Проверка существования родительского класса
                        if (parentClass == null) {
                            throw exceptions.SematicException(
                                "Class '${className}' extends unknown class '${parentName.name}'"
                            )
                        }

                        // Связываем родительский класс
                        classSymbol.parentClass = parentClass
                    }
                    is ClassName.Generic -> {
                        // Дженерики в наследовании пока не поддерживаются
                        throw exceptions.SematicException(
                            "Generic types in inheritance are not supported yet: ${parentName.name}[...]"
                        )
                    }
                }
            }
        }

        // Проверка циклов наследования
        program.classes.forEach { classDecl ->
            val className = (classDecl.name as ClassName.Simple).name
            val classSymbol = classTable.findClass(className)!!

            if (classSymbol.hasInheritanceCycle()) {
                throw exceptions.SematicException(
                    "Inheritance cycle detected in class '${className}'"
                )
            }
        }
    }

    private fun analyzeClass(classDecl: ClassDecl, classSymbol: ClassSymbol) {
        classDecl.members.forEach { member ->
            when (member) {
                is MemberDecl.VarDecl -> {
                    // Проверка на дублирование полей
                    if (classSymbol.fields.containsKey(member.name)) {
                        throw exceptions.SematicException(
                            "Duplicate field '${member.name}' in class '${classSymbol.name}'"
                        )
                    }

                    // Поле класса - добавляем в таблицу полей класса
                    val varType = member.type
                    val fieldSymbol = VarSymbol(member.name, varType)
                    classSymbol.fields[member.name] = fieldSymbol
                }
                is MemberDecl.MethodDecl -> {
                    // Проверка на дублирование параметров
                    val paramNames = member.params.map { it.name }
                    val duplicateParams = paramNames.groupingBy { it }.eachCount().filter { it.value > 1 }
                    if (duplicateParams.isNotEmpty()) {
                        throw exceptions.SematicException(
                            "Duplicate parameter '${duplicateParams.keys.first()}' in method '${member.name}' of class '${classSymbol.name}'"
                        )
                    }

                    // Создаем символ метода
                    val paramSymbols = member.params.map { param ->
                        ParamSymbol(param.name, param.type)
                    }
                    val paramTypes = paramSymbols.map { it.type }

                    // Проверка на переопределение метода родителя
                    classSymbol.parentClass?.let { parent ->
                        val overridden = parent.findMethodBySignature(member.name, paramTypes)
                        if (overridden != null) {
                            // Проверяем совместимость типов возвращаемых значений
                            val parentReturn = overridden.returnType
                            val childReturn = member.returnType

                            if (parentReturn != null && childReturn != null) {
                                if (!isAssignable(childReturn, parentReturn, classTable)) {
                                    throw exceptions.SematicException(
                                        "Return type of overriding method '${member.name}' in class '${classSymbol.name}' " +
                                                "is not compatible with return type of parent method"
                                    )
                                }
                            }
                        }
                    }

                    // Проверка на дублирование сигнатуры метода (имя + типы параметров)
                    if (classSymbol.hasMethodWithSignature(member.name, paramTypes)) {
                        throw exceptions.SematicException(
                            "Method '${member.name}' with signature (${paramTypes.joinToString(", ") { classNameToString(it) }}) already exists in class '${classSymbol.name}'"
                        )
                    }

                    val methodSymbol = MethodSymbol(
                        member.name,
                        paramSymbols,
                        member.returnType,
                        member
                    )
                    methodSymbol.ownerClass = classSymbol

                    // Добавляем параметры в таблицу символов метода
                    paramSymbols.forEach { param ->
                        methodSymbol.symbolTable.addParam(param)
                    }

                    // Анализируем тело метода и собираем локальные переменные
                    member.body?.let { body ->
                        analyzeMethodBody(body, methodSymbol.symbolTable, classSymbol.name, member.name)
                    }

                    // Добавляем метод в таблицу методов класса (поддержка перегрузки)
                    if (!classSymbol.methods.containsKey(member.name)) {
                        classSymbol.methods[member.name] = mutableListOf()
                    }
                    classSymbol.methods[member.name]!!.add(methodSymbol)
                }
                is MemberDecl.ConstructorDecl -> {
                }
            }
        }
    }

    private fun analyzeMethodBody(
        body: MethodBody,
        symbolTable: semantic.tables.MethodTable,
        className: String = "",
        methodName: String = ""
    ) {
        when (body) {
            is MethodBody.BlockBody -> {
                // Локальные переменные метода
                body.vars.forEach { varDecl ->
                    // Проверка на дублирование локальных переменных
                    if (symbolTable.contains(varDecl.name)) {
                        val location = if (className.isNotEmpty() && methodName.isNotEmpty()) {
                            " in method '${methodName}' of class '${className}'"
                        } else {
                            ""
                        }
                        throw exceptions.SematicException(
                            "Duplicate local variable '${varDecl.name}'$location"
                        )
                    }

                    val varType = varDecl.type
                    symbolTable.addLocalVariable(varDecl.name, varType)
                }
                
                // Анализируем вложенные блоки (if/while)
                body.stmts.forEach { stmt ->
                    when (stmt) {
                        is Stmt.If -> {
                            analyzeMethodBody(stmt.thenBody, symbolTable, className, methodName)
                            stmt.elseBody?.let { elseBody ->
                                analyzeMethodBody(elseBody, symbolTable, className, methodName)
                            }
                        }
                        is Stmt.While -> {
                            analyzeMethodBody(stmt.body, symbolTable, className, methodName)
                        }
                        else -> {
                            // Другие statements не содержат объявлений переменных
                        }
                    }
                }
            }
        }
    }


    /**
     * Этап 4: Разрешение ссылок (Name Resolution)
     * - Проверяет, что все используемые переменные объявлены
     * - Проверяет, что все вызываемые методы существуют
     * - Проверяет, что все используемые поля существуют
     */
    private fun resolveReferences() {
        program.classes.forEach { classDecl ->
            val className = (classDecl.name as ClassName.Simple).name
            val classSymbol = classTable.findClass(className)!!

            classDecl.members.forEach { member ->
                when (member) {
                    is MemberDecl.MethodDecl -> {
                        member.body?.let { body ->
                            // Находим конкретный метод по сигнатуре
                            val paramTypes = member.params.map { it.type }
                            val methodSymbol = classSymbol.findMethods(member.name).find { method ->
                                method.params.size == paramTypes.size &&
                                method.params.mapIndexed { index, param -> param.type }.zip(paramTypes).all { (type1, type2) ->
                                    classNameEquals(type1, type2)
                                }
                            }

                            if (methodSymbol != null) {
                                resolveMethodBody(body, methodSymbol.symbolTable, classSymbol, className, member.name)
                            }
                        }
                    }
                    is MemberDecl.ConstructorDecl -> {
                        // Можно добавить проверку конструкторов позже
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * Разрешение ссылок в теле метода
     */
    private fun resolveMethodBody(
        body: MethodBody,
        symbolTable: semantic.tables.MethodTable,
        classSymbol: ClassSymbol,
        className: String,
        methodName: String
    ) {
        when (body) {
            is MethodBody.BlockBody -> {
                // Проверяем все statements
                body.stmts.forEach { stmt ->
                    resolveStmt(stmt, symbolTable, classSymbol, className, methodName)
                }
            }
        }
    }

    /**
     * Разрешение ссылок в statement
     */
    private fun resolveStmt(
        stmt: Stmt,
        symbolTable: semantic.tables.MethodTable,
        classSymbol: ClassSymbol,
        className: String,
        methodName: String
    ) {
        when (stmt) {
            is Stmt.Assignment -> {
                // Проверяем, что переменная объявлена
                resolveVariable(stmt.target, symbolTable, classSymbol, className, methodName)
                // Проверяем выражение
                resolveExpr(stmt.expr, symbolTable, classSymbol, className, methodName)
            }
            is Stmt.While -> {
                resolveExpr(stmt.cond, symbolTable, classSymbol, className, methodName)
                resolveMethodBody(stmt.body, symbolTable, classSymbol, className, methodName)
            }
            is Stmt.If -> {
                resolveExpr(stmt.cond, symbolTable, classSymbol, className, methodName)
                resolveMethodBody(stmt.thenBody, symbolTable, classSymbol, className, methodName)
                stmt.elseBody?.let { elseBody ->
                    resolveMethodBody(elseBody, symbolTable, classSymbol, className, methodName)
                }
            }
            is Stmt.Return -> {
                stmt.expr?.let { expr ->
                    resolveExpr(expr, symbolTable, classSymbol, className, methodName)
                }
            }
            is Stmt.ExprStmt -> {
                resolveExpr(stmt.expr, symbolTable, classSymbol, className, methodName)
            }
        }
    }

    /**
     * Разрешение ссылок в выражении
     */
    private fun resolveExpr(
        expr: Expr,
        symbolTable: semantic.tables.MethodTable,
        classSymbol: ClassSymbol,
        className: String,
        methodName: String
    ) {
        when (expr) {
            is Expr.Identifier -> {
                // Проверяем, что переменная объявлена
                resolveVariable(expr.name, symbolTable, classSymbol, className, methodName)
            }
            is Expr.Call -> {
                // Проверяем receiver (если есть)
                expr.receiver?.let { receiver ->
                    resolveExpr(receiver, symbolTable, classSymbol, className, methodName)
                }

                if (expr.receiver == null && expr.method in BUILDIN_METHODS) {
                    // просто проверяем аргументы, что они корректные выражения
                    expr.args.forEach { arg ->
                        resolveExpr(arg, symbolTable, classSymbol, className, methodName)
                    }
                    return
                }

                // Проверяем вызов метода
                if (expr.receiver == null) {
                    // Проверяем, не является ли это встроенным методом (еще раз, на всякий случай)
                    if (expr.method !in BUILDIN_METHODS) {
                        // Вызов метода без receiver - проверяем, что метод существует в классе
                        val methods = classSymbol.findMethods(expr.method)
                        if (methods.isEmpty()) {
                            throw exceptions.SematicException(
                                "Unknown method '${expr.method}' in class '${className}'"
                            )
                        }
                    }
                }
                // Если receiver есть, проверка метода будет на этапе проверки типов

                // Проверяем аргументы
                expr.args.forEach { arg ->
                    resolveExpr(arg, symbolTable, classSymbol, className, methodName)
                }
            }
            is Expr.FieldAccess -> {
                // Проверяем receiver
                resolveExpr(expr.receiver, symbolTable, classSymbol, className, methodName)
                // Проверка существования поля будет на этапе проверки типов
            }
            is Expr.This -> {
                // this всегда валиден в методах
            }
            is Expr.IntLit,
            is Expr.RealLit,
            is Expr.BoolLit,
            is Expr.ClassNameExpr -> {
                // Литералы и типы не требуют разрешения
            }
        }
    }

    /**
     * Проверка, что переменная объявлена
     */
    private fun resolveVariable(
        varName: String,
        symbolTable: semantic.tables.MethodTable,
        classSymbol: ClassSymbol,
        className: String,
        methodName: String
    ) {
        // Обработка this.field
        val actualVarName = if (varName.startsWith("this.")) {
            varName.removePrefix("this.")
        } else {
            varName
        }
        
        // Если это this.field, сразу ищем в полях класса
        if (varName.startsWith("this.")) {
            if (classSymbol.findField(actualVarName) != null) {
                return
            }
            throw exceptions.SematicException(
                "Unknown field '${actualVarName}' in class '${className}'"
            )
        }
        
        // Сначала ищем в локальных переменных метода (параметры + локальные)
        if (symbolTable.contains(actualVarName)) {
            return
        }

        // Затем ищем в полях класса (с учетом наследования)
        if (classSymbol.findField(actualVarName) != null) {
            return
        }

        // Переменная не найдена
        throw exceptions.SematicException(
            "Unknown variable '${actualVarName}' in method '${methodName}' of class '${className}'"
        )
    }

    /**
     * Этап 5: Проверка типов (Type Checking)
     * - Проверяет типы в присваиваниях
     * - Проверяет типы аргументов при вызове методов
     * - Проверяет возвращаемые типы
     * - Проверяет методы и поля с receiver
     */
    private fun typeCheck() {
        program.classes.forEach { classDecl ->
            val className = (classDecl.name as ClassName.Simple).name
            val classSymbol = classTable.findClass(className)!!

            classDecl.members.forEach { member ->
                when (member) {
                    is MemberDecl.MethodDecl -> {
                        member.body?.let { body ->
                            // Находим конкретный метод по сигнатуре
                            val paramTypes = member.params.map { it.type }
                            val methodSymbol = classSymbol.findMethods(member.name).find { method ->
                                method.params.size == paramTypes.size &&
                                method.params.mapIndexed { index, param -> param.type }.zip(paramTypes).all { (type1, type2) ->
                                    classNameEquals(type1, type2)
                                }
                            }

                            if (methodSymbol != null) {
                                typeCheckMethodBody(body, methodSymbol.symbolTable, classSymbol, className, methodSymbol)
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * Проверка типов в теле метода
     */
    private fun typeCheckMethodBody(
        body: MethodBody,
        symbolTable: semantic.tables.MethodTable,
        classSymbol: ClassSymbol,
        className: String,
        methodSymbol: MethodSymbol
    ) {
        when (body) {
            is MethodBody.BlockBody -> {
                body.stmts.forEach { stmt ->
                    typeCheckStmt(stmt, symbolTable, classSymbol, className, methodSymbol)
                }
            }
        }
    }

    /**
     * Проверка типов в statement
     */
    private fun typeCheckStmt(
        stmt: Stmt,
        symbolTable: semantic.tables.MethodTable,
        classSymbol: ClassSymbol,
        className: String,
        methodSymbol: MethodSymbol
    ) {
        when (stmt) {
            is Stmt.Assignment -> {
                // Получаем тип целевой переменной
                val targetType = getVariableType(stmt.target, symbolTable, classSymbol, className, methodSymbol.name)
                // Получаем тип выражения
                val exprType = getTypeOfExpr(stmt.expr, symbolTable, classSymbol, className, methodSymbol.name)

                // Проверяем совместимость типов
                if (!isAssignable(exprType, targetType, classTable)) {
                    throw exceptions.SematicException(
                        "Type mismatch: cannot assign ${classNameToString(exprType)} to ${classNameToString(targetType)} in assignment '${stmt.target}'"
                    )
                }
            }
            is Stmt.While -> {
                val condType = getTypeOfExpr(stmt.cond, symbolTable, classSymbol, className, methodSymbol.name)
                // Условие должно быть булевым (или можно добавить проверку)
                typeCheckMethodBody(stmt.body, symbolTable, classSymbol, className, methodSymbol)
            }
            is Stmt.If -> {
                val condType = getTypeOfExpr(stmt.cond, symbolTable, classSymbol, className, methodSymbol.name)
                typeCheckMethodBody(stmt.thenBody, symbolTable, classSymbol, className, methodSymbol)
                stmt.elseBody?.let { elseBody ->
                    typeCheckMethodBody(elseBody, symbolTable, classSymbol, className, methodSymbol)
                }
            }
            is Stmt.Return -> {
                stmt.expr?.let { expr ->
                    val exprType = getTypeOfExpr(expr, symbolTable, classSymbol, className, methodSymbol.name)
                    val returnType = methodSymbol.returnType

                    if (returnType != null) {
                        if (!isAssignable(exprType, returnType, classTable)) {
                            throw exceptions.SematicException(
                                "Type mismatch in return: expected ${classNameToString(returnType)}, got ${classNameToString(exprType)}"
                            )
                        }
                    }
                } ?: run {
                    // return без выражения - должен быть void метод
                    if (methodSymbol.returnType != null) {
                        throw exceptions.SematicException(
                            "Method '${methodSymbol.name}' must return ${classNameToString(methodSymbol.returnType!!)}"
                        )
                    }
                }
            }
            is Stmt.ExprStmt -> {
                getTypeOfExpr(stmt.expr, symbolTable, classSymbol, className, methodSymbol.name)
            }
        }
    }

    /**
     * Определение типа выражения
     */
    private fun getTypeOfExpr(
        expr: Expr,
        symbolTable: semantic.tables.MethodTable,
        classSymbol: ClassSymbol,
        className: String,
        methodName: String
    ): ClassName {
        return when (expr) {
            is Expr.IntLit -> ClassName.Simple("Integer")
            is Expr.RealLit -> ClassName.Simple("Real")
            is Expr.BoolLit -> ClassName.Simple("Bool")
            is Expr.Identifier -> {
                getVariableType(expr.name, symbolTable, classSymbol, className, methodName)
            }
            is Expr.This -> {
                ClassName.Simple(className)
            }
            is Expr.Call -> {
                // Проверяем встроенные методы СРАЗУ, до любых других проверок
                if (expr.receiver == null && expr.method in BUILDIN_METHODS) {
                    // Получаем типы аргументов для проверки
                    expr.args.forEach { arg ->
                        getTypeOfExpr(arg, symbolTable, classSymbol, className, methodName)
                    }
                    return ClassName.Simple("void")
                }
                
                // Получаем типы аргументов
                val argTypes = expr.args.map { arg ->
                    getTypeOfExpr(arg, symbolTable, classSymbol, className, methodName)
                }

                if (expr.receiver == null) {
                    // Проверяем, не является ли это встроенным методом (явная проверка для надежности)
                    if (expr.method in BUILDIN_METHODS) {
                        return ClassName.Simple("void")
                    }

                    // Вызов метода без receiver - разрешение перегрузки
                    // callerClass = classSymbol (вызываем из того же класса)
                    val method = resolveMethodCall(classSymbol, expr.method, argTypes, className, callerClass = classSymbol)

                    method.returnType ?: ClassName.Simple("void")
                } else {
                    // Вызов метода с receiver
                    val receiverType = getTypeOfExpr(expr.receiver, symbolTable, classSymbol, className, methodName)
                    val receiverClass = classTable.getClass(receiverType)

                    if (receiverClass == null) {
                        // Встроенный тип (Integer, Real, Bool, Array) - определяем тип возврата
                        val receiverTypeName = when (receiverType) {
                            is ClassName.Simple -> receiverType.name
                            is ClassName.Generic -> receiverType.name  // Array
                        }
                        if (receiverTypeName != null) {
                            val argType = if (argTypes.isNotEmpty()) argTypes[0] else null
                            return getBuiltinMethodReturnType(receiverType, receiverTypeName, expr.method, argType)
                        }
                        return ClassName.Simple("Unknown")
                    }

                    // Разрешение перегрузки для метода с receiver
                    // callerClass = classSymbol (класс, из которого вызывается метод)
                    val method = resolveMethodCall(receiverClass, expr.method, argTypes, receiverClass.name, callerClass = classSymbol)

                    method.returnType ?: ClassName.Simple("void")
                }
            }
            is Expr.FieldAccess -> {
                val receiverType = getTypeOfExpr(expr.receiver, symbolTable, classSymbol, className, methodName)
                val receiverClass = classTable.getClass(receiverType)

                if (receiverClass == null) {
                    // Встроенный тип (Array, Integer, etc.) - пропускаем проверку полей
                    // Возвращаем Unknown, так как не можем определить тип
                    return ClassName.Simple("Unknown")
                }

                val field = receiverClass.findField(expr.name)
                if (field == null) {
                    throw exceptions.SematicException(
                        "Field '${expr.name}' not found in class '${receiverClass.name}'"
                    )
                }

                field.type
            }
            is Expr.ClassNameExpr -> {
                expr.cn
            }
        }
    }

    /**
     * Получить тип переменной
     */
    private fun getVariableType(
        varName: String,
        symbolTable: semantic.tables.MethodTable,
        classSymbol: ClassSymbol,
        className: String,
        methodName: String
    ): ClassName {
        // Обработка this.field
        val actualVarName = if (varName.startsWith("this.")) {
            varName.removePrefix("this.")
        } else {
            varName
        }
        
        // Если это this.field, сразу ищем в полях класса
        if (varName.startsWith("this.")) {
            val field = classSymbol.findField(actualVarName)
            if (field != null) {
                return field.type
            }
            throw exceptions.SematicException(
                "Unknown field '${actualVarName}' in class '${className}'"
            )
        }
        
        // Сначала ищем в локальных переменных
        val localVar = symbolTable.findSymbol(actualVarName)
        if (localVar != null) {
            return localVar.type
        }

        // Затем ищем в полях класса
        val field = classSymbol.findField(actualVarName)
        if (field != null) {
            return field.type
        }

        throw exceptions.SematicException(
            "Variable '${actualVarName}' not found in method '${methodName}' of class '${className}'"
        )
    }

    /**
     * Проверка совместимости типов (можно ли присвоить fromType к toType)
     */
    private fun isAssignable(fromType: ClassName, toType: ClassName, classTable: ClassTable): Boolean {
        // Если типы одинаковые
        if (classNameEquals(fromType, toType)) {
            return true
        }

        // Unknown тип (для встроенных типов) - разрешаем присваивание
        when (fromType) {
            is ClassName.Simple -> {
                if (fromType.name == "Unknown") {
                    return true  // Unknown можно присвоить любому типу
                }
            }
            is ClassName.Generic -> {
                // Для дженериков проверяем совместимость позже
            }
        }

        when (toType) {
            is ClassName.Simple -> {
                if (toType.name == "Unknown") {
                    return true  // Любой тип можно присвоить Unknown
                }
            }
            is ClassName.Generic -> {
                // Для дженериков проверяем совместимость позже
            }
        }

        // Проверка наследования (fromType является подтипом toType)
        when (fromType) {
            is ClassName.Simple -> {
                val fromClass = classTable.findClass(fromType.name)
                when (toType) {
                    is ClassName.Simple -> {
                        val toClass = classTable.findClass(toType.name)
                        if (fromClass != null && toClass != null) {
                            return fromClass.isSubclassOf(toClass)
                        }
                    }
                    is ClassName.Generic -> {
                        // Дженерики не могут наследоваться от обычных классов
                        return false
                    }
                }
            }
            is ClassName.Generic -> {
                // Дженерики могут быть присвоены только дженерикам того же типа
                if (toType is ClassName.Generic) {
                    // Проверяем базовый тип и типы аргументов
                    if (fromType.name == toType.name && 
                        fromType.typeArgs.size == toType.typeArgs.size) {
                        // Проверяем совместимость типов аргументов
                        return fromType.typeArgs.zip(toType.typeArgs).all { (from, to) ->
                            isAssignable(from, to, classTable)
                        }
                    }
                }
                return false
            }
        }

        return false
    }

    /**
     * Сравнение имен классов (поддерживает дженерики)
     */
    private fun classNameEquals(type1: ClassName, type2: ClassName): Boolean {
        return when {
            type1 is ClassName.Simple && type2 is ClassName.Simple -> type1.name == type2.name
            type1 is ClassName.Generic && type2 is ClassName.Generic -> {
                type1.name == type2.name && 
                type1.typeArgs.size == type2.typeArgs.size &&
                type1.typeArgs.zip(type2.typeArgs).all { (t1, t2) -> classNameEquals(t1, t2) }
            }
            else -> false
        }
    }

    /**
     * Разрешение вызова метода (Method Resolution)
     * Выбирает правильный метод из перегруженных на основе типов аргументов
     * @param classSymbol класс-владелец метода
     * @param methodName имя метода
     * @param argTypes типы аргументов
     * @param context контекст для сообщений об ошибках
     * @param callerClass класс, из которого вызывается метод (для проверки доступа)
     */
    private fun resolveMethodCall(
        classSymbol: ClassSymbol,
        methodName: String,
        argTypes: List<ClassName>,
        context: String,
        callerClass: ClassSymbol? = null
    ): MethodSymbol {
        // Получаем все методы с данным именем
        val candidates = classSymbol.findMethods(methodName)

        if (candidates.isEmpty()) {
            throw exceptions.SematicException(
                "No suitable method '${methodName}' found in class '${context}'"
            )
        }

        // Фильтруем методы по количеству параметров
        val matchingCount = candidates.filter { it.params.size == argTypes.size }

        if (matchingCount.isEmpty()) {
            val paramCounts = candidates.map { it.params.size }.distinct().sorted()
            throw exceptions.SematicException(
                "Method '${methodName}' with ${argTypes.size} arguments not found in class '${context}'. " +
                "Available overloads have ${paramCounts.joinToString(", ")} ${if (paramCounts.size == 1) "argument" else "arguments"}"
            )
        }

        // Ищем точное совпадение типов
        val exactMatch = matchingCount.find { method ->
            method.params.size == argTypes.size &&
            method.params.mapIndexed { index, param -> param.type }.zip(argTypes).all { (type1, type2) ->
                classNameEquals(type1, type2)
            }
        }
        if (exactMatch != null) {
            return exactMatch
        }

        // Ищем совместимые методы (где аргументы можно присвоить параметрам)
        val compatibleMethods = matchingCount.filter { method ->
            method.params.mapIndexed { index, param ->
                isAssignable(argTypes[index], param.type, classTable)
            }.all { it }
        }

        if (compatibleMethods.isEmpty()) {
            val expectedTypes = matchingCount.map { method ->
                method.params.joinToString(", ") { classNameToString(it.type) }
            }.distinct()
            throw exceptions.SematicException(
                "No suitable overload found for method '${methodName}' with arguments (${argTypes.joinToString(", ") { classNameToString(it) }}) in class '${context}'. " +
                "Expected: ${expectedTypes.joinToString(" or ")}"
            )
        }

        if (compatibleMethods.size > 1) {
            // Амбигуация - несколько методов подходят
            val signatures = compatibleMethods.map { method ->
                method.params.joinToString(", ") { classNameToString(it.type) }
            }.distinct()
            throw exceptions.SematicException(
                "Ambiguous method call: multiple overloads of '${methodName}' match arguments (${argTypes.joinToString(", ") { classNameToString(it) }}) in class '${context}'. " +
                "Candidates: ${signatures.joinToString("; ")}"
            )
        }

        // Методы всегда доступны (модификаторы доступа для методов не поддерживаются)
        return compatibleMethods.first()
    }

    /**
     * Преобразование ClassName в строку
     */
    private fun classNameToString(className: ClassName): String {
        return when (className) {
            is ClassName.Simple -> className.name
            is ClassName.Generic -> {
                val typeArgsStr = className.typeArgs.joinToString(", ") { classNameToString(it) }
                "${className.name}[$typeArgsStr]"
            }
        }
    }

    /**
     * Определить тип возврата для встроенных методов с учетом перегрузок
     */
    private fun getBuiltinMethodReturnType(
        receiverType: ClassName,
        receiverTypeName: String,
        methodName: String,
        argType: ClassName?
    ): ClassName {
        return when (receiverTypeName) {
            "Integer" -> {
                when (methodName) {
                    "toReal" -> ClassName.Simple("Real")
                    "toBoolean" -> ClassName.Simple("Bool")
                    "UnaryMinus" -> ClassName.Simple("Integer")
                    "Plus", "Mult", "Minus", "Div", "Rem" -> {
                        if (argType is ClassName.Simple && argType.name == "Real") {
                            ClassName.Simple("Real")
                        } else {
                            ClassName.Simple("Integer")
                        }
                    }
                    "Equal", "NotEqual", "Less", "Greater", "LessEqual", "GreaterEqual" -> {
                        ClassName.Simple("Bool")
                    }
                    else -> ClassName.Simple("Unknown")
                }
            }
            "Real" -> {
                when (methodName) {
                    "toInteger" -> ClassName.Simple("Integer")
                    "UnaryMinus" -> ClassName.Simple("Real")
                    "Plus", "Mult", "Minus", "Div", "Rem" -> {
                        ClassName.Simple("Real")
                    }
                    "Equal", "NotEqual", "Less", "Greater", "LessEqual", "GreaterEqual" -> {
                        ClassName.Simple("Bool")
                    }
                    else -> ClassName.Simple("Unknown")
                }
            }
            "Bool", "Boolean" -> {
                when (methodName) {
                    "toInteger" -> ClassName.Simple("Integer")
                    "Equal", "NotEqual", "And", "Or", "Xor", "Not" -> {
                        ClassName.Simple("Bool")
                    }
                    else -> ClassName.Simple("Unknown")
                }
            }
            "Array" -> {
                when (methodName) {
                    "Length" -> ClassName.Simple("Integer")
                    "get" -> {
                        // get возвращает тип элемента массива
                        if (receiverType is ClassName.Generic && receiverType.typeArgs.isNotEmpty()) {
                            receiverType.typeArgs[0]  // Array[T].get() -> T
                        } else {
                            ClassName.Simple("Unknown")
                        }
                    }
                    "set" -> ClassName.Simple("void")
                    else -> ClassName.Simple("Unknown")
                }
            }
            else -> ClassName.Simple("Unknown")
        }
    }

    private fun extractTypeFromInit(init: Expr): ClassName {
        return when (init) {
            is Expr.ClassNameExpr -> init.cn
            is Expr.Call -> {
                ClassName.Simple(init.method)
            }
            is Expr.IntLit -> ClassName.Simple("Integer")
            is Expr.RealLit -> ClassName.Simple("Real")
            is Expr.BoolLit -> ClassName.Simple("Bool")
            else -> ClassName.Simple("Unknown")
        }
    }

    /**
     * Этап 6: Оптимизации AST
     * Оптимизация 1: Удаление недостижимого кода (после return)
     * Оптимизация 2: Упрощение константных выражений (5.Plus(3) → 8)
     * Оптимизация 3: Удаление неиспользуемых переменных
     */
    private fun optimize() {
        // Создаем оптимизированную версию программы
        val optimizedClasses = program.classes.map { classDecl ->
            val className = (classDecl.name as ClassName.Simple).name
            val classSymbol = classTable.findClass(className)!!

            val optimizedMembers = classDecl.members.map { member ->
                when (member) {
                    is MemberDecl.MethodDecl -> {
                        val optimizedBody = member.body?.let { body ->
                            val methodSymbol = classSymbol.findMethods(member.name).firstOrNull()
                            if (methodSymbol != null) {
                                optimizeMethodBody(body, methodSymbol.symbolTable, classSymbol, className, member.name)
                            } else {
                                body
                            }
                        }
                        MemberDecl.MethodDecl(member.name, member.params, member.returnType, optimizedBody)
                    }
                    else -> member
                }
            }

            ClassDecl(classDecl.name, classDecl.parent, optimizedMembers)
        }

        // Заменяем программу оптимизированной версией
        program = Program(optimizedClasses)
    }

    private fun optimizeMethodBody(
        body: MethodBody,
        symbolTable: semantic.tables.MethodTable,
        classSymbol: ClassSymbol,
        className: String,
        methodName: String
    ): MethodBody {
        return when (body) {
            is MethodBody.BlockBody -> {
                // Оптимизируем statements
                val optimizedStmts = optimizeStatements(body.stmts, symbolTable, classSymbol, className, methodName)

                val optimizedBody = MethodBody.BlockBody(body.vars, optimizedStmts)

                removeUnusedVariables(optimizedBody)
            }
        }
    }

    private fun optimizeStatements(
        stmts: List<Stmt>,
        symbolTable: semantic.tables.MethodTable,
        classSymbol: ClassSymbol,
        className: String,
        methodName: String
    ): List<Stmt> {
        val optimizedStmts = mutableListOf<Stmt>()
        var foundReturn = false

        stmts.forEach { stmt ->
            // Оптимизация 1: Удаление недостижимого кода (после return)
            if (foundReturn) {
                return@forEach
            }

            val optimized: Stmt? = when (stmt) {
                is Stmt.Return -> {
                    foundReturn = true
                    val optimizedExpr = stmt.expr?.let { simplifyConstantExpression(it) }
                    Stmt.Return(optimizedExpr)
                }
                is Stmt.Assignment -> {
                    // Оптимизация 2: Упрощение константных выражений
                    val optimizedExpr = simplifyConstantExpression(stmt.expr)
                    Stmt.Assignment(stmt.target, optimizedExpr)
                }
                is Stmt.If -> {
                    // Оптимизируем условие и тела
                    val optimizedCond = simplifyConstantExpression(stmt.cond)
                    Stmt.If(
                        optimizedCond,
                        optimizeMethodBody(stmt.thenBody, symbolTable, classSymbol, className, methodName),
                        stmt.elseBody?.let { optimizeMethodBody(it, symbolTable, classSymbol, className, methodName) }
                    )
                }
                is Stmt.While -> {
                    // Оптимизируем условие и тело цикла
                    val optimizedCond = simplifyConstantExpression(stmt.cond)
                    Stmt.While(
                        optimizedCond,
                        optimizeMethodBody(stmt.body, symbolTable, classSymbol, className, methodName)
                    )
                }
                is Stmt.ExprStmt -> {
                    // Оптимизируем выражение
                    val optimizedExpr = simplifyConstantExpression(stmt.expr)
                    Stmt.ExprStmt(optimizedExpr)
                }
            }

            if (optimized != null) {
                optimizedStmts.add(optimized)
            }
        }

        return optimizedStmts
    }

    /**
     * Оптимизация 2: Упрощение константных выражений
     * Упрощает вызовы методов на константах, например:
     * - 5.Plus(3) → 8
     * - 2.Mult(4) → 8
     * - 5.LessEqual(3) → false
     */
    private fun simplifyConstantExpression(expr: Expr): Expr {
        return when (expr) {
            is Expr.IntLit,
            is Expr.RealLit,
            is Expr.BoolLit,
            is Expr.This,
            is Expr.Identifier,
            is Expr.ClassNameExpr -> expr
            is Expr.Call -> {
                // Упрощаем аргументы
                val optimizedArgs = expr.args.map { simplifyConstantExpression(it) }
                val optimizedReceiver = expr.receiver?.let { simplifyConstantExpression(it) }

                // Если receiver и аргументы - константы, пытаемся упростить
                if (optimizedReceiver != null && optimizedArgs.isNotEmpty()) {
                    val simplified = simplifyConstantMethodCall(optimizedReceiver, expr.method, optimizedArgs)
                    if (simplified != null) {
                        return simplified
                    }
                }

                Expr.Call(optimizedReceiver, expr.method, optimizedArgs)
            }
            is Expr.FieldAccess -> {
                Expr.FieldAccess(
                    simplifyConstantExpression(expr.receiver),
                    expr.name
                )
            }
        }
    }

    /**
     * Упрощение вызова метода на константах
     * Возвращает упрощенное выражение, если возможно, иначе null
     */
    private fun simplifyConstantMethodCall(
        receiver: Expr,
        methodName: String,
        args: List<Expr>
    ): Expr? {
        return when {
            // Операции для Integer
            receiver is Expr.IntLit && args.size == 1 && args[0] is Expr.IntLit -> {
                val left = receiver.v
                val right = (args[0] as Expr.IntLit).v

                when (methodName) {
                    // Арифметические операции
                    "Plus" -> Expr.IntLit(left + right)
                    "Minus" -> Expr.IntLit(left - right)
                    "Mult" -> Expr.IntLit(left * right)
                    "Div" -> if (right != 0L) Expr.IntLit(left / right) else null
                    "Rem" -> if (right != 0L) Expr.IntLit(left % right) else null
                    // Сравнения
                    "Less" -> Expr.BoolLit(left < right)
                    "LessEqual" -> Expr.BoolLit(left <= right)
                    "Greater" -> Expr.BoolLit(left > right)
                    "GreaterEqual" -> Expr.BoolLit(left >= right)
                    "Equal" -> Expr.BoolLit(left == right)
                    "NotEqual" -> Expr.BoolLit(left != right)
                    else -> null
                }
            }
            // Операции для Real
            receiver is Expr.RealLit && args.size == 1 && args[0] is Expr.RealLit -> {
                val left = receiver.v
                val right = (args[0] as Expr.RealLit).v

                when (methodName) {
                    // Арифметические операции
                    "Plus" -> Expr.RealLit(left + right)
                    "Minus" -> Expr.RealLit(left - right)
                    "Mult" -> Expr.RealLit(left * right)
                    "Div" -> if (right != 0.0) Expr.RealLit(left / right) else null
                    "Rem" -> if (right != 0.0) Expr.RealLit(left % right) else null
                    // Сравнения
                    "Less" -> Expr.BoolLit(left < right)
                    "LessEqual" -> Expr.BoolLit(left <= right)
                    "Greater" -> Expr.BoolLit(left > right)
                    "GreaterEqual" -> Expr.BoolLit(left >= right)
                    "Equal" -> Expr.BoolLit(left == right)
                    "NotEqual" -> Expr.BoolLit(left != right)
                    else -> null
                }
            }
            // Логические операции для Bool
            receiver is Expr.BoolLit && args.size == 1 && args[0] is Expr.BoolLit -> {
                val left = receiver.v
                val right = (args[0] as Expr.BoolLit).v

                when (methodName) {
                    "And" -> Expr.BoolLit(left && right)
                    "Or" -> Expr.BoolLit(left || right)
                    else -> null
                }
            }
            else -> null
        }
    }

    /**
     * Оптимизация: Удаление неиспользуемых переменных (Dead Variable Elimination) на уровне метода
     */
    private fun removeUnusedVariables(body: MethodBody): MethodBody {
        return when (body) {
            is MethodBody.BlockBody -> {
                // Собираем все используемые переменные
                val usedVariables = mutableSetOf<String>()
                collectUsedVariables(body.stmts, usedVariables)

                // Фильтруем переменные: оставляем только используемые
                val filteredVars = body.vars.filter { varDecl ->
                    usedVariables.contains(varDecl.name)
                }

                MethodBody.BlockBody(filteredVars, body.stmts)
            }
        }
    }

    /**
     * Собрать все используемые переменные в statements
     */
    private fun collectUsedVariables(stmts: List<Stmt>, usedVariables: MutableSet<String>) {
        stmts.forEach { stmt ->
            when (stmt) {
                is Stmt.Assignment -> {
                    usedVariables.add(stmt.target)
                    collectUsedVariablesInExpr(stmt.expr, usedVariables)
                }
                is Stmt.Return -> {
                    stmt.expr?.let { collectUsedVariablesInExpr(it, usedVariables) }
                }
                is Stmt.While -> {
                    collectUsedVariablesInExpr(stmt.cond, usedVariables)
                    when (stmt.body) {
                        is MethodBody.BlockBody -> collectUsedVariables(stmt.body.stmts, usedVariables)
                    }
                }
                is Stmt.If -> {
                    collectUsedVariablesInExpr(stmt.cond, usedVariables)
                    when (stmt.thenBody) {
                        is MethodBody.BlockBody -> collectUsedVariables(stmt.thenBody.stmts, usedVariables)
                    }
                    stmt.elseBody?.let { elseBody ->
                        when (elseBody) {
                            is MethodBody.BlockBody -> collectUsedVariables(elseBody.stmts, usedVariables)
                        }
                    }
                }
                is Stmt.ExprStmt -> {
                    collectUsedVariablesInExpr(stmt.expr, usedVariables)
                }
            }
        }
    }

    /**
     * Собрать используемые переменные в выражении
     */
    private fun collectUsedVariablesInExpr(expr: Expr, usedVariables: MutableSet<String>) {
        when (expr) {
            is Expr.Identifier -> {
                usedVariables.add(expr.name)
            }
            is Expr.Call -> {
                expr.receiver?.let { collectUsedVariablesInExpr(it, usedVariables) }
                expr.args.forEach { collectUsedVariablesInExpr(it, usedVariables) }
            }
            is Expr.FieldAccess -> {
                collectUsedVariablesInExpr(expr.receiver, usedVariables)
            }
            else -> {}
        }
    }

    companion object {
        val BUILDIN_METHODS = setOf("print")
    }
}

