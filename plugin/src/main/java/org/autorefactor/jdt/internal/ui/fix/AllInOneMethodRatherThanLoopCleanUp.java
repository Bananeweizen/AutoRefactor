/*
 * AutoRefactor - Eclipse plugin to automatically refactor Java code bases.
 *
 * Copyright (C) 2017 Fabrice Tiercelin - Separate the code.
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

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.autorefactor.jdt.internal.corext.dom.ASTNodeFactory;
import org.autorefactor.jdt.internal.corext.dom.ASTNodes;
import org.autorefactor.jdt.internal.corext.dom.ForLoopHelper;
import org.autorefactor.jdt.internal.corext.dom.ForLoopHelper.ForLoopContent;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ArrayAccess;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;

/** See {@link #getDescription()} method. */
public class AllInOneMethodRatherThanLoopCleanUp extends NewClassImportCleanUp {
    private final class RefactoringWithObjectsClass extends CleanUpWithNewClassImport {
        @Override
        public boolean visit(EnhancedForStatement node) {
            return AllInOneMethodRatherThanLoopCleanUp.this
                    .maybeRefactorEnhancedForStatement(node, getClassesToUseWithImport(), getImportsToAdd());
        }

        @Override
        public boolean visit(ForStatement node) {
            return AllInOneMethodRatherThanLoopCleanUp.this.maybeRefactorForStatement(node,
                    getClassesToUseWithImport(), getImportsToAdd());
        }
    }

    /**
     * Get the name.
     *
     * @return the name.
     */
    public String getName() {
        return MultiFixMessages.CleanUpRefactoringWizard_AllInOneMethodRatherThanLoopCleanUp_name;
    }

    /**
     * Get the description.
     *
     * @return the description.
     */
    public String getDescription() {
        return MultiFixMessages.CleanUpRefactoringWizard_AllInOneMethodRatherThanLoopCleanUp_description;
    }

    /**
     * Get the reason.
     *
     * @return the reason.
     */
    public String getReason() {
        return MultiFixMessages.CleanUpRefactoringWizard_AllInOneMethodRatherThanLoopCleanUp_reason;
    }

    @Override
    public RefactoringWithObjectsClass getRefactoringClassInstance() {
        return new RefactoringWithObjectsClass();
    }

    @Override
    public Set<String> getClassesToImport() {
        return new HashSet<>(Arrays.asList(Collections.class.getCanonicalName()));
    }

    @Override
    public boolean visit(final EnhancedForStatement node) {
        return maybeRefactorEnhancedForStatement(node, getAlreadyImportedClasses(node), new HashSet<String>());
    }

    private boolean maybeRefactorEnhancedForStatement(final EnhancedForStatement node,
            final Set<String> classesToUseWithImport, final Set<String> importsToAdd) {
        final Expression iterable= node.getExpression();
        final List<Statement> statements= ASTNodes.asList(node.getBody());

        if (statements.size() != 1) {
            return true;
        }

        final MethodInvocation mi= ASTNodes.asExpression(statements.get(0), MethodInvocation.class);
        final IVariableBinding foreachVariable= node.getParameter().resolveBinding();
        // We should remove all the loop variable occurrences
        // As we replace only one, there should be no more than one occurrence
        if (getVariableUseCount(foreachVariable, node.getBody()) == 1
                && mi != null && mi.arguments().size() == 1) {
            if (ASTNodes.instanceOf(iterable, Collection.class.getCanonicalName())) {
                if (ASTNodes.isSameLocalVariable(node.getParameter(), ASTNodes.arg0(mi))) {
                    return maybeReplaceForCollection(node, mi, iterable);
                }
            } else if (ASTNodes.isArray(iterable) && ASTNodes.isSameLocalVariable(foreachVariable, ASTNodes.arg0(mi))) {
                return maybeReplaceForArray(node, classesToUseWithImport, importsToAdd, iterable, mi);
            }
        }

        return true;
    }

    private void replaceWithCollectionsAddAll(final Statement node, final Expression iterable,
            final MethodInvocation mi, final Set<String> classesToUseWithImport) {
        ASTNodeFactory b= ctx.getASTBuilder();
        ctx.getRefactorings().replace(node,
                b.toStatement(b.invoke(
                        classesToUseWithImport.contains(Collections.class.getCanonicalName()) ? b.name("Collections") //$NON-NLS-1$
                                : b.name("java", "util", "Collections"), //$NON-NLS-1$ $NON-NLS-2$ $NON-NLS-3$
                        "addAll", mi.getExpression() != null ? b.copy(mi.getExpression()) : b.this0(), //$NON-NLS-1$
                        b.copy(iterable))));
    }

    @Override
    public boolean visit(ForStatement node) {
        return maybeRefactorForStatement(node, getAlreadyImportedClasses(node), new HashSet<String>());
    }

