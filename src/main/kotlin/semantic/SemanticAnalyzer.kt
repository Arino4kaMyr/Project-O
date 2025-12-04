package semantic

import exceptions.SematicException
import semantic.tables.ClassTable
import syntaxer.*

class SemanticAnalyzer(private var program: Program) {
    val classTable = ClassTable()
    
    fun getOptimizedProgram(): Program {
        return program
    }

    fun analyze() {
        program.classes.forEach { classDecl ->
            val className = (classDecl.name as ClassName.Simple).name
            val classSymbol = ClassSymbol(className, classDecl)
            classTable.addClass(classSymbol)
        }

        if (!classTable.contains("Program")) throw SematicException("Program class doesn't exist")

        resolveInheritance()

        program.classes.forEach { classDecl ->
            val className = (classDecl.name as ClassName.Simple).name
            val classSymbol = classTable.findClass(className)!!
            analyzeClass(classDecl, classSymbol)
        }

        resolveReferences()

        typeCheck()

        optimize()
    }

    private fun resolveInheritance() {
        program.classes.forEach { classDecl ->
            val className = (classDecl.name as ClassName.Simple).name
            val classSymbol = classTable.findClass(className)!!

            classDecl.parent?.let { parentName ->
                when (parentName) {
                    is ClassName.Simple -> {
                        if (parentName.name == className) {
                            throw exceptions.SematicException(
                                "Class '${className}' cannot extend itself"
                            )
                        }

                        val parentClass = classTable.findClass(parentName.name)

                        if (parentClass == null) {
                            throw exceptions.SematicException(
                                "Class '${className}' extends unknown class '${parentName.name}'"
                            )
                        }

                        classSymbol.parentClass = parentClass
                    }
                    is ClassName.Generic -> {
                        throw exceptions.SematicException(
                            "Generic types in inheritance are not supported yet: ${parentName.name}[...]"
                        )
                    }
                }
            }
        }

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
                    if (classSymbol.fields.containsKey(member.name)) {
                        throw exceptions.SematicException(
                            "Duplicate field '${member.name}' in class '${classSymbol.name}'"
                        )
                    }

                    val varType = member.type
                    val fieldSymbol = VarSymbol(member.name, varType)
                    classSymbol.fields[member.name] = fieldSymbol
                }
                is MemberDecl.MethodDecl -> {
                    val paramNames = member.params.map { it.name }
                    val duplicateParams = paramNames.groupingBy { it }.eachCount().filter { it.value > 1 }
                    if (duplicateParams.isNotEmpty()) {
                        throw exceptions.SematicException(
                            "Duplicate parameter '${duplicateParams.keys.first()}' in method '${member.name}' of class '${classSymbol.name}'"
                        )
                    }

                    val paramSymbols = member.params.map { param ->
                        ParamSymbol(param.name, param.type)
                    }
                    val paramTypes = paramSymbols.map { it.type }

                    classSymbol.parentClass?.let { parent ->
                        val overridden = parent.findMethodBySignature(member.name, paramTypes)
                        if (overridden != null) {
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

                    paramSymbols.forEach { param ->
                        methodSymbol.symbolTable.addParam(param)
                    }

                    member.body?.let { body ->
                        analyzeMethodBody(body, methodSymbol.symbolTable, classSymbol.name, member.name)
                    }

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
                body.vars.forEach { varDecl ->
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
                        }
                    }
                }
            }
        }
    }


    private fun resolveReferences() {
        program.classes.forEach { classDecl ->
            val className = (classDecl.name as ClassName.Simple).name
            val classSymbol = classTable.findClass(className)!!

            classDecl.members.forEach { member ->
                when (member) {
                    is MemberDecl.MethodDecl -> {
                        member.body?.let { body ->
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
                    }
                    else -> {}
                }
            }
        }
    }

    private fun resolveMethodBody(
        body: MethodBody,
        symbolTable: semantic.tables.MethodTable,
        classSymbol: ClassSymbol,
        className: String,
        methodName: String
    ) {
        when (body) {
            is MethodBody.BlockBody -> {
                body.stmts.forEach { stmt ->
                    resolveStmt(stmt, symbolTable, classSymbol, className, methodName)
                }
            }
        }
    }

    private fun resolveStmt(
        stmt: Stmt,
        symbolTable: semantic.tables.MethodTable,
        classSymbol: ClassSymbol,
        className: String,
        methodName: String
    ) {
        when (stmt) {
            is Stmt.Assignment -> {
                resolveVariable(stmt.target, symbolTable, classSymbol, className, methodName)
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

    private fun resolveExpr(
        expr: Expr,
        symbolTable: semantic.tables.MethodTable,
        classSymbol: ClassSymbol,
        className: String,
        methodName: String
    ) {
        when (expr) {
            is Expr.Identifier -> {
                resolveVariable(expr.name, symbolTable, classSymbol, className, methodName)
            }
            is Expr.Call -> {
                expr.receiver?.let { receiver ->
                    resolveExpr(receiver, symbolTable, classSymbol, className, methodName)
                }

                if (expr.receiver == null && expr.method in BUILDIN_METHODS) {
                    expr.args.forEach { arg ->
                        resolveExpr(arg, symbolTable, classSymbol, className, methodName)
                    }
                    return
                }

                if (expr.receiver == null) {
                    if (expr.method !in BUILDIN_METHODS) {
                        val methods = classSymbol.findMethods(expr.method)
                        if (methods.isEmpty()) {
                            throw exceptions.SematicException(
                                "Unknown method '${expr.method}' in class '${className}'"
                            )
                        }
                    }
                }

                expr.args.forEach { arg ->
                    resolveExpr(arg, symbolTable, classSymbol, className, methodName)
                }
            }
            is Expr.FieldAccess -> {
                resolveExpr(expr.receiver, symbolTable, classSymbol, className, methodName)
            }
            is Expr.This -> {
            }
            is Expr.IntLit,
            is Expr.RealLit,
            is Expr.BoolLit,
            is Expr.ClassNameExpr -> {
            }
        }
    }

    private fun resolveVariable(
        varName: String,
        symbolTable: semantic.tables.MethodTable,
        classSymbol: ClassSymbol,
        className: String,
        methodName: String
    ) {
        val actualVarName = if (varName.startsWith("this.")) {
            varName.removePrefix("this.")
        } else {
            varName
        }
        
        if (varName.startsWith("this.")) {
            if (classSymbol.findField(actualVarName) != null) {
                return
            }
            throw exceptions.SematicException(
                "Unknown field '${actualVarName}' in class '${className}'"
            )
        }
        
        if (symbolTable.contains(actualVarName)) {
            return
        }

        if (classSymbol.findField(actualVarName) != null) {
            return
        }

        throw exceptions.SematicException(
            "Unknown variable '${actualVarName}' in method '${methodName}' of class '${className}'"
        )
    }

    private fun typeCheck() {
        program.classes.forEach { classDecl ->
            val className = (classDecl.name as ClassName.Simple).name
            val classSymbol = classTable.findClass(className)!!

            classDecl.members.forEach { member ->
                when (member) {
                    is MemberDecl.MethodDecl -> {
                        member.body?.let { body ->
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

    private fun typeCheckStmt(
        stmt: Stmt,
        symbolTable: semantic.tables.MethodTable,
        classSymbol: ClassSymbol,
        className: String,
        methodSymbol: MethodSymbol
    ) {
        when (stmt) {
            is Stmt.Assignment -> {
                val targetType = getVariableType(stmt.target, symbolTable, classSymbol, className, methodSymbol.name)
                val exprType = getTypeOfExpr(stmt.expr, symbolTable, classSymbol, className, methodSymbol.name)

                if (!isAssignable(exprType, targetType, classTable)) {
                    throw exceptions.SematicException(
                        "Type mismatch: cannot assign ${classNameToString(exprType)} to ${classNameToString(targetType)} in assignment '${stmt.target}'"
                    )
                }
            }
            is Stmt.While -> {
                val condType = getTypeOfExpr(stmt.cond, symbolTable, classSymbol, className, methodSymbol.name)
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
                if (expr.receiver == null && expr.method in BUILDIN_METHODS) {
                    expr.args.forEach { arg ->
                        getTypeOfExpr(arg, symbolTable, classSymbol, className, methodName)
                    }
                    return ClassName.Simple("void")
                }
                
                val argTypes = expr.args.map { arg ->
                    getTypeOfExpr(arg, symbolTable, classSymbol, className, methodName)
                }

                if (expr.receiver == null) {
                    if (expr.method in BUILDIN_METHODS) {
                        return ClassName.Simple("void")
                    }

                    val method = resolveMethodCall(classSymbol, expr.method, argTypes, className, callerClass = classSymbol)

                    method.returnType ?: ClassName.Simple("void")
                } else {
                    val receiverType = getTypeOfExpr(expr.receiver, symbolTable, classSymbol, className, methodName)
                    val receiverClass = classTable.getClass(receiverType)

                    if (receiverClass == null) {
                        val receiverTypeName = when (receiverType) {
                            is ClassName.Simple -> receiverType.name
                            is ClassName.Generic -> receiverType.name
                        }
                        if (receiverTypeName != null) {
                            val argType = if (argTypes.isNotEmpty()) argTypes[0] else null
                            return getBuiltinMethodReturnType(receiverType, receiverTypeName, expr.method, argType)
                        }
                        return ClassName.Simple("Unknown")
                    }

                    val method = resolveMethodCall(receiverClass, expr.method, argTypes, receiverClass.name, callerClass = classSymbol)

                    method.returnType ?: ClassName.Simple("void")
                }
            }
            is Expr.FieldAccess -> {
                val receiverType = getTypeOfExpr(expr.receiver, symbolTable, classSymbol, className, methodName)
                val receiverClass = classTable.getClass(receiverType)

                if (receiverClass == null) {
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

    private fun getVariableType(
        varName: String,
        symbolTable: semantic.tables.MethodTable,
        classSymbol: ClassSymbol,
        className: String,
        methodName: String
    ): ClassName {
        val actualVarName = if (varName.startsWith("this.")) {
            varName.removePrefix("this.")
        } else {
            varName
        }
        
        if (varName.startsWith("this.")) {
            val field = classSymbol.findField(actualVarName)
            if (field != null) {
                return field.type
            }
            throw exceptions.SematicException(
                "Unknown field '${actualVarName}' in class '${className}'"
            )
        }
        
        val localVar = symbolTable.findSymbol(actualVarName)
        if (localVar != null) {
            return localVar.type
        }

        val field = classSymbol.findField(actualVarName)
        if (field != null) {
            return field.type
        }

        throw exceptions.SematicException(
            "Variable '${actualVarName}' not found in method '${methodName}' of class '${className}'"
        )
    }

    private fun isAssignable(fromType: ClassName, toType: ClassName, classTable: ClassTable): Boolean {
        if (classNameEquals(fromType, toType)) {
            return true
        }

        when (fromType) {
            is ClassName.Simple -> {
                if (fromType.name == "Unknown") {
                    return true
                }
            }
            is ClassName.Generic -> {
            }
        }

        when (toType) {
            is ClassName.Simple -> {
                if (toType.name == "Unknown") {
                    return true
                }
            }
            is ClassName.Generic -> {
            }
        }

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
                        return false
                    }
                }
            }
            is ClassName.Generic -> {
                if (toType is ClassName.Generic) {
                    if (fromType.name == toType.name && 
                        fromType.typeArgs.size == toType.typeArgs.size) {
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

    private fun resolveMethodCall(
        classSymbol: ClassSymbol,
        methodName: String,
        argTypes: List<ClassName>,
        context: String,
        callerClass: ClassSymbol? = null
    ): MethodSymbol {
        val candidates = classSymbol.findMethods(methodName)

        if (candidates.isEmpty()) {
            throw exceptions.SematicException(
                "No suitable method '${methodName}' found in class '${context}'"
            )
        }

        val matchingCount = candidates.filter { it.params.size == argTypes.size }

        if (matchingCount.isEmpty()) {
            val paramCounts = candidates.map { it.params.size }.distinct().sorted()
            throw exceptions.SematicException(
                "Method '${methodName}' with ${argTypes.size} arguments not found in class '${context}'. " +
                "Available overloads have ${paramCounts.joinToString(", ")} ${if (paramCounts.size == 1) "argument" else "arguments"}"
            )
        }

        val exactMatch = matchingCount.find { method ->
            method.params.size == argTypes.size &&
            method.params.mapIndexed { index, param -> param.type }.zip(argTypes).all { (type1, type2) ->
                classNameEquals(type1, type2)
            }
        }
        if (exactMatch != null) {
            return exactMatch
        }

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
            val signatures = compatibleMethods.map { method ->
                method.params.joinToString(", ") { classNameToString(it.type) }
            }.distinct()
            throw exceptions.SematicException(
                "Ambiguous method call: multiple overloads of '${methodName}' match arguments (${argTypes.joinToString(", ") { classNameToString(it) }}) in class '${context}'. " +
                "Candidates: ${signatures.joinToString("; ")}"
            )
        }

        return compatibleMethods.first()
    }

    private fun classNameToString(className: ClassName): String {
        return when (className) {
            is ClassName.Simple -> className.name
            is ClassName.Generic -> {
                val typeArgsStr = className.typeArgs.joinToString(", ") { classNameToString(it) }
                "${className.name}[$typeArgsStr]"
            }
        }
    }

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
                        if (receiverType is ClassName.Generic && receiverType.typeArgs.isNotEmpty()) {
                            receiverType.typeArgs[0]
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

    private fun optimize() {
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
                    val optimizedExpr = simplifyConstantExpression(stmt.expr)
                    Stmt.Assignment(stmt.target, optimizedExpr)
                }
                is Stmt.If -> {
                    val optimizedCond = simplifyConstantExpression(stmt.cond)
                    Stmt.If(
                        optimizedCond,
                        optimizeMethodBody(stmt.thenBody, symbolTable, classSymbol, className, methodName),
                        stmt.elseBody?.let { optimizeMethodBody(it, symbolTable, classSymbol, className, methodName) }
                    )
                }
                is Stmt.While -> {
                    val optimizedCond = simplifyConstantExpression(stmt.cond)
                    Stmt.While(
                        optimizedCond,
                        optimizeMethodBody(stmt.body, symbolTable, classSymbol, className, methodName)
                    )
                }
                is Stmt.ExprStmt -> {
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

    private fun simplifyConstantExpression(expr: Expr): Expr {
        return when (expr) {
            is Expr.IntLit,
            is Expr.RealLit,
            is Expr.BoolLit,
            is Expr.This,
            is Expr.Identifier,
            is Expr.ClassNameExpr -> expr
            is Expr.Call -> {
                val optimizedArgs = expr.args.map { simplifyConstantExpression(it) }
                val optimizedReceiver = expr.receiver?.let { simplifyConstantExpression(it) }

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

    private fun simplifyConstantMethodCall(
        receiver: Expr,
        methodName: String,
        args: List<Expr>
    ): Expr? {
        return when {
            receiver is Expr.IntLit && args.size == 1 && args[0] is Expr.IntLit -> {
                val left = receiver.v
                val right = (args[0] as Expr.IntLit).v

                when (methodName) {
                    "Plus" -> Expr.IntLit(left + right)
                    "Minus" -> Expr.IntLit(left - right)
                    "Mult" -> Expr.IntLit(left * right)
                    "Div" -> if (right != 0L) Expr.IntLit(left / right) else null
                    "Rem" -> if (right != 0L) Expr.IntLit(left % right) else null
                    "Less" -> Expr.BoolLit(left < right)
                    "LessEqual" -> Expr.BoolLit(left <= right)
                    "Greater" -> Expr.BoolLit(left > right)
                    "GreaterEqual" -> Expr.BoolLit(left >= right)
                    "Equal" -> Expr.BoolLit(left == right)
                    "NotEqual" -> Expr.BoolLit(left != right)
                    else -> null
                }
            }
            receiver is Expr.RealLit && args.size == 1 && args[0] is Expr.RealLit -> {
                val left = receiver.v
                val right = (args[0] as Expr.RealLit).v

                when (methodName) {
                    "Plus" -> Expr.RealLit(left + right)
                    "Minus" -> Expr.RealLit(left - right)
                    "Mult" -> Expr.RealLit(left * right)
                    "Div" -> if (right != 0.0) Expr.RealLit(left / right) else null
                    "Rem" -> if (right != 0.0) Expr.RealLit(left % right) else null
                    "Less" -> Expr.BoolLit(left < right)
                    "LessEqual" -> Expr.BoolLit(left <= right)
                    "Greater" -> Expr.BoolLit(left > right)
                    "GreaterEqual" -> Expr.BoolLit(left >= right)
                    "Equal" -> Expr.BoolLit(left == right)
                    "NotEqual" -> Expr.BoolLit(left != right)
                    else -> null
                }
            }
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

    private fun removeUnusedVariables(body: MethodBody): MethodBody {
        return when (body) {
            is MethodBody.BlockBody -> {
                val usedVariables = mutableSetOf<String>()
                collectUsedVariables(body.stmts, usedVariables)

                val filteredVars = body.vars.filter { varDecl ->
                    usedVariables.contains(varDecl.name)
                }

                MethodBody.BlockBody(filteredVars, body.stmts)
            }
        }
    }

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

