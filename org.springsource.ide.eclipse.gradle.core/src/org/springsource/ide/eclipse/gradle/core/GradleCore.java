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
package org.springsource.ide.eclipse.gradle.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Plugin;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.core.IJavaProject;
import org.gradle.tooling.model.eclipse.HierarchicalEclipseProject;
import org.osgi.framework.BundleContext;
import org.osgi.service.prefs.BackingStoreException;
import org.springsource.ide.eclipse.gradle.core.autorefresh.DependencyRefresher;
import org.springsource.ide.eclipse.gradle.core.classpathcontainer.FastOperationFailedException;
import org.springsource.ide.eclipse.gradle.core.ivy.IvyProjectResolverWorkspaceListener;
import org.springsource.ide.eclipse.gradle.core.preferences.GradleAPIProperties;
import org.springsource.ide.eclipse.gradle.core.preferences.GradlePreferences;
import org.springsource.ide.eclipse.gradle.core.util.ExceptionUtil;


/**
 * @author Kris De Volder
 */
public class GradleCore extends Plugin {

	public static final String PLUGIN_ID = "org.springsource.ide.eclipse.gradle.core";
	public static final String OLD_PLUGIN_ID = "com.springsource.sts.gradle.core";

	private static BundleContext context;

	static BundleContext getContext() {
		return context;
	}

	private static GradleCore instance;

	private static GradleProjectManager projectManager = new GradleProjectManager();

	private GradlePreferences gradlePreferences = null;

	private GradleAPIProperties properties;

	/*
	 * (non-Javadoc)
	 * @see org.osgi.framework.BundleActivator#start(org.osgi.framework.BundleContext)
	 */
	public void start(BundleContext bundleContext) throws Exception {
		super.start(bundleContext);
		Assert.isTrue(instance==null);
		GradleCore.context = bundleContext;
		instance = this;
		DependencyRefresher.init();
		ResourcesPlugin.getWorkspace().addResourceChangeListener(new IvyProjectResolverWorkspaceListener());
	}

	/*
	 * (non-Javadoc)
	 * @see org.osgi.framework.BundleActivator#stop(org.osgi.framework.BundleContext)
	 */
	public void stop(BundleContext bundleContext) throws Exception {
		instance = null;
		GradleCore.context = null;
		if (gradlePreferences!=null) {
			gradlePreferences.dispose();
		}
		super.stop(bundleContext);
	}

	public static void log(Throwable e) {
		IStatus s = ExceptionUtil.status(IStatus.ERROR, e);
		log(s);
	}
	
	public static void logInfo(Throwable e) {
		IStatus s = ExceptionUtil.status(IStatus.INFO, e);
		log(s);
	}

	public static void log(IStatus s) {
		instance.getLog().log(s);
	}
	
	public static void log(String msg) {
		log(ExceptionUtil.coreException(msg));
	}
	
	public static GradleCore getInstance() {
		return instance;
	}

	public synchronized GradleAPIProperties getAPIProperties() {
		if (properties == null) {
			properties = new GradleAPIProperties();
		}
		return properties;
	}
	
	public GradlePreferences getPreferences() {
		if (gradlePreferences==null) {
			migrateLegacyPreferences(OLD_PLUGIN_ID, PLUGIN_ID);
			gradlePreferences = new GradlePreferences(InstanceScope.INSTANCE.getNode(PLUGIN_ID));
			forceExportOn(gradlePreferences);
		}
		return gradlePreferences;
	}

	private void forceExportOn(GradlePreferences gradlePreferences) {
		gradlePreferences.setExportDependencies(true);
	}
	
	private void migrateLegacyPreferences(String oldPluginId, String pluginId) {
		IEclipsePreferences oldPrefs = InstanceScope.INSTANCE.getNode(OLD_PLUGIN_ID);
		IEclipsePreferences newPrefs = InstanceScope.INSTANCE.getNode(PLUGIN_ID);
		boolean needsMigration = newPrefs.getBoolean("needsMigration", true);
		if (needsMigration) {
			try {
				for (String oldKey : oldPrefs.keys()) {
					String newKey = oldKey.replace(OLD_PLUGIN_ID, PLUGIN_ID);
					newPrefs.put(newKey, oldPrefs.get(oldKey, null));
				}
				newPrefs.putBoolean("needsMigration", false); //We done the migration.
				newPrefs.flush(); //Make sure all the new copied prefs are stored.
				//GradleCore.logInfo("Gradle workspace preferences migrated to 3.0.0 format");
			} catch (BackingStoreException e) {
				GradleCore.log(e);
			}
		}
	}

	public static GradleProject create(IProject project) {
		return projectManager.getOrCreate(project);
	}

	public static GradleProject create(IJavaProject jProject) {
		return create(jProject.getProject());
	}

	public static GradleProject create(HierarchicalEclipseProject gProject) {
		return projectManager.getOrCreate(gProject);
	}

	public static GradleProject create(File location) {
		return projectManager.getOrCreate(location);
	}

	public GradleProjectManager getProjectManager() {
		return projectManager;
	}

	/**
	 * Gets GradleProject associated with given IProject, if it exists. 
	 * May return null if the project itself doesn't exist (has no location) or the GradleProject 
	 * instance associated with thay project wasn't created yet.
	 */
	public static GradleProject getGradleProject(IProject project) {
		IPath location = project.getLocation();
		if (location!=null) {
			return projectManager.get(location.toFile());
		}
		return null;
	}

	/**
	 * Retrieve a collection of all Gradle projects in the workspace.
	 */
	public static Collection<GradleProject> getGradleProjects()  {
		List<GradleProject> list = new ArrayList<GradleProject>();
		for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects())
			if (isGradleProject(p))
				list.add(GradleCore.create(p));
		return list;
	}
	
	public static Set<GradleProject> getGradleRootProjects() {
		Set<GradleProject> rootProjects = new HashSet<GradleProject>();
		for (GradleProject gradleProject : getGradleProjects()) {
			try {
				rootProjects.add(gradleProject.getRootProject());
			} catch (FastOperationFailedException e) {
				GradleCore.log(e);
			}
		}
		return rootProjects;
	}

	public static boolean isGradleProject(IProject p) {
		try {
			return p.isAccessible() && p.hasNature(GradleNature.NATURE_ID);
		} catch (CoreException e) {
			GradleCore.log(e);
			return false;
		}
	}
	
	public static void warn(String message) {
		log(new Status(IStatus.WARNING, GradleCore.PLUGIN_ID, message));
	}

}