    private boolean maybeRefactorForStatement(final ForStatement node, final Set<String> classesToUseWithImport,
            final Set<String> importsToAdd) {
        final ForLoopContent loopContent= ForLoopHelper.iterateOverContainer(node);
        final List<Statement> statements= ASTNodes.asList(node.getBody());

        if (loopContent != null && loopContent.getLoopVariable() != null && statements.size() == 1) {
            final SimpleName loopVariable= (SimpleName) loopContent.getLoopVariable();
            final IVariableBinding loopVariableName= (IVariableBinding) loopVariable.resolveBinding();
            final MethodInvocation mi= ASTNodes.asExpression(statements.get(0), MethodInvocation.class);

            // We should remove all the loop variable occurrences
            // As we replace only one, there should be no more than one occurrence
            if (mi != null && mi.arguments().size() == 1 && getVariableUseCount(loopVariableName, node.getBody()) == 1) {
                switch (loopContent.getContainerType()) {
                case COLLECTION:
                    final Expression addArg01= ASTNodes.arg0(mi);
                    final MethodInvocation getMI= ASTNodes.as(addArg01, MethodInvocation.class);

                    if (getMI != null && getMI.arguments().size() == 1 && isSameVariable(loopContent, getMI)) {
                        return maybeReplaceForCollection(node, mi, getMI.getExpression());
                    }
                    break;

                case ARRAY:
                    final Expression addArg0= ASTNodes.arg0(mi);
                    final ArrayAccess aa= ASTNodes.as(addArg0, ArrayAccess.class);

                    if (isSameVariable(loopContent, aa)) {
                        final Expression iterable= loopContent.getContainerVariable();

                        return maybeReplaceForArray(node, classesToUseWithImport, importsToAdd, iterable, mi);
                    }
                    break;
                }
            }
        }

        return true;
    }

    private boolean maybeReplaceForArray(final Statement node, final Set<String> classesToUseWithImport,
            final Set<String> importsToAdd, final Expression iterable, final MethodInvocation mi) {
        if (ASTNodes.usesGivenSignature(mi, Collection.class.getCanonicalName(), "add", Object.class.getCanonicalName()) //$NON-NLS-1$
                && areTypeCompatible(ASTNodes.getCalledType(mi), iterable.resolveTypeBinding())) {
            replaceWithCollectionsAddAll(node, iterable, mi, classesToUseWithImport);
            importsToAdd.add(Collections.class.getCanonicalName());
            return false;
        }

        return true;
    }

    private int getVariableUseCount(final IVariableBinding variableBinding, Statement toVisit) {
        if (variableBinding != null) {
            final VariableDefinitionsUsesVisitor variableUseVisitor= new VariableDefinitionsUsesVisitor(variableBinding,
                    toVisit).find();
            return variableUseVisitor.getUses().size();
        }
        return 0;
    }

    private boolean isSameVariable(ForLoopContent loopContent, ArrayAccess aa) {
        return aa != null && ASTNodes.isSameLocalVariable(aa.getArray(), loopContent.getContainerVariable())
                && ASTNodes.isSameLocalVariable(aa.getIndex(), loopContent.getLoopVariable());
    }

    private boolean areTypeCompatible(ITypeBinding colTypeBinding, ITypeBinding arrayTypeBinding) {
        if (arrayTypeBinding != null && colTypeBinding != null) {
            ITypeBinding jucTypeBinding= ASTNodes.findImplementedType(colTypeBinding, Collection.class.getCanonicalName());

            if (jucTypeBinding.isRawType()) {
                return true;
            }

            ITypeBinding componentType= arrayTypeBinding.getComponentType();
            ITypeBinding colTypeArgument= jucTypeBinding.getTypeArguments()[0];
            return componentType.isSubTypeCompatible(colTypeArgument);
        }
        return false;
    }

    private boolean maybeReplaceForCollection(final ASTNode node, final MethodInvocation colMI,
            final Expression data) {
        if (ASTNodes.usesGivenSignature(colMI, Collection.class.getCanonicalName(), "add", Object.class.getCanonicalName())) { //$NON-NLS-1$
            replaceWithCollectionMethod(node, "addAll", colMI.getExpression(), data); //$NON-NLS-1$
            return false;
        } else if (ASTNodes.usesGivenSignature(colMI, Set.class.getCanonicalName(), "remove", Object.class.getCanonicalName())) { //$NON-NLS-1$
            replaceWithCollectionMethod(node, "removeAll", colMI.getExpression(), data); //$NON-NLS-1$
            return false;
        }

        return true;
    }

    private boolean isSameVariable(final ForLoopContent loopContent, final MethodInvocation getMI) {
        return ASTNodes.usesGivenSignature(getMI, List.class.getCanonicalName(), "get", int.class.getSimpleName()) && getMI.getExpression() instanceof Name //$NON-NLS-1$
                && ASTNodes.isSameLocalVariable(ASTNodes.arg0(getMI), loopContent.getLoopVariable());
    }

    private void replaceWithCollectionMethod(final ASTNode toReplace, final String methodName,
            final Expression affectedCollection,
            final Expression data) {
        final ASTNodeFactory b= ctx.getASTBuilder();
        final MethodInvocation newMethod;

        if (affectedCollection != null) {
            newMethod= b.invoke(b.copy(affectedCollection), methodName, b.copy(data));
        } else {
            newMethod= b.invoke(methodName, b.copy(data));
        }

        ctx.getRefactorings().replace(toReplace, b.toStatement(newMethod));
    }
}
