package compilation.jasmin

import semantic.ClassSymbol
import semantic.MethodSymbol
import semantic.tables.ClassTable
import syntaxer.*

class JasminCodeGenerator(
    private val program: Program,
    private val classTable: ClassTable
) {

    fun generate(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        program.classes.forEach { classDecl ->
            val className = (classDecl.name as ClassName.Simple).name
            val code = generateClass(classDecl)
            result["$className.j"] = code
        }
        return result
    }

    private fun generateClass(classDecl: ClassDecl): String {
        val sb = StringBuilder()
        val className = (classDecl.name as ClassName.Simple).name
        val classSymbol = classTable.findClass(className)
            ?: throw IllegalStateException("ClassSymbol not found for $className")

        sb.append(".class public $className\n")
        val superName = when (val p = classDecl.parent) {
            null -> "java/lang/Object"
            is ClassName.Simple -> p.name.replace('.', '/')
            is ClassName.Generic -> p.name.replace('.', '/')
        }
        sb.append(".super $superName\n")

        generateField(sb, classDecl, classSymbol)
        generateConstructors(sb, classDecl, classSymbol)
        generateMethods(sb, classDecl, classSymbol)


        return sb.toString()
    }

    private fun generateField(
        sb: StringBuilder,
        classDecl: ClassDecl,
        classSymbol: ClassSymbol,
    ) {
        classDecl.members.filterIsInstance<MemberDecl.VarDecl>().forEach { varDecl ->
            val fieldName = varDecl.name
            val jasminType = toJasminType(varDecl.type)
            val accessModifier = when (varDecl.visibility) {
                syntaxer.AccessModifier.PRIVATE -> "private"
                syntaxer.AccessModifier.PUBLIC -> "public"
            }
            sb.append(".field $accessModifier $fieldName $jasminType\n")
        }

        if (classSymbol.fields.isNotEmpty()) {
            sb.append("\n")
        }
    }

    private fun generateConstructors(
        sb: StringBuilder,
        classDecl: ClassDecl,
        classSymbol: ClassSymbol
    ) {
        val className = classSymbol.name

        val constructors = classDecl.members.filterIsInstance<MemberDecl.ConstructorDecl>()
        if (constructors.isEmpty()) {
            sb.append(".method public <init>()V\n")
            sb.append("    .limit stack 32\n")
            sb.append("    .limit locals 16\n")
            sb.append("    aload_0\n")
            val superName = classSymbol.parentClass?.name ?: "java/lang/Object"
            sb.append("    invokespecial $superName/<init>()V\n")

            createObject(classDecl, classSymbol, sb)


            sb.append("    return\n")
            sb.append(".end method\n\n")
            return
        }

        constructors.forEach { ctor ->
            val paramTypesDesc = ctor.params.joinToString(separator = "") { param ->
                toJasminType(param.type)
            }
            sb.append(".method public <init>($paramTypesDesc)V\n")
            sb.append("    .limit stack 32\n")
            sb.append("    .limit locals 16\n")

            sb.append("    aload_0\n")
            val superName = classSymbol.parentClass?.name ?: "java/lang/Object"
            sb.append("    invokespecial $superName/<init>()V\n")

            createObject(classDecl, classSymbol, sb)


            generateMethodBody(
                sb = sb,
                body = ctor.body,
                classSymbol = classSymbol,
                methodSymbol = MethodSymbol("<init>", emptyList(), null, null)
            )

            sb.append("    return\n")
            sb.append(".end method\n\n")
        }
    }

    private fun createObject(
        classDecl: ClassDecl,
        classSymbol: ClassSymbol,
        sb: StringBuilder
    ) {
        classDecl.members.filterIsInstance<MemberDecl.VarDecl>().forEach { vDecl ->
            val fieldSymbol = classSymbol.findField(vDecl.name)
                ?: error("Field symbol not found for ${classSymbol.name}.${vDecl.name}")
            val fieldType = fieldSymbol.type

            when (val init = vDecl.init) {
                is Expr.Call -> {
                    if (fieldType is ClassName.Generic) {
                        when (fieldType.name) {
                            "Array" -> {
                                sb.append("    aload_0\n")
                                if (init.args.size != 1) {
                                    error("Array constructor requires exactly 1 argument (length)")
                                }
                                generateExpr(
                                    sb,
                                    init.args[0],
                                    classSymbol,
                                    MethodSymbol("<initField>", emptyList(), null, null)
                                )
                                val elementType = if (fieldType.typeArgs.isNotEmpty()) {
                                    fieldType.typeArgs[0]
                                } else {
                                    ClassName.Simple("Unknown")
                                }
                                val arrayType = toJasminType(fieldType)
                                when (elementType) {
                                    is ClassName.Simple -> when (elementType.name) {
                                        "Integer", "Int" -> {
                                            sb.append("    newarray int\n")
                                        }
                                        "Real", "Double" -> {
                                            sb.append("    newarray double\n")
                                        }
                                        "Bool", "Boolean" -> {
                                            sb.append("    newarray boolean\n")
                                        }
                                        else -> {
                                            sb.append("    anewarray ${elementType.name}\n")
                                        }
                                    }
                                    else -> {
                                        sb.append("    anewarray java/lang/Object\n")
                                    }
                                }
                                sb.append("    putfield ${classSymbol.name}/${vDecl.name} $arrayType\n")
                            }
                            else -> {
                                error("Unknown generic type: ${fieldType.name}")
                            }
                        }
                        return
                    }
                    
                    val typeName = when (fieldType) {
                        is ClassName.Simple -> fieldType.name
                        else -> error("Expected simple type for field initialization")
                    }

                    if (typeName == "Integer") {
                        sb.append("    aload_0\n")
                        val arg = init.args.singleOrNull()
                        if (arg != null) {
                            generateExpr(
                                sb,
                                arg,
                                classSymbol,
                                MethodSymbol("<initField>", emptyList(), null, null)
                            )
                        } else {
                            sb.append("    iconst_0\n")
                        }
                        sb.append("    putfield ${classSymbol.name}/${vDecl.name} ${toJasminType(fieldType)}\n")

                    } else if (typeName == "Real") {
                        sb.append("    aload_0\n")
                        val arg = init.args.singleOrNull()
                        if (arg != null) {
                            generateExpr(
                                sb,
                                arg,
                                classSymbol,
                                MethodSymbol("<initField>", emptyList(), null, null)
                            )   
                        } else {
                            sb.append("    ldc2_w 0.0\n")
                        }
                        sb.append("    putfield ${classSymbol.name}/${vDecl.name} ${toJasminType(fieldType)}\n")

                    } else if (typeName == "Bool") {
                        sb.append("    aload_0\n")
                        val arg = init.args.singleOrNull()
                        if (arg != null) {
                            generateExpr(
                                sb,
                                arg,
                                classSymbol,
                                MethodSymbol("<initField>", emptyList(), null, null)
                            )
                        } else {
                            sb.append("    iconst_0\n")
                        }
                        sb.append("    putfield ${classSymbol.name}/${vDecl.name} ${toJasminType(fieldType)}\n")

                    } else {
                        sb.append("    aload_0\n")
                        sb.append("    new $typeName\n")
                        sb.append("    dup\n")
                        init.args.forEach { arg ->
                            generateExpr(
                                sb,
                                arg,
                                classSymbol,
                                MethodSymbol("<initField>", emptyList(), null, null)
                            )
                        }
                        val argsDesc = init.args.joinToString("") { "I" }
                        sb.append("    invokespecial $typeName/<init>($argsDesc)V\n")
                        sb.append("    putfield ${classSymbol.name}/${vDecl.name} ${toJasminType(fieldType)}\n")
                    }
                }

                else -> {
                }
            }
        }
    }


    private fun generateMethods(
        sb: StringBuilder,
        classDecl: ClassDecl,
        classSymbol: ClassSymbol
    ) {
        val className = classSymbol.name

        classDecl.members.filterIsInstance<MemberDecl.MethodDecl>().forEach { methodDecl ->
            val paramTypes = methodDecl.params.map { it.type }
            val methodsWithName = classSymbol.findMethods(methodDecl.name)

            val methodSymbol = methodsWithName.find { m ->
                m.params.size == paramTypes.size &&
                        m.params.map { it.type }.zip(paramTypes).all { (t1, t2) ->
                            t1 is ClassName.Simple && t2 is ClassName.Simple && t1.name == t2.name
                        }
            } ?: error("MethodSymbol not found for ${className}.${methodDecl.name}")

            val paramDesc = methodSymbol.params.joinToString(separator = "") { p ->
                toJasminType(p.type)
            }
            val returnDesc = methodSymbol.returnType?.let { toJasminType(it) } ?: "V"

            sb.append(".method public ${methodSymbol.name}($paramDesc)$returnDesc\n")

            sb.append("    .limit stack 32\n")
            sb.append("    .limit locals 32\n")

            methodDecl.body?.let { body ->
                generateMethodBody(
                    sb = sb,
                    body = body,
                    classSymbol = classSymbol,
                    methodSymbol = methodSymbol
                )
            }

            if (returnDesc == "V") {
                sb.append("    return\n")
            }

            sb.append(".end method\n\n")

            if (className == "Program" &&
                methodSymbol.name == "main" &&
                methodSymbol.params.isEmpty() &&
                returnDesc == "V"
            ) {
                generateJvmMainWrapper(sb, classSymbol, methodSymbol)
            }
        }
    }

    private fun generateJvmMainWrapper(
        sb: StringBuilder,
        classSymbol: ClassSymbol,
        methodSymbol: MethodSymbol
    ) {
        sb.append(".method public static main([Ljava/lang/String;)V\n")
        sb.append("    .limit stack 32\n")
        sb.append("    .limit locals 16\n")

        sb.append("    new ${classSymbol.name}\n")
        sb.append("    dup\n")
        sb.append("    invokespecial ${classSymbol.name}/<init>()V\n")
        sb.append("    invokevirtual ${classSymbol.name}/${methodSymbol.name}()V\n")
        sb.append("    return\n")
        sb.append(".end method\n\n")
    }

    private fun generateMethodBody(
        sb: StringBuilder,
        body: MethodBody,
        classSymbol: ClassSymbol,
        methodSymbol: MethodSymbol
    ) {
        when (body) {
            is MethodBody.BlockBody -> {
                val allItems = mutableListOf<Pair<Boolean, Any>>()
                
                body.vars.forEach { varDecl ->
                    allItems.add(true to varDecl)
                }
                body.stmts.forEach { stmt ->
                    allItems.add(false to stmt)
                }
                
                allItems.forEach { (isVar, item) ->
                    if (isVar) {
                        initializeLocalVariable(sb, item as MemberDecl.VarDecl, classSymbol, methodSymbol)
                    } else {
                        generateStmt(sb, item as Stmt, classSymbol, methodSymbol)
                    }
                }
            }
        }
    }
    
    private fun initializeLocalVariable(
        sb: StringBuilder,
        varDecl: MemberDecl.VarDecl,
        classSymbol: ClassSymbol,
        methodSymbol: MethodSymbol
    ) {
        val varType = varDecl.type
        val varName = varDecl.name
        val init = varDecl.init
        
        val localIndex = methodSymbol.symbolTable.getIndex(varName)
        val realIndex = getRealJvmIndex(varName, methodSymbol)
        
        when (varType) {
            is ClassName.Generic -> when (varType.name) {
                "Array" -> {
                    if (init is Expr.Call && init.args.size == 1) {
                        generateExpr(sb, init.args[0], classSymbol, methodSymbol)
                        val elementType = if (varType.typeArgs.isNotEmpty()) {
                            varType.typeArgs[0]
                        } else {
                            ClassName.Simple("Unknown")
                        }
                        when (elementType) {
                            is ClassName.Simple -> when (elementType.name) {
                                "Integer", "Int" -> {
                                    sb.append("    newarray int\n")
                                }
                                "Real", "Double" -> {
                                    sb.append("    newarray double\n")
                                }
                                "Bool", "Boolean" -> {
                                    sb.append("    newarray boolean\n")
                                }
                                else -> {
                                    sb.append("    anewarray ${elementType.name}\n")
                                }
                            }
                            else -> {
                                sb.append("    anewarray java/lang/Object\n")
                            }
                        }
                        sb.append("    astore $realIndex\n")
                    } else {
                        error("Array initialization requires exactly 1 argument (length)")
                    }
                }
                else -> {
                    error("Unknown generic type: ${varType.name}")
                }
            }
            is ClassName.Simple -> {
                when (varType.name) {
                    "Integer", "Int" -> {
                        if (init is Expr.Call && init.args.isNotEmpty()) {
                            generateExpr(sb, init.args[0], classSymbol, methodSymbol)
                            sb.append("    istore $realIndex\n")
                        } else if (init is Expr.IntLit) {
                            generateExpr(sb, init, classSymbol, methodSymbol)
                            sb.append("    istore $realIndex\n")
                        } else {
                            sb.append("    iconst_0\n")
                            sb.append("    istore $realIndex\n")
                        }
                    }
                    "Real", "Double" -> {
                        if (init is Expr.Call && init.args.isNotEmpty()) {
                            generateExpr(sb, init.args[0], classSymbol, methodSymbol)
                            sb.append("    dstore $realIndex\n")
                        } else if (init is Expr.RealLit) {
                            generateExpr(sb, init, classSymbol, methodSymbol)
                            sb.append("    dstore $realIndex\n")
                        } else {
                            sb.append("    ldc2_w 0.0\n")
                            sb.append("    dstore $realIndex\n")
                        }
                    }
                    "Bool", "Boolean" -> {
                        if (init is Expr.Call && init.args.isNotEmpty()) {
                            generateExpr(sb, init.args[0], classSymbol, methodSymbol)
                            sb.append("    istore $realIndex\n")
                        } else if (init is Expr.BoolLit) {
                            generateExpr(sb, init, classSymbol, methodSymbol)
                            sb.append("    istore $realIndex\n")
                        } else {
                            sb.append("    iconst_0\n")
                            sb.append("    istore $realIndex\n")
                        }
                    }
                    else -> {
                        if (init is Expr.Call) {
                            sb.append("    new ${varType.name}\n")
                            sb.append("    dup\n")
                            init.args.forEach { arg ->
                                generateExpr(sb, arg, classSymbol, methodSymbol)
                            }
                            val argsDesc = init.args.joinToString("") { "I" }
                            sb.append("    invokespecial ${varType.name}/<init>($argsDesc)V\n")
                            sb.append("    astore $realIndex\n")
                        } else if (init is Expr.ClassNameExpr) {
                            sb.append("    new ${varType.name}\n")
                            sb.append("    dup\n")
                            sb.append("    invokespecial ${varType.name}/<init>()V\n")
                            sb.append("    astore $realIndex\n")
                        } else {
                            error("Unknown initialization for type ${varType.name}")
                        }
                    }
                }
            }
        }
    }

    private fun generateStmt(
        sb: StringBuilder,
        stmt: Stmt,
        classSymbol: ClassSymbol,
        methodSymbol: MethodSymbol
    ) {
        when (stmt) {
            is Stmt.Return -> {
                stmt.expr?.let { expr ->
                    generateExpr(sb, expr, classSymbol, methodSymbol)
                    val returnType = methodSymbol.returnType
                    val returnDesc = returnType?.let { toJasminType(it) } ?: "V"
                    when (returnDesc) {
                        "I", "Z" -> sb.append("    ireturn\n")
                        "D" -> sb.append("    dreturn\n")
                        "V" -> sb.append("    return\n")
                        else -> sb.append("    areturn\n")
                    }
                } ?: run {
                    sb.append("    return\n")
                }
            }

            is Stmt.ExprStmt -> {
                generateExpr(sb, stmt.expr, classSymbol, methodSymbol)
                val exprType = inferExprType(stmt.expr, classSymbol, methodSymbol)
                val returnsValue = when (exprType) {
                    is ClassName.Simple -> exprType.name != "void" && exprType.name != "Void"
                    is ClassName.Generic -> true
                }
                if (returnsValue) {
                    sb.append("    pop\n")
                }
            }

            is Stmt.Assignment -> {
                val name = stmt.target

                val actualName = if (name.startsWith("this.")) {
                    name.removePrefix("this.")
                } else {
                    name
                }

                if (!name.startsWith("this.")) {
                    val local = methodSymbol.symbolTable.findSymbol(name)
                    if (local != null) {
                        generateExpr(sb, stmt.expr, classSymbol, methodSymbol)
                        val realIndex = getRealJvmIndex(name, methodSymbol)
                        val jasminType = toJasminType(local.type)
                        when (jasminType) {
                            "I", "Z" -> sb.append("    istore $realIndex\n")
                            "D" -> sb.append("    dstore $realIndex\n")
                            else -> sb.append("    astore $realIndex\n")
                        }
                        return
                    }
                }

                val field = classSymbol.findField(actualName)
                if (field != null) {
                    val jasminType = toJasminType(field.type)
                    sb.append("    aload_0\n")
                    generateExpr(sb, stmt.expr, classSymbol, methodSymbol)
                    val fieldOwnerClass = findFieldOwnerClass(actualName, classSymbol)
                    sb.append("    putfield ${fieldOwnerClass}/${field.name} $jasminType\n")
                    return
                }

                error("Unknown variable/field '${name}' in ${classSymbol.name}.${methodSymbol.name}")
            }

            is Stmt.While -> {
                generateWhile(sb, stmt, classSymbol, methodSymbol)
            }

            is Stmt.If -> {
                generateIf(sb, stmt, classSymbol, methodSymbol)
            }
        }
    }

    private fun generateExpr(
        sb: StringBuilder,
        expr: Expr,
        classSymbol: ClassSymbol,
        methodSymbol: MethodSymbol
    ) {
        when (expr) {
            is Expr.IntLit -> {
                sb.append(numberToJasmin(expr.v))
            }

            is Expr.BoolLit -> {
                val v = if (expr.v) 1 else 0
                if (v == 0) sb.append("    iconst_0\n") else sb.append("    iconst_1\n")
            }

            is Expr.RealLit -> {
                val v = expr.v
                sb.append("    ldc2_w $v\n")
            }

            is Expr.Identifier -> {
                val name = expr.name

                val local = methodSymbol.symbolTable.findSymbol(name)
                if (local != null) {
                    val realIndex = getRealJvmIndex(name, methodSymbol)
                    val jasminType = toJasminType(local.type)
                    when (jasminType) {
                        "I", "Z" -> sb.append("    iload $realIndex\n")
                        "D" -> sb.append("    dload $realIndex\n")
                        else -> sb.append("    aload $realIndex\n")
                    }
                    return
                }

                val field = classSymbol.findField(name)
                if (field != null) {
                    val jasminType = toJasminType(field.type)
                    sb.append("    aload_0\n")
                    sb.append("    getfield ${classSymbol.name}/${field.name} $jasminType\n")
                    return
                }

                error("Unknown identifier '$name' in ${classSymbol.name}.${methodSymbol.name}")
            }

            is Expr.This -> {
                sb.append("    aload_0\n")
            }

            is Expr.Call -> {
                generateCallExpr(sb, expr, classSymbol, methodSymbol)
            }

            is Expr.FieldAccess -> {
                generateExpr(sb, expr.receiver ?: Expr.This, classSymbol, methodSymbol)
                val recvType: ClassName = inferExprType(expr.receiver ?: Expr.This, classSymbol, methodSymbol)
                val recvClass = classTable.getClass(recvType)
                    ?: error("Unknown class for receiver in FieldAccess: $recvType")

                val field = recvClass.findField(expr.name)
                    ?: error("Unknown field '${expr.name}' in class '${recvClass.name}'")
                val jasminType = toJasminType(field.type)
                val fieldOwnerClass = findFieldOwnerClass(expr.name, recvClass)
                sb.append("    getfield ${fieldOwnerClass}/${field.name} $jasminType\n")
            }

            is Expr.ClassNameExpr -> {
            }
        }
    }

    private fun generateWhile(
        sb: StringBuilder,
        stmt: Stmt.While,
        classSymbol: ClassSymbol,
        methodSymbol: MethodSymbol
    ) {
        val startLabel = newLabel("Lstart_")
        val endLabel = newLabel("Lend_")

        sb.append("$startLabel:\n")
        generateExpr(sb, stmt.cond, classSymbol, methodSymbol)
        sb.append("    ifeq $endLabel\n")
        generateMethodBody(sb, stmt.body, classSymbol, methodSymbol)
        sb.append("    goto $startLabel\n")
        sb.append("$endLabel:\n")
    }


    private fun generateIf(
        sb: StringBuilder,
        stmt: Stmt.If,
        classSymbol: ClassSymbol,
        methodSymbol: MethodSymbol
    ) {
        val elseLabel = newLabel("Lelse_")
        val endLabel = newLabel("Lend_")

        generateExpr(sb, stmt.cond, classSymbol, methodSymbol)
        sb.append("    ifeq $elseLabel\n")

        generateMethodBody(sb, stmt.thenBody, classSymbol, methodSymbol)
        sb.append("    goto $endLabel\n")

        sb.append("$elseLabel:\n")
        stmt.elseBody?.let { elseBody ->
            generateMethodBody(sb, elseBody, classSymbol, methodSymbol)
        }

        sb.append("$endLabel:\n")
    }

    private fun generateCallExpr(
        sb: StringBuilder,
        call: Expr.Call,
        classSymbol: ClassSymbol,
        methodSymbol: MethodSymbol
    ) {
        if (call.receiver == null && call.method == "print") {
            val arg = call.args.singleOrNull()
                ?: error("print(...) with ${call.args.size} args is not supported yet")

            sb.append("    getstatic java/lang/System/out Ljava/io/PrintStream;\n")

            generateExpr(sb, arg, classSymbol, methodSymbol)

            val argType = inferExprType(arg, classSymbol, methodSymbol)
            val desc = when ((argType as? ClassName.Simple)?.name) {
                "Integer" -> "(I)V"
                "Real"    -> "(D)V"
                "Bool"    -> "(Z)V"
                else      -> "(Ljava/lang/Object;)V"
            }

            sb.append("    invokevirtual java/io/PrintStream/println$desc\n")
            return
        }

        val receiverType: ClassName?
        if (call.receiver != null) {
            generateExpr(sb, call.receiver, classSymbol, methodSymbol)
            receiverType = inferExprType(call.receiver, classSymbol, methodSymbol)
        } else {
            receiverType = null
        }

        val argTypes = call.args.map { arg ->
            generateExpr(sb, arg, classSymbol, methodSymbol)
            inferExprType(arg, classSymbol, methodSymbol)
        }

        if (receiverType == null) {
            val targetMethod = resolveMethodForCall(
                ownerClass = classSymbol,
                methodName = call.method,
                argTypes = argTypes
            )

            val argsDesc = targetMethod.params.joinToString("") { toJasminType(it.type) }
            val retDesc = targetMethod.returnType?.let { toJasminType(it) } ?: "V"

            sb.append("    invokestatic ${classSymbol.name}/${targetMethod.name}($argsDesc)$retDesc\n")
        } else {
            if (isBuiltin(receiverType)) {
                when (receiverType) {
                    is ClassName.Generic -> when (receiverType.name) {
                        "Array" -> {
                            when (call.method) {
                                "Length" -> {
                                    sb.append("    arraylength\n")
                                }
                                "get" -> {
                                    if (call.args.size != 1) {
                                        error("Array.get() requires exactly 1 argument")
                                    }
                                    val elementType = if (receiverType.typeArgs.isNotEmpty()) {
                                        receiverType.typeArgs[0]
                                    } else {
                                        ClassName.Simple("Unknown")
                                    }
                                    when (elementType) {
                                        is ClassName.Simple -> when (elementType.name) {
                                            "Integer", "Int" -> {
                                                sb.append("    iaload\n")
                                            }
                                            "Real", "Double" -> {
                                                sb.append("    daload\n")
                                            }
                                            "Bool", "Boolean" -> {
                                                sb.append("    baload\n")
                                            }
                                            else -> {
                                                sb.append("    aaload\n")
                                            }
                                        }
                                        else -> {
                                            sb.append("    aaload\n")
                                        }
                                    }
                                }
                                "set" -> {
                                    if (call.args.size != 2) {
                                        error("Array.set() requires exactly 2 arguments")
                                    }
                                    val elementType = if (receiverType.typeArgs.isNotEmpty()) {
                                        receiverType.typeArgs[0]
                                    } else {
                                        ClassName.Simple("Unknown")
                                    }
                                    when (elementType) {
                                        is ClassName.Simple -> when (elementType.name) {
                                            "Integer", "Int" -> {
                                                sb.append("    iastore\n")
                                            }
                                            "Real", "Double" -> {
                                                sb.append("    dastore\n")
                                            }
                                            "Bool", "Boolean" -> {
                                                sb.append("    bastore\n")
                                            }
                                            else -> {
                                                sb.append("    aastore\n")
                                            }
                                        }
                                        else -> {
                                            sb.append("    aastore\n")
                                        }
                                    }
                                }
                                else -> error("Unknown Array method '${call.method}'")
                            }
                        }
                        else -> error("Unknown generic type '${receiverType.name}'")
                    }
                    is ClassName.Simple -> when (receiverType.name) {
                        "Integer" -> {
                            val argType = if (call.args.isNotEmpty()) {
                                inferExprType(call.args[0], classSymbol, methodSymbol)
                            } else {
                                null
                            }
                            
                            when (call.method) {
                                "Plus" -> {
                                    if (argType is ClassName.Simple && argType.name == "Real") {
                                        val tempSlot = methodSymbol.symbolTable.getAllSymbols().size + 1
                                        sb.append("    dstore $tempSlot\n")
                                        sb.append("    i2d\n")
                                        sb.append("    dload $tempSlot\n")
                                        sb.append("    dadd\n")
                                    } else {
                                        sb.append("    iadd\n")
                                    }
                                }
                                "Mult" -> {
                                    if (argType is ClassName.Simple && argType.name == "Real") {
                                        val tempSlot = methodSymbol.symbolTable.getAllSymbols().size + 1
                                        sb.append("    dstore $tempSlot\n")
                                        sb.append("    i2d\n")
                                        sb.append("    dload $tempSlot\n")
                                        sb.append("    dmul\n")
                                    } else {
                                        sb.append("    imul\n")
                                    }
                                }
                                "Minus" -> {
                                    if (argType is ClassName.Simple && argType.name == "Real") {
                                        val tempSlot = methodSymbol.symbolTable.getAllSymbols().size + 1
                                        sb.append("    dstore $tempSlot\n")
                                        sb.append("    i2d\n")
                                        sb.append("    dload $tempSlot\n")
                                        sb.append("    dsub\n")
                                    } else {
                                        sb.append("    isub\n")
                                    }
                                }
                                "Div" -> {
                                    if (argType is ClassName.Simple && argType.name == "Real") {
                                        val tempSlot = methodSymbol.symbolTable.getAllSymbols().size + 1
                                        sb.append("    dstore $tempSlot\n")
                                        sb.append("    i2d\n")
                                        sb.append("    dload $tempSlot\n")
                                        sb.append("    ddiv\n")
                                    } else {
                                        sb.append("    idiv\n")
                                    }
                                }
                                "Rem" -> {
                                    if (argType is ClassName.Simple && argType.name == "Real") {
                                        val tempSlot = methodSymbol.symbolTable.getAllSymbols().size + 1
                                        sb.append("    dstore $tempSlot\n")
                                        sb.append("    i2d\n")
                                        sb.append("    dload $tempSlot\n")
                                        sb.append("    drem\n")
                                    } else {
                                        sb.append("    irem\n")
                                    }
                                }
                                "UnaryMinus" -> {
                                    sb.append("    ineg\n")
                                }
                                "GreaterEqual" -> {
                                    if (argType is ClassName.Simple && argType.name == "Real") {
                                        val tempSlot = methodSymbol.symbolTable.getAllSymbols().size + 1
                                        sb.append("    dstore $tempSlot\n")
                                        sb.append("    i2d\n")
                                        sb.append("    dload $tempSlot\n")
                                        val trueLabel = newLabel("Lge_true_")
                                        val endLabel = newLabel("Lge_end_")
                                        sb.append("    dcmpg\n")
                                        sb.append("    ifge $trueLabel\n")
                                        sb.append("    iconst_0\n")
                                        sb.append("    goto $endLabel\n")
                                        sb.append("$trueLabel:\n")
                                        sb.append("    iconst_1\n")
                                        sb.append("$endLabel:\n")
                                    } else {
                                        val trueLabel = newLabel("Lge_true_")
                                        val endLabel = newLabel("Lge_end_")
                                        sb.append("    if_icmpge $trueLabel\n")
                                        sb.append("    iconst_0\n")
                                        sb.append("    goto $endLabel\n")
                                        sb.append("$trueLabel:\n")
                                        sb.append("    iconst_1\n")
                                        sb.append("$endLabel:\n")
                                    }
                                }
                                "Less" -> {
                                    if (argType is ClassName.Simple && argType.name == "Real") {
                                        val tempSlot = methodSymbol.symbolTable.getAllSymbols().size + 1
                                        sb.append("    dstore $tempSlot\n")
                                        sb.append("    i2d\n")
                                        sb.append("    dload $tempSlot\n")
                                        val trueLabel = newLabel("Llt_true_")
                                        val endLabel = newLabel("Llt_end_")
                                        sb.append("    dcmpg\n")
                                        sb.append("    iflt $trueLabel\n")
                                        sb.append("    iconst_0\n")
                                        sb.append("    goto $endLabel\n")
                                        sb.append("$trueLabel:\n")
                                        sb.append("    iconst_1\n")
                                        sb.append("$endLabel:\n")
                                    } else {
                                        val trueLabel = newLabel("Llt_true_")
                                        val endLabel = newLabel("Llt_end_")
                                        sb.append("    if_icmplt $trueLabel\n")
                                        sb.append("    iconst_0\n")
                                        sb.append("    goto $endLabel\n")
                                        sb.append("$trueLabel:\n")
                                        sb.append("    iconst_1\n")
                                        sb.append("$endLabel:\n")
                                    }
                                }
                                "LessEqual" -> {
                                    if (argType is ClassName.Simple && argType.name == "Real") {
                                        val tempSlot = methodSymbol.symbolTable.getAllSymbols().size + 1
                                        sb.append("    dstore $tempSlot\n")
                                        sb.append("    i2d\n")
                                        sb.append("    dload $tempSlot\n")
                                        val trueLabel = newLabel("Lle_true_")
                                        val endLabel = newLabel("Lle_end_")
                                        sb.append("    dcmpg\n")
                                        sb.append("    ifle $trueLabel\n")
                                        sb.append("    iconst_0\n")
                                        sb.append("    goto $endLabel\n")
                                        sb.append("$trueLabel:\n")
                                        sb.append("    iconst_1\n")
                                        sb.append("$endLabel:\n")
                                    } else {
                                        val trueLabel = newLabel("Lle_true_")
                                        val endLabel = newLabel("Lle_end_")
                                        sb.append("    if_icmple $trueLabel\n")
                                        sb.append("    iconst_0\n")
                                        sb.append("    goto $endLabel\n")
                                        sb.append("$trueLabel:\n")
                                        sb.append("    iconst_1\n")
                                        sb.append("$endLabel:\n")
                                    }
                                }
                                "Greater" -> {
                                    if (argType is ClassName.Simple && argType.name == "Real") {
                                        val tempSlot = methodSymbol.symbolTable.getAllSymbols().size + 1
                                        sb.append("    dstore $tempSlot\n")
                                        sb.append("    i2d\n")
                                        sb.append("    dload $tempSlot\n")
                                        val trueLabel = newLabel("Lgt_true_")
                                        val endLabel = newLabel("Lgt_end_")
                                        sb.append("    dcmpg\n")
                                        sb.append("    ifgt $trueLabel\n")
                                        sb.append("    iconst_0\n")
                                        sb.append("    goto $endLabel\n")
                                        sb.append("$trueLabel:\n")
                                        sb.append("    iconst_1\n")
                                        sb.append("$endLabel:\n")
                                    } else {
                                        val trueLabel = newLabel("Lgt_true_")
                                        val endLabel = newLabel("Lgt_end_")
                                        sb.append("    if_icmpgt $trueLabel\n")
                                        sb.append("    iconst_0\n")
                                        sb.append("    goto $endLabel\n")
                                        sb.append("$trueLabel:\n")
                                        sb.append("    iconst_1\n")
                                        sb.append("$endLabel:\n")
                                    }
                                }
                                "Equal" -> {
                                    if (argType is ClassName.Simple && argType.name == "Real") {
                                        val tempSlot = methodSymbol.symbolTable.getAllSymbols().size + 1
                                        sb.append("    dstore $tempSlot\n")
                                        sb.append("    i2d\n")
                                        sb.append("    dload $tempSlot\n")
                                        val trueLabel = newLabel("Leq_true_")
                                        val endLabel = newLabel("Leq_end_")
                                        sb.append("    dcmpg\n")
                                        sb.append("    ifeq $trueLabel\n")
                                        sb.append("    iconst_0\n")
                                        sb.append("    goto $endLabel\n")
                                        sb.append("$trueLabel:\n")
                                        sb.append("    iconst_1\n")
                                        sb.append("$endLabel:\n")
                                    } else {
                                        val trueLabel = newLabel("Leq_true_")
                                        val endLabel = newLabel("Leq_end_")
                                        sb.append("    if_icmpeq $trueLabel\n")
                                        sb.append("    iconst_0\n")
                                        sb.append("    goto $endLabel\n")
                                        sb.append("$trueLabel:\n")
                                        sb.append("    iconst_1\n")
                                        sb.append("$endLabel:\n")
                                    }
                                }
                                "NotEqual" -> {
                                    if (argType is ClassName.Simple && argType.name == "Real") {
                                        val tempSlot = methodSymbol.symbolTable.getAllSymbols().size + 1
                                        sb.append("    dstore $tempSlot\n")
                                        sb.append("    i2d\n")
                                        sb.append("    dload $tempSlot\n")
                                        val trueLabel = newLabel("Lne_true_")
                                        val endLabel = newLabel("Lne_end_")
                                        sb.append("    dcmpg\n")
                                        sb.append("    ifne $trueLabel\n")
                                        sb.append("    iconst_0\n")
                                        sb.append("    goto $endLabel\n")
                                        sb.append("$trueLabel:\n")
                                        sb.append("    iconst_1\n")
                                        sb.append("$endLabel:\n")
                                    } else {
                                        val trueLabel = newLabel("Lne_true_")
                                        val endLabel = newLabel("Lne_end_")
                                        sb.append("    if_icmpne $trueLabel\n")
                                        sb.append("    iconst_0\n")
                                        sb.append("    goto $endLabel\n")
                                        sb.append("$trueLabel:\n")
                                        sb.append("    iconst_1\n")
                                        sb.append("$endLabel:\n")
                                    }
                                }
                                "toReal" -> {
                                    sb.append("    i2d\n")
                                }
                                "toBoolean" -> {
                                    val falseLabel = newLabel("LtoBool_false_")
                                    val endLabel = newLabel("LtoBool_end_")
                                    sb.append("    ifeq $falseLabel\n")
                                    sb.append("    iconst_1\n")
                                    sb.append("    goto $endLabel\n")
                                    sb.append("$falseLabel:\n")
                                    sb.append("    iconst_0\n")
                                    sb.append("$endLabel:\n")
                                }
                                else -> error("Unknown Integer method '${call.method}'")
                            }
                        }
                        "Real" -> {
                            val argType = if (call.args.isNotEmpty()) {
                                inferExprType(call.args[0], classSymbol, methodSymbol)
                            } else {
                                null
                            }
                            
                            when (call.method) {
                                "Plus" -> {
                                    if (argType is ClassName.Simple && argType.name == "Integer") {
                                        val intSlot = methodSymbol.symbolTable.getAllSymbols().size + 1
                                        val realSlot = intSlot + 1
                                        sb.append("    istore $intSlot\n")
                                        sb.append("    dstore $realSlot\n")
                                        sb.append("    iload $intSlot\n")
                                        sb.append("    i2d\n")
                                        sb.append("    dload $realSlot\n")
                                        sb.append("    dadd\n")
                                    } else {
                                        sb.append("    dadd\n")
                                    }
                                }
                                "Mult" -> {
                                    if (argType is ClassName.Simple && argType.name == "Integer") {
                                        val intSlot = methodSymbol.symbolTable.getAllSymbols().size + 1
                                        val realSlot = intSlot + 1
                                        sb.append("    istore $intSlot\n")
                                        sb.append("    dstore $realSlot\n")
                                        sb.append("    iload $intSlot\n")
                                        sb.append("    i2d\n")
                                        sb.append("    dload $realSlot\n")
                                        sb.append("    dmul\n")
                                    } else {
                                        sb.append("    dmul\n")
                                    }
                                }
                                "Minus" -> {
                                    if (argType is ClassName.Simple && argType.name == "Integer") {
                                        val intSlot = methodSymbol.symbolTable.getAllSymbols().size + 1
                                        val realSlot = intSlot + 1
                                        sb.append("    istore $intSlot\n")
                                        sb.append("    dstore $realSlot\n")
                                        sb.append("    iload $intSlot\n")
                                        sb.append("    i2d\n")
                                        sb.append("    dload $realSlot\n")
                                        sb.append("    dsub\n")
                                    } else {
                                        sb.append("    dsub\n")
                                    }
                                }
                                "Div" -> {
                                    if (argType is ClassName.Simple && argType.name == "Integer") {
                                        val intSlot = methodSymbol.symbolTable.getAllSymbols().size + 1
                                        val realSlot = intSlot + 1
                                        sb.append("    istore $intSlot\n")
                                        sb.append("    dstore $realSlot\n")
                                        sb.append("    iload $intSlot\n")
                                        sb.append("    i2d\n")
                                        sb.append("    dload $realSlot\n")
                                        sb.append("    ddiv\n")
                                    } else {
                                        sb.append("    ddiv\n")
                                    }
                                }
                                "Rem" -> {
                                    if (argType is ClassName.Simple && argType.name == "Integer") {
                                        val intSlot = methodSymbol.symbolTable.getAllSymbols().size + 1
                                        val realSlot = intSlot + 1
                                        sb.append("    istore $intSlot\n")
                                        sb.append("    dstore $realSlot\n")
                                        sb.append("    iload $intSlot\n")
                                        sb.append("    i2d\n")
                                        sb.append("    dload $realSlot\n")
                                        sb.append("    drem\n")
                                    } else {
                                        sb.append("    drem\n")
                                    }
                                }
                                "UnaryMinus" -> {
                                    sb.append("    dneg\n")
                                }
                                "toInteger" -> {
                                    sb.append("    d2i\n")
                                }
                                "Equal" -> {
                                    if (argType is ClassName.Simple && argType.name == "Integer") {
                                        val intSlot = methodSymbol.symbolTable.getAllSymbols().size + 1
                                        val realSlot = intSlot + 1
                                        sb.append("    istore $intSlot\n")
                                        sb.append("    dstore $realSlot\n")
                                        sb.append("    iload $intSlot\n")
                                        sb.append("    i2d\n")
                                        sb.append("    dload $realSlot\n")
                                        val trueLabel = newLabel("Lreq_true_")
                                        val endLabel = newLabel("Lreq_end_")
                                        sb.append("    dcmpg\n")
                                        sb.append("    ifeq $trueLabel\n")
                                        sb.append("    iconst_0\n")
                                        sb.append("    goto $endLabel\n")
                                        sb.append("$trueLabel:\n")
                                        sb.append("    iconst_1\n")
                                        sb.append("$endLabel:\n")
                                    } else {
                                        val trueLabel = newLabel("Lreq_true_")
                                        val endLabel = newLabel("Lreq_end_")
                                        sb.append("    dcmpg\n")
                                        sb.append("    ifeq $trueLabel\n")
                                        sb.append("    iconst_0\n")
                                        sb.append("    goto $endLabel\n")
                                        sb.append("$trueLabel:\n")
                                        sb.append("    iconst_1\n")
                                        sb.append("$endLabel:\n")
                                    }
                                }
                                "NotEqual" -> {
                                    if (argType is ClassName.Simple && argType.name == "Integer") {
                                        val intSlot = methodSymbol.symbolTable.getAllSymbols().size + 1
                                        val realSlot = intSlot + 1
                                        sb.append("    istore $intSlot\n")
                                        sb.append("    dstore $realSlot\n")
                                        sb.append("    iload $intSlot\n")
                                        sb.append("    i2d\n")
                                        sb.append("    dload $realSlot\n")
                                        val trueLabel = newLabel("Lrne_true_")
                                        val endLabel = newLabel("Lrne_end_")
                                        sb.append("    dcmpg\n")
                                        sb.append("    ifne $trueLabel\n")
                                        sb.append("    iconst_0\n")
                                        sb.append("    goto $endLabel\n")
                                        sb.append("$trueLabel:\n")
                                        sb.append("    iconst_1\n")
                                        sb.append("$endLabel:\n")
                                    } else {
                                        val trueLabel = newLabel("Lrne_true_")
                                        val endLabel = newLabel("Lrne_end_")
                                        sb.append("    dcmpg\n")
                                        sb.append("    ifne $trueLabel\n")
                                        sb.append("    iconst_0\n")
                                        sb.append("    goto $endLabel\n")
                                        sb.append("$trueLabel:\n")
                                        sb.append("    iconst_1\n")
                                        sb.append("$endLabel:\n")
                                    }
                                }
                                "Less" -> {
                                    if (argType is ClassName.Simple && argType.name == "Integer") {
                                        val intSlot = methodSymbol.symbolTable.getAllSymbols().size + 1
                                        val realSlot = intSlot + 1
                                        sb.append("    istore $intSlot\n")
                                        sb.append("    dstore $realSlot\n")
                                        sb.append("    iload $intSlot\n")
                                        sb.append("    i2d\n")
                                        sb.append("    dload $realSlot\n")
                                        val trueLabel = newLabel("Lrlt_true_")
                                        val endLabel = newLabel("Lrlt_end_")
                                        sb.append("    dcmpg\n")
                                        sb.append("    iflt $trueLabel\n")
                                        sb.append("    iconst_0\n")
                                        sb.append("    goto $endLabel\n")
                                        sb.append("$trueLabel:\n")
                                        sb.append("    iconst_1\n")
                                        sb.append("$endLabel:\n")
                                    } else {
                                        val trueLabel = newLabel("Lrlt_true_")
                                        val endLabel = newLabel("Lrlt_end_")
                                        sb.append("    dcmpg\n")
                                        sb.append("    iflt $trueLabel\n")
                                        sb.append("    iconst_0\n")
                                        sb.append("    goto $endLabel\n")
                                        sb.append("$trueLabel:\n")
                                        sb.append("    iconst_1\n")
                                        sb.append("$endLabel:\n")
                                    }
                                }
                                "Greater" -> {
                                    if (argType is ClassName.Simple && argType.name == "Integer") {
                                        val intSlot = methodSymbol.symbolTable.getAllSymbols().size + 1
                                        val realSlot = intSlot + 1
                                        sb.append("    istore $intSlot\n")
                                        sb.append("    dstore $realSlot\n")
                                        sb.append("    iload $intSlot\n")
                                        sb.append("    i2d\n")
                                        sb.append("    dload $realSlot\n")
                                        val trueLabel = newLabel("Lrgt_true_")
                                        val endLabel = newLabel("Lrgt_end_")
                                        sb.append("    dcmpg\n")
                                        sb.append("    ifgt $trueLabel\n")
                                        sb.append("    iconst_0\n")
                                        sb.append("    goto $endLabel\n")
                                        sb.append("$trueLabel:\n")
                                        sb.append("    iconst_1\n")
                                        sb.append("$endLabel:\n")
                                    } else {
                                        val trueLabel = newLabel("Lrgt_true_")
                                        val endLabel = newLabel("Lrgt_end_")
                                        sb.append("    dcmpg\n")
                                        sb.append("    ifgt $trueLabel\n")
                                        sb.append("    iconst_0\n")
                                        sb.append("    goto $endLabel\n")
                                        sb.append("$trueLabel:\n")
                                        sb.append("    iconst_1\n")
                                        sb.append("$endLabel:\n")
                                    }
                                }
                                "LessEqual" -> {
                                    if (argType is ClassName.Simple && argType.name == "Integer") {
                                        val intSlot = methodSymbol.symbolTable.getAllSymbols().size + 1
                                        val realSlot = intSlot + 1
                                        sb.append("    istore $intSlot\n")
                                        sb.append("    dstore $realSlot\n")
                                        sb.append("    iload $intSlot\n")
                                        sb.append("    i2d\n")
                                        sb.append("    dload $realSlot\n")
                                        val trueLabel = newLabel("Lrle_true_")
                                        val endLabel = newLabel("Lrle_end_")
                                        sb.append("    dcmpg\n")
                                        sb.append("    ifle $trueLabel\n")
                                        sb.append("    iconst_0\n")
                                        sb.append("    goto $endLabel\n")
                                        sb.append("$trueLabel:\n")
                                        sb.append("    iconst_1\n")
                                        sb.append("$endLabel:\n")
                                    } else {
                                        val trueLabel = newLabel("Lrle_true_")
                                        val endLabel = newLabel("Lrle_end_")
                                        sb.append("    dcmpg\n")
                                        sb.append("    ifle $trueLabel\n")
                                        sb.append("    iconst_0\n")
                                        sb.append("    goto $endLabel\n")
                                        sb.append("$trueLabel:\n")
                                        sb.append("    iconst_1\n")
                                        sb.append("$endLabel:\n")
                                    }
                                }
                                "GreaterEqual" -> {
                                    if (argType is ClassName.Simple && argType.name == "Integer") {
                                        val intSlot = methodSymbol.symbolTable.getAllSymbols().size + 1
                                        val realSlot = intSlot + 1
                                        sb.append("    istore $intSlot\n")
                                        sb.append("    dstore $realSlot\n")
                                        sb.append("    iload $intSlot\n")
                                        sb.append("    i2d\n")
                                        sb.append("    dload $realSlot\n")
                                        val trueLabel = newLabel("Lrge_true_")
                                        val endLabel = newLabel("Lrge_end_")
                                        sb.append("    dcmpg\n")
                                        sb.append("    ifge $trueLabel\n")
                                        sb.append("    iconst_0\n")
                                        sb.append("    goto $endLabel\n")
                                        sb.append("$trueLabel:\n")
                                        sb.append("    iconst_1\n")
                                        sb.append("$endLabel:\n")
                                    } else {
                                        val trueLabel = newLabel("Lrge_true_")
                                        val endLabel = newLabel("Lrge_end_")
                                        sb.append("    dcmpg\n")
                                        sb.append("    ifge $trueLabel\n")
                                        sb.append("    iconst_0\n")
                                        sb.append("    goto $endLabel\n")
                                        sb.append("$trueLabel:\n")
                                        sb.append("    iconst_1\n")
                                        sb.append("$endLabel:\n")
                                    }
                                }
                                else -> error("Unknown Real method '${call.method}'")
                            }
                        }
                        "Bool", "Boolean" -> {
                            when (call.method) {
                                "Equal" -> {
                                    val trueLabel = newLabel("Lbeq_true_")
                                    val endLabel = newLabel("Lbeq_end_")
                                    sb.append("    if_icmpeq $trueLabel\n")
                                    sb.append("    iconst_0\n")
                                    sb.append("    goto $endLabel\n")
                                    sb.append("$trueLabel:\n")
                                    sb.append("    iconst_1\n")
                                    sb.append("$endLabel:\n")
                                }
                                "NotEqual" -> {
                                    val trueLabel = newLabel("Lbne_true_")
                                    val endLabel = newLabel("Lbne_end_")
                                    sb.append("    if_icmpne $trueLabel\n")
                                    sb.append("    iconst_0\n")
                                    sb.append("    goto $endLabel\n")
                                    sb.append("$trueLabel:\n")
                                    sb.append("    iconst_1\n")
                                    sb.append("$endLabel:\n")
                                }
                                "And" -> {
                                    val trueLabel = newLabel("Lband_true_")
                                    val endLabel = newLabel("Lband_end_")
                                    sb.append("    iand\n")
                                    sb.append("    ifne $trueLabel\n")
                                    sb.append("    iconst_0\n")
                                    sb.append("    goto $endLabel\n")
                                    sb.append("$trueLabel:\n")
                                    sb.append("    iconst_1\n")
                                    sb.append("$endLabel:\n")
                                }
                                "Or" -> {
                                    val trueLabel = newLabel("Lbor_true_")
                                    val endLabel = newLabel("Lbor_end_")
                                    sb.append("    ior\n")
                                    sb.append("    ifne $trueLabel\n")
                                    sb.append("    iconst_0\n")
                                    sb.append("    goto $endLabel\n")
                                    sb.append("$trueLabel:\n")
                                    sb.append("    iconst_1\n")
                                    sb.append("$endLabel:\n")
                                }
                                "Xor" -> {
                                    val trueLabel = newLabel("Lbxor_true_")
                                    val endLabel = newLabel("Lbxor_end_")
                                    sb.append("    ixor\n")
                                    sb.append("    ifne $trueLabel\n")
                                    sb.append("    iconst_0\n")
                                    sb.append("    goto $endLabel\n")
                                    sb.append("$trueLabel:\n")
                                    sb.append("    iconst_1\n")
                                    sb.append("$endLabel:\n")
                                }
                                "Not" -> {
                                    val trueLabel = newLabel("Lbnot_true_")
                                    val endLabel = newLabel("Lbnot_end_")
                                    sb.append("    ifeq $trueLabel\n")
                                    sb.append("    iconst_0\n")
                                    sb.append("    goto $endLabel\n")
                                    sb.append("$trueLabel:\n")
                                    sb.append("    iconst_1\n")
                                    sb.append("$endLabel:\n")
                                }
                                "toInteger" -> {
                                }
                                else -> error("Unknown Bool method '${call.method}'")
                            }
                        }
                        else -> error("Builtin method calls for type '${receiverType.name}' not implemented")
                    }
                }
                return
            }

            if (isBuiltin(receiverType)) {
                error("Builtin type '${receiverType}' should have been handled earlier")
            }
            
            if (receiverType is ClassName.Simple && receiverType.name == "Unknown") {
                if (call.method in listOf("Equal", "NotEqual") && call.args.size == 1) {
                    val trueLabel = newLabel("Lbeq_true_")
                    val endLabel = newLabel("Lbeq_end_")
                    sb.append("    if_icmpeq $trueLabel\n")
                    sb.append("    iconst_0\n")
                    sb.append("    goto $endLabel\n")
                    sb.append("$trueLabel:\n")
                    sb.append("    iconst_1\n")
                    sb.append("$endLabel:\n")
                    return
                }
                error("Cannot determine receiver class for call: receiver type is Unknown. Call: ${call.receiver} -> ${call.method}")
            }
            
            val recvClass = classTable.getClass(receiverType)
                ?: error("Unknown receiver class for call: $receiverType. Call: ${call.receiver} -> ${call.method}")

            val targetMethod = resolveMethodForCall(
                ownerClass = recvClass,
                methodName = call.method,
                argTypes = argTypes
            )

            val argsDesc = targetMethod.params.joinToString("") { toJasminType(it.type) }
            val retDesc = targetMethod.returnType?.let { toJasminType(it) } ?: "V"

            sb.append("    invokevirtual ${recvClass.name}/${targetMethod.name}($argsDesc)$retDesc\n")
        }
    }

    private fun inferExprType(
        expr: Expr,
        classSymbol: ClassSymbol,
        methodSymbol: MethodSymbol
    ): ClassName {
        return when (expr) {
            is Expr.IntLit -> ClassName.Simple("Integer")
            is Expr.RealLit -> ClassName.Simple("Real")
            is Expr.BoolLit -> ClassName.Simple("Bool")

            is Expr.This -> ClassName.Simple(classSymbol.name)

            is Expr.Identifier -> {
                val name = expr.name
                methodSymbol.symbolTable.findSymbol(name)?.type
                    ?: classSymbol.findField(name)?.type
                    ?: ClassName.Simple("Unknown")
            }

            is Expr.FieldAccess -> {
                val recvType = inferExprType(expr.receiver, classSymbol, methodSymbol)
                val recvClass = classTable.getClass(recvType)
                    ?: return ClassName.Simple("Unknown")

                recvClass.findField(expr.name)?.type ?: ClassName.Simple("Unknown")
            }

            is Expr.Call -> {
                if (expr.receiver == null && expr.method == "print") {
                    return ClassName.Simple("void")
                }

                val receiverType: ClassName?
                if (expr.receiver != null) {
                    receiverType = inferExprType(expr.receiver, classSymbol, methodSymbol)
                    
                    val argType = if (expr.args.isNotEmpty()) {
                        inferExprType(expr.args[0], classSymbol, methodSymbol)
                    } else {
                        null
                    }
                    
                    if (receiverType is ClassName.Simple && receiverType.name == "Integer") {
                        when (expr.method) {
                            "toReal" -> return ClassName.Simple("Real")
                            "toBoolean" -> return ClassName.Simple("Bool")
                            "UnaryMinus" -> return ClassName.Simple("Integer")
                            "Plus" -> {
                                if (argType is ClassName.Simple && argType.name == "Real") {
                                    return ClassName.Simple("Real") 
                                }
                                return ClassName.Simple("Integer")  
                            }
                            "Mult" -> {
                                if (argType is ClassName.Simple && argType.name == "Real") {
                                    return ClassName.Simple("Real")
                                }
                                return ClassName.Simple("Integer")
                            }
                            "Minus" -> {
                                if (argType is ClassName.Simple && argType.name == "Real") {
                                    return ClassName.Simple("Real")
                                }
                                return ClassName.Simple("Integer")
                            }
                            "Div" -> {
                                if (argType is ClassName.Simple && argType.name == "Real") {
                                    return ClassName.Simple("Real")
                                }
                                return ClassName.Simple("Integer")
                            }
                            "Rem" -> {
                                if (argType is ClassName.Simple && argType.name == "Real") {
                                    return ClassName.Simple("Real")
                                }
                                return ClassName.Simple("Integer")
                            }
                            "Equal", "NotEqual", "Less", "Greater", "LessEqual", "GreaterEqual" -> {
                                return ClassName.Simple("Bool")
                            }
                            else -> {}
                        }
                    }
                    
                    if (receiverType is ClassName.Simple && receiverType.name == "Real") {
                        when (expr.method) {
                            "toInteger" -> return ClassName.Simple("Integer")
                            "UnaryMinus" -> return ClassName.Simple("Real")
                            "Plus", "Mult", "Minus", "Div", "Rem" -> return ClassName.Simple("Real")
                            "Equal", "NotEqual", "Less", "Greater", "LessEqual", "GreaterEqual" -> {
                                return ClassName.Simple("Bool")
                            }
                            else -> {}
                        }
                    }
                    
                    if (receiverType is ClassName.Simple && (receiverType.name == "Bool" || receiverType.name == "Boolean")) {
                        when (expr.method) {
                            "toInteger" -> return ClassName.Simple("Integer")
                            "Equal", "NotEqual", "And", "Or", "Xor", "Not" -> {
                                return ClassName.Simple("Bool")
                            }
                            else -> {}
                        }
                    }
                    
                    if (receiverType is ClassName.Generic && receiverType.name == "Array") {
                        when (expr.method) {
                            "Length" -> return ClassName.Simple("Integer")
                            "get" -> {
                                if (receiverType.typeArgs.isNotEmpty()) {
                                    return receiverType.typeArgs[0]
                                }
                                return ClassName.Simple("Unknown")
                            }
                            "set" -> return ClassName.Simple("void")
                            else -> {}
                        }
                    }
                } else {
                    receiverType = ClassName.Simple(classSymbol.name) 
                }

                if (isBuiltin(receiverType)) {
                    when (receiverType) {
                        is ClassName.Simple -> when (receiverType.name) {
                            "Integer" -> {
                                val argType = if (expr.args.isNotEmpty()) {
                                    inferExprType(expr.args[0], classSymbol, methodSymbol)
                                } else null
                                when (expr.method) {
                                    "Plus", "Mult", "Minus", "Div", "Rem", "UnaryMinus" -> {
                                        if (argType is ClassName.Simple && argType.name == "Real") {
                                            return ClassName.Simple("Real")
                                        }
                                        return ClassName.Simple("Integer")
                                    }
                                    "toReal" -> return ClassName.Simple("Real")
                                    "toBoolean" -> return ClassName.Simple("Bool")
                                    "Equal", "NotEqual", "Less", "Greater", "LessEqual", "GreaterEqual" -> return ClassName.Simple("Bool")
                                    else -> return ClassName.Simple("Unknown")
                                }
                            }
                            "Real" -> {
                                when (expr.method) {
                                    "Plus", "Mult", "Minus", "Div", "Rem", "UnaryMinus" -> return ClassName.Simple("Real")
                                    "toInteger" -> return ClassName.Simple("Integer")
                                    "Equal", "NotEqual", "Less", "Greater", "LessEqual", "GreaterEqual" -> return ClassName.Simple("Bool")
                                    else -> return ClassName.Simple("Unknown")
                                }
                            }
                            "Bool", "Boolean" -> {
                                when (expr.method) {
                                    "Equal", "NotEqual", "And", "Or", "Xor", "Not" -> return ClassName.Simple("Bool")
                                    "toInteger" -> return ClassName.Simple("Integer")
                                    else -> return ClassName.Simple("Unknown")
                                }
                            }
                            else -> return ClassName.Simple("Unknown")
                        }
                        is ClassName.Generic -> when (receiverType.name) {
                            "Array" -> {
                                when (expr.method) {
                                    "Length" -> return ClassName.Simple("Integer")
                                    "get" -> {
                                        if (receiverType.typeArgs.isNotEmpty()) {
                                            return receiverType.typeArgs[0]
                                        }
                                        return ClassName.Simple("Unknown")
                                    }
                                    "set" -> return ClassName.Simple("void")
                                    else -> return ClassName.Simple("Unknown")
                                }
                            }
                            else -> return ClassName.Simple("Unknown")
                        }
                    }
                }
                
                val ownerClass = classTable.getClass(receiverType)
                    ?: return ClassName.Simple("Unknown")

                val argTypes = expr.args.map { inferExprType(it, classSymbol, methodSymbol) }

                val targetMethod = resolveMethodForCall(ownerClass, expr.method, argTypes)
                targetMethod.returnType ?: ClassName.Simple("void")
            }

            is Expr.ClassNameExpr -> expr.cn
        }
    }

    private fun findFieldOwnerClass(fieldName: String, classSymbol: ClassSymbol): String {
        if (fieldName in classSymbol.fields) {
            return classSymbol.name
        }
        var current = classSymbol.parentClass
        while (current != null) {
            if (fieldName in current.fields) {
                return current.name
            }
            current = current.parentClass
        }
        return classSymbol.name
    }

    private fun getRealJvmIndex(
        variableName: String,
        methodSymbol: MethodSymbol
    ): Int {
        val logicalIndex = methodSymbol.symbolTable.getIndex(variableName)

        var realIndex = 1
        
        methodSymbol.params.forEachIndexed { paramIndex, param ->
            if (paramIndex < logicalIndex) {
                realIndex += getJvmSlotSize(param.type)
            } else if (paramIndex == logicalIndex && param.name == variableName) {
                return realIndex
            }
        }
        
        val paramCount = methodSymbol.params.size
        if (logicalIndex >= paramCount) {
            methodSymbol.params.forEach { param ->
                realIndex += getJvmSlotSize(param.type)
            }
            
            val allSymbols = methodSymbol.symbolTable.getAllSymbols()
            allSymbols.forEach { (name, symbol) ->
                val isParam = methodSymbol.params.any { it.name == name }
                if (!isParam) {
                    val varLogicalIndex = methodSymbol.symbolTable.getIndex(name)
                    if (varLogicalIndex < logicalIndex) {
                        realIndex += getJvmSlotSize(symbol.type)
                    } else if (varLogicalIndex == logicalIndex && name == variableName) {
                        return realIndex
                    }
                }
            }
        }
        
        return realIndex
    }

    private fun resolveMethodForCall(
        ownerClass: ClassSymbol,
        methodName: String,
        argTypes: List<ClassName>
    ): MethodSymbol {
        val candidates = ownerClass.findMethods(methodName)
        val matched = candidates.find { m ->
            m.params.size == argTypes.size &&
                    m.params.mapIndexed { index, p -> p.type }.zip(argTypes).all { (t1, t2) ->
                        t1 is ClassName.Simple && t2 is ClassName.Simple && t1.name == t2.name
                    }
        } ?: error("No suitable method '$methodName' found in class '${ownerClass.name}'")
        return matched
    }
    
    companion object {
        private var labelCounter = 0
        private fun newLabel(base: String = "L"): String = "${base}${labelCounter++}"
    }


}