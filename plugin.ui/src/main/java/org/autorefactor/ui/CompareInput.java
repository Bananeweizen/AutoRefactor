package org.autorefactor.ui;

import java.lang.reflect.InvocationTargetException;

import org.autorefactor.refactoring.ApplyRefactoringsJob;
import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareEditorInput;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;

public class CompareInput extends CompareEditorInput {
    public static final String IGNORE_WHITESPACE = "IGNORE_WHITESPACE";

    static CompareConfiguration compareConfiguration = new CompareConfiguration();
    static DiffNode node;
    static IProgressMonitor monitor;

    CompareItem left;
    CompareItem right;

    public CompareInput() {
        super(compareConfiguration);
        compareConfiguration.setLeftLabel("Original Java File");
        compareConfiguration.setRightLabel("Refactored Java File");
        compareConfiguration.setRightEditable(true);
        compareConfiguration.setLeftEditable(true);
        compareConfiguration.setProperty(IGNORE_WHITESPACE, IGNORE_WHITESPACE);

        setDirty(true);
        setTitle("Compare the two java File");
    }

    @SuppressWarnings("restriction")
    @Override
    protected Object prepareInput(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
        CompareItem ancestor = new CompareItem(ApplyRefactoringsJob.codeToRefactor);
        left = new CompareItem(ApplyRefactoringsJob.codeToRefactor);
        right = new CompareItem(ApplyRefactoringsJob.refactoredContent);
        node = new DiffNode(1, ancestor, left, right);

        return node;
    }

    @Override
    public boolean okPressed() {
        ITypedElement itypeElement = node.getRight();

        try {
            CompareItem.newContents = ApplyRefactoringsJob.refactoredContent;
            saveChanges(monitor);
        } catch (Exception e) {
            e.printStackTrace();
        }
        createAndCommit();
        return true;
    }

    @Override
    public void cancelPressed() {
        try {
            ApplyRefactoringsJob.newFile.delete(true, monitor);
        } catch (CoreException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    public void createAndCommit() {
        ICompilationUnit iCompilation = ApplyRefactoringsJob.iCompile;
        try {
            iCompilation.getBuffer().setContents(CompareItem.newContents);
            ApplyRefactoringsJob.newFile.delete(true, monitor);
        }

        catch (Exception e) {
            System.out.println("Exception in create and commit");
            e.printStackTrace();
        }
    }
}
