/*
 * AutoRefactor - Eclipse plugin to automatically refactor Java code bases.
 *
 * Copyright (C) 2013-2017 Jean-Noël Rouvignac - initial API and implementation
 * Copyright (C) 2017 Fabrice Tiercelin - Inline the blocks
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program under LICENSE-GNUGPL.  If not, see
 * <http://www.gnu.org/licenses/>.
 *
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution under LICENSE-ECLIPSE, and is
 * available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.autorefactor.refactoring.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.autorefactor.refactoring.ASTBuilder;
import org.autorefactor.refactoring.Refactorings;
import org.eclipse.jdt.core.dom.ASTMatcher;
import org.eclipse.jdt.core.dom.ArrayAccess;
import org.eclipse.jdt.core.dom.ArrayCreation;
import org.eclipse.jdt.core.dom.ArrayInitializer;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.InstanceofExpression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.TryStatement;
import org.eclipse.jdt.core.dom.VariableDeclarationExpression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.WhileStatement;

import static org.eclipse.jdt.core.dom.InfixExpression.Operator.EQUALS;
import static org.eclipse.jdt.core.dom.InfixExpression.Operator.NOT_EQUALS;
import static org.autorefactor.refactoring.ASTHelper.*;
import static org.eclipse.jdt.core.dom.ASTNode.*;

/**
 * TODO Use variable values analysis for determining where code is dead.
 *
 * @see #getDescription()
 */
public class DeadCodeEliminationRefactoring extends AbstractRefactoringRule {
    @Override
    public String getDescription() {
        return "Removes dead code.";
    }

    @Override
    public String getName() {
        return "Dead code elimination";
    }

    // TODO JNR
    // for (false) // impossible iterations
    // Remove Empty try block?
    // do this by resolvingConstantValue

    @Override
    public boolean visit(IfStatement node) {
        final ASTBuilder b = this.ctx.getASTBuilder();
        final Refactorings r = this.ctx.getRefactorings();

        final Statement thenStmt = node.getThenStatement();
        final Statement elseStmt = node.getElseStatement();
        final Expression condition = node.getExpression();
        if (elseStmt != null && asList(elseStmt).isEmpty()) {
            r.remove(elseStmt);
            return DO_NOT_VISIT_SUBTREE;
        } else if (thenStmt != null && asList(thenStmt).isEmpty()) {
            if (elseStmt != null) {
                r.replace(node,
                          b.if0(b.negate(condition),
                                b.move(elseStmt)));
            } else {
                final List<Expression> sideEffectExprs = new ArrayList<Expression>();
                collectSideEffects(condition, sideEffectExprs);

                if (node.getParent() instanceof IfStatement
                        || node.getParent() instanceof EnhancedForStatement
                        || node.getParent() instanceof ForStatement
                        || node.getParent() instanceof WhileStatement
                        || node.getParent() instanceof DoStatement) {
                    final List<ExpressionStatement> sideEffectStmts =
                            new ArrayList<ExpressionStatement>(sideEffectExprs.size());
                    for (Expression sideEffectExpr : sideEffectExprs) {
                        sideEffectStmts.add(b.toStmt(b.move(sideEffectExpr)));
                    }
                    r.replace(node,
                            b.block(sideEffectStmts.toArray(new ExpressionStatement[sideEffectStmts.size()])));
                } else {
                    for (Expression sideEffectExpr : sideEffectExprs) {
                        r.insertBefore(b.toStmt(b.move(sideEffectExpr)), node);
                    }
                    r.remove(node);
                }
            }
            return DO_NOT_VISIT_SUBTREE;
        }

        final Object constantCondition = peremptoryValue(condition);
        if (Boolean.TRUE.equals(constantCondition)) {
            return maybeInlineBlock(node, thenStmt);
        } else if (Boolean.FALSE.equals(constantCondition)) {
            if (elseStmt != null) {
                return maybeInlineBlock(node, elseStmt);
            } else {
                r.remove(node);
            }
            return DO_NOT_VISIT_SUBTREE;
        }
        return VISIT_SUBTREE;
    }

