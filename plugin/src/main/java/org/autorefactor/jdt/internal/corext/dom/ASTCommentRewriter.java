/*
 * AutoRefactor - Eclipse plugin to automatically refactor Java code bases.
 *
 * Copyright (C) 2013-2016 Jean-Noël Rouvignac - initial API and implementation
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.    See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program under LICENSE-GNUGPL.    If not, see
 * <http://www.gnu.org/licenses/>.
 *
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution under LICENSE-ECLIPSE, and is
 * available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.autorefactor.jdt.internal.corext.dom;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.autorefactor.util.NotImplementedException;
import org.autorefactor.util.Pair;
import org.autorefactor.util.UnhandledException;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.BlockComment;
import org.eclipse.jdt.core.dom.Comment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.LineComment;
import org.eclipse.jface.text.IDocument;
import org.eclipse.text.edits.DeleteEdit;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;
import org.eclipse.text.edits.TextEditVisitor;

/** This class rewrites AST comments. */
public class ASTCommentRewriter {
    private static final Pattern INDENT= Pattern.compile("\\s+"); //$NON-NLS-1$
    /**
     * Using a Set to avoid duplicates because Javadocs are visited twice via
     * CompilationUnit.getCommentList() and visit(Javadoc).
     */
    private final Set<Comment> removals= new LinkedHashSet<>();
    private final Set<Pair<Comment, String>> replacements= new LinkedHashSet<>();
    private final List<BlockComment> blockCommentToJavadoc= new ArrayList<>();
    private final Map<ASTNode, List<LineComment>> lineCommentsToJavadoc= new HashMap<>();
    private final String lineSeparator;

    /**
     * Default constructor.
     *
     * @param astRoot the compilation unit, root of the AST
     */
    public ASTCommentRewriter(CompilationUnit astRoot) {
        this.lineSeparator= getLineSeparator(astRoot);
    }

    private String getLineSeparator(CompilationUnit astRoot) {
        String result= findRecommendedLineSeparator(astRoot);
        return result != null ? result : System.getProperty("line.separator"); //$NON-NLS-1$
    }

    private String findRecommendedLineSeparator(CompilationUnit astRoot) {
        try {
            return astRoot.getTypeRoot().findRecommendedLineSeparator();
        } catch (JavaModelException e) {
            throw new UnhandledException(astRoot, e);
        }
    }

    /**
     * Removes the provided comment.
     *
     * @param comment the comment to remove
     */
    public void remove(Comment comment) {
        this.removals.add(comment);
    }

    /**
     * Replaces the provided comment with the provided replacement text.
     *
     * @param comment     the comment to replace
     * @param replacement the replacement text
     */
    public void replace(Comment comment, String replacement) {
        this.replacements.add(Pair.of(comment, replacement));
    }

    /**
     * Converts the provided block comment into a javadoc.
     *
     * @param comment the block comment to convert into a javadoc
     */
    public void toJavadoc(BlockComment comment) {
        this.blockCommentToJavadoc.add(comment);
    }

    /**
     * Adds the provided line comment to convert to javadoc.
     *
     * @param lineComment the line comment to convert to javadoc
     * @param nextNode    the AST node immediately following the line comment
     */
    public void toJavadoc(LineComment lineComment, ASTNode nextNode) {
        List<LineComment> comments= lineCommentsToJavadoc.get(nextNode);
        if (comments == null) {
            comments= new LinkedList<>();
            lineCommentsToJavadoc.put(nextNode, comments);
        }
        comments.add(lineComment);
    }

    /**
     * Adds the edits contained in the current instance to the provided edits for
     * the provided document.
     *
     * @param document the provided document to edit
     * @param edits    where to add edits
     */
    public void addEdits(IDocument document, TextEdit edits) {
        final String source= document.get();
        final List<TextEdit> commentEdits= new ArrayList<TextEdit>(nbEdits());
        addRemovalEdits(commentEdits, source);
        addReplacementEdits(commentEdits);
        addBlockCommentToJavadocEdits(commentEdits);
        addLineCommentsToJavadocEdits(commentEdits, source);
        if (!commentEdits.isEmpty() && !anyOverlaps(edits, commentEdits)) {
            edits.addChildren(commentEdits.toArray(new TextEdit[commentEdits.size()]));
        }
        // Else, code edits take priority. Give up applying current text edits.
        // They will be retried in the next cleanup loop.
    }

