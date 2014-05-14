package org.springsource.ide.eclipse.gradle.core.classpathcontainer;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IClasspathEntry;
import org.springsource.ide.eclipse.gradle.core.GradleProject;

public class GradleProjectClassPathContainer extends BaseGradleClasspathContainer {
	public static final String ID = "org.springsource.ide.eclipse.gradle.projectclasspathcontainer";
	
	/**
	 * Creates an uninitialised {@link GradleClassPathContainer}. If displayed in the UI it
	 * will have no entries.
	 */
	public GradleProjectClassPathContainer(IPath path, GradleProject project) {
		super(path, project, ID, "Gradle Project Dependencies");
	}

	@Override
	protected IClasspathEntry[] getFilteredClasspathEntries() throws FastOperationFailedException, CoreException {
		return project.getDependencyComputer().getProjectClassPath().toArray();
	}
	
	public boolean dependsOnProject(IProject workspaceProject) {
		return project.getDependencyComputer().getProjectDependencyMaybes().contains(workspaceProject);
	}
}