    private Object peremptoryValue(final Expression condition) {
        final Object constantCondition = condition.resolveConstantExpressionValue();
        if (constantCondition != null) {
            return constantCondition;
        } else if (condition instanceof InfixExpression) {
            InfixExpression ie = (InfixExpression) condition;
            if ((EQUALS.equals(ie.getOperator())
                    || NOT_EQUALS.equals(ie.getOperator()))
                    && isPassive(ie.getLeftOperand())
                    && match(new ASTMatcher(), ie.getLeftOperand(), ie.getRightOperand())) {
                return EQUALS.equals(ie.getOperator());
            }
        }
        return null;
    }

    private boolean maybeInlineBlock(final Statement node, final Statement unconditionnalStatement) {
        if (isEndingWithJump(unconditionnalStatement)) {
            replaceBlockByPlainCode(node, unconditionnalStatement);
            this.ctx.getRefactorings().remove(getNextSiblings(node));
            return DO_NOT_VISIT_SUBTREE;
        } else {
            final Set<String> ifVariableNames =
                    getLocalVariableIdentifiers(unconditionnalStatement, false);

            final Set<String> followingVariableNames = new HashSet<String>();
            for (final Statement statement : getNextSiblings(node)) {
                followingVariableNames.addAll(getLocalVariableIdentifiers(statement, true));
            }

            if (!ifVariableNames.removeAll(followingVariableNames)) {
                replaceBlockByPlainCode(node, unconditionnalStatement);
                return DO_NOT_VISIT_SUBTREE;
            }
        }
        return VISIT_SUBTREE;
    }

    @SuppressWarnings("unchecked")
    private void replaceBlockByPlainCode(final Statement sourceNode, final Statement unconditionnalStatement) {
        final ASTBuilder b = this.ctx.getASTBuilder();
        final Refactorings r = this.ctx.getRefactorings();

        if (unconditionnalStatement instanceof Block
                && sourceNode.getParent() instanceof Block) {
            r.replace(sourceNode, b.copyRange(((Block) unconditionnalStatement).statements()));
        } else {
            r.replace(sourceNode, b.copy(unconditionnalStatement));
        }
    }

