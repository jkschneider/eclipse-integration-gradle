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
import org.eclipse.jdt.core.IClasspathEntry;
import org.springsource.ide.eclipse.gradle.core.GradleProject;


/**
 * @author Kris De Volder
 */
public class GradleClassPathContainer extends BaseGradleClasspathContainer {
	public static final String ID = "org.springsource.ide.eclipse.gradle.classpathcontainer";

	/**
	 * Creates an uninitialised {@link GradleClassPathContainer}. If displayed in the UI it
	 * will have no entries.
	 */
	public GradleClassPathContainer(IPath path, GradleProject project) {
		super(path, project, ID, "Gradle Dependencies");
	}

	protected IClasspathEntry[] getFilteredClasspathEntries() throws FastOperationFailedException, CoreException {
		return project.getDependencyComputer().getClassPath().toArray();
	}
}
