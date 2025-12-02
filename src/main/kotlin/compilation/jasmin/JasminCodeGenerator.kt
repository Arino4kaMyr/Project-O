package compilation.jasmin

import semantic.ClassSymbol
import semantic.MethodSymbol
import semantic.tables.ClassTable
import syntaxer.*

class JasminCodeGenerator(
    private val program: Program,
    private val classTable: ClassTable
) {

    // generate jasmin code, one jasmin file per one class!
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
            is ClassName.Generic -> p.name.replace('.', '/')  // Дженерики в родителях пока не поддерживаются
        }
        sb.append(".super $superName\n")

        generateField(sb, classDecl, classSymbol)             // поля
        generateConstructors(sb, classDecl, classSymbol)  // конструкторы
        generateMethods(sb, classDecl, classSymbol)       // обычные методы


        return sb.toString()
    }

    private fun generateField(
        sb: StringBuilder,
        classDecl: ClassDecl,
        classSymbol: ClassSymbol,
    ) {
        // Идём по полям класса из AST, чтобы получить модификатор доступа
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

        // Если в AST нет ни одного ConstructorDecl — сделаем дефолтный конструктор
        val constructors = classDecl.members.filterIsInstance<MemberDecl.ConstructorDecl>()
        if (constructors.isEmpty()) {
            // default <init>()V
            sb.append(".method public <init>()V\n")
            sb.append("    .limit stack 32\n")
            sb.append("    .limit locals 16\n")
            sb.append("    aload_0\n")
            // Вызов конструктора суперкласса
            val superName = classSymbol.parentClass?.name ?: "java/lang/Object"
            sb.append("    invokespecial $superName/<init>()V\n")

            // Инициализация полей класса из AST VarDecl
            createObject(classDecl, classSymbol, sb)


            sb.append("    return\n")
            sb.append(".end method\n\n")
            return
        }

        constructors.forEach { ctor ->
            // 1. Сигнатура конструктора
            val paramTypesDesc = ctor.params.joinToString(separator = "") { param ->
                toJasminType(param.type)
            }
            sb.append(".method public <init>($paramTypesDesc)V\n")
            sb.append("    .limit stack 32\n")
            sb.append("    .limit locals 16\n")

            // 2. Вызов super.<init>()
            sb.append("    aload_0\n")
            val superName = classSymbol.parentClass?.name ?: "java/lang/Object"
            sb.append("    invokespecial $superName/<init>()V\n")

            // Инициализация полей класса из AST VarDecl
            createObject(classDecl, classSymbol, sb)


            // 3. Генерация тела конструктора из AST body
            generateMethodBody(
                sb = sb,
                body = ctor.body,
                classSymbol = classSymbol,
                methodSymbol = MethodSymbol("<init>", emptyList(), null, null)
            )

            // 4. Возврат
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
                    // Обработка дженериков Array и List
                    if (fieldType is ClassName.Generic) {
                        when (fieldType.name) {
                            "Array" -> {
                                // Array[T](length) -> создание массива
                                sb.append("    aload_0\n")
                                if (init.args.size != 1) {
                                    error("Array constructor requires exactly 1 argument (length)")
                                }
                                // Генерируем длину массива
                                generateExpr(
                                    sb,
                                    init.args[0],
                                    classSymbol,
                                    MethodSymbol("<initField>", emptyList(), null, null)
                                )
                                // Длина уже на стеке (int)
                                val elementType = if (fieldType.typeArgs.isNotEmpty()) {
                                    fieldType.typeArgs[0]
                                } else {
                                    ClassName.Simple("Unknown")
                                }
                                // Создаем массив нужного типа
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
                            // Integer(3)
                            generateExpr(
                                sb,
                                arg,
                                classSymbol,
                                MethodSymbol("<initField>", emptyList(), null, null)
                            )
                        } else {
                            // Integer() или просто Integer без аргументов → 0
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
                            )   // ldc2_w ...
                        } else {
                            // Real() → 0.0
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
                            )   // iconst_0/1
                        } else {
                            // Bool() → false
                            sb.append("    iconst_0\n")
                        }
                        sb.append("    putfield ${classSymbol.name}/${vDecl.name} ${toJasminType(fieldType)}\n")

                    } else {
                        // пользовательский класс: new Type(args) / invokespecial / putfield LType;
                        sb.append("    aload_0\n")              // this
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
                        val argsDesc = init.args.joinToString("") { "I" } // пока считаем все Integer
                        sb.append("    invokespecial $typeName/<init>($argsDesc)V\n")
                        sb.append("    putfield ${classSymbol.name}/${vDecl.name} ${toJasminType(fieldType)}\n")
                    }
                }

                else -> {
                    // при желании можно ещё отдельно обрабатывать IntLit/RealLit/BoolLit
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
            // Находим соответствующий MethodSymbol по имени и типам параметров
            val paramTypes = methodDecl.params.map { it.type }
            val methodsWithName = classSymbol.findMethods(methodDecl.name)

            // Находим methodSymbol, у которого количество параметров и сами параметры
            // из methodDecl и из classTable(classSymbol) совпадают.
            // В таком случае берем этот methodSymbol
            val methodSymbol = methodsWithName.find { m ->
                m.params.size == paramTypes.size &&
                        m.params.map { it.type }.zip(paramTypes).all { (t1, t2) ->
                            t1 is ClassName.Simple && t2 is ClassName.Simple && t1.name == t2.name
                        }
            } ?: error("MethodSymbol not found for ${className}.${methodDecl.name}")

            // 1. Заголовок метода (.method ...)
            val paramDesc = methodSymbol.params.joinToString(separator = "") { p ->
                toJasminType(p.type)
            }
            // return type, if nothing -> it's void
            val returnDesc = methodSymbol.returnType?.let { toJasminType(it) } ?: "V"

            sb.append(".method public ${methodSymbol.name}($paramDesc)$returnDesc\n")

            sb.append("    .limit stack 32\n")
            sb.append("    .limit locals 32\n")

            // 2. Тело метода, если оно есть
            methodDecl.body?.let { body ->
                generateMethodBody(
                    sb = sb,
                    body = body,
                    classSymbol = classSymbol,
                    methodSymbol = methodSymbol
                )
            }

            // 3. Если метод void и нет явного return — добавляем return
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
                // Объединяем переменные и statements в один список, сохраняя порядок
                // Переменные с простыми инициализаторами идут сразу, остальные - по порядку
                val allItems = mutableListOf<Pair<Boolean, Any>>() // (isVar, item)
                
                body.vars.forEach { varDecl ->
                    allItems.add(true to varDecl)
                }
                body.stmts.forEach { stmt ->
                    allItems.add(false to stmt)
                }
                
                // Обрабатываем элементы по порядку
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
    
    /**
     * Инициализация локальной переменной в методе
     */
    private fun initializeLocalVariable(
        sb: StringBuilder,
        varDecl: MemberDecl.VarDecl,
        classSymbol: ClassSymbol,
        methodSymbol: MethodSymbol
    ) {
        val varType = varDecl.type
        val varName = varDecl.name
        val init = varDecl.init
        
        // Получаем индекс локальной переменной
        val localIndex = methodSymbol.symbolTable.getIndex(varName)
        val realIndex = getRealJvmIndex(varName, methodSymbol)
        
        when (varType) {
            is ClassName.Generic -> when (varType.name) {
                "Array" -> {
                    // Array[T](length) -> создание массива
                    if (init is Expr.Call && init.args.size == 1) {
                        // Генерируем длину массива
                        generateExpr(sb, init.args[0], classSymbol, methodSymbol)
                        // Длина уже на стеке (int)
                        val elementType = if (varType.typeArgs.isNotEmpty()) {
                            varType.typeArgs[0]
                        } else {
                            ClassName.Simple("Unknown")
                        }
                        // Создаем массив нужного типа
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
                        // Сохраняем массив в локальную переменную
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
                // Обработка простых типов (Integer, Real, Bool)
                when (varType.name) {
                    "Integer", "Int" -> {
                        if (init is Expr.Call && init.args.isNotEmpty()) {
                            generateExpr(sb, init.args[0], classSymbol, methodSymbol)
                            sb.append("    istore $realIndex\n")
                        } else if (init is Expr.IntLit) {
                            generateExpr(sb, init, classSymbol, methodSymbol)
                            sb.append("    istore $realIndex\n")
                        } else {
                            // Integer() -> 0
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
                            // Real() -> 0.0
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
                            // Bool() -> false
                            sb.append("    iconst_0\n")
                            sb.append("    istore $realIndex\n")
                        }
                    }
                    else -> {
                        // Пользовательские типы - создаем объект
                        if (init is Expr.Call) {
                            sb.append("    new ${varType.name}\n")
                            sb.append("    dup\n")
                            init.args.forEach { arg ->
                                generateExpr(sb, arg, classSymbol, methodSymbol)
                            }
                            val argsDesc = init.args.joinToString("") { "I" } // упрощенно
                            sb.append("    invokespecial ${varType.name}/<init>($argsDesc)V\n")
                            sb.append("    astore $realIndex\n")
                        } else if (init is Expr.ClassNameExpr) {
                            // Без аргументов - просто создаем объект
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
                // Если есть выражение: вернуть его значение
                stmt.expr?.let { expr ->
                    generateExpr(sb, expr, classSymbol, methodSymbol)
                    // Используем правильную инструкцию возврата в зависимости от типа
                    val returnType = methodSymbol.returnType
                    val returnDesc = returnType?.let { toJasminType(it) } ?: "V"
                    when (returnDesc) {
                        "I", "Z" -> sb.append("    ireturn\n")  // Integer или Bool
                        "D" -> sb.append("    dreturn\n")        // Real/Double
                        "V" -> sb.append("    return\n")         // void
                        else -> sb.append("    areturn\n")       // Object types
                    }
                } ?: run {
                    // return без значения, для void‑методов
                    sb.append("    return\n")
                }
            }

            is Stmt.ExprStmt -> {
                // Просто вычисляем выражение, результат игнорируем (снимется за счёт последующих операций или оставим)
                generateExpr(sb, stmt.expr, classSymbol, methodSymbol)
                // Если выражение возвращает значение (не void), нужно убрать его со стека
                val exprType = inferExprType(stmt.expr, classSymbol, methodSymbol)
                // Проверяем, возвращает ли выражение значение (не void)
                val returnsValue = when (exprType) {
                    is ClassName.Simple -> exprType.name != "void" && exprType.name != "Void"
                    is ClassName.Generic -> true  // Дженерики всегда возвращают значение
                }
                if (returnsValue) {
                    sb.append("    pop\n")
                }
            }

            is Stmt.Assignment -> {
                val name = stmt.target

                // Обработка this.field
                val actualName = if (name.startsWith("this.")) {
                    name.removePrefix("this.")
                } else {
                    name
                }

                // Локал/параметр? (только если не this.field)
                if (!name.startsWith("this.")) {
                    val local = methodSymbol.symbolTable.findSymbol(name)
                    if (local != null) {
                        // expr → стек
                        generateExpr(sb, stmt.expr, classSymbol, methodSymbol)
                        val realIndex = getRealJvmIndex(name, methodSymbol)
                        val jasminType = toJasminType(local.type)
                        // Используем правильную инструкцию сохранения в зависимости от типа
                        when (jasminType) {
                            "I", "Z" -> sb.append("    istore $realIndex\n")  // Integer или Bool
                            "D" -> sb.append("    dstore $realIndex\n")      // Real/Double
                            else -> sb.append("    astore $realIndex\n")     // Object types
                        }
                        return
                    }
                }

                // Поле класса? (с учетом this.)
                val field = classSymbol.findField(actualName)
                if (field != null) {
                    val jasminType = toJasminType(field.type)
                    // this.<field> := expr
                    // Надо: aload_0, expr, putfield
                    sb.append("    aload_0\n")
                    generateExpr(sb, stmt.expr, classSymbol, methodSymbol)
                    // Используем имя класса, где поле определено (может быть родительский класс)
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
                // Оптимизированная загрузка целых по правилам JVM:
                sb.append(numberToJasmin(expr.v))
            }

            is Expr.BoolLit -> {
                // В JVM boolean — это тоже int (0/1)
                val v = if (expr.v) 1 else 0
                if (v == 0) sb.append("    iconst_0\n") else sb.append("    iconst_1\n")
            }

            is Expr.RealLit -> {
                // Для простоты можно использовать double и ldc2_w
                val v = expr.v
                sb.append("    ldc2_w $v\n")
            }

            is Expr.Identifier -> {
                val name = expr.name

                // 1. Локальная переменная / параметр?
                val local = methodSymbol.symbolTable.findSymbol(name)
                if (local != null) {
                    val realIndex = getRealJvmIndex(name, methodSymbol)
                    val jasminType = toJasminType(local.type)
                    // Используем правильную инструкцию загрузки в зависимости от типа
                    when (jasminType) {
                        "I", "Z" -> sb.append("    iload $realIndex\n")  // Integer или Bool
                        "D" -> sb.append("    dload $realIndex\n")      // Real/Double
                        else -> sb.append("    aload $realIndex\n")     // Object types
                    }
                    return
                }

                // 2. Поле класса?
                val field = classSymbol.findField(name)
                if (field != null) {
                    val jasminType = toJasminType(field.type)
                    // this.field
                    sb.append("    aload_0\n")
                    sb.append("    getfield ${classSymbol.name}/${field.name} $jasminType\n")
                    return
                }

                error("Unknown identifier '$name' in ${classSymbol.name}.${methodSymbol.name}")
            }

            is Expr.This -> {
                // this — это первый аргумент (локальная 0 в нестатических методах)
                sb.append("    aload_0\n")
            }

            is Expr.Call -> {
                generateCallExpr(sb, expr, classSymbol, methodSymbol)
            }

            is Expr.FieldAccess -> {
                // если нет того к чему применяем метод,
                generateExpr(sb, expr.receiver ?: Expr.This, classSymbol, methodSymbol)
                val recvType: ClassName = inferExprType(expr.receiver ?: Expr.This, classSymbol, methodSymbol)
                val recvClass = classTable.getClass(recvType)
                    ?: error("Unknown class for receiver in FieldAccess: $recvType")

                val field = recvClass.findField(expr.name)
                    ?: error("Unknown field '${expr.name}' in class '${recvClass.name}'")
                val jasminType = toJasminType(field.type)
                // Находим класс, где определено поле (может быть родительский класс)
                val fieldOwnerClass = findFieldOwnerClass(expr.name, recvClass)
                sb.append("    getfield ${fieldOwnerClass}/${field.name} $jasminType\n")
            }

            is Expr.ClassNameExpr -> {
                // Обычно в выражениях это не «значение», а тип; как value оно не нужно.
                // Пока можно ничего не генерировать или бросать ошибку, если такого быть не должно.
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

        // cond
        generateExpr(sb, stmt.cond, classSymbol, methodSymbol)
        sb.append("    ifeq $elseLabel\n")

        // thenBody
        generateMethodBody(sb, stmt.thenBody, classSymbol, methodSymbol)
        sb.append("    goto $endLabel\n")

        // elseLabel
        sb.append("$elseLabel:\n")
        stmt.elseBody?.let { elseBody ->
            generateMethodBody(sb, elseBody, classSymbol, methodSymbol)
        }

        // endLabel
        sb.append("$endLabel:\n")
    }

    private fun generateCallExpr(
        sb: StringBuilder,
        call: Expr.Call,
        classSymbol: ClassSymbol,
        methodSymbol: MethodSymbol
    ) {
        // 0. builtin-функции без receiver (print и т.п.)
        if (call.receiver == null && call.method == "print") {
            // пока поддерживаем только 1 аргумент
            val arg = call.args.singleOrNull()
                ?: error("print(...) with ${call.args.size} args is not supported yet")

            // System.out
            sb.append("    getstatic java/lang/System/out Ljava/io/PrintStream;\n")

            // сгенерировать аргумент
            generateExpr(sb, arg, classSymbol, methodSymbol)

            // выбрать сигнатуру println по типу аргумента
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

        // 1. receiver и его тип
        val receiverType: ClassName?
        if (call.receiver != null) {
            generateExpr(sb, call.receiver, classSymbol, methodSymbol)
            receiverType = inferExprType(call.receiver, classSymbol, methodSymbol)
        } else {
            receiverType = null
        }

        // 2. аргументы и их типы
        val argTypes = call.args.map { arg ->
            generateExpr(sb, arg, classSymbol, methodSymbol)
            inferExprType(arg, classSymbol, methodSymbol)
        }

        if (receiverType == null) {
            // Вызов метода текущего класса как статического (упрощённо)
            val targetMethod = resolveMethodForCall(
                ownerClass = classSymbol,
                methodName = call.method,
                argTypes = argTypes
            )

            val argsDesc = targetMethod.params.joinToString("") { toJasminType(it.type) }
            val retDesc = targetMethod.returnType?.let { toJasminType(it) } ?: "V"

            sb.append("    invokestatic ${classSymbol.name}/${targetMethod.name}($argsDesc)$retDesc\n")
        } else {
            // Вызов метода на объекте: receiver.method(args)
            if (isBuiltin(receiverType)) {
                // Обработка встроенных методов для примитивных типов и дженериков
                when (receiverType) {
                    is ClassName.Generic -> when (receiverType.name) {
                        "Array" -> {
                            when (call.method) {
                                "Length" -> {
                                    // Array.length -> arraylength
                                    sb.append("    arraylength\n")
                                    // arraylength возвращает int, но нам нужен Integer
                                    // На стеке уже int, ничего дополнительного не делаем
                                }
                                "get" -> {
                                    // Array.get(i) -> array[index]
                                    if (call.args.size != 1) {
                                        error("Array.get() requires exactly 1 argument")
                                    }
                                    // Индекс уже на стеке (вызван generateExpr для аргумента)
                                    // Массив уже на стеке (receiver)
                                    // Нужен порядок: [array, index]
                                    // Но сейчас порядок: [receiver, index], что правильно
                                    val elementType = if (receiverType.typeArgs.isNotEmpty()) {
                                        receiverType.typeArgs[0]
                                    } else {
                                        ClassName.Simple("Unknown")
                                    }
                                    // Генерируем код для загрузки элемента
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
                                                sb.append("    aaload\n")  // Object[]
                                            }
                                        }
                                        else -> {
                                            sb.append("    aaload\n")  // Generic type
                                        }
                                    }
                                }
                                "set" -> {
                                    // Array.set(i, v) -> array[index] = value
                                    if (call.args.size != 2) {
                                        error("Array.set() requires exactly 2 arguments")
                                    }
                                    // Стек: [array, index, value]
                                    // Нужно: array[index] = value
                                    val elementType = if (receiverType.typeArgs.isNotEmpty()) {
                                        receiverType.typeArgs[0]
                                    } else {
                                        ClassName.Simple("Unknown")
                                    }
                                    // Генерируем код для сохранения элемента
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
                                                sb.append("    aastore\n")  // Object[]
                                            }
                                        }
                                        else -> {
                                            sb.append("    aastore\n")  // Generic type
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
                            // Определяем тип первого аргумента для перегрузки
                            val argType = if (call.args.isNotEmpty()) {
                                inferExprType(call.args[0], classSymbol, methodSymbol)
                            } else {
                                null
                            }
                            
                            when (call.method) {
                                "Plus" -> {
                                    if (argType is ClassName.Simple && argType.name == "Real") {
                                        // Integer + Real → Real
                                        // Стек: [Integer, Real] -> нужно [Real, Real]
                                        // Используем dup2_x1 для перестановки: [Real, Integer, Real]
                                        // Затем swap (нужно конвертировать Integer, который под Real)
                                        // Проще: сохранить Real, конвертировать Integer, загрузить Real, сложить
                                        val tempSlot = methodSymbol.symbolTable.getAllSymbols().size + 1
                                        sb.append("    dstore $tempSlot\n")  // сохраняем Real
                                        sb.append("    i2d\n")  // конвертируем Integer в Real
                                        sb.append("    dload $tempSlot\n")  // загружаем Real обратно
                                        sb.append("    dadd\n")  // складываем
                                    } else {
                                        // Integer + Integer → Integer
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
                                        // Integer - Real → Real
                                        val tempSlot = methodSymbol.symbolTable.getAllSymbols().size + 1
                                        sb.append("    dstore $tempSlot\n")  // сохраняем Real
                                        sb.append("    i2d\n")  // конвертируем Integer в Real
                                        sb.append("    dload $tempSlot\n")  // загружаем Real
                                        sb.append("    dsub\n")  // Integer - Real
                                    } else {
                                        // Integer - Integer → Integer
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
                                    // Унарный минус: -x
                                    sb.append("    ineg\n")
                                }
                                "GreaterEqual" -> {
                                    if (argType is ClassName.Simple && argType.name == "Real") {
                                        // Integer >= Real → Boolean
                                        // Стек: [Integer, Real] -> нужен [Real, Real] для dcmpg
                                        val tempSlot = methodSymbol.symbolTable.getAllSymbols().size + 1
                                        sb.append("    dstore $tempSlot\n")  // сохраняем Real
                                        sb.append("    i2d\n")  // конвертируем Integer в Real
                                        sb.append("    dload $tempSlot\n")  // загружаем Real
                                        val trueLabel = newLabel("Lge_true_")
                                        val endLabel = newLabel("Lge_end_")
                                        sb.append("    dcmpg\n")  // сравниваем два double (результат: 1, 0, -1)
                                        sb.append("    ifge $trueLabel\n")  // если результат >= 0
                                        sb.append("    iconst_0\n")
                                        sb.append("    goto $endLabel\n")
                                        sb.append("$trueLabel:\n")
                                        sb.append("    iconst_1\n")
                                        sb.append("$endLabel:\n")
                                    } else {
                                        // Integer >= Integer → Boolean
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
                                    // Integer → Real
                                    sb.append("    i2d\n")
                                }
                                "toBoolean" -> {
                                    // Integer → Boolean (0 = false, иначе true)
                                    val falseLabel = newLabel("LtoBool_false_")
                                    val endLabel = newLabel("LtoBool_end_")
                                    sb.append("    ifeq $falseLabel\n")
                                    sb.append("    iconst_1\n")  // true
                                    sb.append("    goto $endLabel\n")
                                    sb.append("$falseLabel:\n")
                                    sb.append("    iconst_0\n")  // false
                                    sb.append("$endLabel:\n")
                                }
                                else -> error("Unknown Integer method '${call.method}'")
                            }
                        }
                        "Real" -> {
                            // Определяем тип первого аргумента для перегрузки
                            val argType = if (call.args.isNotEmpty()) {
                                inferExprType(call.args[0], classSymbol, methodSymbol)
                            } else {
                                null
                            }
                            
                            when (call.method) {
                                "Plus" -> {
                                    if (argType is ClassName.Simple && argType.name == "Integer") {
                                        // Real + Integer → Real
                                        // Стек: [Real(high), Real(low), Integer]
                                        val intSlot = methodSymbol.symbolTable.getAllSymbols().size + 1
                                        val realSlot = intSlot + 1
                                        sb.append("    istore $intSlot\n")  // сохраняем Integer (1 слот)
                                        sb.append("    dstore $realSlot\n")  // сохраняем Real (2 слота)
                                        sb.append("    iload $intSlot\n")  // загружаем Integer
                                        sb.append("    i2d\n")  // конвертируем в Real
                                        sb.append("    dload $realSlot\n")  // загружаем Real
                                        sb.append("    dadd\n")  // Real + Real
                                    } else {
                                        // Real + Real → Real
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
                                        // Real - Integer → Real
                                        val intSlot = methodSymbol.symbolTable.getAllSymbols().size + 1
                                        val realSlot = intSlot + 1
                                        sb.append("    istore $intSlot\n")  // сохраняем Integer
                                        sb.append("    dstore $realSlot\n")  // сохраняем Real
                                        sb.append("    iload $intSlot\n")  // загружаем Integer
                                        sb.append("    i2d\n")  // конвертируем в Real
                                        sb.append("    dload $realSlot\n")  // загружаем Real
                                        sb.append("    dsub\n")  // Real - Integer(Real)
                                    } else {
                                        // Real - Real → Real
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
                                    // Унарный минус для Real: -x
                                    sb.append("    dneg\n")
                                }
                                "toInteger" -> {
                                    // Real → Integer
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
                                    // стек: [x, y] - сравниваем два bool значения (хранятся как int 0/1)
                                    val trueLabel = newLabel("Lbeq_true_")
                                    val endLabel = newLabel("Lbeq_end_")
                                    sb.append("    if_icmpeq $trueLabel\n")
                                    sb.append("    iconst_0\n")  // false
                                    sb.append("    goto $endLabel\n")
                                    sb.append("$trueLabel:\n")
                                    sb.append("    iconst_1\n")  // true
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
                                    // Boolean AND: x && y
                                    // Стек: [x, y] где x и y это 0 или 1
                                    // Результат: 1 если оба 1, иначе 0
                                    val trueLabel = newLabel("Lband_true_")
                                    val endLabel = newLabel("Lband_end_")
                                    sb.append("    iand\n")  // побитовое AND (уже работает для 0/1)
                                    sb.append("    ifne $trueLabel\n")
                                    sb.append("    iconst_0\n")
                                    sb.append("    goto $endLabel\n")
                                    sb.append("$trueLabel:\n")
                                    sb.append("    iconst_1\n")
                                    sb.append("$endLabel:\n")
                                }
                                "Or" -> {
                                    // Boolean OR: x || y
                                    // Стек: [x, y]
                                    val trueLabel = newLabel("Lbor_true_")
                                    val endLabel = newLabel("Lbor_end_")
                                    sb.append("    ior\n")  // побитовое OR
                                    sb.append("    ifne $trueLabel\n")
                                    sb.append("    iconst_0\n")
                                    sb.append("    goto $endLabel\n")
                                    sb.append("$trueLabel:\n")
                                    sb.append("    iconst_1\n")
                                    sb.append("$endLabel:\n")
                                }
                                "Xor" -> {
                                    // Boolean XOR: x ^ y (true если один true, но не оба)
                                    val trueLabel = newLabel("Lbxor_true_")
                                    val endLabel = newLabel("Lbxor_end_")
                                    sb.append("    ixor\n")  // XOR для int (0^0=0, 0^1=1, 1^0=1, 1^1=0)
                                    sb.append("    ifne $trueLabel\n")
                                    sb.append("    iconst_0\n")
                                    sb.append("    goto $endLabel\n")
                                    sb.append("$trueLabel:\n")
                                    sb.append("    iconst_1\n")
                                    sb.append("$endLabel:\n")
                                }
                                "Not" -> {
                                    // Boolean NOT: !x
                                    // 0 (false) -> 1 (true), 1 (true) -> 0 (false)
                                    val trueLabel = newLabel("Lbnot_true_")
                                    val endLabel = newLabel("Lbnot_end_")
                                    sb.append("    ifeq $trueLabel\n")  // если false (0), результат true
                                    sb.append("    iconst_0\n")  // если true (не 0), результат false
                                    sb.append("    goto $endLabel\n")
                                    sb.append("$trueLabel:\n")
                                    sb.append("    iconst_1\n")  // true
                                    sb.append("$endLabel:\n")
                                }
                                "toInteger" -> {
                                    // Boolean → Integer (0 или 1, уже является int)
                                    // Ничего не делаем, Boolean уже хранится как int
                                }
                                else -> error("Unknown Bool method '${call.method}'")
                            }
                        }
                        else -> error("Builtin method calls for type '${receiverType.name}' not implemented")
                    }
                }
                return
            }

            // Если тип - встроенный, уже обработали выше
            if (isBuiltin(receiverType)) {
                error("Builtin type '${receiverType}' should have been handled earlier")
            }
            
            // Если тип - Unknown, не можем определить класс
            if (receiverType is ClassName.Simple && receiverType.name == "Unknown") {
                // Пытаемся обработать как встроенный тип, если метод известен
                // Это может помочь, если тип не был правильно определен
                if (call.method in listOf("Equal", "NotEqual") && call.args.size == 1) {
                    // Попробуем обработать как Bool.Equal
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
                // Сначала ищем среди локальных/параметров метода
                methodSymbol.symbolTable.findSymbol(name)?.type
                // Потом среди полей класса (с учётом наследования)
                    ?: classSymbol.findField(name)?.type
                    ?: ClassName.Simple("Unknown")
            }

            is Expr.FieldAccess -> {
                // receiver.field
                val recvType = inferExprType(expr.receiver, classSymbol, methodSymbol)
                val recvClass = classTable.getClass(recvType)
                    ?: return ClassName.Simple("Unknown")

                recvClass.findField(expr.name)?.type ?: ClassName.Simple("Unknown")
            }

            is Expr.Call -> {
                // Проверка на встроенные методы (print и т.п.)
                if (expr.receiver == null && expr.method == "print") {
                    return ClassName.Simple("void")
                }

                val receiverType: ClassName?
                if (expr.receiver != null) {
                    receiverType = inferExprType(expr.receiver, classSymbol, methodSymbol)
                    
                    // Определяем тип первого аргумента для перегрузки методов
                    val argType = if (expr.args.isNotEmpty()) {
                        inferExprType(expr.args[0], classSymbol, methodSymbol)
                    } else {
                        null
                    }
                    
                    // Обработка методов для Integer с учетом перегрузок
                    if (receiverType is ClassName.Simple && receiverType.name == "Integer") {
                        when (expr.method) {
                            // Методы конвертации
                            "toReal" -> return ClassName.Simple("Real")
                            "toBoolean" -> return ClassName.Simple("Bool")
                            // Унарные операции
                            "UnaryMinus" -> return ClassName.Simple("Integer")
                            // Арифметические операции с перегрузкой
                            "Plus" -> {
                                if (argType is ClassName.Simple && argType.name == "Real") {
                                    return ClassName.Simple("Real")  // Integer + Real → Real
                                }
                                return ClassName.Simple("Integer")  // Integer + Integer → Integer
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
                            // Операции сравнения возвращают Bool (для Integer и Real)
                            "Equal", "NotEqual", "Less", "Greater", "LessEqual", "GreaterEqual" -> {
                                return ClassName.Simple("Bool")
                            }
                            else -> {}
                        }
                    }
                    
                    // Обработка методов для Real с учетом перегрузок
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
                    
                    // Обработка методов для Boolean
                    if (receiverType is ClassName.Simple && (receiverType.name == "Bool" || receiverType.name == "Boolean")) {
                        when (expr.method) {
                            "toInteger" -> return ClassName.Simple("Integer")
                            "Equal", "NotEqual", "And", "Or", "Xor", "Not" -> {
                                return ClassName.Simple("Bool")
                            }
                            else -> {}
                        }
                    }
                    
                    // Обработка методов для Array[T]
                    if (receiverType is ClassName.Generic && receiverType.name == "Array") {
                        when (expr.method) {
                            "Length" -> return ClassName.Simple("Integer")
                            "get" -> {
                                // Array[T].get() -> T
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
                    receiverType = ClassName.Simple(classSymbol.name) // вызов метода своего класса
                }

                // Если receiverType - встроенный тип, определяем тип результата (fallback)
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

                // типы аргументов пока грубо считаем Unknown/Integer — тебе здесь важнее всего receiver
                val argTypes = expr.args.map { inferExprType(it, classSymbol, methodSymbol) }

                val targetMethod = resolveMethodForCall(ownerClass, expr.method, argTypes)
                targetMethod.returnType ?: ClassName.Simple("void")
            }

            is Expr.ClassNameExpr -> expr.cn
        }
    }

    /**
     * Найти класс, где определено поле (с учетом наследования)
     */
    private fun findFieldOwnerClass(fieldName: String, classSymbol: ClassSymbol): String {
        // Сначала проверяем текущий класс
        if (fieldName in classSymbol.fields) {
            return classSymbol.name
        }
        // Если не найдено, ищем в родительских классах
        var current = classSymbol.parentClass
        while (current != null) {
            if (fieldName in current.fields) {
                return current.name
            }
            current = current.parentClass
        }
        // Если не нашли, возвращаем имя текущего класса (для ошибок)
        return classSymbol.name
    }

    /**
     * Вычислить реальный JVM слот индекс с учетом размеров типов
     * В JVM: this (1 слот) + параметры (с учетом размеров) + локальные переменные (с учетом размеров)
     */
    private fun getRealJvmIndex(
        variableName: String,
        methodSymbol: MethodSymbol
    ): Int {
        val logicalIndex = methodSymbol.symbolTable.getIndex(variableName)
        
        // Начинаем с 1 (для this в нестатических методах)
        var realIndex = 1
        
        // Сначала обрабатываем параметры в порядке их объявления
        methodSymbol.params.forEachIndexed { paramIndex, param ->
            if (paramIndex < logicalIndex) {
                // Это параметр, который идет раньше нашей переменной
                realIndex += getJvmSlotSize(param.type)
            } else if (paramIndex == logicalIndex && param.name == variableName) {
                // Это наш параметр
                return realIndex
            }
        }
        
        // Если переменная - параметр, мы уже вернулись
        // Теперь обрабатываем локальные переменные
        // Локальные переменные идут после параметров, их логический индекс начинается с params.size
        val paramCount = methodSymbol.params.size
        if (logicalIndex >= paramCount) {
            // Это локальная переменная
            // Сначала суммируем размеры всех параметров
            methodSymbol.params.forEach { param ->
                realIndex += getJvmSlotSize(param.type)
            }
            
            // Теперь проходим по всем локальным переменным до нашей
            val allSymbols = methodSymbol.symbolTable.getAllSymbols()
            allSymbols.forEach { (name, symbol) ->
                // Пропускаем параметры
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
    
    //metrics for recognize amount of if and while
    companion object {
        private var labelCounter = 0
        private fun newLabel(base: String = "L"): String = "${base}${labelCounter++}"
    }


}