    private int nbEdits() {
        return removals.size() + replacements.size() + blockCommentToJavadoc.size() + lineCommentsToJavadoc.size();
    }

    private boolean anyOverlaps(TextEdit edits, List<TextEdit> commentEdits) {
        for (TextEdit commentEdit : commentEdits) {
            if (overlaps(edits, commentEdit)) {
                return true;
            }
        }
        return false;
    }

    private boolean overlaps(TextEdit edits, TextEdit edit2) {
        final SourceLocation range= toSourceLoc(edit2);
        final AtomicBoolean overlaps= new AtomicBoolean();
        edits.accept(new TextEditVisitor() {
            @Override
            public boolean visit(MultiTextEdit edit) {
                // Move on there is nothing to check here
                return true;
            }

            @Override
            public boolean visitNode(TextEdit edit) {
                if (!overlaps.get()) {
                    overlaps.set(range.overlapsWith(toSourceLoc(edit)));
                }
                return !overlaps.get();
            }
        });
        return overlaps.get();
    }

    private SourceLocation toSourceLoc(TextEdit edit) {
        return new SourceLocation(edit.getOffset(), edit.getLength());
    }

    private void addRemovalEdits(List<TextEdit> commentEdits, String source) {
        if (this.removals.isEmpty()) {
            return;
        }
        for (Comment node : this.removals) {
            final int start= node.getStartPosition();
            final int length= node.getLength();

            // Chomp from the end before the start variable gets modified
            final int startToRemove= chompWhitespacesBefore(source, start);
            final int endToRemove= chompWhitespacesAfter(source, start + length);
            final int lengthToRemove= endToRemove - startToRemove;

            commentEdits.add(new DeleteEdit(startToRemove, lengthToRemove));
        }
    }

    private void addReplacementEdits(List<TextEdit> commentEdits) {
        if (this.replacements.isEmpty()) {
            return;
        }
        for (Pair<Comment, String> pair : this.replacements) {
            final Comment node= pair.getFirst();
            final int start= node.getStartPosition();
            final int length= node.getLength();
            commentEdits.add(new ReplaceEdit(start, length, pair.getSecond()));
        }
    }

    private void addBlockCommentToJavadocEdits(List<TextEdit> commentEdits) {
        for (BlockComment blockComment : this.blockCommentToJavadoc) {
            final int offset= blockComment.getStartPosition() + "/*".length(); //$NON-NLS-1$
            commentEdits.add(new InsertEdit(offset, "*")); //$NON-NLS-1$
        }
    }

    private void addLineCommentsToJavadocEdits(List<TextEdit> commentEdits, String source) {
        if (this.lineCommentsToJavadoc.isEmpty()) {
            return;
        }
        final TreeSet<Integer> lineStarts= getLineStarts(source);
        for (Entry<ASTNode, List<LineComment>> entry : this.lineCommentsToJavadoc.entrySet()) {
            final List<LineComment> lineComments= entry.getValue();
            // TODO Collect all words from the line comments,
            // then get access to indent settings, line length and newline chars
            // then spread them across several lines if needed or folded on one line only
            if (lineComments.size() == 1) {
                addSingleLineCommentToJavadocEdits(commentEdits, entry.getKey(), lineComments, source, lineStarts);
            } else {
                addMultiLineCommentsToJavadocEdits(commentEdits, entry.getKey(), lineComments, source, lineStarts);
            }
        }
    }

    private TreeSet<Integer> getLineStarts(String source) {
        final TreeSet<Integer> lineStarts= new TreeSet<>();
        lineStarts.add(0);

        final Matcher matcher= Pattern.compile("\\r\\n|\\r|\\n").matcher(source); //$NON-NLS-1$
        while (matcher.find()) {
            lineStarts.add(matcher.end());
        }
        return lineStarts;
    }

