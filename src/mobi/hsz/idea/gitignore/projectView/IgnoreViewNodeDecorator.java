/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2017 hsz Jakub Chrzanowski <jakub@hsz.mobi>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package mobi.hsz.idea.gitignore.projectView;

import com.intellij.ide.projectView.PresentationData;
import com.intellij.ide.projectView.ProjectViewNode;
import com.intellij.ide.projectView.ProjectViewNodeDecorator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.packageDependencies.ui.PackageDependenciesNode;
import com.intellij.ui.ColoredTreeCellRenderer;
import mobi.hsz.idea.gitignore.IgnoreBundle;
import mobi.hsz.idea.gitignore.IgnoreManager;
import org.jetbrains.annotations.NotNull;

/**
 * {@link ProjectViewNodeDecorator} implementation to show on the Project Tree if ignored file is
 * still tracked with Git.
 * 
 * @author Jakub Chrzanowski <jakub@hsz.mobi>
 * @since 1.7
 */
public class IgnoreViewNodeDecorator implements ProjectViewNodeDecorator {
    /** {@link IgnoreManager} instance. */
    private final IgnoreManager manager;

    /**
     * Constructor.
     * 
     * @param project current project
     */
    public IgnoreViewNodeDecorator(@NotNull Project project) {
        this.manager = IgnoreManager.getInstance(project);
    }

    /**
     * Modifies the presentation of a project view node.
     *
     * @param node the node to modify (use {@link ProjectViewNode#getValue()} to get the object represented by the node).
     * @param data the current presentation of the node, which you can modify as necessary.
     */
    @Override
    public void decorate(ProjectViewNode node, PresentationData data) {
        final VirtualFile file = node.getVirtualFile();
        if (file != null && manager.isFileIgnoredAndTracked(file)) {
            data.setLocationString(IgnoreBundle.message("projectView.tracked"));
        }
    }

    /**
     * Modifies the presentation of a package dependencies view node.
     *
     * @param node the node to modify.
     * @param cellRenderer the current renderer for the node, which you can modify as necessary.
     */
    @Override
    public void decorate(PackageDependenciesNode node, ColoredTreeCellRenderer cellRenderer) {
    }
}
