package semantic

import syntaxer.*

class SemanticAnalyzer(private val program: Program) {
    
    fun analyze(symbolTable: SymbolTable) {
        program.classes.forEach { classDecl ->
            analyzeClass(classDecl, symbolTable)
        }
    }

    private fun analyzeClass(classDecl: ClassDecl, symbolTable: SymbolTable) {
        classDecl.members.forEach { member ->
            when (member) {
                is MemberDecl.VarDecl -> {
                    val varType = extractTypeFromInit(member.init)
                    symbolTable.addSymbol(VarSymbol(member.name, varType))
                }
                is MemberDecl.MethodDecl -> {
                    member.params.forEach { param ->
                        symbolTable.addSymbol(VarSymbol(param.name, param.type))
                    }
                    member.body?.let { body ->
                        analyzeMethodBody(body, symbolTable)
                    }
                }
                is MemberDecl.ConstructorDecl -> {
                    member.params.forEach { param ->
                        symbolTable.addSymbol(VarSymbol(param.name, param.type))
                    }
                    analyzeMethodBody(member.body, symbolTable)
                }
            }
        }
    }

    private fun analyzeMethodBody(body: MethodBody, symbolTable: SymbolTable) {
        when (body) {
            is MethodBody.BlockBody -> {
                body.vars.forEach { varDecl ->
                    val varType = extractTypeFromInit(varDecl.init)
                    symbolTable.addSymbol(VarSymbol(varDecl.name, varType))
                }
                
                body.stmts.forEach { stmt ->
                    when (stmt) {
                        is Stmt.If -> {
                            analyzeMethodBody(stmt.thenBody, symbolTable)
                            stmt.elseBody?.let { elseBody ->
                                analyzeMethodBody(elseBody, symbolTable)
                            }
                        }
                        is Stmt.While -> {
                            analyzeMethodBody(stmt.body, symbolTable)
                        }
                        else -> {
                        }
                    }
                }
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
}

