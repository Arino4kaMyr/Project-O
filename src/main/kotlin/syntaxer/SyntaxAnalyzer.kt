package syntaxer

import exceptions.NotFoundException
import token.Token
import token.TokenStream
import token.TokenType

class SyntaxAnalyzer(
    val tokens: List<Token>
) {
    private val ts = TokenStream(tokens)

    fun parseProgram(): Program {
        val listOfClasses = mutableListOf<ClassDecl>()
        while (ts.matchTokenType(TokenType.KEYWORD) && ts.peek().text == CLASS) {
            ts.next()
            val classDecl = parseClass()
            listOfClasses.add(classDecl)
        }
        val program = Program(listOfClasses)
        AstPrinter.print(program, "AST")
        
        return program
    }

    private fun parseClass(): ClassDecl {
        val tokenName = ts.expect(TokenType.IDENTIFIER)
        val className = ClassName.Simple(tokenName.text)

        var parentName: ClassName? = null
        var members: List<MemberDecl> = emptyList()

        if (!ts.matchTokenType(TokenType.KEYWORD)) throw NotFoundException("There is no code block in class header in line ${ts.peek().line}")

        if (ts.matchAndNext(TokenType.KEYWORD, EXTENDS)) {
            val parentToken = ts.expect(TokenType.IDENTIFIER)
            parentName = ClassName.Simple(parentToken.text)
        }
        if (ts.matchAndNext(TokenType.KEYWORD, START_OF_CODE_BLOCK)) {
            members = parseClassMembers()
            ts.expectText(END_OF_CODE_BLOCK)
        }
        return ClassDecl(
            className,
            parentName,
            members
        )
    }

    private fun parseClassMembers(): List<MemberDecl> {
        val members = mutableListOf<MemberDecl>()

        while (ts.peek().text != END_OF_CODE_BLOCK) {
            if (ts.matchTokenType(TokenType.KEYWORD) && 
                (ts.peek().text == "private" || ts.peek().text == "public")) {
                val visibility = parseAccessModifier()
                if (ts.matchAndNext(TokenType.KEYWORD, VAR)) {
                    val v = parseVarDecl(visibility)
                    members.add(v)
                    continue
                }
            }
            
            if (ts.matchAndNext(TokenType.KEYWORD, VAR)) {
                val v = parseVarDecl(AccessModifier.PUBLIC)
                members.add(v)
                continue
            } else if (ts.matchAndNext(TokenType.KEYWORD, METHOD)) {
                val m = parseMethodDecl()
                members.add(m)
                continue
            } else if (ts.matchAndNext(TokenType.KEYWORD, CONSTRUCTOR)) {
                val c = parseConstructorDecl()
                members.add(c)
                continue
            } else {
                throw NotFoundException("Expected class member (var/method/this), but found: ${ts.peek().text} in line ${ts.peek().line}")
            }
        }

        return members
    }

    private fun parseAccessModifier(): AccessModifier {
        if (ts.matchTokenType(TokenType.KEYWORD)) {
            when (ts.peek().text) {
                "private" -> {
                    ts.next()
                    return AccessModifier.PRIVATE
                }
                "public" -> {
                    ts.next()
                    return AccessModifier.PUBLIC
                }
            }
        }
        return AccessModifier.PUBLIC
    }

    private fun parseVarDecl(visibility: AccessModifier = AccessModifier.PUBLIC): MemberDecl.VarDecl {
        val nameTok = ts.expect(TokenType.IDENTIFIER)
        val name = nameTok.text

        if (ts.peek().text == ":") ts.next() else throw NotFoundException("Expected ':' in var decl, got ${ts.peek().text} in line ${ts.peek().line}")

        val typeName = parseType()
        
        val baseTypeName = when (typeName) {
            is ClassName.Simple -> typeName.name
            is ClassName.Generic -> typeName.name
        }

        val initExpr: Expr = if (ts.peek().text == OPEN_BRACKET) {
            val args = parseArgs()
            Expr.Call(null, baseTypeName, args)
        } else {
            Expr.ClassNameExpr(typeName)
        }

        return MemberDecl.VarDecl(name, typeName, initExpr, visibility)
    }

    private fun parseMethodDecl(): MemberDecl.MethodDecl {
        val nameTok = ts.expect(TokenType.IDENTIFIER)
        val name = nameTok.text

        val params = parseParams()

        var returnType: ClassName? = null
        if (ts.peek().text == ":") {
            ts.next()
            returnType = parseType()
        }

        var methodBody: MethodBody? = null
        if (ts.matchAndNext(TokenType.KEYWORD, START_OF_CODE_BLOCK)) {
            methodBody = parseMethodBody()
        }

        return MemberDecl.MethodDecl(name, params, returnType, methodBody)
    }

    private fun parseParams(): List<Param> {
        val params = mutableListOf<Param>()

        if (ts.matchAndNext(TokenType.SPECIAL_SYMBOL, OPEN_BRACKET)) {
            if (ts.peek().text == CLOSE_BRACKET) {
                ts.next()
                return params
            }

            while (true) {
                val nameTok = ts.expect(TokenType.IDENTIFIER)
                val paramName = nameTok.text

                if (ts.peek().text == ":") ts.next() else throw NotFoundException("Expected ':' in parameter, got ${ts.peek().text} in line ${ts.peek().line}")

                val type = parseType()
                params.add(Param(paramName, type))

                if (ts.peek().text == ",") {
                    ts.next()
                    continue
                }

                if (ts.peek().text == CLOSE_BRACKET) {
                    ts.next()
                    break
                }

                throw NotFoundException("Unexpected token in parameter list: ${ts.peek().text} in line ${ts.peek().line}")
            }
        } else {
            throw NotFoundException("Expected '$OPEN_BRACKET' before parameters list, got ${ts.peek().text} in line ${ts.peek().line}")
        }

        return params
    }

    private fun parseMethodBody(): MethodBody.BlockBody {
        val vars = mutableListOf<MemberDecl.VarDecl>()
        val stmts = mutableListOf<Stmt>()

        while (!(ts.matchTokenType(TokenType.KEYWORD) && ts.peek().text == END_OF_CODE_BLOCK)) {
            if (ts.matchAndNext(TokenType.KEYWORD, VAR)) {
                val v = parseVarDecl()
                vars.add(v)
                continue
            }

            val s = parseStmt()
            if (s != null) stmts.add(s)
        }

        ts.next()
        return MethodBody.BlockBody(vars, stmts)
    }

    private fun parseConstructorDecl(): MemberDecl.ConstructorDecl {
        val params = mutableListOf<Param>()

        if (ts.matchAndNext(TokenType.SPECIAL_SYMBOL, OPEN_BRACKET)) {
            if (ts.peek().text == CLOSE_BRACKET) {
                ts.next()
            } else {
                while (true) {
                    val nameTok = ts.expect(TokenType.IDENTIFIER)
                    val paramName = nameTok.text
                    if (ts.peek().text == ":") ts.next() else throw NotFoundException("Expected ':' in constructor parameter in line ${ts.peek().line}")
                    val type = parseType()
                    params.add(Param(paramName, type))

                    if (ts.peek().text == ",") {
                        ts.next()
                        continue
                    }
                    if (ts.peek().text == CLOSE_BRACKET) {
                        ts.next()
                        break
                    }
                }
            }
        } else {
            throw NotFoundException("Expected '(' after constructor 'this' in line ${ts.peek().line}")
        }

        var body: MethodBody = MethodBody.BlockBody(emptyList(), emptyList())
        if (ts.matchAndNext(TokenType.KEYWORD, START_OF_CODE_BLOCK)) {
            body = parseMethodBody()
        }

        return MemberDecl.ConstructorDecl(params, body)
    }

    private fun parseStmt(): Stmt? {
        if (ts.matchTokenType(TokenType.KEYWORD) && ts.peek().text == RETURN) {
            ts.next()
            if (ts.matchTokenType(TokenType.KEYWORD) && (ts.peek().text == END_OF_CODE_BLOCK || ts.peek().text == ELSE)) {
                return Stmt.Return(null)
            }
            val expr = parseExpr()
            return Stmt.Return(expr)
        }

        if (ts.matchTokenType(TokenType.KEYWORD) && ts.peek().text == WHILE) {
            ts.next()
            val cond = parseExpr()
            if (!(ts.matchTokenType(TokenType.KEYWORD) && ts.peek().text == LOOP)) throw NotFoundException("Expected 'loop' after while condition in line ${ts.peek().line}")
            ts.next()
            val body = parseMethodBody()
            return Stmt.While(cond, body)
        }

        if (ts.matchTokenType(TokenType.KEYWORD) && ts.peek().text == IF) {
            ts.next()
            val cond = parseExpr()

            if (!(ts.matchTokenType(TokenType.KEYWORD) && ts.peek().text == THEN)) throw NotFoundException("Expected 'then' after if condition in line ${ts.peek().line}")
            ts.next()

            val thenVars = mutableListOf<MemberDecl.VarDecl>()
            val thenStmts = mutableListOf<Stmt>()
            while (!(ts.matchTokenType(TokenType.KEYWORD) && (ts.peek().text == ELSE || ts.peek().text == END_OF_CODE_BLOCK))) {
                if (ts.matchAndNext(TokenType.KEYWORD, VAR)) {
                    thenVars.add(parseVarDecl())
                    continue
                }
                val s = parseStmt()
                if (s != null) thenStmts.add(s)
            }
            val thenBody = MethodBody.BlockBody(thenVars, thenStmts)

            var elseBody: MethodBody? = null
            if (ts.matchTokenType(TokenType.KEYWORD) && ts.peek().text == ELSE) {
                ts.next()
                val elseVars = mutableListOf<MemberDecl.VarDecl>()
                val elseStmts = mutableListOf<Stmt>()
                while (!(ts.matchTokenType(TokenType.KEYWORD) && ts.peek().text == END_OF_CODE_BLOCK)) {
                    if (ts.matchAndNext(TokenType.KEYWORD, VAR)) {
                        elseVars.add(parseVarDecl())
                        continue
                    }
                    val s = parseStmt()
                    if (s != null) elseStmts.add(s)
                }
                elseBody = MethodBody.BlockBody(elseVars, elseStmts)
            }

            val endTok = ts.expect(TokenType.KEYWORD)
            if (endTok.text != END_OF_CODE_BLOCK) throw NotFoundException("Expected 'end' after if, got ${endTok.text} in line ${endTok.line}")

            return Stmt.If(cond, thenBody, elseBody)
        }

        if (ts.matchTokenType(TokenType.KEYWORD) && ts.peek().text == THIS) {
            ts.next()
            
            if (ts.peek().text == ".") {
                ts.next()
                val fieldTok = ts.expect(TokenType.IDENTIFIER)
                val fieldName = fieldTok.text
                
                if (ts.peek().text == ASSIGN) {
                    ts.next()
                    val expr = parseExpr()
                    return Stmt.Assignment("this.${fieldName}", expr)
                } else {
                    if (ts.peek().text == OPEN_BRACKET) {
                        val args = parseArgs()
                        val expr = Expr.Call(Expr.This, fieldName, args)
                        return Stmt.ExprStmt(expr)
                    }
                    
                    var expr: Expr = Expr.FieldAccess(Expr.This, fieldName)
                    
                    while (ts.peek().text == ".") {
                        ts.next()
                        val nameTok = ts.expect(TokenType.IDENTIFIER)
                        val name = nameTok.text
                        
                        if (ts.peek().text == OPEN_BRACKET) {
                            val args = parseArgs()
                            expr = Expr.Call(expr, name, args)
                        } else {
                            expr = Expr.FieldAccess(expr, name)
                        }
                    }
                    return Stmt.ExprStmt(expr)
                }
            } else {
                var expr: Expr = Expr.This
                
                while (ts.peek().text == ".") {
                    ts.next()
                    val nameTok = ts.expect(TokenType.IDENTIFIER)
                    val name = nameTok.text
                    
                    if (ts.peek().text == OPEN_BRACKET) {
                        val args = parseArgs()
                        expr = Expr.Call(expr, name, args)
                    } else {
                        expr = Expr.FieldAccess(expr, name)
                    }
                }
                return Stmt.ExprStmt(expr)
            }
        }
        
        if (ts.matchTokenType(TokenType.IDENTIFIER)) {
            val idTok = ts.next()
            if (ts.peek().text == ASSIGN) {
                ts.next()
                val expr = parseExpr()
                return Stmt.Assignment(idTok.text, expr)
            } else {
                val expr = parseExprStartingWithIdentifier(idTok)
                return Stmt.ExprStmt(expr)
            }
        }

        throw NotFoundException("Unknown statement start: ${ts.peek().text} in line ${ts.peek().line}")
    }

    private fun parseExprStartingWithIdentifier(firstTok: Token): Expr {
        var expr: Expr = Expr.Identifier(firstTok.text)

        if (ts.peek().text == OPEN_BRACKET) {
            val args = parseArgs()
            expr = Expr.Call(null, firstTok.text, args)
        }

        while (ts.peek().text == ".") {
            ts.next()
            val nameTok = ts.expect(TokenType.IDENTIFIER)
            val name = nameTok.text
            if (ts.peek().text == OPEN_BRACKET) {
                val args = parseArgs()
                expr = Expr.Call(expr, name, args)
            } else {
                expr = Expr.FieldAccess(expr, name)
            }
        }

        return expr
    }

    private fun parseExpr(): Expr {
        var left = parsePrimary()

        while (ts.peek().text == ".") {
            ts.next()
            val nameTok = ts.expect(TokenType.IDENTIFIER)
            val name = nameTok.text
            if (ts.peek().text == OPEN_BRACKET) {
                val args = parseArgs()
                left = Expr.Call(left, name, args)
            } else {
                left = Expr.FieldAccess(left, name)
            }
        }

        return left
    }

    private fun parsePrimary(): Expr {
        val p = ts.peek()
        when (p.type) {
            TokenType.NUMBER -> {
                val token = ts.next()

                val expr = if (token.text.contains('.') || token.text.contains('e', ignoreCase = true)) {
                    Expr.RealLit(token.text.toDouble())
                } else {
                    Expr.IntLit(token.text.toLong())
                }

                return expr
            }
            TokenType.IDENTIFIER -> {
                val idTok = ts.next()
                if (ts.peek().text == OPEN_BRACKET) {
                    val args = parseArgs()
                    return Expr.Call(null, idTok.text, args)
                }
                return Expr.Identifier(idTok.text)
            }
            TokenType.KEYWORD -> {
                if (p.text == THIS) {
                    ts.next()
                    return Expr.This
                }
                if (p.text == TRUE) {
                    ts.next()
                    return Expr.BoolLit(true)
                }
                if (p.text == FALSE) {
                    ts.next()
                    return Expr.BoolLit(false)
                }
            }
            TokenType.SPECIAL_SYMBOL -> {
                if (p.text == OPEN_BRACKET) {
                    ts.next()
                    val inner = parseExpr()
                    val close = ts.expect(TokenType.SPECIAL_SYMBOL)
                    if (close.text != CLOSE_BRACKET) throw NotFoundException("Expected ')', got ${close.text} in line ${close.line}")
                    return inner
                }
            }
            else -> {
                
            }
        }
        throw NotFoundException("Unknown primary expression: ${p.text} in line ${p.line}")
    }

    private fun parseType(): ClassName {
        val typeNameTok = ts.expect(TokenType.IDENTIFIER)
        val typeName = typeNameTok.text
        
        if (ts.peek().text == "[") {
            ts.next()
            val typeArgs = mutableListOf<ClassName>()
            
            typeArgs.add(parseType())
            
            while (ts.peek().text == ",") {
                ts.next()
                typeArgs.add(parseType())
            }
            
            ts.expectText("]")
            return ClassName.Generic(typeName, typeArgs)
        }
        
        return ClassName.Simple(typeName)
    }

    private fun parseArgs(): List<Expr> {
        val args = mutableListOf<Expr>()
        val open = ts.expect(TokenType.SPECIAL_SYMBOL)
        if (open.text != OPEN_BRACKET) throw NotFoundException("Expected '(' to start args, got ${open.text} in line ${open.text}")

        if (ts.peek().text == CLOSE_BRACKET) {
            ts.next()
            return args
        }

        while (true) {
            val e = parseExpr()
            args.add(e)
            if (ts.peek().text == ",") {
                ts.next()
                continue
            }
            if (ts.peek().text == CLOSE_BRACKET) {
                ts.next()
                break
            }
            throw NotFoundException("Unexpected token in arguments: ${ts.peek().text} in line ${ts.peek().line}")
        }
        return args
    }

    companion object {
        private const val CLASS = "class"
        private const val EXTENDS = "extends"
        private const val START_OF_CODE_BLOCK = "is"
        private const val END_OF_CODE_BLOCK = "end"

        private const val VAR = "var"
        private const val METHOD = "method"
        private const val CONSTRUCTOR = "this"

        private const val OPEN_BRACKET = "("
        private const val CLOSE_BRACKET = ")"

        private const val WHILE = "while"
        private const val LOOP = "loop"
        private const val IF = "if"
        private const val THEN = "then"
        private const val ELSE = "else"
        private const val RETURN = "return"
        private const val THIS = "this"
        private const val TRUE = "true"
        private const val FALSE = "false"

        private const val ASSIGN = ":="
    }
}
