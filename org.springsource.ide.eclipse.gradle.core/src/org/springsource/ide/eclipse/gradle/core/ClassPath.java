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
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.internal.core.ClasspathAttribute;
import org.eclipse.jdt.internal.core.ClasspathEntry;
import org.eclipse.jdt.launching.JavaRuntime;
import org.gradle.tooling.model.ExternalDependency;
import org.gradle.tooling.model.GradleModuleVersion;
import org.springsource.ide.eclipse.gradle.core.ivy.IvyUtils;
import org.springsource.ide.eclipse.gradle.core.wtp.WTPUtil;

/**
 * Helper class to simplify manipulation of a project's class path.
 * @author Kris De Volder
 */
@SuppressWarnings("restriction")
public class ClassPath {
	
	public static boolean isContainerOnClasspath(IJavaProject jp, String containerID) {
		try {
			IClasspathEntry[] entries = jp.getRawClasspath();
			for (IClasspathEntry e : entries) {
				if (e.getEntryKind()==IClasspathEntry.CPE_CONTAINER) {
					IPath path = e.getPath();
					if (containerID.equals(path.segment(0))) {
						return true;
					}
				}
			}
		} catch (JavaModelException e) {
			GradleCore.log(e);
		}
		return false;
	}
	
	public boolean DEBUG = false;
	
	private void debug(String msg) {
		if (DEBUG) {
			System.out.println("ClassPath: "+msg);
		}
	}
	
	/**
	 * This array defines the ordering classpath entries based on their kinds.
	 */
	public static final int[] kindOrdering = {
		IClasspathEntry.CPE_SOURCE,
		IClasspathEntry.CPE_PROJECT,
		IClasspathEntry.CPE_LIBRARY,
		IClasspathEntry.CPE_CONTAINER,
		IClasspathEntry.CPE_VARIABLE
	};
	
	/**
	 * Entries, divided-up by category so that categories always maintain their order no matter
	 * what order entries are added in.
	 */
	private Map<Integer, Collection<IClasspathEntry>> entryMap = new HashMap<Integer, Collection<IClasspathEntry>>(kindOrdering.length);
	
	private Map<GradleModuleVersion, IClasspathEntry> libraryByModuleVersion = new TreeMap<GradleModuleVersion, IClasspathEntry>(
			new Comparator<GradleModuleVersion>() {
				@Override
				public int compare(GradleModuleVersion mv1, GradleModuleVersion mv2) {
					String mv1Stringified = mv1 == null ? "" : mv1.getName() + ":" + mv1.getGroup();
					String mv2Stringified = mv2 == null ? "" : mv2.getName() + ":" + mv2.getGroup();
					return mv1Stringified.compareTo(mv2Stringified);
				}
			});

	public class ClasspathEntryComparator implements Comparator<IClasspathEntry> {
		public int compare(IClasspathEntry e1, IClasspathEntry e2) {
			int k1 = e1.getEntryKind();
			int k2 = e2.getEntryKind();
			Assert.isLegal(k1==k2, "Only entries with the same kind should be compared");
			String p1 = getCompareString(e1);
			String p2 = getCompareString(e2);
			return p1.compareTo(p2);
		}

		private String getCompareString(IClasspathEntry e) {
			String str = e.getPath().toString();
			if (e.getEntryKind()==IClasspathEntry.CPE_CONTAINER) {
				//STS-3382: DSL support Groovy container should be last entry on classpath
				if (str.startsWith("GROOVY_")) {
					str = "zzz"+str; 
				}
			}
			return str;
		}
	}

	private boolean enableSorting; //If true, entries of the same kind will be sorted otherwise they will retained in the order they are being added.

	private GradleProject gradleProject;

	/** 
	 * Create a classpath prepopoluated with a set of raw classpath entries.
	 * 
	 * @param project 
	 * @param i
	 */
	public ClassPath(GradleProject project, IClasspathEntry[] rawEntries) {
		this(project);
		addAll(Arrays.asList(rawEntries));
	}
	
	public ClassPath(GradleProject project) {
		this.gradleProject = project;
		this.enableSorting = project.getProjectPreferences().getEnableClasspathEntrySorting();
	}

	public Collection<IClasspathEntry> createEntrySet(int size) {
		if (enableSorting) {
			return new TreeSet<IClasspathEntry>(new ClasspathEntryComparator());
		} else {
			return new LinkedHashSet<IClasspathEntry>();
		}
	}

