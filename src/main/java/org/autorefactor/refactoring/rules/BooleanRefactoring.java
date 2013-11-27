/*
 * AutoRefactor - Eclipse plugin to automatically refactor Java code bases.
 *
 * Copyright (C) 2013 Jean-Noël Rouvignac - initial API and implementation
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

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.autorefactor.refactoring.ASTHelper;
import org.autorefactor.refactoring.IJavaRefactoring;
import org.autorefactor.refactoring.Refactorings;
import org.autorefactor.util.NotImplementedException;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.InfixExpression.Operator;

import static org.autorefactor.refactoring.ASTHelper.*;

/**
 * <ul>
 * <li>Use boolean constants when possible</li>
 * <li>Remove if statements when each clause does similar things with opposite
 * boolean values</li>
 * <li>Remove ternary operators when each clause does similar things with
 * opposite boolean values</li>
 * </ul>
 */
public class BooleanRefactoring extends ASTVisitor implements IJavaRefactoring {

	private static class BooleanASTMatcher extends ASTMatcher {

		/** else node to then node */
		final Map<ASTNode, ASTNode> matches = new HashMap<ASTNode, ASTNode>();
		final Map<ASTNode, ASTNode> previousMatches;

		public BooleanASTMatcher() {
			this.previousMatches = null;
		}

		public BooleanASTMatcher(Map<ASTNode, ASTNode> previousMatches) {
			this.previousMatches = previousMatches;
		}

		@Override
		public boolean match(BooleanLiteral node, Object other) {
			if (other instanceof Expression) {
				final Expression expr = (Expression) other;
				if (getBooleanLiteral(node) != getBooleanLiteral(expr)) {
					matches.put(expr, node);
					return true;
				}
			}
			return false;
		}

		@Override
		public boolean match(QualifiedName node, Object other) {
			if (other instanceof Expression) {
				final Expression expr = (Expression) other;
				if ((this.previousMatches != null && this.previousMatches
						.containsKey(other))
						|| (getBooleanLiteral(node) != getBooleanLiteral(expr))) {
					matches.put(expr, node);
					return true;
				}
			}
			return false;
		}
	}

	private class BooleanReplaceVisitor extends ASTVisitor {

		private final Expression ifCondition;
		private final Collection<ASTNode> nodesToReplace;
		private final Expression booleanName;

		public BooleanReplaceVisitor(Expression ifCondition,
				Collection<ASTNode> nodesToReplace, Expression booleanName) {
			this.ifCondition = ifCondition;
			this.nodesToReplace = nodesToReplace;
			this.booleanName = booleanName;
		}

		@Override
		public boolean visit(BooleanLiteral node) {
			if (this.nodesToReplace.contains(node)) {
				final Expression expr = getExpression(
						getBooleanLiteral(node), this.ifCondition,
						"boolean", null);
				replaceInParent(node, expr);
			}
			return DO_NOT_VISIT_SUBTREE;
		}

		@Override
		public boolean visit(QualifiedName node) {
			if (this.nodesToReplace.contains(node)) {
				final QualifiedName qn = ASTHelper
						.as(node, QualifiedName.class);
				final Expression expr = getExpression(
						getBooleanObjectAsLiteral(qn),
						this.ifCondition, "java.lang.Boolean", this.booleanName);
				replaceInParent(node, expr);
			}
			return DO_NOT_VISIT_SUBTREE;
		}

	}

	private RefactoringContext ctx;
	private int javaMinorVersion;

	public BooleanRefactoring() {
		super();
	}

	public void setRefactoringContext(RefactoringContext ctx) {
		this.ctx = ctx;
		this.javaMinorVersion = this.ctx.getJavaSERelease().getMinorVersion();
	}

	@Override
	public boolean visit(ConditionalExpression node) {
		final ITypeBinding typeBinding = node.resolveTypeBinding();
		if (typeBinding != null) {
			final Expression newE = maybeGetExpression(
					typeBinding.getQualifiedName(), node.getExpression(),
					getBooleanLiteral(node.getThenExpression()),
					getBooleanLiteral(node.getElseExpression()));
			if (newE != null) {
				this.ctx.getRefactorings().replace(node, newE);
			}
		}
		return VISIT_SUBTREE;
	}

