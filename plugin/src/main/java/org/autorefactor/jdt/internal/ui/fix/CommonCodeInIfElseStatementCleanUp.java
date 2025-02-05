/*
 * AutoRefactor - Eclipse plugin to automatically refactor Java code bases.
 *
 * Copyright (C) 2013-2017 Jean-Noël Rouvignac - initial API and implementation
 * Copyright (C) 2016 Fabrice Tiercelin - Make sure we do not visit again modified nodes
 * Copyright (C) 2016 Sameer Misger - Make SonarQube more happy
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
package org.autorefactor.jdt.internal.ui.fix;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.autorefactor.jdt.internal.corext.dom.ASTMatcherSameVariablesAndMethods;
import org.autorefactor.jdt.internal.corext.dom.ASTNodeFactory;
import org.autorefactor.jdt.internal.corext.dom.ASTNodes;
import org.autorefactor.jdt.internal.corext.dom.ASTSemanticMatcher;
import org.autorefactor.jdt.internal.corext.dom.Refactorings;
import org.autorefactor.util.IllegalStateException;
import org.autorefactor.util.NotImplementedException;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.Statement;

/** See {@link #getDescription()} method. */
public class CommonCodeInIfElseStatementCleanUp extends AbstractCleanUpRule {
    /**
     * Get the name.
     *
     * @return the name.
     */
    public String getName() {
        return MultiFixMessages.CleanUpRefactoringWizard_CommonCodeInIfElseStatementCleanUp_name;
    }

    /**
     * Get the description.
     *
     * @return the description.
     */
    public String getDescription() {
        return MultiFixMessages.CleanUpRefactoringWizard_CommonCodeInIfElseStatementCleanUp_description;
    }

    /**
     * Get the reason.
     *
     * @return the reason.
     */
    public String getReason() {
        return MultiFixMessages.CleanUpRefactoringWizard_CommonCodeInIfElseStatementCleanUp_reason;
    }

    // TODO handle switch statements
    // TODO also handle ternary operator, ConditionalExpression

    @Override
    public boolean visit(IfStatement node) {
        if (node.getElseStatement() == null) {
            return true;
        }

        final List<List<Statement>> allCasesStatements= new ArrayList<>();

        // Collect all the if / else if / else if / ... / else cases
        if (collectAllCases(allCasesStatements, node)) {
            final List<List<Statement>> caseStmtsToRemove= new LinkedList<>();

            // Initialize removedCaseStatements list
            for (int i= 0; i < allCasesStatements.size(); i++) {
                caseStmtsToRemove.add(new LinkedList<Statement>());
            }

            // If all cases exist
            final ASTSemanticMatcher matcher= new ASTMatcherSameVariablesAndMethods();
            final int minSize= minSize(allCasesStatements);

            // Identify matching statements starting from the end of each case
            boolean hasCodeToMove= false;
            for (int stmtIndex= 1; stmtIndex <= minSize; stmtIndex++) {
                if (!match(matcher, allCasesStatements, stmtIndex, 0, allCasesStatements.size())
                        || anyContains(caseStmtsToRemove, allCasesStatements, stmtIndex)) {
                    break;
                }
                flagStmtsToRemove(allCasesStatements, stmtIndex, caseStmtsToRemove);
                hasCodeToMove= true;
            }

            if (hasCodeToMove) {
                removeIdenticalTrailingCode(node, allCasesStatements, caseStmtsToRemove);
                return false;
            }
        }
        return true;
    }

    private void removeIdenticalTrailingCode(IfStatement node, final List<List<Statement>> allCasesStatements,
            final List<List<Statement>> caseStmtsToRemove) {
        final ASTNodeFactory b= this.ctx.getASTBuilder();
        final Refactorings r= this.ctx.getRefactorings();

        // Remove the nodes common to all cases
        final boolean[] areCasesRemovable= new boolean[allCasesStatements.size()];
        for (int i= 0; i < allCasesStatements.size(); i++) {
            areCasesRemovable[i]= false;
        }
        removeStmtsFromCases(allCasesStatements, caseStmtsToRemove, areCasesRemovable);

        if (allRemovable(areCasesRemovable)) {
            if (node.getParent() instanceof Block) {
                insertIdenticalCode(node, b, r, caseStmtsToRemove.get(0));

                r.removeButKeepComment(node);
            } else {
                List<Statement> orderedStatements= new ArrayList<Statement>(caseStmtsToRemove.get(0).size());
                for (Statement stmtToRemove : caseStmtsToRemove.get(0)) {
                    orderedStatements.add(0, b.copy(stmtToRemove));
                }
                r.replace(node, b.block(orderedStatements.toArray(new Statement[caseStmtsToRemove.get(0).size()])));
            }
        } else {
            // Remove empty cases
            if (areCasesRemovable[0]) {
                if (areCasesRemovable.length == 2 && !areCasesRemovable[1]) {
                    // Then clause is empty and there is only one else clause
                    // => revert if statement
                    r.replace(node, b.if0(b.negate(node.getExpression()), b.move(node.getElseStatement())));
                } else {
                    r.replace(node.getThenStatement(), b.block());
                }
            }

            for (int i= 1; i < areCasesRemovable.length; i++) {
                if (areCasesRemovable[i]) {
                    final Statement firstStatement= allCasesStatements.get(i).get(0);
                    r.remove(findNodeToRemove(firstStatement));
                }
            }

            if (node.getParent() instanceof Block) {
                insertIdenticalCode(node, b, r, caseStmtsToRemove.get(0));
            } else {
                List<Statement> orderedStatements= new ArrayList<Statement>(caseStmtsToRemove.get(0).size() + 1);
                for (Statement stmtToRemove : caseStmtsToRemove.get(0)) {
                    orderedStatements.add(0, b.copy(stmtToRemove));
                }
                orderedStatements.add(0, b.move(node));
                r.replace(node, b.block(orderedStatements.toArray(new Statement[caseStmtsToRemove.get(0).size() + 1])));
            }
        }
    }

