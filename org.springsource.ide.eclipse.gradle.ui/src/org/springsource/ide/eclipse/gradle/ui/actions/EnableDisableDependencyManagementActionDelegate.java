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
package org.springsource.ide.eclipse.gradle.ui.actions;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.viewers.ISelection;
import org.springsource.ide.eclipse.gradle.core.classpathcontainer.GradleClasspathContainerGroup;
import org.springsource.ide.eclipse.gradle.core.util.GradleRunnable;


/**
 * @author Kris De Volder
 */
public class EnableDisableDependencyManagementActionDelegate extends GradleProjectActionDelegate {

	public EnableDisableDependencyManagementActionDelegate() {
	}

	public void run(IAction action) {
		final List<IProject> projects = getProjects();
		if (projects.size()>0) {
			runInUi(new GradleRunnable("Enable/Disable Gradle Dependency Management") {
				@Override
				public void doit(IProgressMonitor monitor) throws Exception {
					boolean enable = !GradleClasspathContainerGroup.isOnClassPath(JavaCore.create(projects.get(0)));
					monitor.beginTask(jobName, projects.size());
					try {
						for (IProject project : projects) {
							try {
								final IJavaProject javaProject = JavaCore.create(project);
								boolean isEnabled = GradleClasspathContainerGroup.isOnClassPath(javaProject);
								if (enable!=isEnabled) {
									if (enable) {
										GradleClasspathContainerGroup.addTo(javaProject, new SubProgressMonitor(monitor, 1));
									} else {
										GradleClasspathContainerGroup.removeFrom(javaProject, new SubProgressMonitor(monitor, 1));
									}
								} else {
									monitor.worked(1);
								}
							} catch (JavaModelException e) {
								throw new InvocationTargetException(e);
							}
						}
					} finally {
						monitor.done();
					}
				}
			});
		}
	} 
	
	@Override
	public void selectionChanged(IAction action, ISelection selection) {
		super.selectionChanged(action, selection);
		if (action.isEnabled()) {
			if (GradleClasspathContainerGroup.isOnClassPath(getJavaProject())) {
				action.setText("Disable Dependency Management");
			} else {
				action.setText("Enable Dependency Management");
			}
		}
	}

}