	@Override
	public boolean visit(IfStatement node) {
		final BooleanASTMatcher matcher = new BooleanASTMatcher();
		if (match(matcher, node.getThenStatement(), node.getElseStatement())) {
			// Then and else statement are matching, bar the boolean values
			// which are opposite
			final Statement copyStmt = copySubtree(node.getThenStatement());
			// identify the node that need to be replaced after the copy
			final BooleanASTMatcher matcher2 = new BooleanASTMatcher(
					matcher.matches);
			if (match(matcher2, copyStmt, node.getElseStatement())) {
				final Expression ifCondition = node.getExpression();
				copyStmt.accept(new BooleanReplaceVisitor(ifCondition,
						matcher2.matches.values(), getBooleanName(node)));
				if (copyStmt instanceof Block && asList(copyStmt).size() == 1) {
					this.ctx.getRefactorings().replace(node, asList(copyStmt).get(0));
				} else {
					this.ctx.getRefactorings().replace(node, copyStmt);
				}
				return DO_NOT_VISIT_SUBTREE;
			}
		}

		final ReturnStatement thenRs = as(node.getThenStatement(), ReturnStatement.class);
		final ReturnStatement elseRs = as(node.getElseStatement(), ReturnStatement.class);
		if (thenRs != null) {
			if (elseRs == null) {
				// The != null case is handled with the matcher above
				final ReturnStatement rs = as(getNextSibling(node), ReturnStatement.class);
				if (rs != null) {
					final Boolean thenBool = getBooleanLiteral(thenRs.getExpression());
					final Boolean elseBool = getBooleanLiteral(rs.getExpression());
					ReturnStatement newRs = getReturnStatement(node, thenBool, elseBool);
					if (newRs != null) {
						this.ctx.getRefactorings().replace(node, newRs);
						this.ctx.getRefactorings().remove(rs);
					} else {
						MethodDeclaration md = getAncestor(node, MethodDeclaration.class);
						final Type returnType = md.getReturnType2();
						if (returnType != null && returnType.isPrimitiveType()) {
							final PrimitiveType pt = (PrimitiveType) returnType;
							if (PrimitiveType.BOOLEAN.equals(pt
									.getPrimitiveTypeCode())) {
								newRs = getReturnStatement(node, thenBool,
										elseBool, thenRs.getExpression(),
										rs.getExpression());
								if (newRs != null) {
									this.ctx.getRefactorings().replace(node, newRs);
									this.ctx.getRefactorings().remove(rs);
								}
							}
						}
					}
				}
			}
		} else {
			ExpressionStatement thenEs = as(node.getThenStatement(), ExpressionStatement.class);
			if (thenEs != null && asList(node.getElseStatement()).isEmpty()) {
				final Assignment thenA = as(thenEs.getExpression(), Assignment.class);
				if (thenA != null
						&& Assignment.Operator.ASSIGN.equals(thenA
								.getOperator())
						&& (thenA.getLeftHandSide() instanceof Name || thenA
								.getLeftHandSide() instanceof FieldAccess)) {
					final Statement previousSibling = getPreviousSibling(node);
					if (previousSibling instanceof VariableDeclarationStatement) {
						final VariableDeclarationStatement vds = (VariableDeclarationStatement) previousSibling;
						final VariableDeclarationFragment vdf = getVariableDeclarationFragment(
								vds, thenA.getLeftHandSide());
						if (vdf == null) {
							return VISIT_SUBTREE;
						}

						final ITypeBinding typeBinding = vds.getType()
								.resolveBinding();
						if (typeBinding == null) {
							return VISIT_SUBTREE;
						}
						final String expressionTypeName = typeBinding
								.getQualifiedName();
						final Expression newE = maybeGetExpression(
								expressionTypeName, node.getExpression(),
								getBooleanLiteral(thenA.getRightHandSide()),
								getBooleanLiteral(vdf.getInitializer()));
						if (newE != null) {
							this.ctx.getRefactorings().replace(vdf.getInitializer(),
									newE);
							this.ctx.getRefactorings().remove(node);
						}
					} else if (previousSibling instanceof ExpressionStatement) {
						ExpressionStatement elseEs = (ExpressionStatement) previousSibling;
						final Assignment elseA = as(elseEs.getExpression(), Assignment.class);
						if (elseA != null
								&& Assignment.Operator.ASSIGN.equals(elseA
										.getOperator())
								&& isSameVariable(
										thenA.getLeftHandSide(),
										elseA.getLeftHandSide())) {
							final ITypeBinding typeBinding = elseA
									.resolveTypeBinding();
							if (typeBinding == null) {
								return VISIT_SUBTREE;
							}
							final String expressionTypeName = typeBinding
									.getQualifiedName();
							final Expression newE = maybeGetExpression(
									expressionTypeName,
									node.getExpression(),
									getBooleanLiteral(thenA.getRightHandSide()),
									getBooleanLiteral(elseA.getRightHandSide()));
							if (newE != null) {
								this.ctx.getRefactorings().replace(
										elseA.getRightHandSide(), newE);
								this.ctx.getRefactorings().remove(node);
							}
						}
					}
				}
			}
		}
		return VISIT_SUBTREE;
	}