	/**
	 * See STS-2054, it is not sufficient to avoid adding duplicates, sometimes gradle
	 * adds them and it is nice for us to remove them if it does.
	 */
	private void removeDuplicateJREContainers() {
		Collection<IClasspathEntry> entries = getEntries(IClasspathEntry.CPE_CONTAINER);
		Iterator<IClasspathEntry> iterator = entries.iterator();
		IClasspathEntry jreContainer = null;
		while (iterator.hasNext()) {
			IClasspathEntry element = iterator.next();
			if (isJREContainer(element)) {
				if (jreContainer!=null) {
					iterator.remove(); //Already seen a container, so this one is duplicate!
				} else {
					jreContainer = element;
				}
			}
		}
	}
	
	/**
	 * Retrieves the classpath entries of a particular kind only.
	 */
	private Collection<IClasspathEntry> getEntries(int kind) {
		Collection<IClasspathEntry> entries = entryMap.get(kind);
		if (entries==null) {
			entries = createEntrySet(0);
			entryMap.put(kind, entries);
		}
		return entries;
	}
		
	public IClasspathEntry[] toArray() {
		ArrayList<IClasspathEntry> entries = new ArrayList<IClasspathEntry>();
		for (int kind : kindOrdering) {
			entries.addAll(getEntries(kind));
		}
		return entries.toArray(new IClasspathEntry[entries.size()]);
	}

	public void add(IClasspathEntry newEntry) {
		int kind = newEntry.getEntryKind();
		Collection<IClasspathEntry> entries = getEntries(kind);
		entries.add(newEntry);
	}

	/**
	 * Make sure that a JRE container is present in the classpath. If one is already there, leave it as is.
	 * Otherwise add a default one.
	 */
	public void ensureJREContainer() {
		removeDuplicateJREContainers(); //STS-2054
		IClasspathEntry currentJREContainer = getContainer(JavaRuntime.JRE_CONTAINER);
		if (currentJREContainer==null) {
			add(JavaRuntime.getDefaultJREContainerEntry());
		}
	}

	/**
	 * Find class path container entry with given container ID. If more than one entry 
	 * exists the first one found will be returned. If no entry is found null will be
	 * returned.
	 * 
	 * @param containerID
	 * @return First matching classpath entry or null if no match.
	 */
	public IClasspathEntry getContainer(String containerID) {
		for (IClasspathEntry e : getEntries(IClasspathEntry.CPE_CONTAINER)) {
			Assert.isLegal(e.getEntryKind()==IClasspathEntry.CPE_CONTAINER);
			if (containerID.equals(e.getPath().segment(0))) {
				return e;
			}
		}
		return null;
	}
	
	public void removeContainer(String containerID) {
		Collection<IClasspathEntry> containers = getEntries(IClasspathEntry.CPE_CONTAINER);
		Iterator<IClasspathEntry> iter = containers.iterator();
		while (iter.hasNext()) {
			IClasspathEntry el = iter.next();
			if (el.getPath().segment(0).equals(containerID)) {
				iter.remove();
			}
		}
	}
	
	private boolean isJREContainer(IClasspathEntry e) {
		return isContainer(e, JavaRuntime.JRE_CONTAINER);
	}

	private boolean isContainer(IClasspathEntry e, String containerID) {
		return e.getEntryKind()==IClasspathEntry.CPE_CONTAINER
				&& containerID.equals(e.getPath().segment(0));
	}

	/**
	 * @param sourceEntries
	 */
	public void addAll(List<IClasspathEntry> toAdd) {
		for (IClasspathEntry e : toAdd) {
			add(e);
		}
	}

	/**
	 * @param javaProject
	 * @param subProgressMonitor
	 */
	public void setOn(IJavaProject javaProject, IProgressMonitor mon) throws JavaModelException {
		mon.beginTask("Setting classpath...", 2);
		try {
			IClasspathEntry[] oldClasspath = javaProject.getRawClasspath();
			mon.worked(1);
			IClasspathEntry[] newClasspath = toArray();
			if (isChanged(oldClasspath, newClasspath)) {
				debug("setRawClasspath...");
				javaProject.setRawClasspath(newClasspath, new SubProgressMonitor(mon, 1));
				debug("setRawClasspath DONE");
			} else {
				debug("Skipping setRawClasspath because old == new");
			}
		} finally {
			mon.done();
		}
	}