    private void insertIdenticalCode(final IfStatement node, final ASTNodeFactory b, final Refactorings r,
            final List<Statement> stmtsToRemove) {
        for (Statement stmtToRemove : stmtsToRemove) {
            r.insertAfter(b.copy(stmtToRemove), node);
        }
    }

    private ASTNode findNodeToRemove(ASTNode node) {
        ASTNode parent= node.getParent();
        if (parent instanceof IfStatement) {
            if (node.equals(((IfStatement) parent).getThenStatement())) {
                return parent;
            } else {
                return node;
            }
        }
        if (parent instanceof Block) {
            final Block block= (Block) parent;
            return findNodeToRemove(block);
        }
        throw new NotImplementedException(parent, "for parent of type " + parent.getClass()); //$NON-NLS-1$
    }

    private boolean allRemovable(boolean[] areCasesRemovable) {
        for (boolean isCaseRemovable : areCasesRemovable) {
            if (!isCaseRemovable) {
                return false;
            }
        }
        return true;
    }

    private void removeStmtsFromCases(List<List<Statement>> allCasesStatements, List<List<Statement>> removedCaseStatements,
            boolean[] areCasesRemovable) {
        for (int i= 0; i < allCasesStatements.size(); i++) {
            final List<Statement> removedStatements= removedCaseStatements.get(i);
            final ASTNode parent= findNodeToRemove(allCasesStatements.get(i).get(0));

            if (removedStatements.containsAll(allCasesStatements.get(i))
                    && (!(parent instanceof IfStatement) || ASTNodes.isPassive(((IfStatement) parent).getExpression()))) {
                areCasesRemovable[i]= true;
            } else {
                this.ctx.getRefactorings().remove(removedStatements);
            }
        }
    }

    private boolean anyContains(List<List<Statement>> removedCaseStatements, List<List<Statement>> allCasesStatements,
            int stmtIndex) {
        for (int i= 0; i < allCasesStatements.size(); i++) {
            final List<Statement> caseStatements= allCasesStatements.get(i);
            if (removedCaseStatements.get(i).contains(caseStatements.get(caseStatements.size() - stmtIndex))) {
                return true;
            }
        }
        return false;
    }

    private void flagStmtsToRemove(List<List<Statement>> allCasesStatements, int stmtIndex,
            List<List<Statement>> removedCaseStatements) {
        for (int i= 0; i < allCasesStatements.size(); i++) {
            final List<Statement> caseStatements= allCasesStatements.get(i);
            final Statement stmtToRemove= caseStatements.get(caseStatements.size() - stmtIndex);
            removedCaseStatements.get(i).add(stmtToRemove);
        }
    }

    private boolean match(ASTSemanticMatcher matcher, List<List<Statement>> allCasesStatements, int stmtIndex,
            int startIndex, int endIndex) {
        if (startIndex == endIndex || startIndex == endIndex - 1) {
            return true;
        }
        final int comparisonIndex;
        if (endIndex - startIndex > 1) {
            final int pivotIndex= (endIndex + startIndex + 1) / 2;
            if (!match(matcher, allCasesStatements, stmtIndex, startIndex, pivotIndex)
                    || !match(matcher, allCasesStatements, stmtIndex, pivotIndex, endIndex)) {
                return false;
            }
            comparisonIndex= pivotIndex;
        } else {
            comparisonIndex= endIndex - 1;
        }

        final List<Statement> caseStmts1= allCasesStatements.get(startIndex);
        final List<Statement> caseStmts2= allCasesStatements.get(comparisonIndex);
        return ASTNodes.match(matcher, caseStmts1.get(caseStmts1.size() - stmtIndex),
                caseStmts2.get(caseStmts2.size() - stmtIndex));
    }

    private int minSize(List<List<Statement>> allCasesStatements) {
        if (allCasesStatements.isEmpty()) {
            throw new IllegalStateException(null, "allCasesStatements List must not be empty"); //$NON-NLS-1$
        }
        int min= Integer.MAX_VALUE;
        for (List<Statement> statements : allCasesStatements) {
            min= Math.min(min, statements.size());
        }
        if (min == Integer.MAX_VALUE) {
            throw new IllegalStateException(null, "The minimum size should never have been equal to Integer.MAX_VALUE"); //$NON-NLS-1$
        }
        return min;
    }

    /**
     * Collects all cases (if/else, if/else if/else, etc.) and returns whether all
     * are covered.
     *
     * @param allCases the output collection for all the cases
     * @param node     the {@link IfStatement} to examine
     * @return true if all cases (if/else, if/else if/else, etc.) are covered, false
     *         otherwise
     */
    private boolean collectAllCases(List<List<Statement>> allCases, IfStatement node) {
        final List<Statement> thenStatements= ASTNodes.asList(node.getThenStatement());
        final List<Statement> elseStatements= ASTNodes.asList(node.getElseStatement());
        if (thenStatements.isEmpty() || elseStatements.isEmpty()) {
            // If the then or else clause is empty, then there is no common code whatsoever.
            // let other cleanups take care of removing empty blocks.
            return false;
        }

        allCases.add(thenStatements);
        if (elseStatements.size() == 1) {
            final IfStatement is= ASTNodes.as(elseStatements.get(0), IfStatement.class);
            if (is != null) {
                return collectAllCases(allCases, is);
            }
        }
        allCases.add(elseStatements);
        return true;
    }
}