	private ReturnStatement getReturnStatement(IfStatement node,
			final Boolean thenBool, final Boolean elseBool,
			final Expression thenExpr, final Expression elseExpr) {
		final Expression copiedIfCondition = copySubtree(node.getExpression());
		if (thenBool == null && elseBool != null) {
			final InfixExpression ie = this.ctx.getAST().newInfixExpression();
			ie.setLeftOperand(copiedIfCondition);
			ie.setOperator(getConditionalOperator(!elseBool.booleanValue()));
			ie.setRightOperand(copySubtree(thenExpr));

			final ReturnStatement rs = this.ctx.getAST().newReturnStatement();
			rs.setExpression(getBooleanExpression(ie, !elseBool.booleanValue()));
			return rs;
		} else if (thenBool != null && elseBool == null) {
			final InfixExpression ie = this.ctx.getAST().newInfixExpression();
			ie.setLeftOperand(copiedIfCondition);
			ie.setOperator(getConditionalOperator(!thenBool.booleanValue()));
			ie.setRightOperand(copySubtree(elseExpr));

			final ReturnStatement rs = this.ctx.getAST().newReturnStatement();
			rs.setExpression(getBooleanExpression(ie, thenBool.booleanValue()));
			return rs;
		}
		return null;
	}

	private <T extends ASTNode> T copySubtree(T node) {
		return ASTHelper.copySubtree(this.ctx.getAST(), node);
	}

	private Operator getConditionalOperator(boolean isAndOperator) {
		return isAndOperator ? InfixExpression.Operator.CONDITIONAL_AND
				: InfixExpression.Operator.CONDITIONAL_OR;
	}

	private Expression getBooleanExpression(final InfixExpression ie,
			boolean doNotRevertExpression) {
		if (doNotRevertExpression) {
			return ie;
		}
		return negate(this.ctx.getAST(), ie, false);
	}

	private VariableDeclarationFragment getVariableDeclarationFragment(
			final VariableDeclarationStatement vds, final Expression expr) {
		if (vds == null) {
			return null;
		}
		for (VariableDeclarationFragment vdf : (List<VariableDeclarationFragment>) vds
				.fragments()) {
			if (isSameVariable(expr, vdf.getName())) {
				return vdf;
			}
		}
		return null;
	}

	private static Boolean getBooleanLiteral(Expression node) {
		return getBooleanLiteral(node);
	}

	private ReturnStatement getReturnStatement(IfStatement node,
			final Boolean returnThenLiteral, final Boolean returnElseLiteral) {
		if (returnThenLiteral != null && returnElseLiteral != null
				&& returnThenLiteral == !returnElseLiteral) {
			Expression exprToReturn = copySubtree(node.getExpression());
			if (!returnThenLiteral) {
				exprToReturn = negate(this.ctx.getAST(), exprToReturn, false);
			}

			final MethodDeclaration md = getAncestor(node, MethodDeclaration.class);
			final Expression returnExpr = getReturnExpression(md, exprToReturn);
			if (returnExpr == null) {
				return null;
			}
			final ReturnStatement rs = this.ctx.getAST().newReturnStatement();
			rs.setExpression(returnExpr);
			return rs;
		}
		return null;
	}

