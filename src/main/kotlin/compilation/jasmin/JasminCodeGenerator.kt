package compilation.jasmin

import semantic.ClassSymbol
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
                methodName = "<init>"
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
                    methodName = methodSymbol.name
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
        methodName: String
    ) {
        when (body) {
            is MethodBody.BlockBody -> {
                // Генерируем байткод для каждого стейтмента
                body.stmts.forEach { stmt ->
                    generateStmt(sb, stmt, classSymbol, methodName)
                }
            }
        }
    }

    private fun generateStmt(
        sb: StringBuilder,
        stmt: Stmt,
        classSymbol: ClassSymbol,
        methodName: String
    ) {
        when (stmt) {
            is Stmt.Return -> {
                // Если есть выражение: вернуть его значение
                stmt.expr?.let { expr ->
                    generateExpr(sb, expr, classSymbol, methodName)
                    // Предположим, что пока работаем только с Integer → ireturn
                    sb.append("    ireturn\n")
                } ?: run {
                    // return без значения, для void‑методов
                    sb.append("    return\n")
                }
            }

            is Stmt.ExprStmt -> {
                // Просто вычисляем выражение, результат игнорируем (снимется за счёт последующих операций или оставим)
                generateExpr(sb, stmt.expr, classSymbol, methodName)
                // Для простых случаев (например, вызов метода, который возвращает значение),
                // можно явно снимать со стека инструкцией pop:
                sb.append("    pop\n")
            }

            is Stmt.Assignment -> {
                // Пока не реализуем — здесь нужна таблица локальных/полей и istore/putfield
                // Оставим как TODO
                // TODO: generate assignment
            }

            is Stmt.While -> {
                // Пока не реализуем — нужны labels и ветвления
                // TODO: generate while
            }

            is Stmt.If -> {
                // Пока не реализуем — нужны labels и ветвления
                // TODO: generate if
            }
        }
    }

    private fun generateExpr(
        sb: StringBuilder,
        expr: Expr,
        classSymbol: ClassSymbol,
        methodName: String
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
                // Здесь нужно знать, где хранится переменная: локал или поле.
                // Пока можно оставить заглушку, потом привяжем к MethodTable.
                // TODO: iload N / getfield
            }

            is Expr.This -> {
                // this — это первый аргумент (локальная 0 в нестатических методах)
                sb.append("    aload_0\n")
            }

            is Expr.Call -> {
                // TODO: загрузка receiver (если есть), аргументов, вызов invoke*.
            }

            is Expr.FieldAccess -> {
                // TODO: сгенерировать receiver и getfield
            }

            is Expr.ClassNameExpr -> {
                // Обычно в выражениях это не «значение», а тип; как value оно не нужно.
                // Пока можно ничего не генерировать или бросать ошибку, если такого быть не должно.
            }
        }
    }



}