package syntaxer

data class Program(val classes: List<ClassDecl>)

data class ClassDecl(val name: ClassName, val parent: ClassName?, val members: List<MemberDecl>)


sealed class ClassName {
    data class Simple(val name: String) : ClassName()
}

sealed class MemberDecl {
    data class VarDecl(val name: String, val init: Expr) : MemberDecl()
    data class MethodDecl(val name: String, val params: List<Param>, val returnType: ClassName?, val body: MethodBody) : MemberDecl()
    data class ConstructorDecl(val params: List<Param>, val body: MethodBody) : MemberDecl()
}

data class Param(val name: String, val type: ClassName)

sealed class MethodBody {
    data class BlockBody(val vars: List<MemberDecl.VarDecl>, val stmts: List<Stmt>): MethodBody()
}

sealed class Stmt {
    data class Assignment(val target: String, val expr: Expr) : Stmt()
    data class While(val cond: Expr, val body: MethodBody) : Stmt()
    data class If(val cond: Expr, val thenBody: MethodBody, val elseBody: MethodBody?) : Stmt()
    data class Return(val expr: Expr?) : Stmt()
}

sealed class Expr {
    data class IntLit(val v: Long) : Expr()
    data class RealLit(val v: Double) : Expr()
    data class BoolLit(val v: Boolean) : Expr()
    object This : Expr()
    data class Ident(val name: String) : Expr()
    data class Call(val receiver: Expr?, val method: String, val args: List<Expr>) : Expr()
    data class FieldAccess(val receiver: Expr, val name: String) : Expr()
    data class ClassNameExpr(val cn: ClassName) : Expr()
}