	private Expression getReturnExpression(MethodDeclaration md,
			final Expression ifCondition) {
		final IMethodBinding typeBinding = md.resolveBinding();
		if (typeBinding == null) {
			return null;
		}
		final Expression newE = getExpression(ifCondition, typeBinding
				.getReturnType().getQualifiedName(), getBooleanName(md));
		if (newE != null) {
			return newE;
		}
		// TODO JNR rejuggle exception messages like this:
		// compilationUnit.java:line number: error message
		throw new IllegalStateException(
				"Did not expect any other return type than boolean or java.lang.Boolean for method "
						+ md.getName().getIdentifier()
						+ ", but found "
						+ typeBinding.getReturnType().getQualifiedName());
	}

	private Expression maybeGetExpression(String expressionName,
			Expression ifCondition, final Boolean thenBoolLiteral,
			final Boolean elseBoolLiteral) {
		if (thenBoolLiteral != null && elseBoolLiteral != null
				&& thenBoolLiteral == !elseBoolLiteral) {
			final Expression copiedIfCondition = copySubtree(ifCondition);
			final Expression booleanName = getBooleanName(ifCondition);
			return getExpression(thenBoolLiteral, copiedIfCondition,
					expressionName, booleanName);
		}
		return null;
	}

	private Expression getExpression(final boolean doNotNegate,
			final Expression ifCondition, String expressionName,
			final Expression booleanName) {
		if (doNotNegate) {
			return getExpression(copySubtree(ifCondition), expressionName, booleanName);
		}
		final Expression negatedIfCondition = negate(this.ctx.getAST(), ifCondition, true);
		return getExpression(negatedIfCondition, expressionName, booleanName);
	}

	private Expression getExpression(final Expression ifCondition,
			String expressionTypeName, Expression booleanName) {
		if ("boolean".equals(expressionTypeName)) {
			return ifCondition;
		} else if (javaMinorVersion >= 4
				&& "java.lang.Boolean".equals(expressionTypeName)) {
			final MethodInvocation mi = this.ctx.getAST().newMethodInvocation();
			mi.setExpression(booleanName);
			mi.setName(this.ctx.getAST().newSimpleName("valueOf"));
			mi.arguments().add(ifCondition);
			return mi;
		}
		return null;
	}

	private Expression getBooleanName(ASTNode node) {
		if (!isSimpleNameAlreadyUsed("Boolean", getAncestor(node, CompilationUnit.class))) {
			return this.ctx.getAST().newSimpleName("Boolean");
		}
		return this.ctx.getAST().newName("java.lang.Boolean");
	}

	private boolean isSimpleNameAlreadyUsed(String simpleName,
			CompilationUnit cu) {
		for (ImportDeclaration id : (List<ImportDeclaration>) cu.imports()) {
			if (id.getName() instanceof QualifiedName) {
				QualifiedName f = (QualifiedName) id.getName();
				if (simpleName.equals(f.getName())) {
					return true;
				}
			} else {
				throw new NotImplementedException(id.getName());
			}
		}
		return false;
	}

	@Override
	public boolean visit(MethodInvocation node) {
		if (node.getExpression() != null) {
			final ITypeBinding typeBinding = node.getExpression()
					.resolveTypeBinding();
			if (typeBinding != null
					&& "java.lang.Boolean".equals(typeBinding
							.getQualifiedName())
					&& "valueOf".equals(node.getName().getIdentifier())
					&& node.arguments().size() == 1) {
				final BooleanLiteral l = as((Expression) node
						.arguments().get(0), BooleanLiteral.class);
				if (l != null) {
					this.ctx.getRefactorings().replace(
							node,
							getRefactoring(typeBinding.getName(), node,
									l.booleanValue()));
					return DO_NOT_VISIT_SUBTREE;
				}
			}
		}
		return VISIT_SUBTREE;
	}

	private FieldAccess getRefactoring(String typeName, MethodInvocation node,
			boolean booleanLiteral) {
		final AST ast = this.ctx.getAST();
		final FieldAccess fa = ast.newFieldAccess();
		fa.setExpression(ast.newSimpleName(typeName));
		fa.setName(ast.newSimpleName(booleanLiteral ? "TRUE" : "FALSE"));
		return fa;
	}

	public Refactorings getRefactorings(CompilationUnit astRoot) {
		astRoot.accept(this);
		return this.ctx.getRefactorings();
	}
}
