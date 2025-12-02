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

        // next token should be either 'extends' or 'is'
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

        // loop until we see 'end'
        while (ts.peek().text != END_OF_CODE_BLOCK) {
            // Проверяем, есть ли модификатор доступа перед VAR
            if (ts.matchTokenType(TokenType.KEYWORD) && 
                (ts.peek().text == "private" || ts.peek().text == "public")) {
                // Есть модификатор - читаем его и проверяем, что дальше VAR
                val visibility = parseAccessModifier()
                if (ts.matchAndNext(TokenType.KEYWORD, VAR)) {
                    // Модификатор был перед VAR
                    val v = parseVarDecl(visibility)
                    members.add(v)
                    continue
                }
            }
            
            if (ts.matchAndNext(TokenType.KEYWORD, VAR)) {
                // VAR без модификатора
                val v = parseVarDecl(AccessModifier.PUBLIC)
                members.add(v)
                continue
            } else if (ts.matchAndNext(TokenType.KEYWORD, METHOD)) {
                // Для методов модификаторы доступа не поддерживаются
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
        // По умолчанию public
        return AccessModifier.PUBLIC
    }

    private fun parseVarDecl(visibility: AccessModifier = AccessModifier.PUBLIC): MemberDecl.VarDecl {
        val nameTok = ts.expect(TokenType.IDENTIFIER)
        val name = nameTok.text

        if (ts.peek().text == ":") ts.next() else throw NotFoundException("Expected ':' in var decl, got ${ts.peek().text} in line ${ts.peek().line}")

        val typeName = parseType()
        
        // Для дженериков нужно сохранить базовое имя
        val baseTypeName = when (typeName) {
            is ClassName.Simple -> typeName.name
            is ClassName.Generic -> typeName.name
        }

        val initExpr: Expr = if (ts.peek().text == OPEN_BRACKET) {
            // constructor-like initializer Type(args)
            val args = parseArgs()
            Expr.Call(null, baseTypeName, args)
        } else {
            // no parentheses — just type name as ClassNameExpr
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
            // empty params
            if (ts.peek().text == CLOSE_BRACKET) {
                ts.next() // consume ')'
                return params
            }

            while (true) {
                val nameTok = ts.expect(TokenType.IDENTIFIER)
                val paramName = nameTok.text

                if (ts.peek().text == ":") ts.next() else throw NotFoundException("Expected ':' in parameter, got ${ts.peek().text} in line ${ts.peek().line}")

                val type = parseType()
                params.add(Param(paramName, type))

                if (ts.peek().text == ",") {
                    ts.next() // consume ','
                    continue
                }

                if (ts.peek().text == CLOSE_BRACKET) {
                    ts.next() // consume ')'
                    break
                }

                throw NotFoundException("Unexpected token in parameter list: ${ts.peek().text} in line ${ts.peek().line}")
            }
        } else {
            throw NotFoundException("Expected '$OPEN_BRACKET' before parameters list, got ${ts.peek().text} in line ${ts.peek().line}")
        }

        return params
    }

    //parse method body until end
    private fun parseMethodBody(): MethodBody.BlockBody {
        val vars = mutableListOf<MemberDecl.VarDecl>()
        val stmts = mutableListOf<Stmt>()

        // while not 'end', each iteration either parses a var-decl (if found) or a statement
        while (!(ts.matchTokenType(TokenType.KEYWORD) && ts.peek().text == END_OF_CODE_BLOCK)) {
            if (ts.matchAndNext(TokenType.KEYWORD, VAR)) {
                // parse a var declaration
                val v = parseVarDecl()
                vars.add(v)
                continue
            }

            // otherwise parse a statement (return/if/while/assignment/expr)
            val s = parseStmt()
            if (s != null) stmts.add(s)
        }

        // consume closing 'end'
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
                        ts.next() // consume ')'
                        break
                    }
                }
            }
        } else {
            throw NotFoundException("Expected '(' after constructor 'this' in line ${ts.peek().line}")
        }

        // optional body for constructor
        var body: MethodBody = MethodBody.BlockBody(emptyList(), emptyList())
        if (ts.matchAndNext(TokenType.KEYWORD, START_OF_CODE_BLOCK)) {
            body = parseMethodBody() // consumes 'end'
        }

        return MemberDecl.ConstructorDecl(params, body)
    }

    private fun parseStmt(): Stmt? {
        // RETURN
        if (ts.matchTokenType(TokenType.KEYWORD) && ts.peek().text == RETURN) {
            ts.next()
            // return may be followed by expression or no expr
            if (ts.matchTokenType(TokenType.KEYWORD) && (ts.peek().text == END_OF_CODE_BLOCK || ts.peek().text == ELSE)) {
                return Stmt.Return(null)
            }
            val expr = parseExpr()
            return Stmt.Return(expr)
        }

        // WHILE ... loop ... end
        if (ts.matchTokenType(TokenType.KEYWORD) && ts.peek().text == WHILE) {
            ts.next() // consume 'while'
            val cond = parseExpr()
            if (!(ts.matchTokenType(TokenType.KEYWORD) && ts.peek().text == LOOP)) throw NotFoundException("Expected 'loop' after while condition in line ${ts.peek().line}")
            ts.next() // consume 'loop'
            // parse body until 'end' (parseMethodBody consumes 'end')
            val body = parseMethodBody()
            return Stmt.While(cond, body)
        }

        // IF cond THEN ... [else ...] end
        if (ts.matchTokenType(TokenType.KEYWORD) && ts.peek().text == IF) {
            ts.next() // consume 'if'
            val cond = parseExpr()

            if (!(ts.matchTokenType(TokenType.KEYWORD) && ts.peek().text == THEN)) throw NotFoundException("Expected 'then' after if condition in line ${ts.peek().line}")
            ts.next() // consume 'then'

            // parse then-body until 'else' or 'end'
            val thenVars = mutableListOf<MemberDecl.VarDecl>()
            val thenStmts = mutableListOf<Stmt>()
            while (!(ts.matchTokenType(TokenType.KEYWORD) && (ts.peek().text == ELSE || ts.peek().text == END_OF_CODE_BLOCK))) {
                if (ts.matchAndNext(TokenType.KEYWORD, VAR)) {
                    thenVars.add(parseVarDecl()) // consume 'if'
                    continue
                }
                val s = parseStmt()
                if (s != null) thenStmts.add(s)
            }
            val thenBody = MethodBody.BlockBody(thenVars, thenStmts)

            var elseBody: MethodBody? = null
            if (ts.matchTokenType(TokenType.KEYWORD) && ts.peek().text == ELSE) {
                ts.next() // consume 'else'
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

            // consume 'end' of if
            val endTok = ts.expect(TokenType.KEYWORD)
            if (endTok.text != END_OF_CODE_BLOCK) throw NotFoundException("Expected 'end' after if, got ${endTok.text} in line ${endTok.line}")

            return Stmt.If(cond, thenBody, elseBody)
        }

        // Обработка присваивания с использованием this: this.field := expr
        // или выражения с this: this.field, this.method() и т.д.
        if (ts.matchTokenType(TokenType.KEYWORD) && ts.peek().text == THIS) {
            ts.next() // пропускаем 'this'
            
            // Если после this идет точка, значит это доступ к полю или методу
            if (ts.peek().text == ".") {
                ts.next() // пропускаем '.'
                val fieldTok = ts.expect(TokenType.IDENTIFIER)
                val fieldName = fieldTok.text
                
                // Проверяем, является ли это присваиванием: this.field := expr
                if (ts.peek().text == ASSIGN) {
                    ts.next() // пропускаем ':='
                    val expr = parseExpr()
                    return Stmt.Assignment("this.${fieldName}", expr)
                } else {
                    // Это выражение: this.field или this.method() и т.д.
                    // Проверяем, может ли это быть вызов метода: this.method(args)
                    if (ts.peek().text == OPEN_BRACKET) {
                        // Вызов метода: this.method(args)
                        val args = parseArgs()
                        val expr = Expr.Call(Expr.This, fieldName, args)
                        return Stmt.ExprStmt(expr)
                    }
                    
                    // Иначе это доступ к полю: this.field
                    var expr: Expr = Expr.FieldAccess(Expr.This, fieldName)
                    
                    // Обрабатываем цепочку обращений: this.field1.field2.method()...
                    while (ts.peek().text == ".") {
                        ts.next() // пропускаем '.'
                        val nameTok = ts.expect(TokenType.IDENTIFIER)
                        val name = nameTok.text
                        
                        if (ts.peek().text == OPEN_BRACKET) {
                            // Вызов метода: expr.method(args)
                            val args = parseArgs()
                            expr = Expr.Call(expr, name, args)
                        } else {
                            // Доступ к полю: expr.field
                            expr = Expr.FieldAccess(expr, name)
                        }
                    }
                    return Stmt.ExprStmt(expr)
                }
            } else {
                // Просто this без точки - начинаем строить выражение
                var expr: Expr = Expr.This
                
                // Обрабатываем цепочку после this: this.field, this.method() и т.д.
                while (ts.peek().text == ".") {
                    ts.next() // пропускаем '.'
                    val nameTok = ts.expect(TokenType.IDENTIFIER)
                    val name = nameTok.text
                    
                    if (ts.peek().text == OPEN_BRACKET) {
                        // Вызов метода: this.method(args)
                        val args = parseArgs()
                        expr = Expr.Call(expr, name, args)
                    } else {
                        // Доступ к полю: this.field
                        expr = Expr.FieldAccess(expr, name)
                    }
                }
                return Stmt.ExprStmt(expr)
            }
        }
        
        // Assignment: IDENTIFIER ':=' expr
        if (ts.matchTokenType(TokenType.IDENTIFIER)) {
            val idTok = ts.next()
            if (ts.peek().text == ASSIGN) {
                ts.next() // consume ':='
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

        // если сразу '(' после идентификатора. Например метод print()
        if (ts.peek().text == OPEN_BRACKET) {
            val args = parseArgs()
            expr = Expr.Call(null, firstTok.text, args)
        }

        // поддерживаем цепочку .Name или .Name(args). несколько подряд может вызваться
        while (ts.peek().text == ".") {
            ts.next() // consume '.'
            val nameTok = ts.expect(TokenType.IDENTIFIER)
            val name = nameTok.text
            if (ts.peek().text == OPEN_BRACKET) {
                val args = parseArgs()
                // метод вызывается на текущем выражении как receiver
                expr = Expr.Call(expr, name, args)
            } else {
                expr = Expr.FieldAccess(expr, name)
            }
        }

        return expr
    }

    private fun parseExpr(): Expr {
        var left = parsePrimary()

        // left.Name(...) or left.Name (to method, or to field)
        while (ts.peek().text == ".") {
            ts.next() // consume '.'
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
                    ts.next() // consume '('
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

    /**
     * Парсинг типа (может быть простым или дженериком)
     * Поддерживает синтаксис: Integer, Array[Integer], Array[Array[Integer]]
     */
    private fun parseType(): ClassName {
        val typeNameTok = ts.expect(TokenType.IDENTIFIER)
        val typeName = typeNameTok.text
        
        // Проверяем, есть ли параметры типа (дженерики)
        if (ts.peek().text == "[") {
            ts.next()  // consume '['
            val typeArgs = mutableListOf<ClassName>()
            
            // Парсим первый аргумент типа
            typeArgs.add(parseType())
            
            // Парсим остальные аргументы (если есть)
            while (ts.peek().text == ",") {
                ts.next()  // consume ','
                typeArgs.add(parseType())
            }
            
            ts.expectText("]")  // expect ']'
            return ClassName.Generic(typeName, typeArgs)
        }
        
        // Простой тип без дженериков
        return ClassName.Simple(typeName)
    }

    private fun parseArgs(): List<Expr> {
        val args = mutableListOf<Expr>()
        // current token must be '('
        val open = ts.expect(TokenType.SPECIAL_SYMBOL)
        if (open.text != OPEN_BRACKET) throw NotFoundException("Expected '(' to start args, got ${open.text} in line ${open.text}")

        if (ts.peek().text == CLOSE_BRACKET) {
            ts.next() // consume ')'
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
                ts.next() // consume ')'
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

        // class members
        private const val VAR = "var"
        private const val METHOD = "method"
        private const val CONSTRUCTOR = "this"

        // params / symbols
        private const val OPEN_BRACKET = "("
        private const val CLOSE_BRACKET = ")"

        // other keywords
        private const val WHILE = "while"
        private const val LOOP = "loop"
        private const val IF = "if"
        private const val THEN = "then"
        private const val ELSE = "else"
        private const val RETURN = "return"
        private const val THIS = "this"
        private const val TRUE = "true"
        private const val FALSE = "false"

        // symbols
        private const val ASSIGN = ":="
    }
}
