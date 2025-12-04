package syntaxer

object AstPrinter {
    fun print(program: Program, name: String) {
        println("\n" + "=".repeat(30) + name + "=".repeat(30))
        program.classes.forEachIndexed { index, classDecl ->
            printClass(classDecl, "", index == program.classes.size - 1)
        }
        println("=".repeat(60+name.length) + "\n")
    }

    private fun printClass(classDecl: ClassDecl, indent: String, isLast: Boolean) {
        val connector = if (isLast) "└── " else "├── "
        println("$indent$connector Class: ${classNameToString(classDecl.name)}")
        
        if (classDecl.parent != null) {
            val childConnector = if (isLast) "    " else "│   "
            println("$indent$childConnector extends ${classNameToString(classDecl.parent)}")
        }

        val newIndent = indent + if (isLast) "    " else "│   "
        classDecl.members.forEachIndexed { index, member ->
            printMember(member, newIndent, index == classDecl.members.size - 1)
        }
    }

    private fun printMember(member: MemberDecl, indent: String, isLast: Boolean) {
        when (member) {
            is MemberDecl.VarDecl -> {
                val connector = if (isLast) "└── " else "├── "
                val varType = member.type
                println("$indent$connector Field: ${member.name} : ${classNameToString(varType)}")
            }
            is MemberDecl.MethodDecl -> {
                val connector = if (isLast) "└── " else "├── "
                val paramsStr = member.params.joinToString(", ") { "${it.name}: ${classNameToString(it.type)}" }
                val returnTypeStr = member.returnType?.let { classNameToString(it) } ?: "void"
                println("$indent$connector Method: ${member.name}($paramsStr) : $returnTypeStr")
                
                member.body?.let { body ->
                    val newIndent = indent + if (isLast) "    " else "│   "
                    printMethodBody(body, newIndent)
                }
            }
            is MemberDecl.ConstructorDecl -> {
                val connector = if (isLast) "└── " else "├── "
                val paramsStr = member.params.joinToString(", ") { "${it.name}: ${classNameToString(it.type)}" }
                println("$indent$connector Constructor: this($paramsStr)")
                
                val newIndent = indent + if (isLast) "    " else "│   "
                printMethodBody(member.body, newIndent)
            }
        }
    }

    private fun printMethodBody(body: MethodBody, indent: String) {
        when (body) {
            is MethodBody.BlockBody -> {
                body.vars.forEachIndexed { index, varDecl ->
                    val connector = if (index == body.vars.size - 1 && body.stmts.isEmpty()) "└── " else "├── "
                    val varType = varDecl.type
                    println("$indent$connector Local Var: ${varDecl.name} : ${classNameToString(varType)}")
                }
                
                body.stmts.forEachIndexed { index, stmt ->
                    val isLast = index == body.stmts.size - 1
                    printStmt(stmt, indent, isLast)
                }
            }
        }
    }

    private fun printStmt(stmt: Stmt, indent: String, isLast: Boolean) {
        val connector = if (isLast) "└── " else "├── "
        when (stmt) {
            is Stmt.Assignment -> {
                println("$indent$connector Assignment: ${stmt.target} := ${exprToString(stmt.expr)}")
            }
            is Stmt.While -> {
                println("$indent$connector While: ${exprToString(stmt.cond)}")
                val newIndent = indent + if (isLast) "    " else "│   "
                printMethodBody(stmt.body, newIndent)
            }
            is Stmt.If -> {
                println("$indent$connector If: ${exprToString(stmt.cond)}")
                val newIndent = indent + if (isLast) "    " else "│   "
                println("$newIndent├── Then:")
                printMethodBody(stmt.thenBody, newIndent + "│   ")
                stmt.elseBody?.let { elseBody ->
                    println("$newIndent└── Else:")
                    printMethodBody(elseBody, newIndent + "    ")
                }
            }
            is Stmt.Return -> {
                val exprStr = stmt.expr?.let { exprToString(it) } ?: "(void)"
                println("$indent$connector Return: $exprStr")
            }
            is Stmt.ExprStmt -> {
                println("$indent$connector ExprStmt: ${exprToString(stmt.expr)}")
            }
        }
    }

    private fun exprToString(expr: Expr): String {
        return when (expr) {
            is Expr.IntLit -> expr.v.toString()
            is Expr.RealLit -> expr.v.toString()
            is Expr.BoolLit -> expr.v.toString()
            is Expr.This -> "this"
            is Expr.Identifier -> expr.name
            is Expr.Call -> {
                val receiverStr = expr.receiver?.let { "${exprToString(it)}." } ?: ""
                val argsStr = expr.args.joinToString(", ") { exprToString(it) }
                "$receiverStr${expr.method}($argsStr)"
            }
            is Expr.FieldAccess -> {
                "${exprToString(expr.receiver)}.${expr.name}"
            }
            is Expr.ClassNameExpr -> {
                classNameToString(expr.cn)
            }
        }
    }

    private fun extractTypeFromInit(init: Expr): ClassName {
        return when (init) {
            is Expr.ClassNameExpr -> init.cn
            is Expr.Call -> {
                ClassName.Simple(init.method)
            }
            is Expr.IntLit -> ClassName.Simple("Int")
            is Expr.RealLit -> ClassName.Simple("Real")
            is Expr.BoolLit -> ClassName.Simple("Bool")
            else -> ClassName.Simple("Unknown")
        }
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
}
