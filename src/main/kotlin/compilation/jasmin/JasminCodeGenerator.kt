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

        // Идём по полям класса из семантики
        // Используем protected вместо private, чтобы дочерние классы могли обращаться к полям родителя
        classSymbol.fields.values.forEach { fieldSymbol ->
            val fieldName = fieldSymbol.name
            val jasminType = toJasminType(fieldSymbol.type)
            sb.append(".field protected $fieldName $jasminType\n")
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
                    val typeName = (fieldType as ClassName.Simple).name

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

            // пока делаем все методы public
            sb.append(".method public ${methodSymbol.name}($paramDesc)$returnDesc\n")

            sb.append("    .limit stack 32\n")
            sb.append("    .limit locals 16\n")

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
                // Генерируем байткод для каждого стейтмента
                body.stmts.forEach { stmt ->
                    generateStmt(sb, stmt, classSymbol, methodSymbol)
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
                    else -> true
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
                // Обработка встроенных методов для примитивных типов
                when (receiverType) {
                    is ClassName.Simple -> when (receiverType.name) {
                        "Integer" -> {
                            when (call.method) {
                                "Plus" -> {
                                    // стек: [x, y] уже положили перед вызовом (receiver + args)
                                    sb.append("    iadd\n")
                                }
                                "Mult" -> {
                                    sb.append("    imul\n")
                                }
                                else -> error("Unknown Integer method '${call.method}'")
                            }
                        }
                        "Real" -> {
                            when (call.method) {
                                "Plus" -> {
                                    // стек: [x, y] уже положили перед вызовом (receiver + args)
                                    sb.append("    dadd\n")
                                }
                                "Mult" -> {
                                    sb.append("    dmul\n")
                                }
                                else -> error("Unknown Real method '${call.method}'")
                            }
                        }
                        "Bool" -> {
                            error("Builtin method calls for type 'Bool' not implemented")
                        }
                        else -> error("Builtin method calls for type '${receiverType.name}' not implemented")
                    }
                }
                return
            }

            val recvClass = classTable.getClass(receiverType)
                ?: error("Unknown receiver class for call: $receiverType")

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
                } else {
                    receiverType = ClassName.Simple(classSymbol.name) // вызов метода своего класса
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