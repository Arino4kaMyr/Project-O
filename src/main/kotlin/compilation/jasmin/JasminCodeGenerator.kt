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
        classSymbol.fields.values.forEach { fieldSymbol ->
            val fieldName = fieldSymbol.name
            val jasminType = toJasminType(fieldSymbol.type)
            sb.append(".field private $fieldName $jasminType\n")
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
            sb.append("    aload_0\n")
            // Вызов конструктора суперкласса
            val superName = classSymbol.parentClass?.name ?: "java/lang/Object"
            sb.append("    invokespecial $superName/<init>()V\n")
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

            // 2. Вызов super.<init>()
            sb.append("    aload_0\n")
            val superName = classSymbol.parentClass?.name ?: "java/lang/Object"
            sb.append("    invokespecial $superName/<init>()V\n")

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

            // TODO: .limit stack / .limit locals можно добавить позже (или довериться Jasminу, конечно да!)
            // sb.append("    .limit stack 32\n")
            // sb.append("    .limit locals 32\n")

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
        }
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
                    // Предположим, что пока работаем только с Integer → ireturn
                    sb.append("    ireturn\n")
                } ?: run {
                    // return без значения, для void‑методов
                    sb.append("    return\n")
                }
            }

            is Stmt.ExprStmt -> {
                // Просто вычисляем выражение, результат игнорируем (снимется за счёт последующих операций или оставим)
                generateExpr(sb, stmt.expr, classSymbol, methodSymbol)
                // Для простых случаев (например, вызов метода, который возвращает значение),
                // можно явно снимать со стека инструкцией pop:
                sb.append("    pop\n")
            }

            is Stmt.Assignment -> {
                val name = stmt.target

                // Локал/параметр?
                val local = methodSymbol.symbolTable.findSymbol(name)
                if (local != null) {
                    // expr → стек
                    generateExpr(sb, stmt.expr, classSymbol, methodSymbol)
                    val index = methodSymbol.symbolTable.getIndex(name)
                    // считаем, что int/bool
                    sb.append("    istore $index\n")
                    return
                }

                // Поле класса?
                val field = classSymbol.findField(name)
                if (field != null) {
                    val jasminType = toJasminType(field.type)
                    // this.<field> := expr
                    // Надо: aload_0, expr, putfield
                    sb.append("    aload_0\n")
                    generateExpr(sb, stmt.expr, classSymbol, methodSymbol)
                    sb.append("    putfield ${classSymbol.name}/${field.name} $jasminType\n")
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
                    val index = methodSymbol.symbolTable.getIndex(name)
                    // Сейчас считаем, что это int/bool → iload
                    sb.append("    iload $index\n")
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
                val recvType: ClassName = /* здесь нужен тип receiver из семантики, сейчас можно взять ClassName.Simple(classSymbol.name) для this */
                    ClassName.Simple(classSymbol.name)
                val recvClass = classTable.getClass(recvType)
                    ?: error("Unknown class for receiver in FieldAccess: $recvType")

                val field = recvClass.findField(expr.name)
                    ?: error("Unknown field '${expr.name}' in class '${recvClass.name}'")
                val jasminType = toJasminType(field.type)
                sb.append("    getfield ${recvClass.name}/${field.name} $jasminType\n")
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
        // Типы аргументов — в идеале надо взять из семантики (getTypeOfExpr),
        // но здесь можем использовать methodSymbol или classTable, если это вызов своего метода.

        // Сначала receiver (если есть)
        if (call.receiver != null) {
            generateExpr(sb, call.receiver, classSymbol, methodSymbol)
        }

        // Затем аргументы
        call.args.forEach { arg ->
            generateExpr(sb, arg, classSymbol, methodSymbol)
        }

        // Разрешаем метод
        // Если receiver == null → считаем, что это статический метод текущего класса
        if (call.receiver == null) {
            val argTypes: List<ClassName> = call.args.map { _ ->
                // здесь по уму нужен тип из семантики, но для упрощения:
                ClassName.Simple("Integer")
            }

            val targetMethod = resolveMethodForCall(
                ownerClass = classSymbol,
                methodName = call.method,
                argTypes = argTypes
            )

            val argsDesc = targetMethod.params.joinToString("") { toJasminType(it.type) }
            val retDesc = targetMethod.returnType?.let { toJasminType(it) } ?: "V"

            sb.append("    invokestatic ${classSymbol.name}/${targetMethod.name}($argsDesc)$retDesc\n")
        } else {
            // метод на объекте
            val argTypes: List<ClassName> = call.args.map { ClassName.Simple("Integer") } // здесь тоже нужно взять реальные типы

            // тип receiver'а — тут по-хорошему вызывать семантический getTypeOfExpr
            val recvType = ClassName.Simple(classSymbol.name)
            val recvClass = classTable.getClass(recvType)
                ?: error("Unknown receiver class for call: $recvType")

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