    private void collectSideEffects(Expression expr, List<Expression> sideEffectExprs) {
        // local variable, parameter, enum constant, etc.
        // OR method starting with is*(), get*()
        // except atomic long, atomic integer, etc.
        switch (expr.getNodeType()) {
        case METHOD_INVOCATION:
            MethodInvocation mi = (MethodInvocation) expr;
            methodHasSideEffects(mi.resolveMethodBinding(), mi, sideEffectExprs);
            if (mi.getExpression() != null) {
                collectSideEffects(mi.getExpression(), sideEffectExprs);
            }
            collectSideEffects(arguments(mi), sideEffectExprs);
            break;

        case SUPER_METHOD_INVOCATION:
            SuperMethodInvocation smi = (SuperMethodInvocation) expr;
            methodHasSideEffects(smi.resolveMethodBinding(), smi, sideEffectExprs);
            collectSideEffects(arguments(smi), sideEffectExprs);
            break;

        case CLASS_INSTANCE_CREATION:
            ClassInstanceCreation cic = (ClassInstanceCreation) expr;
            methodHasSideEffects(cic.resolveConstructorBinding(), cic, sideEffectExprs);
            collectSideEffects(cic.getExpression(), sideEffectExprs);
            collectSideEffects(arguments(cic), sideEffectExprs);
            break;

        case ARRAY_ACCESS:
            ArrayAccess aa = (ArrayAccess) expr;
            collectSideEffects(aa.getArray(), sideEffectExprs);
            collectSideEffects(aa.getIndex(), sideEffectExprs);
            break;
        case ARRAY_CREATION:
            ArrayCreation ac = (ArrayCreation) expr;
            collectSideEffects(ac.getInitializer(), sideEffectExprs);
            collectSideEffects(ac.dimensions(), sideEffectExprs);
            break;
        case ARRAY_INITIALIZER:
            ArrayInitializer ai = (ArrayInitializer) expr;
            collectSideEffects(expressions(ai), sideEffectExprs);
            break;
        case ASSIGNMENT:
            Assignment as = (Assignment) expr;
            collectSideEffects(as.getLeftHandSide(), sideEffectExprs);
            collectSideEffects(as.getRightHandSide(), sideEffectExprs);
            break;

        case CONDITIONAL_EXPRESSION:
            ConditionalExpression ce = (ConditionalExpression) expr;
            collectSideEffects(ce.getExpression(), sideEffectExprs);
            collectSideEffects(ce.getThenExpression(), sideEffectExprs);
            collectSideEffects(ce.getElseExpression(), sideEffectExprs);
            break;

        case FIELD_ACCESS:
            FieldAccess fa = (FieldAccess) expr;
            collectSideEffects(fa.getExpression(), sideEffectExprs);
            collectSideEffects(fa.getName(), sideEffectExprs);
            break;
        case SUPER_FIELD_ACCESS:
            SuperFieldAccess sfa = (SuperFieldAccess) expr;
            collectSideEffects(sfa.getQualifier(), sideEffectExprs);
            collectSideEffects(sfa.getName(), sideEffectExprs);
            break;
        case THIS_EXPRESSION:
            collectSideEffects(((ThisExpression) expr).getQualifier(), sideEffectExprs);
            break;
        case VARIABLE_DECLARATION_EXPRESSION:
            collectSideEffects((VariableDeclarationExpression) expr, sideEffectExprs);
            break;

        case INFIX_EXPRESSION:
            InfixExpression ie = (InfixExpression) expr;
            collectSideEffects(ie.getLeftOperand(), sideEffectExprs);
            collectSideEffects(ie.getRightOperand(), sideEffectExprs);
            collectSideEffects(extendedOperands(ie), sideEffectExprs);
            break;

        case CAST_EXPRESSION:
            collectSideEffects(((CastExpression) expr).getExpression(), sideEffectExprs);
            break;
        case INSTANCEOF_EXPRESSION:
            collectSideEffects(((InstanceofExpression) expr).getLeftOperand(), sideEffectExprs);
            break;
        case PARENTHESIZED_EXPRESSION:
            collectSideEffects(((ParenthesizedExpression) expr).getExpression(), sideEffectExprs);
            break;
        case POSTFIX_EXPRESSION:
            sideEffectExprs.add(expr);
            break;
        case PREFIX_EXPRESSION:
            PrefixExpression pe = (PrefixExpression) expr;
            PrefixExpression.Operator op = pe.getOperator();
            if (PrefixExpression.Operator.INCREMENT.equals(op)
                  || PrefixExpression.Operator.DECREMENT.equals(op)) {
                sideEffectExprs.add(pe);
            } else {
                collectSideEffects(pe.getOperand(), sideEffectExprs);
            }
            break;

        default:
            // literals
            // names
        }
    }

    private void collectSideEffects(List<Expression> expressions, List<Expression> sideEffectExprs) {
        for (Expression expr : expressions) {
            collectSideEffects(expr, sideEffectExprs);
        }
    }

    private void collectSideEffects(VariableDeclarationExpression vde, List<Expression> sideEffectExprs) {
        for (VariableDeclarationFragment vdf : fragments(vde)) {
            collectSideEffects(vdf.getInitializer(), sideEffectExprs);
        }
    }

    private void methodHasSideEffects(
            IMethodBinding methodBinding, Expression methodCall, List<Expression> sideEffectExprs) {
        if (methodBinding == null || methodHasSideEffects(methodBinding)) {
            // Do not remove method calls for which there is no type information (method bindings is null)
            sideEffectExprs.add(methodCall);
        }
    }

