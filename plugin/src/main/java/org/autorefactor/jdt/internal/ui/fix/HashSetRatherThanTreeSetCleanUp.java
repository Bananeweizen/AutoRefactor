/*
 * AutoRefactor - Eclipse plugin to automatically refactor Java code bases.
 *
 * Copyright (C) 2017 Fabrice Tiercelin - Initial API and implementation
 * Copyright (C) 2018 Jean-Noël Rouvignac - minor changes
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodInvocation;

import static org.autorefactor.jdt.internal.corext.dom.ASTNodes.*;
import static org.autorefactor.util.Utils.*;

/** See {@link #getDescription()} method. */
public class HashSetRatherThanTreeSetCleanUp extends AbstractClassSubstituteCleanUp {
    private static final Map<String, String[]> CAN_BE_CASTED_TO= new HashMap<String, String[]>();

    static {
        CAN_BE_CASTED_TO.put("java.lang.Object", new String[] { "java.lang.Object" });
        CAN_BE_CASTED_TO.put("java.lang.Cloneable", new String[] { "java.lang.Cloneable", "java.lang.Object" });
        CAN_BE_CASTED_TO.put("java.io.Serializable", new String[] { "java.io.Serializable", "java.lang.Object" });
        CAN_BE_CASTED_TO.put("java.util.Collection", new String[] { "java.util.Collection", "java.lang.Object" });
        CAN_BE_CASTED_TO.put("java.util.AbstractCollection", new String[] { "java.util.AbstractCollection", "java.util.Collection", "java.lang.Object" });
        CAN_BE_CASTED_TO.put("java.util.AbstractSet", new String[] { "java.util.AbstractSet", "java.util.Set", "java.util.AbstractCollection", "java.util.Collection", "java.lang.Object" });
        CAN_BE_CASTED_TO.put("java.util.TreeSet", new String[] { "java.util.TreeSet", "java.util.AbstractSet", "java.util.Set", "java.util.AbstractCollection", "java.util.Collection", "java.io.Serializable", "java.lang.Cloneable", "java.lang.Object" });
    }

    /**
     * Get the name.
     *
     * @return the name.
     */
    public String getName() {
        return MultiFixMessages.CleanUpRefactoringWizard_HashSetRatherThanTreeSetCleanUp_name;
    }

    /**
     * Get the description.
     *
     * @return the description.
     */
    public String getDescription() {
        return MultiFixMessages.CleanUpRefactoringWizard_HashSetRatherThanTreeSetCleanUp_description;
    }

    /**
     * Get the reason.
     *
     * @return the reason.
     */
    public String getReason() {
        return MultiFixMessages.CleanUpRefactoringWizard_HashSetRatherThanTreeSetCleanUp_reason;
    }

    @Override
    protected String[] getExistingClassCanonicalName() {
        return new String[] { "java.util.TreeSet" };
    }

    @Override
    public Set<String> getClassesToImport() {
        return new HashSet<String>(Arrays.asList("java.util.HashSet"));
    }

    @Override
    protected String getSubstitutingClassName(String origRawType) {
        if ("java.util.TreeSet".equals(origRawType)) {
            return "java.util.HashSet";
        } else {
            return null;
        }
    }

    @Override
    protected boolean canInvokeIterator() {
        return false;
    }

    @Override
    protected boolean canInstantiationBeRefactored(final ClassInstanceCreation instanceCreation) {
        return instanceCreation.arguments().size() != 1
                || !hasType((Expression) instanceCreation.arguments().get(0), "java.util.Comparator");
    }

    @Override
    protected boolean canMethodBeRefactored(final MethodInvocation mi,
            final List<MethodInvocation> methodCallsToRefactor) {
        return isMethod(mi, "java.util.Collection", "add", "java.lang.Object")
                || isMethod(mi, "java.util.Collection", "clear")
                || isMethod(mi, "java.util.Collection", "contains", "java.lang.Object")
                || isMethod(mi, "java.util.Collection", "isEmpty")
                || isMethod(mi, "java.util.Collection", "remove", "java.lang.Object")
                || isMethod(mi, "java.util.Collection", "size")
                || isMethod(mi, "java.util.Collection", "removeAll", "java.util.Collection")
                || isMethod(mi, "java.util.Collection", "addAll", "java.util.Collection")
                || isMethod(mi, "java.util.Collection", "containsAll", "java.util.Collection")
                || isMethod(mi, "java.util.Collection", "retainAll", "java.util.Collection")
                || isMethod(mi, "java.lang.Object", "finalize") || isMethod(mi, "java.lang.Object", "notify")
                || isMethod(mi, "java.lang.Object", "notifyAll") || isMethod(mi, "java.lang.Object", "wait")
                || isMethod(mi, "java.lang.Object", "wait", "long")
                || isMethod(mi, "java.lang.Object", "wait", "long", "int");
    }

    @Override
    protected boolean isTypeCompatible(final ITypeBinding variableType, final ITypeBinding refType) {
        return super.isTypeCompatible(variableType, refType) || hasType(variableType,
                getOrDefault(CAN_BE_CASTED_TO, refType.getErasure().getQualifiedName(), new String[0]));
    }
}
