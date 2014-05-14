package org.springsource.ide.eclipse.gradle.core.classpathcontainer;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.ClasspathContainerInitializer;
import org.eclipse.jdt.core.IJavaProject;
import org.springsource.ide.eclipse.gradle.core.GradleCore;
import org.springsource.ide.eclipse.gradle.core.GradleProject;

public class GradleProjectClasspathContainerInitializer extends ClasspathContainerInitializer {

	@Override
	public void initialize(final IPath path, final IJavaProject project) throws CoreException {
		get(project, path).setJDTClassPathContainer();
	}

	private synchronized GradleProjectClassPathContainer get(IJavaProject jProject, IPath path) {
		GradleProject gradleProject = GradleCore.create(jProject.getProject());
		GradleProjectClassPathContainer it;
		synchronized (gradleProject) {
			it = gradleProject.getProjectClassPathContainer();
			if (it==null) {
				it = new GradleProjectClassPathContainer(path, GradleCore.create(jProject));
				gradleProject.setProjectClassPathContainer(it);
			}
		}
		return it;
	}
}
