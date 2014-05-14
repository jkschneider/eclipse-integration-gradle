/*******************************************************************************
 * Copyright (c) 2012 Pivotal Software, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * Pivotal Software, Inc. - initial API and implementation
 *******************************************************************************/
package org.springsource.ide.eclipse.gradle.core.classpathcontainer;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.ClasspathContainerInitializer;
import org.eclipse.jdt.core.IJavaProject;
import org.springsource.ide.eclipse.gradle.core.Debug;
import org.springsource.ide.eclipse.gradle.core.GradleCore;
import org.springsource.ide.eclipse.gradle.core.GradleProject;


/**
 * @author Kris De Volder
 */
public class GradleClasspathContainerInitializer extends ClasspathContainerInitializer {
	
	private static final boolean DEBUG = Debug.FALSE;

	public static void debug(String msg) {
		if (DEBUG) {
			System.out.println("GradleClasspathContainerInitializer:" +msg);
		}
	}
	
	public static GradleClasspathContainerInitializer instance = null;

	public GradleClasspathContainerInitializer() {
		instance = this;
		debug("Creating instance");
	}
	
	private synchronized GradleClassPathContainer get(IJavaProject jProject, IPath path) {
		GradleProject gradleProject = GradleCore.create(jProject.getProject());
		GradleClassPathContainer it;
		synchronized (gradleProject) {
			it = gradleProject.getClassPathContainer();
			if (it==null) {
				it = new GradleClassPathContainer(path, GradleCore.create(jProject));
				gradleProject.setClassPathContainer(it);
			}
		}
		return it;
	}

	@Override
	public void initialize(final IPath path, final IJavaProject project) throws CoreException {
		debug("initialize called");
		get(project, path).setJDTClassPathContainer();
	}
	
	@Override
	public Object getComparisonID(IPath containerPath, IJavaProject project) {
		return containerPath.append(project.getElementName());
	}

}