	private boolean isChanged(IClasspathEntry[] oldClasspath, IClasspathEntry[] newClasspath) {
		if (oldClasspath.length!=newClasspath.length) 
			return true;
		for (int i = 0; i < newClasspath.length; i++) {
			if (!oldClasspath[i].equals(newClasspath[i])) {
				return true;
			}
		}
		return false;
	}
	
	public void addJarEntry(ExternalDependency gEntry) {
		IClasspathEntry newLibraryEntry = buildJarEntry(gEntry);
		add(newLibraryEntry);
		if (newLibraryEntry.toString().contains("unresolved dependency")) {
			debug("entry: "+newLibraryEntry);
		}
		
		libraryByModuleVersion.put(gEntry.getGradleModuleVersion(), newLibraryEntry);
	}
	
	private IClasspathEntry buildJarEntry(ExternalDependency gEntry) {
		IClasspathEntry entry = libraryByModuleVersion.get(gEntry.getGradleModuleVersion());
		if(entry != null) return entry;
		
		// Get the location of a source jar, if any.
		IPath sourceJarPath = null;
		File sourceJarFile = gEntry.getSource();
		if (sourceJarFile!=null) {
			sourceJarPath = new Path(sourceJarFile.getAbsolutePath());
		}

		//Get the location of Java doc attachement, if any
		List<IClasspathAttribute> extraAttributes = new ArrayList<IClasspathAttribute>();
		File javaDoc = gEntry.getJavadoc();
		if (javaDoc!=null) {
			//Example of what it looks like in the eclipse .classpath file:
			//<attributes>
			//  <attribute name="javadoc_location" value="jar:file:/tmp/workspace/repos/test-with-jdoc-1.0-javadoc.jar!/"/>
			//</attributes>
			String jdoc = javaDoc.toURI().toString();
			if (!javaDoc.isDirectory()) {
				//Assume its a jar or zip containing the docs
				jdoc = "jar:"+jdoc+"!/";
			}
			IClasspathAttribute javaDocAttribute = new ClasspathAttribute(IClasspathAttribute.JAVADOC_LOCATION_ATTRIBUTE_NAME, jdoc);
			extraAttributes.add(javaDocAttribute);
		}
		
		File file = gEntry.getFile();
		IPath jarPath = new Path(file.getAbsolutePath()); 

		WTPUtil.excludeFromDeployment(gradleProject.getJavaProject(), jarPath, extraAttributes);

		//Create classpath entry with all this info
		return JavaCore.newLibraryEntry(
				jarPath, 
				sourceJarPath, 
				null, 
				ClasspathEntry.NO_ACCESS_RULES, 
				extraAttributes.toArray(new IClasspathAttribute[extraAttributes.size()]), 
				GradleCore.getInstance().getPreferences().isExportDependencies());
	}
	
	public boolean swapWithProject(IProject project) {
		Collection<IClasspathEntry> libraryEntries = getEntries(IClasspathEntry.CPE_LIBRARY);
		boolean removed = false;
		IClasspathEntry library = libraryByModuleVersion.get(IvyUtils.gradleModuleVersion(GradleCore.create(project)));
		if(library != null) {
			removed = libraryEntries.remove(library);
			
			if(removed) {
				Collection<IClasspathEntry> projectEntries = getEntries(IClasspathEntry.CPE_PROJECT);
				projectEntries.add(JavaCore.newProjectEntry(project.getFullPath(), GradleCore.getInstance().getPreferences().isExportDependencies()));
			}
		}
		
		return removed;
	}
	
	public boolean swapWithLibrary(IProject project) {
		boolean removed = false;
		
		Collection<IClasspathEntry> projectEntries = getEntries(IClasspathEntry.CPE_PROJECT);
		
		IClasspathEntry projectEntry = null;
		for(IClasspathEntry entry : projectEntries) {
			if(entry.getPath().equals(project.getFullPath())) {
				projectEntry = entry;
				break;
			}
		}
		if(projectEntry != null) {
			removed = projectEntries.remove(projectEntry);
			Collection<IClasspathEntry> libraryEntries = getEntries(IClasspathEntry.CPE_LIBRARY);
			
			IClasspathEntry library = libraryByModuleVersion.get(IvyUtils.gradleModuleVersion(GradleCore.create(project)));
			if(library != null)
				libraryEntries.add(library);
		}
		
		return removed;
	}
}
