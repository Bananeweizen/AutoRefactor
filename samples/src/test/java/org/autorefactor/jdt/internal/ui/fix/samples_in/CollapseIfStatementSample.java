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
package org.autorefactor.jdt.internal.ui.fix.samples_in;

public class CollapseIfStatementSample {

    public void collapseIfStatements(boolean b1, boolean b2) {
        // Keep this comment 1
        if (b1) {
            // Keep this comment 2
            if (b2) {
                // Keep this comment 3
                int i = 0;
            }
        }
    }

    public void collapseIfStatementsAddParenthesesIfDifferentConditionalOperator(boolean b1, boolean b2, boolean b3) {
        // Keep this comment 1
        if (b1) {
            // Keep this comment 2
            if (b2 || b3) {
                // Keep this comment 3
                int i = 0;
            }
        }
    }

    public void doNotCollapseOuterIfWithElseStatement(boolean b1, boolean b2) {
        if (b1) {
            if (b2) {
                int i = 0;
            }
        } else {
            int i = 0;
        }
    }

    public void doNotCollapseIfWithElseStatement2(boolean b1, boolean b2) {
        if (b1) {
            if (b2) {
                int i = 0;
            } else {
                int i = 0;
            }
        }
    }

}