    private boolean methodHasSideEffects(IMethodBinding methodBinding) {
        String methodName = methodBinding.getName();
        if (methodName.startsWith("get")) {
            return isAtomicGetter(methodBinding);
        }
        return !(methodName.startsWith("is")
              || methodName.equals("equals")
              || methodName.equals("hashCode")
              || methodName.equals("contains")
              || methodName.equals("containsAll")
              || methodName.equals("matches")
              || methodName.equals("exists"));
    }

    private boolean isAtomicGetter(IMethodBinding methodBinding) {
        String methodName = methodBinding.getName();
        return methodName.startsWith("getAnd")
            && (isMethod(methodBinding, "java.util.concurrent.atomic.AtomicBoolean", "getAndSet", "boolean")
            || isMethod(methodBinding, "java.util.concurrent.atomic.AtomicInteger", "getAndAdd", "int")
            || isMethod(methodBinding, "java.util.concurrent.atomic.AtomicInteger", "getAndDecrement")
            || isMethod(methodBinding, "java.util.concurrent.atomic.AtomicInteger", "getAndIncrement")
            || isMethod(methodBinding, "java.util.concurrent.atomic.AtomicInteger", "getAndSet", "int")
            || isMethod(methodBinding, "java.util.concurrent.atomic.AtomicIntegerArray", "getAndAdd", "int", "int")
            || isMethod(methodBinding, "java.util.concurrent.atomic.AtomicIntegerArray", "getAndDecrement", "int")
            || isMethod(methodBinding, "java.util.concurrent.atomic.AtomicIntegerArray", "getAndIncrement", "int")
            || isMethod(methodBinding, "java.util.concurrent.atomic.AtomicIntegerArray", "getAndSet", "int", "int")
            || isMethod(methodBinding, "java.util.concurrent.atomic.AtomicLong", "getAndAdd", "long")
            || isMethod(methodBinding, "java.util.concurrent.atomic.AtomicLong", "getAndDecrement")
            || isMethod(methodBinding, "java.util.concurrent.atomic.AtomicLong", "getAndIncrement")
            || isMethod(methodBinding, "java.util.concurrent.atomic.AtomicLong", "getAndSet", "long")
            || isMethod(methodBinding, "java.util.concurrent.atomic.AtomicLongArray", "getAndAdd", "int", "long")
            || isMethod(methodBinding, "java.util.concurrent.atomic.AtomicLongArray", "getAndDecrement", "int")
            || isMethod(methodBinding, "java.util.concurrent.atomic.AtomicLongArray", "getAndIncrement", "int")
            || isMethod(methodBinding, "java.util.concurrent.atomic.AtomicLongArray", "getAndSet", "int", "long")
            || isMethod(methodBinding, "java.util.concurrent.atomic.AtomicReference", "getAndSet", "java.lang.Object")
            || isMethod(methodBinding,
                    "java.util.concurrent.atomic.AtomicReferenceArray", "getAndSet", "int", "java.lang.Object"));
    }

    private List<Statement> getNextSiblings(Statement node) {
        if (node.getParent() instanceof Block) {
            final List<Statement> stmts = asList((Statement) node.getParent());
            final int indexOfNode = stmts.indexOf(node);
            final int siblingIndex = indexOfNode + 1;
            if (0 <= siblingIndex && siblingIndex < stmts.size()) {
                return stmts.subList(siblingIndex, stmts.size());
            }
        }
        return Collections.emptyList();
    }

    @Override
    public boolean visit(TryStatement node) {
        if (node.resources().isEmpty()) {
            final List<Statement> tryStmts = asList(node.getBody());
            if (tryStmts.isEmpty()) {
                final List<Statement> finallyStmts = asList(node.getFinally());
                if (!finallyStmts.isEmpty()) {
                    return maybeInlineBlock(node, node.getFinally());
                } else {
                    this.ctx.getRefactorings().remove(node);
                    return DO_NOT_VISIT_SUBTREE;
                }
            }
        }

        return VISIT_SUBTREE;
    }
}