    private void addSingleLineCommentToJavadocEdits(List<TextEdit> commentEdits, ASTNode nextNode,
            List<LineComment> lineComments, String source, TreeSet<Integer> lineStarts) {
        final int nodeStart= nextNode.getStartPosition();
        final LineComment lineComment= lineComments.get(0);

        // TODO JNR how to obey configured indentation?
        // TODO JNR how to obey configured line length?
        final int commentStart= lineComment.getStartPosition();
        if (commentStart < nodeStart) {
            // Assume comment is situated exactly before target node for javadoc
            final String spaceAtStart= getSpaceAtStart(source, lineComment);
            commentEdits.add(new ReplaceEdit(commentStart, "//".length(), "/**" + spaceAtStart)); //$NON-NLS-1$ $NON-NLS-2$
            commentEdits.add(new InsertEdit(SourceLocation.getEndPosition(lineComment), getSpaceAtEnd(source, lineComment) + "*/")); //$NON-NLS-1$
            replaceEndsOfBlockCommentFromCommentText(commentEdits, lineComment, source);
        } else {
            // Assume comment is situated exactly after target node for javadoc
            final StringBuilder newJavadoc= new StringBuilder("/**").append(getSpaceAtStart(source, lineComment)); //$NON-NLS-1$

            appendCommentTextReplaceEndsOfBlockComment(newJavadoc, lineComment, source);

            SourceLocation indent= getIndent(nextNode, lineStarts);
            newJavadoc.append(getSpaceAtEnd(source, lineComment)).append("*/").append(lineSeparator).append(source, //$NON-NLS-1$
                    indent.getStartPosition(), indent.getEndPosition());
            commentEdits.add(new InsertEdit(nodeStart, newJavadoc.toString()));
            deleteLineCommentAfterNode(commentEdits, source, lineComment);
        }
    }

    private void appendCommentTextReplaceEndsOfBlockComment(StringBuilder sb, LineComment lineComment, String source) {
        final int commentStart= lineComment.getStartPosition();
        int nextStart= commentStart + "//".length(); //$NON-NLS-1$
        final Matcher matcher= endsOfBlockCommentMatcher(lineComment, source, nextStart);
        while (matcher.find()) {
            sb.append(source, nextStart, matcher.start());
            sb.append("* /"); //$NON-NLS-1$
            nextStart= matcher.end();
        }
        if (source.charAt(nextStart) == '/') {
            sb.append(' ');
        }
        sb.append(source, nextStart, SourceLocation.getEndPosition(lineComment));
    }

    private String getSpaceAtStart(String source, final LineComment lineComment) {
        final char firstChar= source.charAt(lineComment.getStartPosition() + "//".length()); //$NON-NLS-1$
        return !Character.isWhitespace(firstChar) ? " " : ""; //$NON-NLS-1$ $NON-NLS-2$
    }

    private String getSpaceAtEnd(String source, final LineComment lineComment) {
        final char lastChar= source.charAt(SourceLocation.getEndPosition(lineComment) - 1);
        return !Character.isWhitespace(lastChar) ? " " : ""; //$NON-NLS-1$ $NON-NLS-2$
    }

    private void deleteLineCommentAfterNode(List<TextEdit> commentEdits, String source, LineComment lineComment) {
        final int commentStart= lineComment.getStartPosition();
        final int commentLength= lineComment.getLength();
        final int nbWhiteSpaces= nbTrailingSpaces(source, commentStart);
        commentEdits.add(new DeleteEdit(commentStart - nbWhiteSpaces, nbWhiteSpaces + commentLength));
    }

    private int nbTrailingSpaces(String source, int commentStart) {
        int result= 0;
        while (Character.isWhitespace(source.charAt(commentStart - result - 1))) {
            ++result;
        }
        return result;
    }

    private void addMultiLineCommentsToJavadocEdits(List<TextEdit> commentEdits, ASTNode node,
            List<LineComment> lineComments, String source, TreeSet<Integer> lineStarts) {
        for (int i= 0; i < lineComments.size(); i++) {
            final LineComment lineComment= lineComments.get(i);
            if (lineComment.getStartPosition() <= node.getStartPosition()) {
                replaceLineCommentBeforeJavaElement(commentEdits, lineComment, lineComments, i, source, lineStarts);
            } else {
                replaceLineCommentAfterJavaElement(commentEdits, lineComment, lineComments, i, source, lineStarts);
            }
        }
    }

    private void replaceLineCommentBeforeJavaElement(List<TextEdit> commentEdits, LineComment lineComment,
            List<LineComment> lineComments, int i, String source, TreeSet<Integer> lineStarts) {
        final int replaceLength= "//".length(); //$NON-NLS-1$
        final boolean isFirst= i == 0;
        String replacementText;
        final SourceLocation indentLoc= getIndentForJavadoc(lineComment, source, lineStarts);
        if (isFirst) {
            // TODO JNR how to obey configured indentation?
            replacementText= "/**" + lineSeparator + indentLoc.substring(source) + " *"; //$NON-NLS-1$ $NON-NLS-2$
        } else {
            replacementText= " *"; //$NON-NLS-1$
        }
        final boolean commentStartsWithSlash= source.charAt(lineComment.getStartPosition() + replaceLength) == '/';
        if (commentStartsWithSlash) {
            replacementText+= " "; //$NON-NLS-1$
        }
        commentEdits.add(new ReplaceEdit(lineComment.getStartPosition(), replaceLength, replacementText));

        replaceEndsOfBlockCommentFromCommentText(commentEdits, lineComment, source);

        final boolean isLast= i == lineComments.size() - 1;
        if (isLast) {
            // TODO JNR how to obey configured indentation?
            final int position= SourceLocation.getEndPosition(lineComment);
            commentEdits.add(new InsertEdit(position, lineSeparator + indentLoc.substring(source) + " */")); //$NON-NLS-1$
        }
    }

    private void replaceEndsOfBlockCommentFromCommentText(List<TextEdit> commentEdits, LineComment lineComment,
            String source) {
        final Matcher matcher= endsOfBlockCommentMatcher(lineComment, source, lineComment.getStartPosition());
        while (matcher.find()) {
            commentEdits.add(new ReplaceEdit(matcher.start(), matcher.end() - matcher.start(), "* /")); //$NON-NLS-1$
        }
    }

    private Matcher endsOfBlockCommentMatcher(LineComment lineComment, String source, int startPos) {
        return Pattern.compile("\\*/").matcher(source).region(startPos, SourceLocation.getEndPosition(lineComment)); //$NON-NLS-1$
    }

    private void replaceLineCommentAfterJavaElement(List<TextEdit> commentEdits, LineComment lineComment,
            List<LineComment> lineComments, int i, String source, TreeSet<Integer> lineStarts) {
        if (i - 1 < 0) {
            throw new NotImplementedException(lineComment,
                    "for a line comment situated after the java elements that it documents," //$NON-NLS-1$
                            + " and this line comment is not the last line comment to add to the javadoc."); //$NON-NLS-1$
        }

        final LineComment previousLineComment= lineComments.get(i - 1);
        final int position= SourceLocation.getEndPosition(previousLineComment);
        final String indent= getIndentForJavadoc(previousLineComment, source, lineStarts).substring(source);
        final StringBuilder newJavadoc= new StringBuilder(lineSeparator).append(indent).append(" *"); //$NON-NLS-1$

        appendCommentTextReplaceEndsOfBlockComment(newJavadoc, lineComment, source);

        newJavadoc.append(lineSeparator).append(indent).append(" */"); //$NON-NLS-1$
        commentEdits.add(new InsertEdit(position, newJavadoc.toString()));
        deleteLineCommentAfterNode(commentEdits, source, lineComment);
    }

    private SourceLocation getIndentForJavadoc(LineComment lineComment, String source, TreeSet<Integer> lineStarts) {
        final SourceLocation indentLoc= getIndent(lineComment, lineStarts);
        final Matcher matcher= INDENT.matcher(source).region(indentLoc.getStartPosition(), indentLoc.getEndPosition());
        if (matcher.matches()) {
            return indentLoc;
        }
        return SourceLocation.fromPositions(0, 0);
    }

    private SourceLocation getIndent(ASTNode node, TreeSet<Integer> lineStarts) {
        if (lineStarts.isEmpty()) {
            // No match, return empty range
            return SourceLocation.fromPositions(0, 0);
        }

        final int commentStart= node.getStartPosition();
        final int previousLineStart= findPreviousLineStart(lineStarts, commentStart);
        return SourceLocation.fromPositions(previousLineStart, commentStart);
    }

    private int findPreviousLineStart(TreeSet<Integer> lineStarts, final int commentStart) {
        return lineStarts.headSet(commentStart + 1).last();
    }

    private int chompWhitespacesBefore(final String text, int start) {
        int i= start - 1;
        while (i >= 0) {
            final char c= text.charAt(i);
            // TODO JNR how to get project specific newline separator?? @see #lineSeparator
            if (!Character.isWhitespace(c) || c == '\n') {
                break;
            }
            start= i;
            i--;
        }
        return start;
    }

    private int chompWhitespacesAfter(final String text, int end) {
        int i= end;
        while (i < text.length()) {
            final char c= text.charAt(i);
            if (!Character.isWhitespace(c)) {
                break;
            }
            i++;
            end= i;
            // TODO JNR how to get project specific newline separator?? @see #lineSeparator
            if (c == '\n') {
                // We chomped the newline character, do not chomp on the next line
                break;
            }
        }
        return end;
    }
}
